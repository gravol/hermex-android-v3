package com.hermex.v3.feature.chat.replay

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P2 — frame-replay sweep for the D/14 blank-final-row failure.
 *
 * Baseline test: with NO transport event injected, the real reducer must render the
 * served answer (plumbing sanity + positive control that the stream itself is good).
 *
 * Sweep test: inject a transport detach/reconnect (ring intact / ring lost) or a second
 * chat ViewModel registering for the same session after EVERY frame index of the
 * 424-frame stream, and report at which insertion points the final assistant row ends
 * up BLANK while the served message.complete text is non-empty.
 *
 * The expectation FLIPPED in v0.1.894 and that flip is deliberate. Before the fixes this
 * test's job was to prove blanks reproduce (positive control: `blanks.isNotEmpty()`), and
 * it did — SECOND_VM@422 ended NO_ROW and SECOND_VM_SID_ROTATION@0 left vmA holding a
 * zero-character row (longrun/P2_replay_results.md, artifacts/p2/matrix*.csv). After
 * FIX 3 (terminal event = id-keyed upsert) and FIX 4 (a rotated live sid belonging to
 * THIS session is adopted, not dropped) the same sweep is the regression guard: no
 * insertion point may end blank. The pre-fix behaviour is pinned separately by the
 * failing-test-first runs recorded for those two fixes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class P2FrameReplayTest {

    private val outDir = File("/home/jeff/hermex-qa/longrun/artifacts/p2").apply { mkdirs() }

    private fun app(): android.app.Application =
        ApplicationProvider.getApplicationContext()

    @Test
    fun baseline_fullStreamNoInjection_rendersServedAnswer() {
        val tb = ReplayTestbed(app(), outDir)
        val r = tb.runTrial(
            ReplayTestbed.TrialConfig(
                mode = ReplayTestbed.Mode.NONE,
                ringMode = ReplayTestbed.RingMode.INTACT,
                injectAfter = -1,
                detailedTags = true,
            )
        )
        // The full pipeline (processFrame -> SharedFlow fan-out -> handleNotification ->
        // delta batching/flush -> completion) must land the complete answer.
        assertTrue("baseline final row missing", r.obsA.present)
        assertEquals("baseline final row content must equal served message.complete text",
            ReplayStream.FULL_ANSWER.length, r.obsA.contentLen)
        assertEquals("final row must not still be streaming", false, r.obsA.streaming)
        assertEquals("reasoning.delta x161 + thinking.delta must reach thinkingText",
            ReplayStream.FULL_REASONING.length + ReplayStream.thinkingSegment.length,
            r.obsA.thinkingLen)
        println("P2 baseline OK: final row id=${r.obsA.rowId} contentLen=${r.obsA.contentLen} " +
            "thinkingLen=${r.obsA.thinkingLen} — tags at ${File(outDir, "tags_NONE_INTACT_after-1.log")}")
    }

    @Test
    fun sweep_blankFinalRowAcrossAllFrameInsertionPoints() {
        val tb = ReplayTestbed(app(), outDir)
        val spec = ReplayStream.spec()
        val matrix = File(outDir, "matrix.csv")
        matrix.writeText(
            "mode,ring,injectAfter,frameType,blankA,rowIdA,contentLenA,streamA,thinkLenA," +
                "blankB,rowIdB,contentLenB,reconcile,replayAction,filteredLines,genEnd\n"
        )
        data class BlankHit(val cfg: ReplayTestbed.TrialConfig, val which: String,
                            val obs: ReplayTestbed.RowObservation, val gate: List<String>)
        val blanks = ArrayList<BlankHit>()
        val perConfigCounts = LinkedHashMap<String, Int>()
        val startMs = System.currentTimeMillis()
        var sweepTruncatedAt: String? = null

        val configs = ArrayList<ReplayTestbed.TrialConfig>()
        for (ring in listOf(ReplayTestbed.RingMode.INTACT, ReplayTestbed.RingMode.TRUNCATED_EMPTY)) {
            for (i in 0..spec.frames.lastIndex) {
                configs += ReplayTestbed.TrialConfig(ReplayTestbed.Mode.RECONNECT, ring, i)
            }
        }
        for (i in 0..spec.frames.lastIndex) {
            configs += ReplayTestbed.TrialConfig(ReplayTestbed.Mode.SECOND_VM, ReplayTestbed.RingMode.INTACT, i)
            configs += ReplayTestbed.TrialConfig(ReplayTestbed.Mode.SECOND_VM_SID_ROTATION, ReplayTestbed.RingMode.INTACT, i)
        }

        for ((n, cfg) in configs.withIndex()) {
            if (System.currentTimeMillis() - startMs > 780_000) {
                sweepTruncatedAt = "stopped after $n/${configs.size} trials (time budget)"
                break
            }
            val detailed = cfg.injectAfter % 101 == 0
            val r = tb.runTrial(cfg.copy(detailedTags = detailed))
            val frameType = spec.frames[cfg.injectAfter].type
            val reconcile = r.gateLines.firstOrNull { it.contains("reconcile=") }
                ?.substringAfter("reconcile=")?.take(5) ?: "-"
            val replayAction = r.gateLines.firstOrNull { it.contains("action=") }
                ?.substringAfter("action=")?.take(12) ?: "-"
            matrix.appendText(
                listOf(
                    cfg.mode, cfg.ringMode, cfg.injectAfter, frameType,
                    r.blankA, r.obsA.rowId, r.obsA.contentLen, r.obsA.streaming, r.obsA.thinkingLen,
                    r.blankB ?: "", r.obsB?.rowId ?: "", r.obsB?.contentLen ?: "",
                    reconcile, replayAction, r.filteredCount, r.genAtEnd,
                ).joinToString(",") + "\n"
            )
            if (r.blankA) blanks += BlankHit(cfg, "A", r.obsA, r.gateLines)
            if (r.blankB == true) blanks += BlankHit(cfg, "B", r.obsB!!, r.gateLines)
            perConfigCounts.merge("${cfg.mode}/${cfg.ringMode}/blank=${r.blankA || r.blankB == true}", 1) { a, b -> a + b }
            if (n % 100 == 0) {
                println("P2 sweep $n/${configs.size} elapsed=${(System.currentTimeMillis() - startMs) / 1000}s blanks=${blanks.size}")
            }
        }

        val summary = File(outDir, "summary.txt")
        summary.writeText(buildString {
            appendLine("P2 frame-replay sweep — ${java.time.Instant.now()}")
            appendLine("trials configured=${configs.size} run=${minOf(configs.size, if (sweepTruncatedAt == null) configs.size else -1)}" +
                (sweepTruncatedAt?.let { " TRUNCATED: $it" } ?: ""))
            appendLine("blank-final-row hits: ${blanks.size}")
            for (b in blanks.take(80)) {
                appendLine("BLANK mode=${b.cfg.mode} ring=${b.cfg.ringMode} afterIdx=${b.cfg.injectAfter}" +
                    "(${spec.frames[b.cfg.injectAfter].type}) vm=${b.which} row=${b.obs.rowId} " +
                    "present=${b.obs.present} contentLen=${b.obs.contentLen} stream=${b.obs.streaming}")
                b.gate.take(3).forEach { appendLine("   gate: $it") }
            }
            appendLine("per-config tallies:")
            perConfigCounts.forEach { (k, v) -> appendLine("  $k -> $v") }
        })
        println("P2 sweep done: blanks=${blanks.size} matrix=${matrix.absolutePath}")

        assertTrue(
            "blank final assistant rows remain at ${blanks.size} insertion points after the " +
            "v0.1.894 fixes (FIX 3 terminal upsert / FIX 4 liveSid rotation adoption): " +
            "${blanks.take(5).map { it.cfg.mode.toString() + "/" + it.which + "@" + it.cfg.injectAfter }} " +
            "— see ${matrix.absolutePath} and summary.txt",
            blanks.isEmpty(),
        )
    }
}
