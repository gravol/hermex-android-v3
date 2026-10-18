package com.hermex.core.data.auth

import android.content.Context
import android.content.SharedPreferences
import com.hermex.core.network.SecretStorageMode
import com.hermex.core.network.SecureStore

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
        return prefsMap.getOrPut(context.applicationContext) { SecureStore.open(context, PREFS_NAME) }
    }

    /**
     * v0.1.891 (audit finding #4/#5 chain): the old code fell back to **plaintext**
     * SharedPreferences whenever `EncryptedSharedPreferences.create` threw — silently — while this
     * screen kept claiming credentials were encrypted. A Keystore failure is precisely what
     * happens after a device transfer/restore, so the silent case was the ordinary one. Storage now
     * fails closed (see [SecureStore]): retry once with a fresh key, then keep secrets in memory
     * only. This reports which of the two is true so the UI can never claim more than it does.
     */
    fun isStorageEncrypted(): Boolean = SecureStore.mode == SecretStorageMode.Secure

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
