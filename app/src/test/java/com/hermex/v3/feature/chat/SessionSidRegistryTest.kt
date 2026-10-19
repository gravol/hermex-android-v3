package com.hermex.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The live-sid ownership map that lets a chat ViewModel accept frames carrying a sid the
 * gateway rotated after someone else re-attached the session (v0.1.894 FIX 4).
 *
 * Why it exists, measured: in the P2 replay testbed the blank final row appeared in exactly
 * one shape — `SECOND_VM_SID_ROTATION_INTACT`, where another ViewModel's `session.resume`
 * handed out a new live sid and the first ViewModel then filtered 423 of 424 frames while
 * still parsing and seq-watermarking them. Accepting a rotated-but-known sid is what fixes
 * that; the panel review asked for the safety rules that make it impossible for one chat to
 * render another chat's answer.
 */
class SessionSidRegistryTest {

    @Before fun reset() = SessionSidRegistry.clear()

    @Test fun aSidFromThisSessionsResumeIsKnownToThisSession() {
        assertTrue(SessionSidRegistry.note("dbKeyA", "sidA1", "loadMessages_resume"))
        assertTrue(SessionSidRegistry.isKnownFor("dbKeyA", "sidA1"))
        assertFalse("an unbound key must never claim it", SessionSidRegistry.isKnownFor("dbKeyB", "sidA1"))
        assertEquals("dbKeyA", SessionSidRegistry.ownerOf("sidA1"))
    }

    @Test fun everyRotationStaysAcceptedUntilTheSessionIsDeleted() {
        // The exact production sequence: attach, then another actor re-attaches, then us.
        SessionSidRegistry.note("dbKeyA", "sidA1", "loadMessages_resume#1")
        SessionSidRegistry.note("dbKeyA", "sidA2", "reconnect_resume#2")
        SessionSidRegistry.note("dbKeyA", "sidA3", "loadMessages_resume#3")
        listOf("sidA1", "sidA2", "sidA3").forEach { sid ->
            assertTrue("$sid must still be accepted for its own session",
                SessionSidRegistry.isKnownFor("dbKeyA", sid))
        }
        assertFalse(SessionSidRegistry.isKnownFor("dbKeyB", "sidA2"))
    }

    @Test fun repeatedResumeForTheSamePairIsNotReportedAsARotation() {
        assertTrue(SessionSidRegistry.note("dbKeyA", "sidA1", "resume#1"))
        assertFalse("a repeat of the same pair is not a rotation and must not log one",
            SessionSidRegistry.note("dbKeyA", "sidA1", "resume#2"))
        assertEquals(1, SessionSidRegistry.sidsOf("dbKeyA").size)
    }

    /** Panel requirement: one live sid, one owning chat. */
    @Test fun aSidClaimedByAnotherSessionIsTakenFromTheOlderOne() {
        SessionSidRegistry.note("dbKeyA", "sidX", "resume")
        SessionSidRegistry.note("dbKeyB", "sidX", "resume")
        assertTrue("the newest claim owns it", SessionSidRegistry.isKnownFor("dbKeyB", "sidX"))
        assertFalse("the older key must stop accepting frames for it — otherwise one chat " +
            "would render another chat's answer", SessionSidRegistry.isKnownFor("dbKeyA", "sidX"))
        assertEquals("dbKeyB", SessionSidRegistry.ownerOf("sidX"))
    }

    @Test fun forgetStopsADeletedSessionsSidsBeingAcceptedAnywhere() {
        SessionSidRegistry.note("dbKeyA", "sidA1", "resume")
        SessionSidRegistry.forget("dbKeyA")
        assertFalse(SessionSidRegistry.isKnownFor("dbKeyA", "sidA1"))
        assertNull(SessionSidRegistry.ownerOf("sidA1"))
        // Re-opening the same key re-binds from the gateway's own resume response.
        SessionSidRegistry.note("dbKeyA", "sidA2", "loadMessages_resume")
        assertTrue(SessionSidRegistry.isKnownFor("dbKeyA", "sidA2"))
    }

    /** Bounded growth: a long-lived process must not accumulate sids forever per session. */
    @Test fun sidHistoryPerSessionIsBoundedToTheDocumentedCap() {
        val cap = 32
        for (i in 1..(cap + 9)) SessionSidRegistry.note("dbKeyA", "sid$i", "resume#$i")
        val kept = SessionSidRegistry.sidsOf("dbKeyA")
        assertEquals("history must stop at the cap", cap, kept.size)
        assertTrue("the newest must be kept", kept.contains("sid${cap + 9}"))
        assertFalse("the oldest is dropped", kept.contains("sid1"))
    }

    @Test fun degeneratePairsAreIgnored() {
        assertFalse(SessionSidRegistry.note("", "sid", "x"))
        assertFalse(SessionSidRegistry.note("dbKeyA", "", "x"))
        assertFalse("a db key is never its own live sid", SessionSidRegistry.note("k", "k", "x"))
        assertFalse(SessionSidRegistry.isKnownFor("dbKeyA", ""))
        assertFalse(SessionSidRegistry.isKnownFor("", "sid"))
    }
}
