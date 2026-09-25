package com.hermex.v3.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hermex.v3.MainActivity
import com.hermex.v3.R

/**
 * Foreground service that keeps the Hermex process alive while the WebSocket
 * chat connection is active. Prevents Android from killing the process or
 * throttling network when the phone locks or the app is backgrounded.
 *
 * This service does NOT own the WebSocket connection — it only ensures the
 * process remains alive so [WsConnectionManager] (owned by the ViewModel)
 * can continue uninterrupted.
 *
 * Lifecycle:
 *   start(context)  — called when WebSocket connects
 *   stop(context)   — called when ViewModel is cleared (user leaves chat)
 */
class WsKeepaliveService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 8080
        private const val CHANNEL_ID = "hermex_keepalive_channel"

        /** Start the keepalive foreground service. Safe to call multiple times. */
        fun start(context: Context) {
            val intent = Intent(context, WsKeepaliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the keepalive foreground service. */
        fun stop(context: Context) {
            val intent = Intent(context, WsKeepaliveService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart if killed by the system (e.g. due to temporary resource pressure)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)                // not swipeable
            .setContentIntent(pendingIntent)  // tap to open app
            .build()
    }
}
