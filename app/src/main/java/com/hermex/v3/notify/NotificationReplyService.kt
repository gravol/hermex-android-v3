package com.hermex.v3.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
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
        serviceScope.launch {
            runReply(safeSession, safeTitle, safeReply)
        }
        // Never auto-restart with a null intent (would re-send the reply).
        return START_NOT_STICKY
    }

    private suspend fun runReply(sessionKey: String, title: String, reply: String) {
        var ws: WsConnectionManager? = null
        try {
            DebugLog.log("REPLY", "Service", "reply to $sessionKey: ${reply.take(60)}")

            // Same stack the chat VM uses — fresh ticket, one-shot connection.
            val conn = WsConnectionManager(serviceScope)
            ws = conn
            val rpc = JsonRpcClient(conn, serviceScope)
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

            val content = done?.content?.takeIf { it.isNotBlank() }
                ?: "Your reply was sent."
            // Same notification as a finished turn — carries the Reply action,
            // so the loop continues. ID_TURNS replace-semantics avoid dupes if
            // a chat VM is also watching this session.
            NotificationHelper.postTurnFinished(this, sessionKey, title, content)
            DebugLog.log("REPLY", "Service", "reply complete — notification posted")
        } catch (e: Exception) {
            DebugLog.log("REPLY", "Service", "reply failed: ${e.message}")
            NotificationHelper.postReplyFailed(this, sessionKey, title)
        } finally {
            runCatching { ws?.disconnect() }
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
