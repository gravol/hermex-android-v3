package com.hermex.v3.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The D/14 failure shape, pinned as a test.
 *
 * Measured on v0.1.892 (trial matrix note, drop-trial-harness-v0.1.892-2026-10-18.md):
 * the gateway routed the whole turn — `message.start`, 258 `message.delta`,
 * `message.complete` — to the phone's live transport (`route=session_transport`) and
 * finished it ~5 s BEFORE I killed the socket. The app applied `message.start`, lost
 * the delta stream to its own handling, and ended up holding an assistant row with no
 * text plus a tool card that the watchdog force-closed.
 *
 * At the next resume the reconcile gate read `running=false localInFlight=false
 * turnDoneSeen=true reconcile=false`: nothing looked in flight, and the server's
 * message count EQUALLED the local row count, because the blank row is itself a row.
 * So all three existing rules (A: streaming/open card, B: running-flag transition,
 * C: count gap) are blind to this state, and the user just sees their prompt with no
 * answer.
 *
 * Rule D: a turn that the server says is over must not leave a blank final assistant
 * row. A finished turn always ends with text, so a blank one means our copy is wrong
 * and the history has to be re-read. Gated on knowing a turn ran in this chat
 * (`serverTurnWasRunning`/`turnDoneSeen`) so an old blank row from an aborted turn
 * cannot start a reload loop.
 */
class ReconcileBlankTurnTest {

    private fun user(text: String = "prompt") = UiMessage(id = "u1", role = "user", content = text)

    private fun assistant(content: String, streaming: Boolean = false, toolCompleted: Boolean = true) =
        UiMessage(
            id = "m1", role = "assistant", content = content, isStreaming = streaming,
            toolCalls = listOf(UiToolCall(id = "t1", toolName = "terminal", completed = toolCompleted)),
        )

    /** D/14 exactly: local rows match the server count, nothing streams, cards closed,
     *  but the final assistant row has no content. */
    @Test fun finishedTurnWithBlankFinalAssistantRow_reloads() {
        val msgs = listOf(user(), assistant(content = ""))
        assertFalse(Reconcile.localInFlight(msgs))                 // rule A blind
        assertFalse(Reconcile.needsReload(msgs, serverRunning = false,
            serverMessageCount = msgs.size, serverTurnWasRunning = false)) // rule C blind (before fix)
        assertTrue(Reconcile.needsReload(msgs, serverRunning = false,
            serverMessageCount = msgs.size, serverTurnWasRunning = false, turnDoneSeen = true))
    }

    /** Same blank row, but we watched the turn run — rule B already covers it; this
     *  just documents that both paths agree. */
    @Test fun blankRowAfterWatchingTheTurnRun_reloads() {
        val msgs = listOf(user(), assistant(content = ""))
        assertTrue(Reconcile.needsReload(msgs, serverRunning = false,
            serverMessageCount = msgs.size, serverTurnWasRunning = true))
    }

    /** A normal completed turn: the final assistant row has text. Never reload. */
    @Test fun completedTurnWithContent_doesNotReload() {
        val msgs = listOf(user(), assistant(content = "PONG"))
        assertFalse(Reconcile.needsReload(msgs, serverRunning = false,
            serverMessageCount = msgs.size, serverTurnWasRunning = false, turnDoneSeen = true))
    }

    /** An old blank assistant row from an aborted turn, with no turn known to have run
     *  in this session, must not fire every poll. */
    @Test fun staleBlankRowWithoutATurn_doesNotReload() {
        val msgs = listOf(user(), assistant(content = ""))
        assertFalse(Reconcile.needsReload(msgs, serverRunning = false,
            serverMessageCount = msgs.size, serverTurnWasRunning = false, turnDoneSeen = false))
    }

    /** While the turn is still running a blank assistant row is normal (text arrives
     *  later), so rule D must stay off — otherwise it reloads mid-turn and duplicates. */
    @Test fun blankAssistantWhileServerRunning_doesNotReload() {
        val msgs = listOf(user(), assistant(content = "", streaming = true))
        assertFalse(Reconcile.needsReload(msgs, serverRunning = true,
            serverMessageCount = msgs.size, serverTurnWasRunning = true, turnDoneSeen = false))
    }

    /** Whitespace-only content counts as blank: the D/14 row rendered nothing visible. */
    @Test fun whitespaceOnlyFinalRow_reloads() {
        val msgs = listOf(user(), assistant(content = "   \n "))
        assertTrue(Reconcile.needsReload(msgs, serverRunning = false,
            serverMessageCount = msgs.size, serverTurnWasRunning = false, turnDoneSeen = true))
    }
}
