package com.hermex.v3.feature.chat

import com.hermex.core.network.WsConnectionManager

/**
 * v0.5.5: honest connection status for the chat top bar. Before this the app went
 * silent when the link dropped — the user could only infer it from a stalled spinner
 * (and asked "what happened?" twice during the 2026-11-03 gateway restarts).
 */
sealed interface ConnectionNotice {
    /** Link is down and the manager is retrying with backoff. Persistent while true. */
    data object Reconnecting : ConnectionNotice

    /** We just got back up after a drop; [reason] says what the drop was, so the
     *  wording can distinguish a gateway restart from a Wi-Fi blip. Transient. */
    data class Restored(val reason: WsConnectionManager.DropReason) : ConnectionNotice
}
