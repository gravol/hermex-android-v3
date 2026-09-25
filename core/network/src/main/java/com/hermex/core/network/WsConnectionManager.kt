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

    // v0.1.170 fix: capabilities() needs its OWN id space, never the request-counter range.
    // advertiseCapabilities() fires from onOpen() on every fresh connection BEFORE any real
    // request; if it reused a request id (it used a hardcoded 1, colliding with the first
    // session.list/session.create) its result hijacked pendingRequests[thatId], so session.list
    // decoded {server_requests} as SessionListResult ("Field 'sessions' is required ... missing")
    // and session.create got no session_id. Start high so it can never collide with request ids.
    private val capabilitiesCounter = java.util.concurrent.atomic.AtomicLong(1_000_000)

    private val wsClient: OkHttpClient by lazy {
        DashboardApiClient.httpClient().newBuilder()
            .pingInterval(300, TimeUnit.SECONDS)  // v0.1.74: 5-min liveness ping — the server never drops a silent WS (no read timeout) and streaming traffic verifies liveness anyway; 30s was ~10x more radio wakeups than needed
            .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for WS
            .build()
    }

    // ── Public API ──

    /** Full auth chain: ensure logged in → fetch ticket → open WS. Blocks until connected. */
    suspend fun connect() {
        if (_state.value == State.Connected || _state.value == State.Connecting) return
        isUserDisconnect = false
        _state.value = State.Connecting
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
                _state.value = State.Disconnected
                startReconnectLoop()
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
        reconnectJob?.cancel(); reconnectJob = null
        if (isUserDisconnect) return
        _state.value = State.Connecting
        DebugLog.log("WS", "Connection", "forceConnect() — fresh socket")
        val ticketResult = DashboardApiClient.fetchWsTicket()
        when (ticketResult) {
            is NetworkResult.Success -> {
                openWebSocket(ticketResult.data.ticket)
                waitForConnection()
            }
            is NetworkResult.Error, is NetworkResult.HttpError -> {
                DebugLog.log("WS", "Connection", "forceConnect ticket failed — reconnect loop")
                _state.value = State.Disconnected
                startReconnectLoop()
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
        _state.value = State.Disconnected
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
        val frame = "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"client.capabilities\",\"params\":{\"server_requests\":true}}"
        send(frame)
        DebugLog.log("WS", "Connection", "advertised client.capabilities {server_requests:true}")
    }
    // ── Internal ──

    private fun openWebSocket(ticket: String) {
        val wsUrl = DashboardApiClient.wsBaseUrl()
            .trimEnd('/') + "/api/ws?ticket=$ticket"

        DebugLog.log("WS", "Connection", "opening → $wsUrl")
        Log.d("Hermex", "WsConnectionManager: opening $wsUrl")

        val request = Request.Builder().url(wsUrl).build()

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("Hermex", "WsConnectionManager: onOpen")
                DebugLog.log("WS", "Connection", "onOpen — connected")
                _state.value = State.Connected
                // v0.1.170: advertise that this client answers server→client requests
                // (approval, clarify, sudo, …). Without it the gateway treats us as an
                // old build and fails every approval/clarify immediately with "the
                // attached client cannot answer approval requests". Sent once per
                // connection generation; the gateway ignores a -32601 from an older
                // backend. Must be sent after onOpen, before any tool call can fire.
                advertiseCapabilities()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                messageChannel.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("Hermex", "WsConnectionManager: onClosing code=$code reason=$reason")
                DebugLog.log("WS", "Connection", "onClosing code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("Hermex", "WsConnectionManager: onClosed code=$code reason=$reason")
                DebugLog.log("WS", "Connection", "onClosed code=$code")
                this@WsConnectionManager.webSocket = null
                if (!isUserDisconnect) {
                    startReconnectLoop()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("Hermex", "WsConnectionManager: onFailure", t)
                DebugLog.log("WS", "Connection", "onFailure: ${t.message} (http=${response?.code})")
                this@WsConnectionManager.webSocket = null
                if (!isUserDisconnect) {
                    startReconnectLoop()
                }
            }
        })
    }

    private fun startReconnectLoop() {
        if (reconnectJob?.isActive == true) return
        if (isUserDisconnect) return

        _state.value = State.Reconnecting
        DebugLog.log("WS", "Reconnect", "starting reconnect loop")

        reconnectJob = scope.launch {
            var attempt = 0

            while (!isUserDisconnect) {
                attempt++
                val delayMs = min(30_000L, (2.0.pow(attempt.toDouble()) * 1000).toLong())
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
                        openWebSocket(ticketResult.data.ticket)
                        // Wait for connection to stabilize before deciding
                        delay(2000)
                        if (_state.value == State.Connected) {
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
