package com.hermex.v3

import android.app.Application
import android.util.Log
import com.hermex.core.data.auth.KeychainStore
import com.hermex.core.network.DashboardApiClient
import com.hermex.core.network.DebugLog

class HermexApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // v0.1.82: journal FIRST — lines logged after this survive crashes and
        // force-kills; the previous session's tail is reloaded into the buffer.
        DebugLog.init(this)
        DebugLog.log("INFO", "HermexApp", "app process start (v${BuildConfig.VERSION_NAME})")

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Record the crash + flush the journal synchronously so the log
            // that explains THIS crash survives it. Then hand off to Android.
            DebugLog.log("ERROR", "CRASH", "uncaught on ${thread.name}: ${throwable::class.simpleName}: ${throwable.message}", throwable)
            DebugLog.flushNow()
            Log.e("Hermex", "FATAL: uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Lifecycle breadcrumbs: the app is frequently force-killed while the
        // agent is streaming — these lines mark foreground/background boundaries.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) {
                DebugLog.log("INFO", "AppLifecycle", "foreground (resumed ${a::class.simpleName})")
            }
            override fun onActivityPaused(a: android.app.Activity) {
                DebugLog.log("INFO", "AppLifecycle", "background (paused ${a::class.simpleName})")
                DebugLog.flushNow()  // mark the boundary durably before any kill
            }
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })

        try {
            DashboardApiClient.init(this)

            val savedDashboardUrl = KeychainStore.getDashboardUrl(this)
            val savedDashboardPassword = KeychainStore.getDashboardPassword(this)
            val savedDashboardUsername = KeychainStore.getDashboardUsername(this)
            if (savedDashboardUrl != null && savedDashboardPassword != null) {
                DashboardApiClient.setDashboardUrl(savedDashboardUrl)
                DashboardApiClient.setPassword(savedDashboardPassword)
                DashboardApiClient.setUsername(savedDashboardUsername ?: "jeff")
                Log.d("Hermex", "HermexApplication: restored dashboard URL + password")
                DebugLog.log("INFO", "HermexApp", "restored dashboard credentials → isConfigured=true")
            } else {
                DebugLog.log("INFO", "HermexApp", "no dashboard credentials stored → isConfigured=false")
            }
        } catch (e: Exception) {
            Log.e("Hermex", "HermexApplication: DashboardApiClient.init failed", e)
        }
    }
}
