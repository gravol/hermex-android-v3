package com.hermex.v3.feature.chat.replay

import androidx.test.core.app.ApplicationProvider
import com.hermex.core.network.ClientConnId
import com.hermex.core.network.DashboardApiClient
import com.hermex.core.network.WsConnectionManager
import com.hermex.v3.di.AppModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Method

/**
 * P5 A1 — every WebSocket connect mints a short client connection id and logs it once,
 * so gateway-side transport ids can be joined to the app journal.
 *
 * These assertions need the new API (`ClientConnId`, `WsConnectionManager.currentCid`),
 * so before this change they could not compile at all — the capability did not exist.
 * The socket-open path is driven through its real (private) method with an unroutable
 * URL: the cid is minted and logged synchronously at open time, and the handshake
 * failure afterwards is irrelevant. `disconnect()` afterwards stops the reconnect loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class ClientConnectionIdTest {

    private val outDir = File("/home/jeff/hermex-qa/longrun/artifacts/p2fix").apply { mkdirs() }
    private val ws = AppModule.provideWsConnection()

    private fun app(): android.app.Application = ApplicationProvider.getApplicationContext()

    private fun openRealSocketAndCaptureCid(reason: String): String {
        // The real open path resolves the base URL through DashboardApiClient, which is
        // initialised by HermexApplication in production; point it at a port nothing
        // listens on so the handshake fails fast and only the open-time work runs.
        DashboardApiClient.init(app())
        DashboardApiClient.setDashboardUrl("http://127.0.0.1:9")
        val m: Method = WsConnectionManager::class.java
            .getDeclaredMethod("openWebSocket", String::class.java, String::class.java)
            .apply { isAccessible = true }
        m.invoke(ws, "unittest-ticket", reason)
        return ws.currentCid()
    }

    private fun closeRealSocket() {
        WsConnectionManager::class.java.getDeclaredField("webSocket")
            .apply { isAccessible = true }.set(ws, null)
        ws.disconnect()
    }

    @Suppress("UNCHECKED_CAST")
    private fun journal(): List<com.hermex.core.network.DebugLog.Entry> {
        val f = com.hermex.core.network.DebugLog::class.java.getDeclaredField("buffer")
            .apply { isAccessible = true }
        return (f.get(com.hermex.core.network.DebugLog) as Collection<com.hermex.core.network.DebugLog.Entry>).toList()
    }

    @Test
    fun a1_clientConnectionIdsAreUniqueAndStableFormat() {
        val ids = (1..500).map { ClientConnId.new() }
        assertTrue("cids must be 8 lowercase hex chars",
            ids.all { Regex("^[0-9a-f]{8}$").matches(it) })
        assertEquals("cids must not collide within a process", ids.size, ids.toSet().size)
    }

    @Test
    fun a1_everySocketOpenMintsItsOwnCidAndLogsItOnce() {
        val first = openRealSocketAndCaptureCid("unit-test-a")
        try {
            assertTrue("currentCid must be a fresh cid, got '$first'",
                Regex("^[0-9a-f]{8}$").matches(first))
            val conn = journal().filter { it.tag == "conn" }
            assertTrue("no WS|conn cid=… gen=… line was written: ${conn.map { it.message }}",
                conn.any { Regex("""cid=$first gen=\d+ reason=unit-test-a""").containsMatchIn(it.message) })
            assertEquals("exactly one conn line per socket open", 1,
                conn.count { it.message.contains("cid=$first") })

            // A reconnect opens a new socket: it must get a DIFFERENT id.
            val second = openRealSocketAndCaptureCid("unit-test-b")
            assertTrue("each connection needs its own cid (both were $first)", second != first)
            assertEquals("currentCid tracks the newest socket", second, ws.currentCid())
            File(outDir, "conn_lines_a1.txt").writeText(
                journal().filter { it.tag == "conn" || it.tag == "gen_open" || it.tag == "ws_cb" }
                    .joinToString("\n") { "${it.level}|${it.tag}|${it.message}" })
        } finally {
            closeRealSocket()
        }
    }

    @Test
    fun a1_frameLinesCarryTheCidOfTheConnectionThatOpened() {
        val cid = openRealSocketAndCaptureCid("unit-test-frames")
        try {
            val spec = ReplayStream.spec()
            val r = ReplayTestbed(app(), outDir).runTrial(
                ReplayTestbed.TrialConfig(
                    ReplayTestbed.Mode.NONE, ReplayTestbed.RingMode.INTACT, 2,
                    frames = listOf(spec.frames[spec.idxStart], spec.frames[spec.idxFirstContentDelta],
                        spec.frames[spec.idxComplete]),
                )
            )
            assertTrue("no TRACE|frame lines captured", r.frameLines.isNotEmpty())
            assertTrue("every frame line must name the connection it arrived on: ${r.frameLines.take(3)}",
                r.frameLines.all { it.contains("cid=$cid") })
        } finally {
            closeRealSocket()
        }
    }
}
