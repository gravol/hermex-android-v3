package com.hermex.v3.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.hermex.v3.MainActivity
import com.hermex.core.network.DebugLog
import java.net.URLEncoder

/**
 * Local notifications for Hermex (v0.1.74).
 *
 * Channels:
 *  - "turns" — a chat turn finished while you weren't looking at it
 *  - "cron"  — a scheduled cron job finished running
 *  - "reply" — low-importance foreground notification while an inline
 *              notification reply is being submitted (v0.1.98)
 *
 * Tapping either deep-links into the app: MainActivity receives
 * `open_session` (the session key — a chat session or a cron run session)
 * and navigates to chat/{sessionKey}.
 *
 * Inline reply (v0.1.98): turn-finished notifications carry a "Reply"
 * action with a RemoteInput. Typing a reply (lock screen or shade) delivers
 * the text to [NotificationReplyReceiver], which hands it to
 * [NotificationReplyService] to submit via prompt.submit; the assistant's
 * reply then arrives as a new turn-finished notification — a full chat loop
 * without unlocking.
 */
object NotificationHelper {

    const val CHANNEL_TURNS = "turns"
    const val CHANNEL_TURNS_QUIET = "turns_quiet"
    const val CHANNEL_CRON = "cron"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_REPLY = "reply"
    const val EXTRA_OPEN_SESSION = "open_session"
    const val EXTRA_OPEN_TITLE = "open_title"
    const val EXTRA_OPEN_ROUTE = "open_route"
    const val EXTRA_REPLY_SESSION = "reply_session"
    const val EXTRA_REPLY_TITLE = "reply_title"
    const val EXTRA_NTFY_SERVER = "ntfy_server"
    const val EXTRA_NTFY_TOPIC = "ntfy_topic"
    const val KEY_REPLY_TEXT = "reply_text"

    private const val ID_TURNS = 1001
    private const val ID_CRON = 1002
    private const val ID_APPROVAL = 1003
    private const val ID_REPLY_FOREGROUND = 1004

