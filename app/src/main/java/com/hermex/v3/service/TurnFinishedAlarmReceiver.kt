package com.hermex.v3.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hermex.v3.notify.TurnWatcher

/**
 * Receives TurnWatcher alarms. AlarmManager wakes the process for this receiver
 * even if the app was killed or the phone is locked — this is what makes
 * turn-finished notifications work while backgrounded, unlike the WS-event path.
 */
class TurnFinishedAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM = "com.hermex.v3.action.TURN_FINISHED"
        const val EXTRA_SESSION_ID = "session_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // goAsync() keeps the receiver alive until the async work finishes —
        // WITHOUT this, Doze can kill the process mid-coroutine when the app was
        // backgrounded/killed, silently dropping the notification. Mirrors
        // CronAlarmReceiver exactly (which is why cron pings work while locked).
        val pending = goAsync()
        try {
            when (intent.action) {
                ACTION_ALARM -> {
                    val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
                    if (sessionId.isNotEmpty()) TurnWatcher.onAlarm(context, sessionId)
                }
            }
        } finally {
            pending.finish()
        }
    }
}
