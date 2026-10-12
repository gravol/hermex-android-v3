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
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Native ntfy subscription (v0.1.87) — Hermex's own push-delivery rail.
 *
 * Hermex speaks the public ntfy subscription protocol directly and posts
 * notifications on its OWN channels: cron output, infra alerts, and
 * finished background sessions arrive as plain Hermex notifications, not
 * through the ntfy app.
 *
 * Protocol facts (verified against ntfy 2.24 on BigRed, 2026-10-12 — the
 * first cut of this file invented a protocol ntfy does not have):
 *  - The WebSocket endpoint is `<topic>/ws`, one connection per topic list.
 *    There is NO global `/ws`: that path serves the web app's SPA HTML, so a
 *    handshake there answers HTTP 200 and OkHttp fails the upgrade.
 *  - Multiple topics share one connection: `/<a>,<b>/ws` (comma-separated).
 *  - Catch-up is the `?since=<id | unix-seconds | duration>` query param on
 *    the upgrade URL. ntfy accepts NO client frames at all — a "subscribe"
 *    or "catch-up" JSON frame is ignored by the server.
 *  - Server->client frames carry `event` (open / message / keepalive /
 *    poll_request), NOT `type`.
 *  - Message ids are base64 snowflakes with a random component: NOT
 *    lexicographically ordered and NOT numerically monotonic, so
 *    `id <= lastId` is not a valid duplicate test. Order by `time` (unix
 *    seconds) and de-dupe ids within the same second.
 *  - The deep-link URL a notification opens lives in the `click` field
 *    (published as the `X-Click` header). `Link` is the attachment URL field
 *    and never reaches the message JSON.
 *
 * Design (docs/NATIVE_NTFY_DELIVERY_PLAN.md):
 *  - foreground service (dataSync, like WsKeepaliveService) + persistent
 *    low-importance "Notifications connected" indicator (Android requires it)
 *  - last-seen timestamp per topic persisted in DataStore — a socket death in
 *    Doze replays from there, so nothing is missed
 *  - message routing: click hermex://session/<key> -> postTurnFinished
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

    /** Newest delivered message time (unix seconds) per topic. */
    private val lastTimes = HashMap<String, Long>()

    /** Ids delivered at exactly [lastTimes] — kills same-second replay dupes. */
    private val sameSecondIds = HashMap<String, MutableSet<String>>()

    /** Replay floor for a topic with no persisted cursor (first run = live edge). */
    private var seedTs = 0L

    companion object {
        const val DEFAULT_SERVER = "http://100.80.204.66:8080"

        // Every topic the BigRed side publishes to: hermes-out (gateway replies +
        // cron deliver=ntfy), hermes-sessions (turn-finished), hermes-cron and
        // hermes-alerts (hermes-ops notifier). A topic missing here is a silent
        // delivery hole.
        const val DEFAULT_TOPICS_CSV = "hermes-out,hermes-sessions,hermes-cron,hermes-alerts"
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
            .ifEmpty { DEFAULT_TOPICS_CSV.split(",") }
        // First run: start at the live edge so cached history never floods in.
        seedTs = System.currentTimeMillis() / 1000
        for (t in topicList) {
            lastTimes[t] = settings.getNtfyLastTs(t).first() ?: seedTs
            sameSecondIds[t] = mutableSetOf()
        }
        connect()
    }

    private fun connect() {
        if (destroyed) return
        val scheme = if (serverUrl.startsWith("https://")) "wss://" else "ws://"
        val host = serverUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        val topics = topicList.joinToString(",")
        // One replay floor for the shared connection: the oldest cursor wins,
        // the per-topic time/id filter discards anything already delivered.
        val since = (lastTimes.values.minOrNull() ?: seedTs) - 1
        val url = "$scheme$host/$topics/ws?since=$since"
        DebugLog.log("NTFY", "Sub", "connecting $url")
        ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    DebugLog.log("NTFY", "Sub", "connected (topics=$topics since=$since)")
                    backoffMs = 5_000L
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
            val event = obj["event"]?.jsonPrimitive?.contentOrNull ?: return
            if (event != "message") return // open / keepalive / poll_request
            val topic = obj["topic"]?.jsonPrimitive?.contentOrNull ?: return
            if (topic !in topicList) return
            val msgId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return
            val msgTs = obj["time"]?.jsonPrimitive?.long
                ?: (System.currentTimeMillis() / 1000)
            val prev = lastTimes[topic] ?: seedTs
            // Ids are not order-comparable; time is. Replay of an older second
            // is stale, and a same-second replay is a duplicate.
            if (msgTs < prev) return
            if (msgTs == prev && sameSecondIds[topic]?.contains(msgId) == true) return
            lastTimes[topic] = msgTs
            sameSecondIds[topic] =
                if (msgTs == prev) (sameSecondIds[topic] ?: mutableSetOf()).apply { add(msgId) }
                else mutableSetOf(msgId)
            scope.launch {
                SettingsRepository(applicationContext).setNtfyLastTs(topic, msgTs)
            }
            val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val body = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val click = obj["click"]?.jsonPrimitive?.contentOrNull
            val priority = obj["priority"]?.jsonPrimitive?.int ?: 3
            NotificationHelper.postNtfyMessage(this, topic, msgId, title, body, click, priority)
            DebugLog.log("NTFY", "Sub", "posted topic=$topic id=$msgId title=${title.take(40)}")
        } catch (e: Exception) {
            DebugLog.log("NTFY", "Sub", "frame parse failed: ${e.message} :: ${text.take(120)}")
        }
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
