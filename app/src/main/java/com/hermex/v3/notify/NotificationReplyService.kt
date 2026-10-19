package com.hermex.v3.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.hermex.v3.di.AppModule
import com.hermex.core.network.DebugLog
import com.hermex.core.network.JsonRpcClient
import com.hermex.core.network.RpcNotification
import com.hermex.core.network.WsConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Submits an inline notification reply (v0.1.98) — the "full chat loop
 * without unlocking" piece of the plan:
 *
 *   lock screen / shade "Reply" → [NotificationReplyReceiver] →
 *   this service → fresh WS (WsConnectionManager + JsonRpcClient, same stack
 *   the chat VM uses) → session.resume (attach) → prompt.submit (DB key,
 *   server resolves) → wait for message.completed → post the assistant's
 *   reply as a new turn-finished notification (which carries a Reply action
 *   again, so replies chain) → stop.
 *
 * Runs as a foreground service (dataSync, like WsKeepaliveService) so the
 * process survives long turns and background execution limits. A reply turn
 * can take minutes; we wait up to [REPLY_TIMEOUT_MS] then give up quietly.
 */
class NotificationReplyService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val NOTIFICATION_ID = 4001
        const val EXTRA_SESSION_ID = "reply_service_session"
        const val EXTRA_SESSION_TITLE = "reply_service_title"
        const val EXTRA_REPLY_TEXT = "reply_service_text"

        /** WS connect + initial RPC budget. */
        private const val CONNECT_TIMEOUT_MS = 20_000L
        /** How long to wait for the reply turn to finish (minutes). */
        private const val REPLY_TIMEOUT_MS = 10 * 60_000L

        fun start(context: Context, sessionKey: String, title: String, reply: String) {
            val intent = Intent(context, NotificationReplyService::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionKey)
                putExtra(EXTRA_SESSION_TITLE, title)
                putExtra(EXTRA_REPLY_TEXT, reply)
            }
            startInternal(context, intent)
        }

        /** ntfy-rail reply (v0.1.87): publish to the chat topic; the agent's
         *  answer arrives back as a subscription notification. */
        fun startNtfy(context: Context, server: String, topic: String, title: String, reply: String) {
            val intent = Intent(context, NotificationReplyService::class.java).apply {
                putExtra(EXTRA_SESSION_ID, "ntfy:$topic")
                putExtra(EXTRA_SESSION_TITLE, title)
                putExtra(EXTRA_REPLY_TEXT, reply)
                putExtra(EXTRA_NTFY_SERVER, server)
                putExtra(EXTRA_NTFY_TOPIC, topic)
            }
            startInternal(context, intent)
        }

        private fun startInternal(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        const val EXTRA_NTFY_SERVER = "reply_service_ntfy_server"
        const val EXTRA_NTFY_TOPIC = "reply_service_ntfy_topic"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionKey = intent?.getStringExtra(EXTRA_SESSION_ID)
        val reply = intent?.getStringExtra(EXTRA_REPLY_TEXT)
        if (sessionKey.isNullOrBlank() || reply.isNullOrBlank()) {
            // START_STICKY restart with no payload — nothing to do.
            stopSelf()
            return START_NOT_STICKY
        }
        val safeSession = sessionKey
        val safeReply = reply
        val safeTitle = intent?.getStringExtra(EXTRA_SESSION_TITLE) ?: safeSession
        // Foreground ASAP (5s window after startForegroundService).
        startForeground(NOTIFICATION_ID, NotificationHelper.postReplying(this, safeTitle))
        if (safeSession.startsWith("ntfy:")) {
            // ntfy-rail reply (v0.1.87): POST to the chat topic; the agent's
            // answer arrives through the subscription as a new notification.
            val topic = intent?.getStringExtra(EXTRA_NTFY_TOPIC) ?: "hermes-in"
            val server = intent?.getStringExtra(EXTRA_NTFY_SERVER)
                ?: NtfySubscriptionService.DEFAULT_SERVER
            serviceScope.launch {
                val ok = NtfyClient.publish(server, topic, safeReply)
                DebugLog.log("REPLY", "Service", "ntfy publish to $topic: ok=$ok")
                if (!ok) {
                    NotificationHelper.postReplyFailed(
                        this@NotificationReplyService, safeSession, safeTitle,
                    )
                }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        serviceScope.launch {
            runReply(safeSession, safeTitle, safeReply)
        }
        // Never auto-restart with a null intent (would re-send the reply).
        return START_NOT_STICKY
    }

    private suspend fun runReply(sessionKey: String, title: String, reply: String) {
        try {
            DebugLog.log("REPLY", "Service", "reply to $sessionKey: ${reply.take(60)}")

            // v0.1.885: reuse the app-wide shared connection. The old one-shot
            // connection opened a second socket, and `session.resume` on it
            // re-attached the session to the service's socket — the running turn's
            // events then went there, where no chat ViewModel is listening, so the
            // turn never rendered on screen.
            val conn = AppModule.provideWsConnection()
            val rpc = AppModule.provideRpcClient()
            withTimeout(CONNECT_TIMEOUT_MS) { conn.connect() }
            rpc.start()

            // Attach the session (fresh ticket => new runtime sid) so the
            // submission routes correctly; prompt.submit still takes the DB key.
            val resume = rpc.sessionResume(sessionKey, omitMessages = true)
            val liveSid = resume.session_id
            DebugLog.log("REPLY", "Service", "resumed liveSid=$liveSid — submitting prompt")

            rpc.promptSubmit(sessionKey, reply)

            // Wait for the reply turn to complete. Match by DB key OR live sid
            // (same two-phase rule as the chat VM's notification filter).
            val done = withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                rpc.notifications.firstOrNull { n ->
                    n is RpcNotification.MessageCompleted &&
                        (n.sessionId == sessionKey || n.sessionId == liveSid)
                }
            } as? RpcNotification.MessageCompleted

            if (done == null) {
                // v0.1.896 (#18): this used to post a finished-turn notification reading
                // "Your reply was sent." — an outcome the code does not have. withTimeoutOrNull
                // returning null means no completion arrived, not that nothing happened: the prompt
                // may well have reached the gateway and run a turn we never saw finish. Say what is
                // known, and keep the Reply action so retrying is one tap.
                val waitedMin = (REPLY_TIMEOUT_MS / 60_000L).toInt()
                NotificationHelper.postReplyTimedOut(this, sessionKey, title, waitedMin)
                DebugLog.log("REPLY", "Service",
                    "timed out: no message.complete for $sessionKey within ${REPLY_TIMEOUT_MS / 1000}s")
            } else {
                val content = done.content?.takeIf { it.isNotBlank() } ?: "Your reply was sent."
                // Same notification as a finished turn — carries the Reply action, so the loop continues.
                // ID_TURNS replace-semantics avoid dupes if a chat VM is also watching this session.
                NotificationHelper.postTurnFinished(this, sessionKey, title, content)
                DebugLog.log("REPLY", "Service", "reply complete — notification posted")
            }
        } catch (e: Exception) {
            DebugLog.log("REPLY", "Service", "reply failed: ${e.message}")
            NotificationHelper.postReplyFailed(this, sessionKey, title)
        } finally {
            // v0.1.885: never disconnect — the connection is app-wide and the chat
            // VM is watching the same socket.
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
