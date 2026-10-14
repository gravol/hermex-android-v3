package com.hermex.v3.feature.botscreen

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermex.core.network.DashboardApiClient
import com.hermex.core.network.DebugLog
import com.hermex.core.network.JsonRpcClient
import com.hermex.core.network.WsConnectionManager
import com.hermex.core.network.rfb.RfbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Bot Screen engine (Tier 3): views and drives the bot's Linux desktop.
 *
 * Flow:
 *  1. JSON-RPC over the gateway WS — `display.status` (start if needed),
 *     `display.observe` → single-use 30 s ticket + viewer_id.
 *  2. Redeem the ticket on `/api/display/ws?display_ticket=…` — raw RFB
 *     bytes over WebSocket binary frames — parsed by [RfbClient].
 *  3. `display.lease.acquire` / `release` = Take over / Hand back.
 *
 * The framebuffer arrives as an IntArray; [blitTo] paints it into a
 * Bitmap the Compose pane displays. Input (pointer/key) is forwarded only
 * while the lease is held — the server enforces the same rule.
 */
class BotScreenEngine(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Streaming : State
        data object Takeover : State
        data class Error(val message: String) : State
    }

    var state: State by androidx.compose.runtime.mutableStateOf(State.Idle)
        private set
    var width by androidx.compose.runtime.mutableStateOf(0)
        private set
    var height by androidx.compose.runtime.mutableStateOf(0)
        private set
    var desktopName by androidx.compose.runtime.mutableStateOf("")
        private set
    var leaseHolder by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var frames by androidx.compose.runtime.mutableStateOf(0L)
        private set

    private val wsConnection = WsConnectionManager(viewModelScope)
    private val rpc = JsonRpcClient(wsConnection, viewModelScope)

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var displayWs: WebSocket? = null
    private var rfb: RfbClient? = null
    private var viewerId: String = ""
    private var runJob: Job? = null
    private var pollJob: Job? = null

    // ── lifecycle ──────────────────────────────────────────────────

    fun connect() {
        if (state != State.Idle && state !is State.Error) return
        state = State.Connecting
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!wsConnection.isConnected) {
                    wsConnection.connect()
                }
                rpc.start()

                var status = rpc.displayStatus()
                val running = status["running"]?.jsonPrimitive?.content?.toBoolean() == true
                if (!running) {
                    DebugLog.log("BOT", "Screen", "display.start …")
                    rpc.displayStart()
                    // Xvnc + Xfce need a moment to come up
                    repeat(10) {
                        delay(1000)
                        status = rpc.displayStatus()
                        if (status["running"]?.jsonPrimitive?.content?.toBoolean() == true) return@repeat
                    }
                }
                width = status["width"]?.jsonPrimitive?.intOrNull() ?: 0
                height = status["height"]?.jsonPrimitive?.intOrNull() ?: 0
                leaseHolder = status["lease"]?.jsonObject?.get("holder_id")
                    ?.jsonPrimitive?.content

                openDisplaySocket()
            } catch (e: Exception) {
                DebugLog.log("BOT", "Screen", "connect failed: ${e.message}")
                state = State.Error(e.message ?: "connect failed")
            }
        }
    }

    private suspend fun openDisplaySocket() {
        val obs = rpc.displayObserve()
        val ticket = obs["ticket"]?.jsonPrimitive?.content
            ?: error("no ticket in display.observe")
        viewerId = obs["viewer_id"]?.jsonPrimitive?.content ?: ""
        val url = DashboardApiClient.wsBaseUrl().trimEnd('/') +
            "/api/display/ws?display_ticket=$ticket"
        DebugLog.log("BOT", "Screen", "opening display ws (viewer=$viewerId)")

        val client = RfbClient { bytes -> displayWs?.send(okio.ByteString.of(*bytes)) }
        rfb = client
        val request = Request.Builder().url(url).build()
        displayWs = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLog.log("BOT", "Screen", "display ws open")
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                client.feed(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                DebugLog.log("BOT", "Screen", "display ws closing: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLog.log("BOT", "Screen", "display ws failure: ${t.message}")
                viewModelScope.launch { state = State.Error("display ws: ${t.message}") }
            }
        })

        // RfbClient.run() blocks (handshake + decode loop) on its own thread.
        runJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                client.run()
                withContextMain { state = State.Error("rfb session ended") }
            } catch (e: Exception) {
                DebugLog.log("BOT", "Screen", "rfb run failed: ${e.message}")
                withContextMain { state = State.Error(e.message ?: "rfb failed") }
            }
        }
        // Wait for ServerInit, then flip to Streaming + start frame polling.
        repeat(20) {
            delay(250)
            if (client.connected) {
                width = client.width
                height = client.height
                desktopName = client.desktopName
                withContextMain { state = State.Streaming }
                startPolling(client)
                return
            }
        }
        withContextMain { state = State.Error("rfb handshake timeout") }
    }

    private fun startPolling(client: RfbClient) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            var last = -1L
            while (client.connected) {
                if (client.frameSeq != last) {
                    last = client.frameSeq
                    frames = last
                }
                // Keep the server painting: request an update every 400 ms.
                client.requestUpdate(0, 0, client.width, client.height)
                delay(400)
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel(); runJob?.cancel()
        displayWs?.close(1000, null)
        displayWs = null
        rfb = null
        rpc.stop()
        wsConnection.disconnect()
        state = State.Idle
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    // ── lease: Take over / Hand back ───────────────────────────────

    fun takeover() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rpc.displayLeaseAcquire(viewerId)
                leaseHolder = viewerId
                if (state == State.Streaming) state = State.Takeover
            } catch (e: Exception) {
                DebugLog.log("BOT", "Screen", "takeover failed: ${e.message}")
            }
        }
    }

    fun handback() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rpc.displayLeaseRelease(viewerId)
                leaseHolder = null
                if (state == State.Takeover) state = State.Streaming
            } catch (e: Exception) {
                DebugLog.log("BOT", "Screen", "handback failed: ${e.message}")
            }
        }
    }

    // ── input ──────────────────────────────────────────────────────

    fun pointer(nx: Float, ny: Float, buttons: Int) {
        val c = rfb ?: return
        if (!c.connected) return
        c.sendPointer(nx, ny, buttons)
        c.requestUpdate(0, 0, c.width, c.height)
    }

    fun typeText(text: String) {
        val c = rfb ?: return
        if (!c.connected) return
        c.sendText(text)
        c.requestUpdate(0, 0, c.width, c.height)
    }

    fun key(keysym: Int, down: Boolean) {
        val c = rfb ?: return
        if (!c.connected) return
        c.sendKey(keysym, down)
        c.requestUpdate(0, 0, c.width, c.height)
    }

    // ── rendering ──────────────────────────────────────────────────

    /** Paint the latest framebuffer into [bitmap] (scaled to fit). */
    fun blitTo(bitmap: Bitmap): Boolean {
        val c = rfb ?: return false
        val w = c.width; val h = c.height
        if (!c.connected || w <= 0 || h <= 0) return false
        if (bitmap.width != w || bitmap.height != h) return false
        bitmap.setPixels(c.pixels, 0, w, 0, 0, w, h)
        return true
    }

    private suspend fun withContextMain(block: suspend () -> Unit) =
        withContext(Dispatchers.Main) { block() }

    private fun kotlinx.serialization.json.JsonPrimitive.intOrNull(): Int? =
        content.toIntOrNull()
}
