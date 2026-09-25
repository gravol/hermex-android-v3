package com.hermex.core.network

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * OkHttp CookieJar backed by [CookiePersistor] for persistence across
 * app restarts. Cookies are held in-memory during the process lifetime
 * and flushed to SharedPreferences on every save.
 */
class NetworkCookieJar(context: Context) : CookieJar {

    private val persistor = CookiePersistor(context)
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    init {
        // No pre-loading — cookies are loaded lazily per host on first request.
        // SharedPreferences read on the init thread would be fine since
        // this object is created during DI setup on the main thread.
    }

    override fun saveFromResponse(httpUrl: HttpUrl, cookies: List<Cookie>) {
        val host = httpUrl.host
        val filtered = cookies.filter { it.persistent || it.expiresAt > 0 || it.name.contains("session", ignoreCase = true) }
        cookieStore[host] = filtered.toMutableList()
        persistor.save(host, filtered)
    }

    override fun loadForRequest(httpUrl: HttpUrl): List<Cookie> {
        val host = httpUrl.host
        return cookieStore.getOrPut(host) {
            persistor.load(host).toMutableList()
        }
    }

    /** Clear all cookies (memory + disk). */
    fun clear() {
        cookieStore.clear()
        persistor.clear()
    }
}
