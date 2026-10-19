package com.hermex.v3.feature.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermex.core.network.DebugLog
import com.hermex.core.network.JsonRpcClient
import com.hermex.core.network.WsConnectionManager
import com.hermex.v3.di.AppModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Request
import okhttp3.WebSocket
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.time.Duration

/**
 * FIX 2 — the foreground poll loop must not die at its first reconcile.
 *
 * Measured in production journals: every reconcile branch ended with
 * `loadMessages(); break` inside `while (screenVisible)`, so of 315 recorded gate
 * evaluations, 274 loops logged exactly ONE tick and 19 reached a second. A chat left
 * open therefore stops polling for the context gauge, stops re-checking rules A/B/D,
 * and never recovers a blank row until the screen is closed and reopened.
 *
 * This test drives the real ViewModel + real JsonRpcClient request path against a
 * scripted gateway (no socket): the first poll answers `running=true`, every later one
 * `running=false`, which fires rule B on each tick. It asserts the loop is still
 * ticking after a reload, and that full reloads are rate-limited rather than storming.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class PollLoopReconcileTest {

    private val ws = AppModule.provideWsConnection()
    private val rpc = AppModule.provideRpcClient()

    private val wsStateField: Field =
        WsConnectionManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
    private val wsWebSocketField: Field =
        WsConnectionManager::class.java.getDeclaredField("webSocket").apply { isAccessible = true }
    private val rpcProcessFrame: Method =
        JsonRpcClient::class.java.getDeclaredMethod("processFrame", String::class.java)
            .apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private val rpcPendingRequests: MutableMap<Long, *> =
        JsonRpcClient::class.java.getDeclaredField("pendingRequests").apply { isAccessible = true }
            .get(rpc) as MutableMap<Long, *>
    @Suppress("UNCHECKED_CAST")
    private val debugBuffer: Collection<DebugLog.Entry> =
        DebugLog::class.java.getDeclaredField("buffer").apply { isAccessible = true }
            .get(DebugLog) as Collection<DebugLog.Entry>
    private val vmSessionIdField: Field =
        DashboardChatViewModel::class.java.getDeclaredField("sessionId").apply { isAccessible = true }
    private val vmOnCleared: Method =
        DashboardChatViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }

    private inner class CapturingSocket : WebSocket {
        val sent = ArrayDeque<JsonObject>()
        override fun send(text: String): Boolean {
            sent.addLast(Json.parseToJsonElement(text) as JsonObject); return true
        }
        override fun send(bytes: okio.ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
        override fun queueSize(): Long = 0L
        override fun request(): Request = Request.Builder().url("http://poll.test/ws").build()
    }

    private var socket = CapturingSocket()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun setState(s: WsConnectionManager.State) {
        @Suppress("UNCHECKED_CAST")
        (wsStateField.get(ws) as MutableStateFlow<WsConnectionManager.State>).value = s
    }

    private fun idleAll(maxPasses: Int = 30) {
        val looper = shadowOf(android.os.Looper.getMainLooper())
        repeat(maxPasses) { looper.idle(); if (looper.isIdle) return }
    }

    private fun idleFor(ms: Long) = shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private val DB_KEY = "20261019_pollloop_deadbeef"
    private val LIVE_SID = "polllive01"

    /** Scripted gateway: history is complete and non-blank, so only the running-flag
     *  transition (rule B) can ask for a reload — and it asks on every tick. */
    private fun resumeResult(running: Boolean): JsonObject = buildJsonObject {
        put("session_id", JsonPrimitive(LIVE_SID))
        put("resumed", JsonPrimitive(DB_KEY))
        put("session_key", JsonPrimitive(DB_KEY))
        put("running", JsonPrimitive(running))
        put("messages", JsonArray(listOf(
            buildJsonObject {
                put("id", JsonPrimitive("hist-user")); put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive("prompt"))
            },
            buildJsonObject {
                put("id", JsonPrimitive("hist-asst")); put("role", JsonPrimitive("assistant"))
                put("content", JsonPrimitive("the answer"))
            },
        )))
        put("message_count", JsonPrimitive(2))
    }

    /**
     * Scripted gateway for the POLLS (requests carrying `omit_messages:true`): running
     * true/false alternating. Each `true` re-arms rule B ("we saw a turn running"), each
     * `false` while that flag is set asks for a full reload — so after the first reload
     * clears the flag, the following ticks keep asking, which is exactly the storm the
     * rate limiter has to absorb. The full-history resumes (loadMessages) always answer
     * "not running" and are not counted.
     */
    private var pollAnswersSeen = 0

    /** Pump the looper and answer every request the app sent, until quiet. */
    private fun drainRequests(): Int {
        var answered = 0
        repeat(10) {
            idleAll()
            var progressed = false
            while (socket.sent.isNotEmpty()) {
                val req = socket.sent.removeFirst()
                val id = (req["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
                val method = (req["method"] as? JsonPrimitive)?.content
                if (method != "session.resume") continue
                val params = req["params"] as? JsonObject
                val isPollRequest = params?.containsKey("omit_messages") == true
                val runningNow = if (isPollRequest) {
                    val r = pollAnswersSeen % 2 == 0
                    pollAnswersSeen++
                    r
                } else false
                val frame = buildJsonObject {
                    put("jsonrpc", JsonPrimitive("2.0"))
                    put("id", JsonPrimitive(id))
                    put("result", resumeResult(runningNow))
                }.toString()
                rpcProcessFrame.invoke(rpc, frame)
                answered++
                progressed = true
            }
            if (!progressed) return@repeat
        }
        idleAll()
        return answered
    }

    @Test
    fun chatLeftOpenPastAReconcile_keepsPolling_andReloadsAreRateLimited() {
        val startMs = System.currentTimeMillis()
        rpcPendingRequests.clear()
        socket = CapturingSocket()
        wsWebSocketField.set(ws, socket)
        setState(WsConnectionManager.State.Connected)

        val vm = DashboardChatViewModel(app())
        vmSessionIdField.set(vm, DB_KEY)
        try {
            pollAnswersSeen = 0
            vm.setScreenVisible(true)      // starts the real foreground poll loop
            // Virtual clock: ticks at t=0 (running=true), then every 2 s (running=false).
            repeat(4) {
                drainRequests()
                idleFor(2_500)
            }
            drainRequests()
        } finally {
            vm.setScreenVisible(false)
            idleAll()
            vmOnCleared.invoke(vm)
            wsWebSocketField.set(ws, null)
            setState(WsConnectionManager.State.Disconnected)
        }

        val lines = debugBuffer.filter { it.timestamp >= startMs }.map { "${it.tag}|${it.message}" }
        val ticks = lines.count { it.startsWith("poll_tick|") }
        val reloads = lines.count { it.contains("reconcile") && it.contains("full reload") }
        val suppressed = lines.count { it.contains("SUPPRESSED") }
        println("poll-loop evidence: ticks=$ticks reloads=$reloads suppressed=$suppressed")
        lines.filter { it.contains("gate_poll") || it.contains("reconcile") }.take(8).forEach { println("  $it") }

        assertTrue(
            "the poll loop died after its first reconcile: only $ticks poll_tick lines were " +
                "logged (expected >= 4 — the loop must keep polling while the screen is visible)",
            ticks >= 4,
        )
        assertTrue("a reconcile must still happen at least once", reloads >= 1)
        assertTrue(
            "reloads must be rate-limited, not storming: $reloads full reloads and " +
                "$suppressed suppressed reload requests while $ticks ticks kept polling",
            suppressed >= 1 && reloads < ticks,
        )
    }
}
