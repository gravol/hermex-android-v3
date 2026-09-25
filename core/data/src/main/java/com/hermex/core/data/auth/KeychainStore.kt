package com.hermex.core.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Encrypted storage for Hermex credentials.
 */
object KeychainStore {
    private const val PREFS_NAME = "hermex_auth"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_DASHBOARD_URL = "dashboard_url"
    private const val KEY_DASHBOARD_USERNAME = "dashboard_username"
    private const val KEY_DASHBOARD_PASSWORD = "dashboard_password"

    private val prefsMap = mutableMapOf<Context, SharedPreferences>()

    @Synchronized
    private fun getPrefs(context: Context): SharedPreferences {
        return prefsMap.getOrPut(context.applicationContext) {
            try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKeyAlias,
                    context.applicationContext,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e("Hermex", "KeychainStore: EncryptedSharedPreferences failed, fallback to plain SP", e)
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    fun save(context: Context, serverUrl: String, apiKey: String) {
        getPrefs(context).edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    fun getServerUrl(context: Context): String? =
        getPrefs(context).getString(KEY_SERVER_URL, null)

    fun getApiKey(context: Context): String? =
        getPrefs(context).getString(KEY_API_KEY, null)

    // ── Dashboard credentials ──

    fun saveDashboardCredentials(
        context: Context, dashboardUrl: String, dashboardPassword: String,
        dashboardUsername: String = "jeff",
    ) {
        getPrefs(context).edit()
            .putString(KEY_DASHBOARD_URL, dashboardUrl)
            .putString(KEY_DASHBOARD_USERNAME, dashboardUsername)
            .putString(KEY_DASHBOARD_PASSWORD, dashboardPassword)
            .apply()
    }

    fun getDashboardUrl(context: Context): String? =
        getPrefs(context).getString(KEY_DASHBOARD_URL, null)

    fun getDashboardUsername(context: Context): String? =
        getPrefs(context).getString(KEY_DASHBOARD_USERNAME, null)

    fun getDashboardPassword(context: Context): String? =
        getPrefs(context).getString(KEY_DASHBOARD_PASSWORD, null)

    // ──

    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
