package com.hermex.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which chat ViewModel gets released once the holder is at its cap.
 *
 * The holder keeps one ViewModel per session on purpose — a turn has to keep running after you leave
 * the chat — but it used to keep every session forever, each holding its transcript and two live
 * collectors, with no bound at all. Evicting is only safe if the wrong thing is never chosen, so the
 * policy is a pure function and these are its cases. The Android half (disposing a VM cancels its
 * collectors without touching the shared WebSocket) is covered by the v0.1.885 rules and by device QA;
 * what this file pins is the choice of victim.
 */
class ChatVmsHolderEvictionTest {

    @Test fun underTheCapNothingIsEvicted() {
        val lru = listOf("a", "b", "c")
        assertNull(ChatVmsHolder.pickToEvict(lru, streaming = emptySet(), current = "c", limit = 6))
        assertNull("exactly at the cap must not evict — eviction starts when we exceed it",
            ChatVmsHolder.pickToEvict(lru + listOf("d", "e", "f"), emptySet(), "f", 6))
    }

    @Test fun overTheCapTheLeastRecentlyUsedIdleChatGoesFirst() {
        val lru = listOf("oldest", "mid", "newer", "current")
        assertEquals("oldest", ChatVmsHolder.pickToEvict(lru, emptySet(), "current", limit = 3))
    }

    @Test fun aChatWhoseTurnIsStillRunningIsNeverTheVictim() {
        // Reopening a chat that is still working would drop its live collector mid-turn and the
        // answer would only come back through a full history reload. Not worth an eviction.
        val lru = listOf("streaming1", "streaming2", "idle", "current")
        assertEquals("idle",
            ChatVmsHolder.pickToEvict(lru, streaming = setOf("streaming1", "streaming2"),
                current = "current", limit = 3))
    }

    @Test fun theChatJustOpenedIsNeverTheVictimEvenWhenItIsNotStreaming() {
        val lru = listOf("current")   // every other entry already gone
        assertNull(ChatVmsHolder.pickToEvict(lru, emptySet(), "current", limit = 0))
    }

    @Test fun whenEverythingIsProtectedNothingIsEvictedAndNoLoopRunsForever() {
        val lru = listOf("s1", "s2", "s3")
        assertNull("all three streaming: hold them all — over-cap is survivable, killing a live turn is not",
            ChatVmsHolder.pickToEvict(lru, streaming = setOf("s1", "s2", "s3"), current = null, limit = 1))
    }

    @Test fun withNoCurrentChatTheOldestIdleStillGoes() {
        assertEquals("b", ChatVmsHolder.pickToEvict(listOf("a", "b", "c"), streaming = setOf("a"),
            current = null, limit = 2))
    }

    @Test fun capOfOneWithOneEntryKeepsIt() {
        assertNull(ChatVmsHolder.pickToEvict(listOf("only"), emptySet(), current = null, limit = 1))
    }
}
