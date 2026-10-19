package com.hermex.v3.feature.chat.replay

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P2 supplement — completes what the main sweep's time budget truncated:
 *  1. second-VM registrations for insertion points idx >= 310 (the main sweep ran
 *     out of its 780 s internal budget before reaching them);
 *  2. a raw TRACE/gate + TRACE/replay_decision dump for the RECONNECT/TRUNCATED
 *     insert-after-frame-0 case, so the reconcile-decision evidence is on disk
 *     verbatim instead of parsed into a CSV column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class P2ReplaySupplementTest {

    private val outDir = File("/home/jeff/hermex-qa/longrun/artifacts/p2").apply { mkdirs() }

    @Test
    fun tailSweepSecondVmRegistrationsIdx310To423() {
        val tb = ReplayTestbed(ApplicationProvider.getApplicationContext(), outDir)
        val spec = ReplayStream.spec()
        val matrix = File(outDir, "matrix_supplement.csv")
        matrix.writeText(
            "mode,ring,injectAfter,frameType,blankA,rowIdA,contentLenA,streamA,thinkLenA," +
                "blankB,rowIdB,contentLenB,reconcile,replayAction,filteredLines,genEnd\n"
        )
        var blanks = 0
        for (i in 310..spec.frames.lastIndex) {
            for (mode in listOf(ReplayTestbed.Mode.SECOND_VM, ReplayTestbed.Mode.SECOND_VM_SID_ROTATION)) {
                val cfg = ReplayTestbed.TrialConfig(mode, ReplayTestbed.RingMode.INTACT, i)
                val r = tb.runTrial(cfg)
                matrix.appendText(
                    listOf(
                        mode, cfg.ringMode, i, spec.frames[i].type,
                        r.blankA, r.obsA.rowId, r.obsA.contentLen, r.obsA.streaming, r.obsA.thinkingLen,
                        r.blankB ?: "", r.obsB?.rowId ?: "", r.obsB?.contentLen ?: "",
                        r.gateLines.map { it.hashCode() }.joinToString(";"), "-",
                        r.filteredCount, r.genAtEnd,
                    ).joinToString(",") + "\n"
                )
                if (r.blankA || r.blankB == true) blanks++
            }
        }
        println("P2 tail sweep done: trials=${2 * (spec.frames.lastIndex - 309)} blanks=$blanks")
    }

    @Test
    fun gateDecisionDumpEarlyTruncatedReconnect() {
        val tb = ReplayTestbed(ApplicationProvider.getApplicationContext(), outDir)
        val r = tb.runTrial(
            ReplayTestbed.TrialConfig(
                ReplayTestbed.Mode.RECONNECT, ReplayTestbed.RingMode.TRUNCATED_EMPTY, 0, detailedTags = true,
            )
        )
        File(outDir, "gate_debug_truncated_idx0.txt").writeText(
            buildString {
                appendLine("trial: RECONNECT/TRUNCATED_EMPTY after idx 0 (message.start)")
                appendLine("blankA=${r.blankA} obsA=${r.obsA} filteredCount=${r.filteredCount}")
                appendLine("gate/replay_decision lines (verbatim from DebugLog ring buffer):")
                r.gateLines.forEach { appendLine(it) }
            }
        )
        // This early-truncated case must NOT end blank: live frames re-render after the reload.
        assertTrue("expected non-blank after early truncated reconnect (frames re-delivered)", !r.blankA)
    }
}
