package com.hermex.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The target-row choice for a terminal `message.complete` (v0.1.894 FIX 3 guard).
 *
 * Two real failures sit on either side of this decision:
 *  - the old code's `indexOfLast { role == "assistant" }` was a **silent no-op** when no
 *    assistant row existed, so a client that attached late rendered nothing at all
 *    (P2 testbed: SECOND_VM at insertion index 422, `rowIdB=NO_ROW`);
 *  - blindly taking the last assistant row would let a late or replayed terminal event
 *    overwrite an ALREADY-FINISHED answer from an earlier turn — trading the blank-row
 *    bug for a lost answer. That is the case all three panel reviewers flagged.
 *
 * The rule under test: exact server id → the row actually streaming → a last-assistant
 * fallback only if it belongs to this turn (after the last user message, and either our
 * own placeholder or the same server id) → otherwise create a new row.
 */
class ReconcileTerminalUpsertTest {

    private fun user(id: String, text: String = "p") = UiMessage(id = id, role = "user", content = text)
    private fun asst(id: String, content: String = "", streaming: Boolean = false) =
        UiMessage(id = id, role = "assistant", content = content, isStreaming = streaming)

    @Test fun exactServerIdMatchIsPreferredOverEverythingElse() {
        val msgs = listOf(
            user("u1"),
            asst("m_other", "older answer"),
            asst("m_target", "partial", streaming = true),
        )
        val (idx, reason) = Reconcile.terminalUpsertTarget(msgs, "m_target")
        assertEquals("the id match wins even though a streaming row also exists", 2, idx)
        assertNull(reason)
    }

    @Test fun streamingRowIsChosenWhenTheIdIsUnknownToUs() {
        val msgs = listOf(user("u1"), asst("asst_7", "streaming so far", streaming = true))
        val (idx, reason) = Reconcile.terminalUpsertTarget(msgs, "srv-99")
        assertEquals("the live streaming row of this turn is the target", 1, idx)
        assertNull(reason)
    }

    /** The panel's case: a late complete must not land on an earlier finished turn. */
    @Test fun completedRowFromAnEarlierTurnIsRefusedAndANewRowIsCreated() {
        val msgs = listOf(
            user("u1"), asst("m_a1", "first answer"),
            user("u2"),                       // second turn, its answer never arrived
        )
        val (idx, reason) = Reconcile.terminalUpsertTarget(msgs, "m_a2")
        assertEquals("no in-place target — the caller must create a row", -1, idx)
        assertNotNull("the refusal must be explainable in the journal", reason)
        assertTrue("reason should name the position rule: $reason",
            reason!!.contains("earlier turn"))
    }

    @Test fun finishedServerRowAfterTheLastUserIsStillRefusedWhenIdsDiffer() {
        // History re-read mid-turn: the last assistant row is a server row from a previous
        // turn that happens to sit after the last user row (e.g. an assistant-only turn).
        // It carries a different server id, so it must not be overwritten.
        val msgs = listOf(user("u1"), asst("m_prev", "previous answer"))
        val (idx, reason) = Reconcile.terminalUpsertTarget(msgs, "m_new")
        assertEquals(-1, idx)
        assertNotNull(reason)
        assertTrue("reason should name the id rule: $reason", reason!!.contains("finished server row"))
    }

    @Test fun ourOwnPlaceholderAfterTheLastUserIsAccepted() {
        // The normal case for a client that got message.start but lost the deltas: the row
        // exists as our own placeholder and this turn's complete belongs on it.
        val msgs = listOf(user("u1"), asst("asst_3", "", streaming = false))
        val (idx, reason) = Reconcile.terminalUpsertTarget(msgs, null)
        assertEquals(1, idx)
        assertNull(reason)
    }

    @Test fun noAssistantRowAtAllMeansCreateWithoutARefusal() {
        val msgs = listOf(user("u1"))
        val (idx, reason) = Reconcile.terminalUpsertTarget(msgs, "m_first")
        assertEquals(-1, idx)
        assertNull("nothing was refused — FIX 3 must not log a refusal here", reason)
    }

    @Test fun blankServerMessageIdBehavesLikeNoId() {
        val msgs = listOf(user("u1"), asst("m_a", "older"))
        val (idx, reason) = Reconcile.terminalUpsertTarget(msgs, "   ")
        assertEquals("a blank id must not disable the earlier-turn guard", -1, idx)
        assertNotNull(reason)
    }
}
