package com.hermex.v3.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hermex.core.network.DebugLog
import com.hermex.v3.MainActivity
import com.hermex.v3.R
import com.hermex.v3.feature.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Native ntfy subscription (v0.1.87) — Hermex's own push-delivery rail.
 *
 * Hermex speaks the public ntfy WebSocket protocol directly and posts
 * notifications on its OWN channels: cron output, infra alerts, and
 * finished background sessions arrive as plain Hermex notifications, not
 * through the ntfy app.
 *
 * Design (docs/NATIVE_NTFY_DELIVERY_PLAN.md):
 *  - foreground service (dataSync, like WsKeepaliveService) + persistent
 *    low-importance "Notifications connected" indicator (Android requires it)
 *  - subscribe frames + per-topic catch-up frames after every (re)connect;
 *    last-seen message ids persisted in DataStore — a socket death in Doze
 *    loses nothing, and the id filter kills every duplicate
 *  - message routing: link hermex://session/<key> -> postTurnFinished
 *    (opens the chat, carries the inline Reply action); everything else ->
 *    cron channel, or the loud alerts channel when priority >= 4
 *  - duplicate discipline with the app's live WS notifications: the server
 *    suppresses pushes for sessions with a fresh interactive lease, and both
 *    paths share the session-keyed notification id, so a late server push
 *    REPLACES the live notification instead of doubling it.
 */
class NtfySubscriptionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private var ws: WebSocket? = null
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    }
    private var backoffMs = 5_000L
    private var destroyed = false
    private var serverUrl = DEFAULT_SERVER
    private var topicList = listOf("hermes-out", "hermes-sessions")
    private val lastIds = HashMap<String, String>()

    companion object {
        const val DEFAULT_SERVER = "http://100.80.204.66:8080"
        const val DEFAULT_TOPICS_CSV = "hermes-out,hermes-sessions"
        private const val NOTIFICATION_ID = 8090
        private const val CHANNEL_ID = "ntfy_connection"

        fun start(context: Context) {
            val intent = Intent(context, NtfySubscriptionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NtfySubscriptionService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        createConnChannel()
        startForeground(NOTIFICATION_ID, buildConnNotification())
        scope.launch { boot() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        runCatching { ws?.cancel() }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun boot() {
        val settings = SettingsRepository(applicationContext)
        if (settings.ntfyEnabled.first() != true) {
            DebugLog.log("NTFY", "Sub", "disabled in settings — stopping")
            stopSelf()
            return
        }
        serverUrl = settings.ntfyServer.first().ifBlank { DEFAULT_SERVER }
        topicList = settings.ntfyTopics.first()
            .ifBlank { DEFAULT_TOPICS_CSV }
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("hermes-out", "hermes-sessions") }
        for (t in topicList) {
            // First run: seed at the live edge so history never floods in.
            lastIds[t] = settings.getNtfyLastId(t).first() ?: snowflakeNow()
        }
        connect()
    }

    private fun connect() {
        if (destroyed) return
        DebugLog.log("NTFY", "Sub", "connecting $serverUrl topics=$topicList")
        val c = client
        val wsUrl = serverUrl.replace("http://", "ws://")
        ws = c.newWebSocket(
            Request.Builder().url("$wsUrl/ws").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    DebugLog.log("NTFY", "Sub", "connected")
                    backoffMs = 5_000L
                    for (t in topicList) {
                        webSocket.send("""{"type":"subscribe","topic":"$t"}""")
                        webSocket.send(
                            """{"type":"catch-up","topic":"$t","id":"${lastIds[t]}"}""",
                        )
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleFrame(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    DebugLog.log("NTFY", "Sub", "closed code=$code — reconnecting")
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    DebugLog.log("NTFY", "Sub", "ws failure: ${t.message} — reconnecting")
                    scheduleReconnect()
                }
            },
        )
    }

    private fun scheduleReconnect() {
        if (destroyed) return
        scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            connect()
        }
    }

    private fun handleFrame(text: String) {
        try {
            val obj = json.parseToJsonElement(text).jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
            if (type != "message") return // keepalive / welcome frames
            val topic = obj["topic"]?.jsonPrimitive?.contentOrNull ?: return
            if (topic !in topicList) return
            val msgId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return
            val prev = lastIds[topic] ?: return
            if (msgId <= prev) return // duplicate (catch-up replay) — drop
            lastIds[topic] = msgId
            scope.launch {
                SettingsRepository(applicationContext).setNtfyLastId(topic, msgId)
            }
            val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val body = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val link = obj["link"]?.jsonPrimitive?.contentOrNull
            val priority = obj["priority"]?.jsonPrimitive?.int ?: 3
            NotificationHelper.postNtfyMessage(this, topic, msgId, title, body, link, priority)
            DebugLog.log("NTFY", "Sub", "posted topic=$topic id=$msgId title=${title.take(40)}")
        } catch (e: Exception) {
            DebugLog.log("NTFY", "Sub", "frame parse failed: ${e.message} :: ${text.take(120)}")
        }
    }

    /** Legacy-format snowflake id for the current instant (time-sortable). */
    private fun snowflakeNow(): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-"
        return "%016d".format(System.currentTimeMillis() * 1000) +
            (0 until 2).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun createConnChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Push delivery",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Hermex listening on the notification topics"
                setShowBadge(false)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildConnNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Hermex notifications connected")
            .setContentText("Listening: ${topicList.joinToString()}")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}

/** Minimal ntfy publisher — used by the inline reply path to reach the chat door. */
object NtfyClient {
    fun publish(server: String, topic: String, text: String): Boolean {
        return try {
            val conn = URL("$server/$topic").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            conn.responseCode == 200
        } catch (e: Exception) {
            DebugLog.log("NTFY", "Publish", "failed: ${e.message}")
            false
        }
    }
}