    // v0.1.103: per-session turn-notification ids (2000-10999 band, away from the
    // fixed ids above). Previously every turn-finished notification used ID_TURNS,
    // so a second session's completion REPLACED the first — with several chats
    // finishing while you're away you only ever saw the last one.
    private fun turnNotificationId(sessionKey: String): Int =
        2000 + ((sessionKey.hashCode() and 0x7fffffff) % 9000)

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TURNS, "Turn finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when a chat turn finishes while you're away"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CRON, "Cron jobs", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when scheduled cron jobs finish"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Approval requests and urgent notices"
                enableVibration(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REPLY, "Replying", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Foreground indicator while an inline notification reply is being sent"
                setShowBadge(false)
            }
        )
        // v0.1.874: a turn that finishes in the chat you are LOOKING AT is not
        // news, so it must not ping. It is still the inline-reply affordance, so
        // it must not be suppressed either. A silent channel is the only shape
        // that satisfies both: the card lands in the shade with the Reply
        // action, and the phone makes no sound and no vibration.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TURNS_QUIET, "Turn finished (quiet)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Turns that finished in the chat you were already looking at"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    private fun openSessionIntent(context: Context, sessionKey: String, title: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_SESSION, sessionKey)
            putExtra(EXTRA_OPEN_TITLE, title)
        }
        return PendingIntent.getActivity(
            context,
            sessionKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * v0.1.872: deep link to a nav route. Pushes that carry no `hermex://session/`
     * click (cron output, infra alerts) previously had NO content intent at all, so
     * tapping them opened the app and stopped at whatever screen was already up.
     * The app owns its routes, so the server never needs to learn them — a plain
     * text push still gets a meaningful tap target.
     */
    private fun openRouteIntent(context: Context, route: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ROUTE, route)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Inline "Reply" action (v0.1.98): RemoteInput text → NotificationReplyReceiver
     * → NotificationReplyService (prompt.submit). Present on every turn-finished
     * notification so replies chain into a full notification loop.
     */
    fun replyAction(context: Context, sessionKey: String, title: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationReplyReceiver::class.java).apply {
            putExtra(EXTRA_REPLY_SESSION, sessionKey)
            putExtra(EXTRA_REPLY_TITLE, title)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (sessionKey.hashCode() and 0x7fffffff) + 1000,
            intent,
            // MUTABLE: a PendingIntent carrying a RemoteInput cannot be FLAG_IMMUTABLE —
            // Android throws when the notification is built, which silently killed every
            // turn-finished notification (the build was wrapped in runCatching {}).
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("Reply")
            .build()
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            pendingIntent,
        ).addRemoteInput(remoteInput).build()
    }

    /** A chat turn finished while the chat screen wasn't visible.
     *
     * v0.1.144: built on the loud ALERTS channel (IMPORTANCE_HIGH + vibration +
     * PRIORITY_HIGH), matching postApproval — a plain IMPORTANCE_DEFAULT
     * turn-finished notification was silently swallowed / shown as an
     * unobtrusive card on Android 13+, so "turn finished" never surfaced even
     * when it fired. This is what made approval requests reliably noticed and
     * turn-finished notifications effectively silent. */
    fun postTurnFinished(context: Context, sessionKey: String, sessionTitle: String, preview: String) {
        // v0.1.874: a turn that finishes in the chat you are LOOKING AT must not
        // ping — the answer is already rendering in front of you. It is NOT
        // suppressed, because this notification is the inline-reply affordance
        // (Reply action, lock-screen/shade reply loop). Silent channel = card in
        // the shade, no sound, no vibration, no heads-up popup.
        val watching = com.hermex.v3.AppState.isWatching(sessionKey)
        ensureChannels(context)
        val title = sessionTitle.ifBlank { "Hermes" }
        val text = preview.take(200).ifBlank { "Turn finished" }
        val notification = NotificationCompat.Builder(context, if (watching) CHANNEL_TURNS_QUIET else CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(if (watching) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .apply { if (watching) setSilent(true) }
            .setContentIntent(openSessionIntent(context, sessionKey, title))
            .addAction(replyAction(context, sessionKey, title))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(turnNotificationId(sessionKey), notification) }
        DebugLog.log("NOTIF", "Notify", "postTurnFinished posted for $sessionKey (title=$title, quiet=$watching)")
    }

    /** Foreground notification while NotificationReplyService submits a reply (v0.1.98). */
    fun postReplying(context: Context, title: String): Notification {
        ensureChannels(context)
        val label = if (title.isNotBlank()) " to $title" else ""
        return NotificationCompat.Builder(context, CHANNEL_REPLY)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Hermex")
            .setContentText("Sending reply$label…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /** The inline reply could not be delivered (v0.1.98). */
    fun postReplyFailed(context: Context, sessionKey: String, title: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title.ifBlank { "Hermes" })
            .setContentText("⚠️ Reply failed — check connection and try again")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(turnNotificationId(sessionKey), notification) }
    }

    /** Clear the stale turn-finished notification once a reply is submitted (v0.1.98). */
    fun cancelTurns(context: Context, sessionKey: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(turnNotificationId(sessionKey)) }
    }

    /** A cron job produced a finished run. Tap opens the run's session. */
    fun postCronRun(
        context: Context,
        jobName: String,
        runTitle: String?,
        runId: String,
        output: String? = null,
        missedLabel: String? = null,
    ) {
        ensureChannels(context)
        val title = "Cron: ${jobName.ifBlank { "job" }}"
        val text = missedLabel.orEmpty() + (output?.take(400)?.ifBlank { null }
            ?: runTitle?.take(120)
            ?: "Run finished")
        val notification = NotificationCompat.Builder(context, CHANNEL_CRON)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openSessionIntent(context, runId, text))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_CRON, notification) }
    }

    /** Approval request while the user isn't watching the chat (v0.1.84). */
    fun postApproval(context: Context, sessionKey: String, toolName: String, args: String) {
        ensureChannels(context)
        val text = if (args.isBlank()) "Command needs your approval"
        else "Approval needed: $toolName — ${args.take(120)}"
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("⚠️ Approval needed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openSessionIntent(context, sessionKey, "Approval needed"))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_APPROVAL, notification) }
    }

    /** Inline reply (ntfy path, v0.1.87): reply posts to the chat topic via
     *  [NtfyClient]; the agent's answer comes back through the subscription. */
    fun replyActionNtfy(context: Context, server: String, topic: String, title: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationReplyReceiver::class.java).apply {
            putExtra(EXTRA_REPLY_SESSION, "ntfy:$topic")
            putExtra(EXTRA_REPLY_TITLE, title)
            putExtra(NotificationReplyService.EXTRA_NTFY_SERVER, server)
            putExtra(NotificationReplyService.EXTRA_NTFY_TOPIC, topic)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (title.hashCode() and 0x7fffffff) + 2000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("Reply")
            .build()
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            pendingIntent,
        ).addRemoteInput(remoteInput).build()
    }

    /**
     * A message arrived on the ntfy subscription (v0.1.87). Routing:
     *  - link hermex://session/<key> → the chat-opens form (same id as the
     *    live turn notification → a late server push REPLACES, never doubles)
     *  - priority >= 4 → loud alerts channel
     *  - everything else (cron output, infra notices) → cron channel
     * Notification id is derived from the ntfy message id: idempotent per
     * delivery (reconnect replays overwrite, they never stack).
     */
    fun postNtfyMessage(
        context: Context,
        topic: String,
        msgId: String,
        title: String,
        body: String,
        link: String?,
        priority: Int,
    ) {
        ensureChannels(context)
        val sessionId = link?.takeIf { it.startsWith("hermex://session/") }
            ?.removePrefix("hermex://session/")
        if (sessionId != null) {
            postTurnFinished(context, sessionId, title, body.ifBlank { "Turn finished" })
            return
        }
        val text = body.take(400).ifBlank { title }
        val label = title.ifBlank { "Hermes" }
        val channelId = if (priority >= 4) CHANNEL_ALERTS else CHANNEL_CRON
        val nid = 3000 + ((msgId.hashCode() and 0x7fffffff) % 9000)
        // Tap target: cron output opens the Cron screen, everything else the
        // session list. A server-published hermex://route/<name> click wins.
        val route = link?.takeIf { it.startsWith("hermex://route/") }
            ?.removePrefix("hermex://route/")
            ?: if (topic == "hermes-cron") "cron" else "home"
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(label)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openRouteIntent(context, route, nid))
            .setPriority(if (priority >= 4) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .addAction(replyActionNtfy(context, NtfySubscriptionService.DEFAULT_SERVER, "hermes-in", label))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(nid, notification) }
    }

    /** Deep-link route helper: chat/{sessionKey}/{encodedTitle} */
    fun chatRoute(sessionKey: String, title: String): String {
        val encoded = URLEncoder.encode(title.ifBlank { sessionKey }, "UTF-8")
        return "chat/$sessionKey/$encoded"
    }
}
