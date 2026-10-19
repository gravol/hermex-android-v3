package com.hermex.v3.feature.chat

import com.hermex.core.network.RpcNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the protocol-trace path used by events that are parsed but NOT rendered.
 *
 * Two invariants matter here, and both are testable without Android:
 *  1. the trace line carries ids and counts only — never payload text. The debug journal is
 *     shareable (file_paths.xml exposes it), and these payloads carry process output, review
 *     text and status lines that must not leak into an exported log.
 *  2. volume is bounded. `platforms.changed` fires roughly every 5 s on a live gateway
 *     (1633 occurrences across the D_* trial journals); one line per event would evict
 *     DebugLog's 1000-entry ring within minutes, which is exactly what the existing
 *     delta-batching comments were written to avoid.
 */
class ProtocolTraceTest {

    // ── plan(): shape of the line, no payload text ──────────────────────────────────

    @Test fun statusUpdatePlanCarriesKindAndLengthNotText() {
        val n = RpcNotification.StatusUpdate(
            sessionId = "955c00b6",
            kind = "process",
            text = "[Background process proc_487b3b9829de heartbeat #1 — still running after 5m",
        )
        val line = ProtocolTrace.plan(n)!!
        assertEquals("status.update", line.name)
        assertTrue(line.fields.contains("kind=process"))
        assertTrue(line.fields.contains("textLen=${n.text.length}"))
        assertNoPayloadText(line, n.text)
    }

    @Test fun terminalOutputPlanCarriesProcessIdAndChunkLength() {
        val n = RpcNotification.TerminalOutput(
            sessionId = "955c00b6",
            processId = "proc_487b3b9829de",
            chunk = "  prompt send: sent\n",
        )
        val line = ProtocolTrace.plan(n)!!
        assertEquals("agent.terminal.output", line.name)
        assertTrue(line.fields.contains("proc=proc_487b3b9829de"))
        assertTrue(line.fields.contains("chunkLen=${n.chunk.length}"))
        assertNoPayloadText(line, n.chunk)
    }

    @Test fun reviewSummaryPlanCarriesLengthOnly() {
        val n = RpcNotification.ReviewSummary(
            sessionId = "34b8c2a1",
            text = "💾 Self-improvement review: Skill 'openeq' patched · Skill 'directory-file-inventory' created.",
        )
        val line = ProtocolTrace.plan(n)!!
        assertEquals("review.summary", line.name)
        assertTrue(line.fields.contains("textLen=${n.text.length}"))
        assertNoPayloadText(line, n.text)
    }

    @Test fun changeSignalPlansAreFieldlessSignals() {
        // The signal is the fact that something moved; there is nothing to report but the name.
        assertEquals("cron.changed", ProtocolTrace.plan(RpcNotification.CronChanged(""))!!.name)
        assertEquals("platforms.changed", ProtocolTrace.plan(RpcNotification.PlatformsChanged(""))!!.name)
    }

    @Test fun renderedAndUnknownEventsHaveNoProtocolTracePlan() {
        // Events with a real UI path must not double-log through the proto trace, and neither
        // may Unknown (it already logs its own rawParams line in handleNotification).
        assertNull(ProtocolTrace.plan(RpcNotification.MessageDelta("s", "hello")))
        assertNull(ProtocolTrace.plan(RpcNotification.SessionChanged(null)))
        assertNull(ProtocolTrace.plan(RpcNotification.Unknown("subagent.tool", null, "s")))
    }

    private fun assertNoPayloadText(line: ProtocolTraceLine, payloadText: String) {
        val joined = (listOf(line.name) + line.fields).joinToString(" ")
        val probe = payloadText.trim().takeWhile { !it.isWhitespace() }
        assertTrue("trace leaks payload text: $joined", probe.isEmpty() || !joined.contains(probe))
        assertFalse(joined.contains("Self-improvement"))
        assertFalse(joined.contains("prompt send"))
    }

    // ── gate(): bounded line rate per event name ─────────────────────────────────────

    @Test fun firstOccurrenceAlwaysLogsThenEveryNth() {
        val gate = ProtoTraceGate(everyN = 25)
        val logged = (1..60).filter { gate.shouldLog("platforms.changed") }
        assertEquals(listOf(1, 25, 50), logged)
    }

    @Test fun gateCountsArePerEventName() {
        val gate = ProtoTraceGate(everyN = 25)
        assertTrue(gate.shouldLog("cron.changed"))          // #1 always logs
        repeat(23) { assertFalse(gate.shouldLog("cron.changed")) }  // #2..#24 suppressed
        assertTrue("25th cron.changed must log", gate.shouldLog("cron.changed"))
        // A different name starts its own series — the flood of one signal must not
        // silence another signal's first line.
        assertTrue(gate.shouldLog("status.update"))
        assertEquals(25, gate.countOf("cron.changed"))
        assertEquals(1, gate.countOf("status.update"))
        assertEquals(0, gate.countOf("review.summary"))
    }

    @Test fun countsTrackEvenWhenSuppressed() {
        // The count is what makes a suppressed burst still auditable: the line that does
        // print says "count=25", i.e. 24 were dropped, not lost.
        val gate = ProtoTraceGate(everyN = 10)
        repeat(97) { gate.shouldLog("agent.terminal.output") }
        assertEquals(97, gate.countOf("agent.terminal.output"))
    }

    @Test fun everyNBelowOneIsClamped() {
        // Defensive: everyN=0 would mean modulo-by-zero / log-everything depending on impl.
        val gate = ProtoTraceGate(everyN = 0)
        assertTrue(gate.shouldLog("x"))
        assertTrue(gate.shouldLog("x"))
    }
}
