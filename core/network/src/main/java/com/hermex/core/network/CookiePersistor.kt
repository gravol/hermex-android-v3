package com.hermex.core.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists OkHttp session cookies to EncryptedSharedPreferences (AES-256 GCM).
 * Used by [NetworkCookieJar] as its backing store. Session cookies are live
 * credentials — they get the same encryption as KeychainStore passwords.
 */
class CookiePersistor(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "hermex_cookies",
            masterKeyAlias,
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e("Hermex", "CookiePersistor: EncryptedSharedPreferences failed, falling back to plain SP", e)
        context.applicationContext.getSharedPreferences("hermex_cookies", Context.MODE_PRIVATE)
    }

    /** Load all persisted cookies for a given host. */
    fun load(host: String): List<Cookie> {
        val json = prefs.getString(host, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val cookies = (0 until arr.length()).mapNotNull { i ->
                cookieFromJson(arr.getJSONObject(i))
            }
            Log.d("Hermex", "CookiePersistor.load($host) → ${cookies.size} cookies: ${cookies.map { "${it.name}=${it.value.take(12)}..." }}")
            cookies
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Persist cookies for a host. Overwrites any previous cookies for that host. */
    fun save(host: String, cookies: List<Cookie>) {
        if (cookies.isEmpty()) {
            prefs.edit().remove(host).apply()
            Log.d("Hermex", "CookiePersistor.save($host) → cleared")
            return
        }
        val arr = JSONArray()
        for (cookie in cookies) {
            arr.put(cookieToJson(cookie))
        }
        prefs.edit().putString(host, arr.toString()).apply()
        Log.d("Hermex", "CookiePersistor.save($host) → ${cookies.size} cookies: ${cookies.map { "${it.name}=${it.value.take(12)}..." }}")
    }

    /** Remove all persisted cookies. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun cookieToJson(c: Cookie): JSONObject = JSONObject().apply {
        put("name", c.name)
        put("value", c.value)
        put("expiresAt", c.expiresAt)
        put("domain", c.domain)
    }

    private fun cookieFromJson(o: JSONObject): Cookie {
        val domain = o.optString("domain", "")
        return Cookie.Builder()
            .name(o.getString("name"))
            .value(o.getString("value"))
            .expiresAt(o.getLong("expiresAt"))
            .apply { if (domain.isNotEmpty()) domain(domain) }
            .build()
    }
}
