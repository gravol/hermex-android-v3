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
}
