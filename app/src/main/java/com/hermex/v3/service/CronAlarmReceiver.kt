package com.hermex.v3.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hermex.v3.notify.CronWatcher

/**
 * Receives CronWatcher alarms and device-boot broadcasts.
 * AlarmManager wakes the process for this receiver even if the app was killed.
 */
class CronAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM = "com.hermex.v3.action.CRON_ALARM"
        const val ACTION_BOOT = "com.hermex.v3.action.CRON_BOOT"
        const val EXTRA_JOB_ID = "job_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        try {
            when (intent.action) {
                ACTION_ALARM -> {
                    val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: ""
                    if (jobId.isNotEmpty()) CronWatcher.onAlarm(context, jobId)
                    else CronWatcher.sync(context)
                }
                ACTION_BOOT -> CronWatcher.sync(context)
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED -> CronWatcher.sync(context)
            }
        } finally {
            pending.finish()
        }
    }
}
