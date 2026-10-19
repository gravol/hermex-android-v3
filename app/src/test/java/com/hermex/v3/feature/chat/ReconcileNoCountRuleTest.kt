package com.hermex.v3.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX 1 — Reconcile rule C (`serverMessageCount > messages.size`) must be gone.
 *
 * Why it is not a heuristic but a category error: `sessions.message_count` is the
 * gateway's PERSISTED transcript counter (user+assistant rows it has written to its
 * own store, including rows this client never renders — tool rows, system rows, rows
 * created after our last resume), while `messages.size` is the app's rendered row
 * count for one chat screen. They are different quantities with a permanently
 * different zero point.
 *
 * Measured across 37 captured production journals (note
 * `HermexAndroid/poll-loop-dead-ruleC-falsepositive-2026-10-19.md`): 235 of 315 gate
 * evaluations fired rule C alone, with stable offsets (`count=46 local=38`,
 * `count=100 local=90`) and sometimes inverted (`count=230 local=375`). It fires on
 * essentially every evaluation, so it also MASKS rules A/B/D (short-circuit) and,
 * together with the loop's `break`, killed the poll loop at its first tick.
 *
 * These tests assert the count gap alone reloads nothing, and that A/B/D still fire
 * without any count input helping them.
 */
class ReconcileNoCountRuleTest {

    private fun user(text: String = "prompt") = UiMessage(id = "u1", role = "user", content = text)

    private fun assistant(
        content: String = "a complete answer",
        streaming: Boolean = false,
        toolCompleted: Boolean = true,
    ) = UiMessage(
        id = "m1", role = "assistant", content = content, isStreaming = streaming,
        toolCalls = listOf(UiToolCall(id = "t1", toolName = "terminal", completed = toolCompleted)),
    )

    /** The production false positive, verbatim in shape: a caught-up chat whose
     *  server counter is ahead of the rendered list for reasons unrelated to any
     *  missing turn tail. Must NOT reload. */
    @Test fun countGapAloneDoesNotReload_measuredFalsePositive() {
        val msgs = listOf(user(), assistant())
        assertFalse(
            "rule C: server count 46 vs 2 rendered rows must not reload a caught-up chat",
            Reconcile.needsReload(msgs, serverRunning = false, serverMessageCount = 46,
                serverTurnWasRunning = false),
        )
        assertFalse(
            "rule C: a count gap while the turn is RUNNING must not reload either",
            Reconcile.needsReload(msgs, serverRunning = true, serverMessageCount = 46,
                serverTurnWasRunning = false),
        )
    }

    /** A big offset with the turn explicitly over and nothing in flight — the
     *  `count=100 local=90` class of reading. Not a stuck UI, so no reload. */
    @Test fun largeStableCountOffsetDoesNotReload() {
        val msgs = ArrayList<UiMessage>()
        msgs += user()
        msgs += assistant()
        repeat(8) { i -> msgs += user("prompt $i"); msgs += assistant("answer $i") }
        // 10 rendered rows, server claims 100 — an offset, not a missing tail.
        assertFalse(Reconcile.localInFlight(msgs))
        assertFalse(Reconcile.needsReload(msgs, serverRunning = false, serverMessageCount = 100,
            serverTurnWasRunning = false))
    }

    /** Rules A, B and D must still fire with counts that give them NO help. */
    @Test fun rulesA_B_D_stillFireWithoutTheCountRule() {
        // A: turn finished while disconnected, UI still streaming (counts equal).
        assertTrue(Reconcile.needsReload(listOf(user(), assistant(streaming = true)),
            serverRunning = false, serverMessageCount = 2, serverTurnWasRunning = false))
        // A': an unfinished tool card with the server stopped (counts equal).
        assertTrue(Reconcile.needsReload(listOf(user(), assistant(toolCompleted = false)),
            serverRunning = false, serverMessageCount = 2, serverTurnWasRunning = false))
        // B: attached mid-turn — running-flag transition, counts equal.
        assertTrue(Reconcile.needsReload(listOf(user(), assistant()),
            serverRunning = false, serverMessageCount = 2, serverTurnWasRunning = true))
        // D: blank final assistant row after a turn we know completed.
        assertTrue(Reconcile.needsReload(listOf(user(), assistant(content = "")),
            serverRunning = false, serverMessageCount = 2, serverTurnWasRunning = false,
            turnDoneSeen = true))
    }

    /** Guard for the opposite offset direction (count BELOW local rows): also never
     *  a reload signal on its own. */
    @Test fun countBelowLocalRowsDoesNotReload() {
        val msgs = listOf(user(), assistant(), user("second"), assistant("second answer"))
        assertFalse(Reconcile.needsReload(msgs, serverRunning = false, serverMessageCount = 1,
            serverTurnWasRunning = false))
    }
}
