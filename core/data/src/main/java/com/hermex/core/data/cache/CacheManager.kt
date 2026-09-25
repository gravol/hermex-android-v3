package com.hermex.core.data.cache

import android.util.LruCache

object CacheManager {
    private val cache: LruCache<String, Any> = object : LruCache<String, Any>(100) {
        override fun sizeOf(key: String, value: Any): Int {
            return 1 // Each entry is 1 unit in size for simplicity
        }
    }

    fun put(key: String, value: Any) {
        cache.put(key, value)
    }

    fun get(key: String): Any? {
        return cache.get(key)
    }

    fun clear() {
        cache.evictAll()
    }

    fun remove(key: String) {
        cache.remove(key)
    }
}
