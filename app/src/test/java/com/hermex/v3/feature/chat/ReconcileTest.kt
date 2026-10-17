package com.hermex.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frozen-tool-card gate, tested as a pure function. These cases were found in
 * field logs and in the v0.1.886 device repro, where the `isStreaming`-only gate
 * never fired.
 */
class ReconcileTest {

    private fun assistant(
        streaming: Boolean = false,
        toolCompleted: Boolean = true,
    ) = UiMessage(
        id = "m1",
        role = "assistant",
        content = "partial answer",
        isStreaming = streaming,
        toolCalls = listOf(UiToolCall(id = "t1", toolName = "terminal",
            completed = toolCompleted)),
    )

    private fun user() = UiMessage(id = "u1", role = "user", content = "prompt")

    // A. turn finished while disconnected, UI still streaming
    @Test fun streamingMessageAndServerStopped_reloads() {
        assertTrue(Reconcile.needsReload(listOf(user(), assistant(streaming = true)),
            serverRunning = false, serverMessageCount = 2, serverTurnWasRunning = false))
    }

    // The v0.1.886 gate bug: the watchdog cleared isStreaming, leaving a tool card
    // that never completed. isStreaming-only gate returns false here; this must not.
    @Test fun watchdogClearedStreamingButToolUnfinished_reloads() {
        val msgs = listOf(user(), assistant(streaming = false, toolCompleted = false))
        assertFalse(Reconcile.localInFlight(msgs).let { it && false })  // sanity: list non-empty
        assertTrue(Reconcile.localInFlight(msgs))
        assertTrue(Reconcile.needsReload(msgs, serverRunning = false,
            serverMessageCount = 2, serverTurnWasRunning = false))
    }

    // B. attached mid-turn: every local row is complete, so nothing looks in-flight.
    // Only the running-flag transition catches this — the case the device repro proved
    // the isStreaming gate misses.
    @Test fun attachedMidTurnRunningTrueThenFalse_reloads() {
        val msgs = listOf(user(), assistant(streaming = false, toolCompleted = true))
        assertFalse(Reconcile.localInFlight(msgs))
        // first poll: server running, count matches local -> nothing to do
        assertFalse(Reconcile.needsReload(msgs, serverRunning = true,
            serverMessageCount = 2, serverTurnWasRunning = false))
        // later poll: turn ended
        assertTrue(Reconcile.needsReload(msgs, serverRunning = false,
            serverMessageCount = 2, serverTurnWasRunning = true))
    }

    // C. server has more rows than the local list — tail of the turn missing.
    @Test fun serverHasMoreRows_reloads() {
        val msgs = listOf(user(), assistant(streaming = false, toolCompleted = true))
        assertTrue(Reconcile.needsReload(msgs, serverRunning = true,
            serverMessageCount = 26, serverTurnWasRunning = false))
    }

    // No-op cases: must not reload a chat that is already caught up.
    @Test fun caughtUpDoesNotReload() {
        val msgs = listOf(user(), assistant(streaming = false, toolCompleted = true))
        assertFalse(Reconcile.needsReload(msgs, serverRunning = false,
            serverMessageCount = 2, serverTurnWasRunning = false))
    }

    @Test fun runningFlagAloneDoesNotReloadWhenCaughtUp() {
        val msgs = listOf(user(), assistant(streaming = false, toolCompleted = true))
        assertFalse(Reconcile.needsReload(msgs, serverRunning = true,
            serverMessageCount = 2, serverTurnWasRunning = true))
    }

    @Test fun nullRunningAndNullCountAreSafe() {
        val msgs = listOf(user(), assistant(streaming = false, toolCompleted = true))
        assertFalse(Reconcile.needsReload(msgs, serverRunning = null,
            serverMessageCount = null, serverTurnWasRunning = false))
    }

    @Test fun userMessageStreamingIsNotInFlight() {
        val msgs = listOf(UiMessage(id = "u1", role = "user", content = "x",
            isStreaming = true))
        assertFalse(Reconcile.localInFlight(msgs))
    }

    // ── v0.1.889 tool-card watchdog ──

    @Test fun openCardClosesWhenServerTurnNotRunning() {
        val msgs = listOf(assistant(streaming = false, toolCompleted = false))
        val r = Reconcile.closeStaleCards(msgs, serverRunning = false, turnDoneSeen = false)
        assertTrue(r != null)
        assertEquals(1, r!!.second)
        assertTrue(r.first[0].toolCalls[0].completed)
    }

    @Test fun openCardClosesWhenTurnDoneSeen() {
        val msgs = listOf(assistant(streaming = false, toolCompleted = false))
        val r = Reconcile.closeStaleCards(msgs, serverRunning = true, turnDoneSeen = true)
        assertTrue(r != null && r.first[0].toolCalls[0].completed)
    }

    @Test fun longRunningToolIsNotClosed() {
        // False-positive guard: the turn IS running, so a `tool.complete` is still
        // coming. A genuinely long-running tool must keep spinning.
        val msgs = listOf(assistant(streaming = false, toolCompleted = false))
        assertTrue(Reconcile.closeStaleCards(msgs, serverRunning = true, turnDoneSeen = false) == null)
    }

    @Test fun alreadyCompletedCardsAreUntouched() {
        val msgs = listOf(assistant(streaming = false, toolCompleted = true))
        assertTrue(Reconcile.closeStaleCards(msgs, serverRunning = false, turnDoneSeen = false) == null)
    }

    @Test fun nullRunningWithNoTurnDoneIsSafe() {
        val msgs = listOf(assistant(streaming = false, toolCompleted = false))
        assertTrue(Reconcile.closeStaleCards(msgs, serverRunning = null, turnDoneSeen = false) == null)
    }
}
