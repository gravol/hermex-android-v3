package com.hermex.v3.feature.chat.replay

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P5 instrumentation A1 (per-frame join key), A2 (disposition line) and A4 (identity
 * lines), asserted as JOURNAL LINES on the real reducer path — deliberately written
 * with no reference to any new production API, so it fails on current code by showing
 * what the journal does NOT contain.
 *
 * Why each line is needed (from the D/14 investigation):
 *  - `cid=` on TRACE|frame is the only way to join the app journal to the gateway's
 *    per-connection frame trace (`transport=715e85ef3350` style ids).
 *  - a per-notification disposition line is the only way to tell "the frame never
 *    reached the app" from "it reached the app and this ViewModel dropped it" — the
 *    open question in the corrected D/14 facts.
 *  - liveSid change + register/unregister lines are the only way to see WHY a filter
 *    went stale (two live connections for one phone at prompt time).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class JournalLineInstrumentationTest {

    private val outDir = File("/home/jeff/hermex-qa/longrun/artifacts/p2fix").apply { mkdirs() }

    private fun app(): android.app.Application = ApplicationProvider.getApplicationContext()

    /** A 3-frame trial (start / delta / complete) over the real reducer path. */
    private fun threeFrameTrial(): ReplayTestbed.TrialResult {
        val spec = ReplayStream.spec()
        return ReplayTestbed(app(), outDir).runTrial(
            ReplayTestbed.TrialConfig(
                ReplayTestbed.Mode.NONE, ReplayTestbed.RingMode.INTACT, 2,
                frames = listOf(spec.frames[spec.idxStart], spec.frames[spec.idxFirstContentDelta],
                    spec.frames[spec.idxComplete]),
            )
        )
    }

    @Test
    fun a1_perFrameJournalLinesCarryAClientConnectionId() {
        val r = threeFrameTrial()
        assertTrue("no TRACE|frame lines were captured at all", r.frameLines.isNotEmpty())
        val missing = r.frameLines.filterNot { it.contains("cid=") }
        assertTrue("${missing.size}/${r.frameLines.size} frame lines carry no cid= — gateway " +
            "transport ids cannot be joined to the app journal: ${missing.take(2)}", missing.isEmpty())
    }

    @Test
    fun a2_everyNotificationGetsOneDispositionLine() {
        val r = threeFrameTrial()
        val disp = r.dispLines
        try {
            assertTrue("no TRACE|disp lines exist: current code cannot distinguish " +
                "'frame not delivered' from 'frame dropped by this ViewModel'", disp.isNotEmpty())
            // v0.1.894: the disposition line also names the chat (dbKey) and carries the
            // cid stamped at parse time (frameCid), not the connection current at handling
            // time — after a reconnect those differ, and these lines are the client half of
            // a join against the gateway's per-transport frame trace.
            val shape = Regex(
                """cid=\S+ dbKey=\S+ sid=\S+ seq=\d+ type=\S+ disp=(APPLIED|FILTERED|NO_ROW) vm=\d+""")
            val bad = disp.filterNot { shape.containsMatchIn(it) }
            assertTrue("malformed disp lines: $bad", bad.isEmpty())
            val noKey = disp.filterNot { Regex("""dbKey=[^ ]+""").containsMatchIn(it) }
            assertTrue("disp lines must name the chat they were dropped/applied for: $noKey",
                noKey.isEmpty())
            for (type in listOf("message.start", "message.delta", "message.complete")) {
                assertTrue("no APPLIED disposition for $type", disp.any { it.contains("type=$type") })
            }
            assertTrue("own-session frames must not be reported FILTERED",
                disp.none { it.contains("disp=FILTERED") })
        } finally {
            File(outDir, "disp_lines_a2.txt").writeText(disp.joinToString("\n"))
        }
    }

    @Test
    fun a4_liveSidChangesAndVmRegisterUnregisterAreLogged() {
        val r = threeFrameTrial()
        val ident = r.identityLines
        File(outDir, "identity_lines_a4.txt").writeText(ident.joinToString("\n"))
        assertTrue("no TRACE|identity lines: liveSid changes and VM (un)registration are invisible",
            ident.isNotEmpty())
        val vmHashes = ident.mapNotNull { Regex("""vm=(\d+)""").find(it)?.groupValues?.get(1) }.toSet()
        assertTrue("identity lines must name the ViewModel instance", vmHashes.isNotEmpty())
        assertTrue("register must be logged with dbKey", ident.any { it.contains("event=register") && it.contains("dbKey=") })
        assertTrue("the session.resume liveSid assignment must be logged old/new",
            ident.any { it.contains("event=live_sid") && it.contains("new=${ReplayStream.LIVE_SID}") })
        assertTrue("unregister must be logged", ident.any { it.contains("event=unregister") })
    }

    /** A2's FILTERED branch must carry the three values that explain the drop. */
    @Test
    fun a2_filteredDispositionLineNamesNSidDbKeyAndLiveSid() {
        val spec = ReplayStream.spec()
        val r = ReplayTestbed(app(), outDir).runTrial(
            ReplayTestbed.TrialConfig(
                ReplayTestbed.Mode.NONE, ReplayTestbed.RingMode.INTACT, 2,
                frames = listOf(spec.frames[spec.idxStart], spec.frames[spec.idxFirstContentDelta],
                    spec.frames[spec.idxComplete]),
                overrideSidFromIdx = 0, overrideSid = "p2foreign77",   // another session
            )
        )
        val filtered = r.dispLines.filter { it.contains("disp=FILTERED") }
        assertTrue("expected FILTERED disposition lines for a foreign session", filtered.isNotEmpty())
        val bad = filtered.filterNot {
            it.contains("nSid=") && it.contains("dbKey=") && it.contains("liveSid=")
        }
        assertTrue("FILTERED lines must state why (nSid/dbKey/liveSid): $bad", bad.isEmpty())
    }
}
