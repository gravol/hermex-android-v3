package com.hermex.core.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists OkHttp session cookies encrypted at rest (AES-256 GCM).
 * Used by [NetworkCookieJar] as its backing store. Session cookies are live
 * credentials — they get the same storage rules as KeychainStore passwords.
 *
 * v0.1.891 (audit finding #5): the old code fell back to plaintext SharedPreferences when the
 * Keystore threw, which put live session cookies in the clear with no signal anywhere. Storage is
 * delegated to [SecureStore], which fails closed: encrypted, retry once, then in-memory only.
 */
class CookiePersistor(context: Context) {

    private val prefs: SharedPreferences = SecureStore.open(context, "hermex_cookies")

    /** Load all persisted cookies for a given host. */
    fun load(host: String): List<Cookie> {
        val json = prefs.getString(host, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val cookies = (0 until arr.length()).mapNotNull { i ->
                cookieFromJson(arr.getJSONObject(i))
            }
            // v0.1.889 (Phase 0): cookie values are live credentials. Names and counts
            // only — the old line printed the first 12 chars of every value to logcat.
            Log.d("Hermex", "CookiePersistor.load($host) → ${cookies.size} cookies: ${cookies.map { it.name }}")
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
        // v0.1.889 (Phase 0): names and counts only, never cookie values.
        Log.d("Hermex", "CookiePersistor.save($host) → ${cookies.size} cookies: ${cookies.map { it.name }}")
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
