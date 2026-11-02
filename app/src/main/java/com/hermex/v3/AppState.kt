package com.hermex.v3

/**
 * App-wide foreground/background flag (v0.1.75).
 *
 * The chat VM's `screenVisible` tracks whether the chat screen is in
 * composition — it stays true when the app is merely backgrounded, so
 * turn-finished notifications never fired in that case. MainActivity
 * updates this from its lifecycle instead.
 */
object AppState {
    @Volatile
    var isBackgrounded: Boolean = false

    /**
     * Session key whose chat screen is currently open and visible (v0.1.874).
     *
     * The server-side turnwatch suppresses pushes for sessions a client is
     * driving, but it cannot see a session the user is merely READING in
     * Hermex, and it cannot see the ntfy path at all. This is the app-side
     * half: a finished turn in the session you're looking at is not news.
     */
    @Volatile
    var visibleSessionId: String = ""

    /**
     * One-shot flag set when the system assistant launches Hermex
     * (VoiceInteractionSession, v0.5.4). Same process as every Hermex
     * component, so it works for cold starts (service binds in this process
     * before the activity draws) as well as warm re-launches. ChatScreen
     * consumes it to auto-start the mic.
     */
    @Volatile
    var pendingVoiceStart: Boolean = false

    fun consumePendingVoiceStart(): Boolean {
        val v = pendingVoiceStart
        pendingVoiceStart = false
        return v
    }

    /** True when the user is looking at this exact session right now. */
    fun isWatching(sessionId: String): Boolean =
        !isBackgrounded && sessionId.isNotEmpty() && sessionId == visibleSessionId
}
