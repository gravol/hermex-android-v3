package com.hermex.v3.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hermex.v3.service.TurnFinishedAlarmReceiver
import com.hermex.core.data.auth.KeychainStore
import com.hermex.core.network.DashboardApiClient
import com.hermex.core.network.DebugLog
import com.hermex.core.network.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Turn-finished notification watcher (v0.1.141) — the "alarm sync" model.
 *
 * The WS-event path can't notify while the phone is locked or the app is
 * backgrounded: Android eventually kills the process and the completion event
 * never arrives at the app, so no client code can post a notification. This
 * mirrors CronWatcher's proven pattern instead — arm a local AlarmManager
 * alarm shortly after you submit a prompt, wake ONLY then, check whether the
 * turn finished server-side (last assistant message has content via REST),
 * notify, and clean up. Alarms bypass app state entirely: AlarmManager wakes
 * the process for TurnFinishedAlarmReceiver even if the app was killed or the
 * phone is locked — which is exactly why cron notifications work while locked.
 *
 * Lifecycle:
 *   arm(context, sessionId)     — from DashboardChatViewModel on prompt.submit
 *   cancel(context, sessionId)  — when a completion event arrives live
 *   onAlarm(context, sessionId) — alarm fired; check + notify + give up after N
 */
object TurnWatcher {

    private const val TAG = "TurnWatcher"
    private const val PREFS = "turn_watcher"
    // Alarm fires ~90s after submit (covers most turns); still-running runs are
    // re-checked every 2 min up to a hard cap so a very long turn can't pin us.
    private const val INITIAL_FIRE_MS = 90_000L
    private const val RE_CHECK_MS = 120_000L
    private const val MAX_CHECKS = 30   // ~9 min past initial fire, then give up quietly

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastLoginMs = 0L
    private const val LOGIN_COOLDOWN_MS = 30 * 60_000L

    /** Arm an alarm to fire INITIAL_FIRE_MS from now for this session. */
    fun arm(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        val fireAt = System.currentTimeMillis() + INITIAL_FIRE_MS
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TurnFinishedAlarmReceiver::class.java).apply {
            action = TurnFinishedAlarmReceiver.ACTION_ALARM
            putExtra(TurnFinishedAlarmReceiver.EXTRA_SESSION_ID, sessionId)
        }
        // Distinct request code per session so concurrent sessions don't clobber
        // each other (the v0.1.77 cron bug class — same identity, same replace).
        val requestCode = (sessionId.hashCode() and 0x7fffffff)
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        } catch (_: Exception) {
            // Exact-alarm grant may be revoked — fall back to the inexact path.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        }
        DebugLog.log("NOTIF", "TurnWatcher",
            "armed turn-finished alarm for $sessionId (fires in ${INITIAL_FIRE_MS / 1000}s)")
    }

    /** Cancel a pending arm — called when a completion event arrives live. */
    fun cancel(context: Context, sessionId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TurnFinishedAlarmReceiver::class.java).apply {
            action = TurnFinishedAlarmReceiver.ACTION_ALARM
            putExtra(TurnFinishedAlarmReceiver.EXTRA_SESSION_ID, sessionId)
        }
        val requestCode = (sessionId.hashCode() and 0x7fffffff)
        PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )?.let { alarmManager.cancel(it) }
        // Clear any stored check count so a later arm starts fresh.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("checks_" + sessionId).apply()
        DebugLog.log("NOTIF", "TurnWatcher", "cancelled turn-finished alarm for $sessionId")
    }

    /** Alarm fired — check if the turn finished server-side, notify, re-arm or give up. */
    fun onAlarm(context: Context, sessionId: String) {
        val app = context.applicationContext
        scope.launch {
            try {
                if (!ensureAuthenticated(app)) return@launch
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val checks = prefs.getInt("checks_" + sessionId, 0)

                val messagesResult = runCatching {
                    DashboardApiClient.sessionMessages(sessionId, limit = 3)
                }.getOrNull()

                when (messagesResult) {
                    is NetworkResult.Success -> {
                        val lastAssistant = messagesResult.data.messages
                            .lastOrNull { it.role == "assistant" }
                        val finished = lastAssistant?.resolvedContent?.isNotBlank() == true
                        if (finished) {
                            // Notify once, then clear the bookkeeping.
                            val preview = lastAssistant.resolvedContent!!.take(200)
                            NotificationHelper.postTurnFinished(
                                app, sessionId,
                                messagesResult.data.sessionId ?: sessionId,
                                preview.ifBlank { "Turn finished" },
                            )
                            prefs.edit().remove("checks_" + sessionId).apply()
                            DebugLog.log("NOTIF", "TurnWatcher",
                                "notified turn finished for $sessionId via alarm")
                        } else if (checks >= MAX_CHECKS) {
                            // Too long — give up quietly.
                            prefs.edit().remove("checks_" + sessionId).apply()
                            DebugLog.log("NOTIF", "TurnWatcher",
                                "giving up on $sessionId after $checks checks")
                        } else {
                            // Still running — re-arm shortly with an incremented count.
                            prefs.edit().putInt("checks_" + sessionId, checks + 1).apply()
                            val fireAt = System.currentTimeMillis() + RE_CHECK_MS
                            val alarmManager =
                                app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                            val intent = Intent(app, TurnFinishedAlarmReceiver::class.java).apply {
                                action = TurnFinishedAlarmReceiver.ACTION_ALARM
                                putExtra(TurnFinishedAlarmReceiver.EXTRA_SESSION_ID, sessionId)
                            }
                            val pi = PendingIntent.getBroadcast(
                                app, (sessionId.hashCode() and 0x7fffffff), intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            )
                            try {
                                alarmManager.setExactAndAllowWhileIdle(
                                    AlarmManager.RTC_WAKEUP, fireAt, pi)
                            } catch (_: Exception) {
                                alarmManager.setAndAllowWhileIdle(
                                    AlarmManager.RTC_WAKEUP, fireAt, pi)
                            }
                        }
                    }
                    else -> {
                        // Couldn't reach the server — don't spam. Re-arm once more.
                        if (checks < 2) arm(app, sessionId)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "onAlarm failed for $sessionId: ${e.message}")
            }
        }
    }

    private suspend fun ensureAuthenticated(context: Context): Boolean {
        val url = KeychainStore.getDashboardUrl(context) ?: return false
        val password = KeychainStore.getDashboardPassword(context) ?: return false
        val username = KeychainStore.getDashboardUsername(context) ?: "jeff"
        if (DashboardApiClient.baseUrl() != url) DashboardApiClient.setDashboardUrl(url)
        DashboardApiClient.setPassword(password)
        DashboardApiClient.setUsername(username)
        // Bound explicit logins so the turn watcher doesn't hammer the gateway.
        val now = System.currentTimeMillis()
        if (now - lastLoginMs < LOGIN_COOLDOWN_MS) return true
        lastLoginMs = now
        when (DashboardApiClient.login(username, password)) {
            is NetworkResult.Success -> return true
            else -> return false
        }
    }
}
