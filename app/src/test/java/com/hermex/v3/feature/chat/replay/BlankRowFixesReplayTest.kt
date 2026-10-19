package com.hermex.v3.feature.chat.replay

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * FIX 3 (terminal event with no row) and FIX 4 (stale-liveSid filter loss), pinned
 * against the real frame-replay path.
 *
 * Both starting states are reproduced by the existing P2 testbed and were measured
 * before these fixes (longrun/P2_replay_results.md):
 *  - `SECOND_VM` at idx 422: a second chat ViewModel registers for the same session
 *    with only `message.complete` still to come. It renders nothing at all —
 *    `MessageCompleted` does `indexOfLast { role == "assistant" }` and silently does
 *    nothing when there is no such row (`rowIdB=NO_ROW`).
 *  - `SECOND_VM_SID_ROTATION` at idx 0: the second ViewModel's `session.resume` hands
 *    out a NEW live sid, every following frame carries it, and the first ViewModel —
 *    whose `liveSid` is now stale — filters 423 of 424 frames and keeps a zero-char
 *    assistant row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class BlankRowFixesReplayTest {

    private val outDir = File("/home/jeff/hermex-qa/longrun/artifacts/p2fix").apply { mkdirs() }

    private fun app(): android.app.Application =
        ApplicationProvider.getApplicationContext()

    // ── FIX 3: message.complete must be an id-keyed upsert, never a silent no-op ──

    @Test
    fun fixD_lateRegisterThenCompleteOnly_nowRendersTheAnswer() {
        val tb = ReplayTestbed(app(), outDir)
        val r = tb.runTrial(
            ReplayTestbed.TrialConfig(ReplayTestbed.Mode.SECOND_VM, ReplayTestbed.RingMode.INTACT, 422)
        )
        val obsB = r.obsB ?: error("expected a second ViewModel in this trial")
        assertTrue(
            "FIX 3: a terminal message.complete for a session with no assistant row must create it " +
                "(was NO_ROW before the fix); filtered=${r.filteredCount}",
            obsB.present,
        )
        assertEquals("FIX 3: the created row must carry the delivered complete text",
            ReplayStream.FULL_ANSWER.length, obsB.contentLen)
        assertEquals("the created row must not be left streaming", false, obsB.streaming)
        assertEquals("exactly one assistant row for one message id", 1, obsB.assistantRows)
        // The first VM (which saw the whole turn live) must still be correct.
        assertEquals("vmA must be unaffected", ReplayStream.FULL_ANSWER.length, r.obsA.contentLen)
    }

    @Test
    fun fixD_completeAloneInIsolation_createsTheRowWithText() {
        val spec = ReplayStream.spec()
        val tb = ReplayTestbed(app(), outDir)
        val r = tb.runTrial(
            ReplayTestbed.TrialConfig(
                ReplayTestbed.Mode.NONE, ReplayTestbed.RingMode.INTACT, 0,
                frames = listOf(spec.frames[spec.idxComplete]),   // tail-only replay
            )
        )
        assertTrue("FIX 3: a lone message.complete must create the assistant row", r.obsA.present)
        assertEquals(ReplayStream.FULL_ANSWER.length, r.obsA.contentLen)
        assertEquals(1, r.obsA.assistantRows)
    }

    @Test
    fun fixD_normalStreamingTurn_doesNotDuplicateTheRow() {
        val spec = ReplayStream.spec()
        val tb = ReplayTestbed(app(), outDir)
        val r = tb.runTrial(
            ReplayTestbed.TrialConfig(
                ReplayTestbed.Mode.NONE, ReplayTestbed.RingMode.INTACT, 2,
                frames = listOf(spec.frames[spec.idxStart], spec.frames[spec.idxFirstContentDelta],
                    spec.frames[spec.idxComplete]),
            )
        )
        assertTrue(r.obsA.present)
        assertEquals("start+delta+complete must land in exactly ONE assistant row",
            1, r.obsA.assistantRows)
        assertEquals(ReplayStream.FULL_ANSWER.length, r.obsA.contentLen)
    }

    // ── FIX 4: a rotated live sid for THIS session must not silently drop frames ──

    @Test
    fun fixE_staleLiveSidFrames_areAcceptedAndTheRowCompletes() {
        val tb = ReplayTestbed(app(), outDir)
        val r = tb.runTrial(
            ReplayTestbed.TrialConfig(
                ReplayTestbed.Mode.SECOND_VM_SID_ROTATION, ReplayTestbed.RingMode.INTACT, 0,
                detailedTags = true,
            )
        )
        assertFalse(
            "FIX 4: vmA's liveSid went stale after vmB re-attached; its frames must not be " +
                "dropped (was blankA=true with 423 FILTERED lines before the fix)",
            r.blankA,
        )
        assertEquals("vmA must render the full served answer",
            ReplayStream.FULL_ANSWER.length, r.obsA.contentLen)
        assertEquals("vmA must not still be streaming", false, r.obsA.streaming)
        assertTrue(
            "the sid rotation must be visible in the journal (TRACE|identity liveSid rotated)",
            r.identityLines.any { it.contains("liveSid rotated old=") && it.contains("new=") },
        )
        File(outDir, "tags_fixE_sidRotation_after0.log").writeText(r.dispLines.joinToString("\n"))
    }

    @Test
    fun fixE_crossSessionFrames_areStillFiltered() {
        val spec = ReplayStream.spec()
        val tb = ReplayTestbed(app(), outDir)
        // Every frame carries a sid this app never obtained from a resume for this DB key:
        // a genuinely different session. It must stay filtered — FIX 4 must not turn the
        // filter into an accept-anything.
        val r = tb.runTrial(
            ReplayTestbed.TrialConfig(
                ReplayTestbed.Mode.NONE, ReplayTestbed.RingMode.INTACT, spec.frames.lastIndex,
                overrideSidFromIdx = 0, overrideSid = "p2foreign77",
            )
        )
        assertFalse("frames for another session must not create an assistant row here", r.obsA.present)
        assertEquals("no assistant row may be added for a foreign session (history rows excepted)",
            0, r.obsA.assistantRows)
        assertTrue("the foreign frames must be counted as FILTERED", r.filteredCount > 0)
    }

    /**
     * The P2 sweep re-run for FIX 4: SECOND_VM_SID_ROTATION at EVERY insertion index.
     * Before the fix vmA was blank at idx 0 and left `streaming=true` with truncated
     * text elsewhere; now no index may end blank for either ViewModel.
     */
    @Test
    fun fixE_sidRotationSweep_noBlankRowAtAnyInsertionIndex() {
        val tb = ReplayTestbed(app(), outDir)
        val spec = ReplayStream.spec()
        val matrix = File(outDir, "matrix_sid_rotation_after_fix.csv")
        matrix.writeText("injectAfter,frameType,blankA,rowIdA,contentLenA,streamA,blankB,rowIdB,contentLenB,filteredLines\n")
        val blanks = ArrayList<String>()
        for (i in 0..spec.frames.lastIndex) {
            val cfg = ReplayTestbed.TrialConfig(
                ReplayTestbed.Mode.SECOND_VM_SID_ROTATION, ReplayTestbed.RingMode.INTACT, i)
            val r = tb.runTrial(cfg)
            matrix.appendText(listOf(
                i, spec.frames[i].type, r.blankA, r.obsA.rowId, r.obsA.contentLen, r.obsA.streaming,
                r.blankB ?: "", r.obsB?.rowId ?: "", r.obsB?.contentLen ?: "", r.filteredCount,
            ).joinToString(",") + "\n")
            if (r.blankA) blanks += "idx=$i(${spec.frames[i].type}) vm=A row=${r.obsA.rowId} len=${r.obsA.contentLen}"
            if (r.blankB == true) blanks += "idx=$i(${spec.frames[i].type}) vm=B row=${r.obsB!!.rowId} len=${r.obsB.contentLen}"
            if (i % 100 == 0) println("sid-rotation sweep $i/${spec.frames.lastIndex} blanks=${blanks.size}")
        }
        File(outDir, "summary_sid_rotation_after_fix.txt").writeText(
            "SECOND_VM_SID_ROTATION sweep ${spec.frames.size} indices — blanks=${blanks.size}\n" +
                blanks.joinToString("\n") + "\nmatrix=${matrix.absolutePath}\n")
        println("sid-rotation sweep done: trials=${spec.frames.size} blanks=${blanks.size}")
        assertTrue("blank final rows remain: ${blanks.take(10)}", blanks.isEmpty())
    }
}
