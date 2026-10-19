package com.hermex.v3.feature.chat

import com.hermex.core.network.DebugLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Which transient live RPC sids belong to which DB session key.
 *
 * The gateway stamps every event frame with a LIVE sid (`session_id` on the wire), and
 * a live sid is per-attach: a `session.resume` that re-attaches a session hands out a
 * new one (the "transient live RPC SID" contract documented on
 * DashboardChatViewModel). A chat ViewModel filters incoming events by
 * `dbKey == nSid || liveSid == nSid`, so when any other actor re-attaches the same
 * session — a second chat ViewModel, a reconnect resume, another device — the first
 * ViewModel's liveSid goes stale and it silently drops every subsequent frame. That is
 * the reproduced D/14 shape: 423 of 424 frames parsed, seq-watermarked and logged, and
 * the assistant row never grew.
 *
 * The app CAN know the mapping without asking the gateway again: a `session.resume(dbKey)`
 * response is the gateway itself telling us "live sid Y is now the attachment for stored
 * session X". Every place we take such a sid records the pair here, so a frame carrying
 * an unfamiliar-but-mapped sid can be accepted (and the local liveSid rotated) instead
 * of dropped, while frames for genuinely different sessions stay filtered.
 */
object SessionSidRegistry {

    /** dbKey (persistent session key) -> live sids the gateway handed out for it. */
    private val pairs = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * sid -> the ONE dbKey currently allowed to accept it. A live sid identifies one
     * attachment of one session, so two chats claiming the same sid means our mapping is
     * wrong (or the gateway reused it); whichever claimed it last wins and the other's
     * entry is dropped, because letting both accept the same frames would render one
     * chat's answer inside another. Panel review asked for exactly this single-owner
     * rule plus a visible conflict line.
     */
    private val ownerOfSid = ConcurrentHashMap<String, String>()

    private const val MAX_SID_HISTORY_PER_KEY = 32

    /**
     * Record that the gateway returned [liveSid] for [dbKey]. Returns true when this is
     * a NEW pair — i.e. a live-sid rotation happened, which is exactly the event the
     * blank-row investigation needs to see in the journal.
     */
    fun note(dbKey: String, liveSid: String, reason: String): Boolean {
        if (dbKey.isBlank() || liveSid.isBlank() || dbKey == liveSid) return false
        val previousOwner = ownerOfSid.put(liveSid, dbKey)
        if (previousOwner != null && previousOwner != dbKey) {
            pairs[previousOwner]?.let { stale ->
                synchronized(stale) { stale.remove(liveSid) }
            }
            DebugLog.log("TRACE", "identity",
                "event=sid_conflict sid=$liveSid previousDbKey=${previousOwner.take(24)} " +
                "newDbKey=${dbKey.take(24)} reason=$reason — the older key no longer accepts it")
        }
        val set = pairs.computeIfAbsent(dbKey) {
            java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        }
        val isNew: Boolean
        synchronized(set) {
            isNew = set.add(liveSid)
            while (set.size > MAX_SID_HISTORY_PER_KEY) set.iterator().let { it.next(); it.remove() }
        }
        if (isNew) {
            DebugLog.log("TRACE", "identity",
                "event=sid_bound dbKey=$dbKey liveSid=$liveSid reason=$reason " +
                "knownSids=${sidsOf(dbKey).size}")
        }
        return isNew
    }

    /** True when the gateway has told us this sid belongs to this session key. */
    fun isKnownFor(dbKey: String, sid: String): Boolean =
        sid.isNotBlank() && sidsOf(dbKey).contains(sid)

    /** Which session key currently owns a live sid, or null. Diagnostics/tests only. */
    fun ownerOf(sid: String): String? = ownerOfSid[sid]

    /**
     * Drop everything we know about a session key — used when the user deletes it, so a
     * frame for a deleted session can never be accepted by a chat that later reuses the
     * key. Bounded growth argument from panel review: entries are created per resume, and
     * without this they would outlive the sessions they describe.
     */
    fun forget(dbKey: String) {
        val set = pairs.remove(dbKey) ?: return
        synchronized(set) { set.forEach { sid -> ownerOfSid.remove(sid, dbKey) } }
        DebugLog.log("TRACE", "identity",
            "event=sid_registry_forget dbKey=${dbKey.take(24)} droppedSids=${set.size}")
    }

    fun sidsOf(dbKey: String): Set<String> {
        val set = pairs[dbKey] ?: return emptySet()
        synchronized(set) { return LinkedHashSet(set) }
    }

    /** Test isolation only — the registry is process-wide by design. */
    fun clear() {
        pairs.clear()
        ownerOfSid.clear()
    }
}
