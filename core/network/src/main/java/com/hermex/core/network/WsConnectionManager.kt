package com.hermex.core.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Manages a persistent WebSocket connection to the Hermes Dashboard.
 *
 * Lifecycle:
 *   1. connect() → fetch fresh ws-ticket → open WS → Connected
 *   2. On disconnect → Reconnecting → exponential backoff → fresh ticket → reconnect
 *   3. disconnect() → clean close, cancel reconnect, Disconnected
 *
 * Exposes [messages] as a Flow of raw text frames (newline-delimited JSON-RPC).
 */
class WsConnectionManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    enum class State { Disconnected, Connecting, Connected, Reconnecting }

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()
    val isConnected: Boolean get() = _state.value == State.Connected

    private val messageChannel = Channel<String>(UNLIMITED)
    /** Raw text frames from the server. Consumed by [JsonRpcClient]. */
    val messages = messageChannel.receiveAsFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var isUserDisconnect = false

    /** v0.1.889 liveness watchdog. OkHttp fires onClosed/onFailure only when it learns the
     *  socket is gone; with `readTimeout(0)` and a 300 s ping, a black-holed route (a
     *  Tailscale path change, a phone that switches networks) can leave the reader blocked
     *  for minutes with state still Connected. The watchdog is the app-level backstop:
     *  state says Connected, there is no socket — reconnect. */
    private var watchdogJob: Job? = null

    /** v0.1.884: serializes connect()/forceConnect() so exactly one socket is
     *  in flight. Every screen calls connect() on entry; the state check alone
     *  is check-then-act and lets all of them through. */
    private val connectMutex = Mutex()

    // v0.1.170 fix: capabilities() needs its OWN id space, never the request-counter range.
    // advertiseCapabilities() fires from onOpen() on every fresh connection BEFORE any real
    // request; if it reused a request id (it used a hardcoded 1, colliding with the first
    // session.list/session.create) its result hijacked pendingRequests[thatId], so session.list
    // decoded {server_requests} as SessionListResult ("Field 'sessions' is required ... missing")
    // and session.create got no session_id. Start high so it can never collide with request ids.
    private val capabilitiesCounter = java.util.concurrent.atomic.AtomicLong(1_000_000)

    // v0.1.884: every socket we open gets a generation number. A listener only drives
    // state when its own generation is still the current one. Without this, sockets
    // opened by earlier reconnect rounds stay alive untracked: each fires onOpen
    // (capabilities advertisement), each fires onClosed later (a brand-new reconnect
    // loop), and each replays session.resume — so one turn-end notification reaches
    // the phone several times. Debug log 2026-10-15 16:46:53 shows 5 reconnect loops
    // starting in the same 2ms and 5 sockets reporting "connected!".
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    // v0.1.894 (P5 instrumentation A1): alongside the generation, every socket we open
    // gets a short CLIENT connection id. The gateway can only see its own transport id;
    // without a client-side join key, "the gateway routed 424 frames to transport X" and
    // "the app parsed N frames" cannot be connected in one log. Kept for the last opened
    // socket on purpose: frames that arrive late still name the connection they came on.
    @Volatile private var currentCid: String = ""
    private val cidByGen = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** The cid of the most recently opened socket ("" before any connect). */
    fun currentCid(): String = currentCid

    /** Phase 0 trace (log-only, no behaviour change): every state transition, stamped
     *  with the generation that caused it. The hypothesis under test is that a CONNECTED
     *  edge never surfaces to the ViewModel's reconnect handler — so every edge, and
     *  every suppressed edge, has to be explicit in the trace. */
    private fun emitState(gen: Int, to: State, reason: String) {
        val from = _state.value
        DebugLog.log("TRACE", "state_emit", "gen=$gen ${from}→$to reason=$reason")
        _state.value = to
    }

    /** A callback fired on a superseded generation and returned without touching state. */
    private fun suppressState(gen: Int, reason: String) {
        DebugLog.log("TRACE", "state_suppress", "gen=$gen reason=$reason state stays ${_state.value}")
    }

    /** Ids sent fire-and-forget (capabilities advertisement). The server replies, and
     *  there is no continuation to resume, so [JsonRpcClient] must not read the reply
     *  as a stale/timed-out request. */
    internal val fireAndForgetIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    private val wsClient: OkHttpClient by lazy {
        DashboardApiClient.httpClient().newBuilder()
            .pingInterval(300, TimeUnit.SECONDS)  // v0.1.74: 5-min liveness ping — the server never drops a silent WS (no read timeout) and streaming traffic verifies liveness anyway; 30s was ~10x more radio wakeups than needed
            .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for WS
            .build()
    }

    // ── Public API ──

    /** Full auth chain: ensure logged in → fetch ticket → open WS. Blocks until connected.
     *
     *  v0.1.884: single-flight under a Mutex. `connect()` is called from every screen's
     *  LaunchedEffect (SessionsViewModel, DashboardChatViewModel, SoulScreen, BotScreen,
     *  NotificationReplyService), so app launch fires several at the same millisecond.
     *  The old guard `if (state == Connected || Connecting) return` is check-then-act:
     *  every caller passed it, so N tickets were fetched and N sockets opened — logcat
     *  2026-10-15 20:29:29.658 shows two `connect()` lines, two tickets, two `onOpen`
     *  from a single fresh process. Each socket receives every server event, and the
     *  delta handler appends each delta with no dedupe, so chat text lands N times.
     *  A caller that arrives mid-connect now waits on the mutex and returns once the
     *  in-flight socket is Connected, instead of opening its own. */
    suspend fun connect() {
        connectMutex.withLock {
            if (_state.value == State.Connected) return
            isUserDisconnect = false
            if (_state.value == State.Connecting) {
                DebugLog.log("WS", "Connection", "connect() — connect already in flight, waiting for it")
                Log.d("Hermex", "WsConnectionManager.connect(): in flight, waiting")
                waitForConnection()
                return
            }
            emitState(generation.get(), State.Connecting, "connect()")
            startLivenessWatchdog()
            DebugLog.log("WS", "Connection", "connect() — fetching ticket")
            Log.d("Hermex", "WsConnectionManager.connect()")

            // Fetch fresh single-use ticket
            val ticketResult = DashboardApiClient.fetchWsTicket()
            when (ticketResult) {
                is NetworkResult.Success -> {
                    DebugLog.log("WS", "Connection", "ticket fetched (ttl=${ticketResult.data.ttl_seconds}s)")
                    openWebSocket(ticketResult.data.ticket)
                    // Wait for the async WebSocket handshake to complete
                    waitForConnection()
                }
                is NetworkResult.Error, is NetworkResult.HttpError -> {
                    DebugLog.log("WS", "Connection", "ticket fetch failed — starting reconnect loop")
                    emitState(generation.get(), State.Disconnected, "connect ticket fetch failed")
                    startReconnectLoop()
                }
            }
        }
    }

    /** Block until the WebSocket reaches State.Connected or times out. */
    private suspend fun waitForConnection(timeoutMs: Long = 10_000) {
        val start = System.currentTimeMillis()
        while (_state.value != State.Connected) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                DebugLog.log("WS", "Connection", "connect() timed out waiting for onOpen after ${timeoutMs}ms")
                Log.w("Hermex", "WsConnectionManager: timed out waiting for Connected state")
                throw Exception("WebSocket connection timed out after ${timeoutMs}ms")
            }
            if (isUserDisconnect) {
                throw Exception("WebSocket connection cancelled")
            }
            delay(50)
        }
        DebugLog.log("WS", "Connection", "connect() — WebSocket ready")
    }

    /** Force a fresh connection regardless of current state (used when the socket is dead
     *  mid-session — e.g. a silent Tailscale drop leaves resume/submit retrying over a dead
     *  socket). Cancels any pending reconnect loop first so there's exactly one active socket. */
    suspend fun forceConnect() {
        connectMutex.withLock {
            reconnectJob?.cancel(); reconnectJob = null
            if (isUserDisconnect) return
            emitState(generation.get(), State.Connecting, "forceConnect()")
            DebugLog.log("WS", "Connection", "forceConnect() — fresh socket")
            Log.d("Hermex", "WsConnectionManager.forceConnect() — fresh socket")
            val ticketResult = DashboardApiClient.fetchWsTicket()
            when (ticketResult) {
                is NetworkResult.Success -> {
                    openWebSocket(ticketResult.data.ticket, "forceConnect()")
                    waitForConnection()
                }
                is NetworkResult.Error, is NetworkResult.HttpError -> {
                    DebugLog.log("WS", "Connection", "forceConnect ticket failed — reconnect loop")
                    emitState(generation.get(), State.Disconnected, "forceConnect ticket failed")
                    startReconnectLoop()
                }
            }
        }
    }

    /** Clean disconnect — cancels reconnect, closes WS. */
    fun disconnect() {
        isUserDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        emitState(generation.get(), State.Disconnected, "disconnect() user initiated")
        DebugLog.log("WS", "Connection", "disconnect() — user initiated")
        Log.d("Hermex", "WsConnectionManager.disconnect()")
    }

    /** Send a raw text frame on the WebSocket. */
    fun send(text: String) {
        val ws = webSocket
        if (ws != null && _state.value == State.Connected) {
            ws.send(text)
        } else {
            Log.w("Hermex", "WsConnectionManager.send: not connected, dropping frame")
            DebugLog.log("WS", "Send", "dropped — not connected")
        }
    }

    private fun advertiseCapabilities() {
        val id = capabilitiesCounter.getAndIncrement()
        // v0.1.884: register the id as fire-and-forget. The server answers every
        // request, and this one has no continuation registered, so its reply used to
        // log as "[id] no pending request (stale/timed out)" on every connection —
        // five of those lines in the 2026-10-15 log read as a connection failure and
        // are really just the capabilities ack.
        fireAndForgetIds.add(id)
        val frame = "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"client.capabilities\",\"params\":{\"server_requests\":true}}"
        send(frame)
        DebugLog.log("WS", "Connection", "advertised client.capabilities {server_requests:true}")
    }
    // ── Internal ──

    private fun openWebSocket(ticket: String, reason: String = "connect"): Int {
        val wsUrl = DashboardApiClient.wsBaseUrl()
            .trimEnd('/') + "/api/ws?ticket=$ticket"

        // v0.1.889 (Phase 0): the ticket rides the WS URL as a query param, so the old
        // Log.d line printed a live single-use ticket to logcat. DebugLog redacts
        // [?&]ticket=; logcat does not. Strip it before any log call.
        val safeUrl = wsUrl.replace(Regex("[?&]ticket=[^&\\s]*"), "ticket=***")
        DebugLog.log("WS", "Connection", "opening → $safeUrl")
        Log.d("Hermex", "WsConnectionManager: opening $safeUrl")

        val request = Request.Builder().url(wsUrl).build()

        // v0.1.884: close the socket we are replacing BEFORE opening the new one, and
        // stamp this socket's generation. Leaked sockets were the root cause of the
        // duplicate-notification storm: they stay open, keep receiving every server
        // event, and their onClosed starts additional reconnect loops.
        val myGen = generation.incrementAndGet()
        // Phase 0 trace: which generation opened, and why. Without this the log cannot
        // tell a connect() socket from a forceConnect() socket from a reconnect-attempt
        // socket, which is exactly what the drop trials need to distinguish.
        DebugLog.log("TRACE", "gen_open", "gen=$myGen reason=$reason")
        // P5 A1: one line per connection, joining our generation to a client connection
        // id that every later frame line repeats. Logged once, at open time — the point
        // where the id exists — so a journal always shows the id even if onOpen never
        // fires (a ticket that dies in the handshake still consumed a connection).
        val cid = ClientConnId.new()
        currentCid = cid
        cidByGen[myGen] = cid
        DebugLog.log("WS", "conn", "cid=$cid gen=$myGen reason=$reason")
        val stale = webSocket
        webSocket = null
        if (stale != null) {
            DebugLog.log("WS", "Connection", "closing superseded socket (gen ${myGen - 1})")
            Log.w("Hermex", "WsConnectionManager: closing superseded socket gen=${myGen - 1}")
            runCatching { stale.close(1000, "superseded by fresh socket") }
        }
        Log.d("Hermex", "WsConnectionManager: opening socket gen=$myGen")

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            /** True while this listener's generation is the live one. Stale sockets
             *  must not touch shared state — they null the var, drop sends, and
             *  spawn extra reconnect loops. */
            private fun isCurrent(): Boolean = generation.get() == myGen

            /** Phase 0 trace: every OkHttp callback, stamped with the generation that
             *  fired it and whether that generation is still live. The "superseded,
             *  ignored" branch is the exact path the frozen-card hypothesis blames, so
             *  it has to be visible in the trace, not inferred from absence. */
            private fun ws_cb(name: String, code: Int, current: Boolean) {
                DebugLog.log("TRACE", "ws_cb", "gen=$myGen cid=$cid $name code=$code isCurrent=$current")
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws_cb("onOpen", 0, isCurrent())
                if (!isCurrent()) {
                    suppressState(myGen, "onOpen on superseded socket")
                    DebugLog.log("WS", "Connection", "onOpen on superseded socket (gen $myGen) — ignored")
                    Log.w("Hermex", "WsConnectionManager: onOpen on superseded socket gen=$myGen — ignored")
                    return
                }
                Log.d("Hermex", "WsConnectionManager: onOpen")
                DebugLog.log("WS", "Connection", "onOpen — connected")
                emitState(myGen, State.Connected, "onOpen")
                // v0.1.170: advertise that this client answers server→client requests
                // (approval, clarify, sudo, …). Without it the gateway treats us as an
                // old build and fails every approval/clarify immediately with "the
                // attached client cannot answer approval requests". Sent once per
                // connection generation; the gateway ignores a -32601 from an older
                // backend. Must be sent after onOpen, before any tool call can fire.
                advertiseCapabilities()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent()) {
                    DebugLog.log("TRACE", "ws_cb", "gen=$myGen onMessage isCurrent=false — frame dropped")
                    return
                }
                messageChannel.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                ws_cb("onClosing", code, isCurrent())
                Log.d("Hermex", "WsConnectionManager: onClosing code=$code reason=$reason")
                DebugLog.log("WS", "Connection", "onClosing code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws_cb("onClosed", code, isCurrent())
                if (!isCurrent()) {
                    suppressState(myGen, "onClosed on superseded socket")
                    DebugLog.log("WS", "Connection", "onClosed on superseded socket (gen $myGen) — ignored")
                    Log.w("Hermex", "WsConnectionManager: onClosed on superseded socket gen=$myGen — ignored")
                    return
                }
                Log.d("Hermex", "WsConnectionManager: onClosed code=$code reason=$reason")
                DebugLog.log("WS", "Connection", "onClosed code=$code")
                this@WsConnectionManager.webSocket = null
                if (!isUserDisconnect) {
                    // v0.1.889: same race as onFailure — a reconnect loop in flight is
                    // driving this socket, and its success check reads the shared state.
                    reconnectJob?.cancel()
                    reconnectJob = null
                    startReconnectLoop()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ws_cb("onFailure", response?.code ?: -1, isCurrent())
                if (!isCurrent()) {
                    suppressState(myGen, "onFailure on superseded socket")
                    DebugLog.log("WS", "Connection", "onFailure on superseded socket (gen $myGen) — ignored")
                    Log.w("Hermex", "WsConnectionManager: onFailure on superseded socket gen=$myGen — ignored")
                    return
                }
                Log.e("Hermex", "WsConnectionManager: onFailure", t)
                DebugLog.log("WS", "Connection", "onFailure: ${t.message} (http=${response?.code})")
                this@WsConnectionManager.webSocket = null
                if (!isUserDisconnect) {
                    // v0.1.889: an in-flight reconnect loop is driving the socket that just
                    // died, and its success check reads the shared state — so it reports
                    // "connected!" on this dead generation. Drop the loop so the next
                    // attempt opens a real socket.
                    reconnectJob?.cancel()
                    reconnectJob = null
                    startReconnectLoop()
                }
            }
        })
        return myGen
    }

    // v0.1.884: @Synchronized. The old guard was check-then-act across OkHttp's callback
    // threads: when several sockets died in the same millisecond (keepalive ping timeout
    // kills them all at once), every onClosed passed `reconnectJob?.isActive == true`
    // before any assignment landed, so N reconnect loops started, N tickets were
    // fetched, and N sockets came up — each replaying session.resume and each
    // delivering the turn-end notification. Debug log 2026-10-15 16:46:53: 5 loops
    // started within 2ms, 5 "attempt 1 — connected!" at 16:46:57.
    /** Watch the state/socket pair every 15 s. If the UI believes it is Connected with no
     *  socket under it, the connection is a lie — start a reconnect. */
    private fun startLivenessWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (!isUserDisconnect) {
                delay(15_000)
                if (_state.value == State.Connected && webSocket == null) {
                    DebugLog.log("WS", "Watchdog", "state=Connected with webSocket=null — forcing reconnect")
                    Log.w("Hermex", "WsConnectionManager: watchdog — Connected state with no socket, reconnecting")
                    startReconnectLoop()
                }
            }
        }
    }

    @Synchronized
    private fun startReconnectLoop() {
        if (reconnectJob?.isActive == true) return
        if (isUserDisconnect) return

        startLivenessWatchdog()
        emitState(generation.get(), State.Reconnecting, "reconnect loop start")
        DebugLog.log("WS", "Reconnect", "starting reconnect loop")

        reconnectJob = scope.launch {
            var attempt = 0

            while (!isUserDisconnect) {
                attempt++
                // Phase 0 measurement: the 2^n backoff capped at 30 s meant a mid-turn
                // drop took 91 s to reach attempt 5 — the UI sat stale for a minute and
                // a half. On a tailnet a reconnect is cheap, so cap the wait at 8 s.
                val delayMs = min(8_000L, (2.0.pow(attempt.toDouble()) * 1000).toLong())
                DebugLog.log("WS", "Reconnect", "attempt $attempt — waiting ${delayMs}ms")
                Log.d("Hermex", "WsConnectionManager: reconnect attempt $attempt, delay=${delayMs}ms")
                delay(delayMs)

                if (isUserDisconnect) break

                DebugLog.log("WS", "Reconnect", "attempt $attempt — fetching fresh ticket")
                Log.d("Hermex", "WsConnectionManager: fetching fresh ticket (attempt $attempt)")

                val ticketResult = DashboardApiClient.fetchWsTicket()
                when (ticketResult) {
                    is NetworkResult.Success -> {
                        DebugLog.log("WS", "Reconnect", "attempt $attempt — ticket ok, opening WS")
                        val myGen = openWebSocket(ticketResult.data.ticket, "reconnect attempt $attempt")
                        // Wait for the connection to stabilize before deciding
                        delay(2000)
                        // v0.1.889: the success test has to be about the socket THIS attempt
                        // opened, not the shared state. Debug log 2026-10-17 11:10:25: the
                        // loop opened gen=4, its onOpen set state=Connected, the server
                        // closed gen=4 800 ms later (code 1012, dashboard restart), and the
                        // check read that stale edge — it logged "connected!" with
                        // webSocket == null and no loop running. The app sat "Connected" on
                        // a dead socket for 18 minutes while the gateway was healthy.
                        if (_state.value == State.Connected &&
                            generation.get() == myGen && webSocket != null) {
                            DebugLog.log("WS", "Reconnect", "attempt $attempt — connected!")
                            Log.d("Hermex", "WsConnectionManager: reconnected (attempt $attempt)")
                            return@launch
                        }
                    }
                    is NetworkResult.Error, is NetworkResult.HttpError -> {
                        DebugLog.log("WS", "Reconnect", "attempt $attempt — ticket fetch failed")
                    }
                }
            }
        }
    }
}
