package com.hermex.v3.notify

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hermex.core.network.DebugLog

/**
 * Inline-reply entry point (v0.1.98): the "Reply" action on a turn-finished
 * notification carries a RemoteInput; typing the reply delivers this receiver
 * the text plus the session extras. We cancel the stale notification and hand
 * the reply to [NotificationReplyService], which submits it via prompt.submit
 * and posts the assistant's reply as a new notification (with a Reply action,
 * so the loop continues without ever unlocking).
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionKey = intent.getStringExtra(NotificationHelper.EXTRA_REPLY_SESSION) ?: return
        val title = intent.getStringExtra(NotificationHelper.EXTRA_REPLY_TITLE) ?: sessionKey
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationHelper.KEY_REPLY_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (reply.isBlank()) {
            DebugLog.log("REPLY", "Receiver", "empty reply — ignoring")
            return
        }
        DebugLog.log("REPLY", "Receiver", "reply to $sessionKey: ${reply.take(60)}")
        NotificationHelper.cancelTurns(context, sessionKey)
        // ntfy-rail replies (v0.1.87): session key "ntfy:<topic>" means post to
        // the chat topic — the agent answers via the subscription notification.
        val ntfyTopic = intent.getStringExtra(NotificationReplyService.EXTRA_NTFY_TOPIC)
        if (sessionKey.startsWith("ntfy:") && ntfyTopic != null) {
            val server = intent.getStringExtra(NotificationReplyService.EXTRA_NTFY_SERVER)
                ?: NtfySubscriptionService.DEFAULT_SERVER
            NotificationReplyService.startNtfy(context, server, ntfyTopic, title, reply)
            return
        }
        NotificationReplyService.start(context, sessionKey, title, reply)
    }
}
