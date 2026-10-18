package com.hermex.core.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * How secret storage behaves on this device. Reported to the UI so it can never claim
 * encryption it did not deliver.
 */
enum class SecretStorageMode { Secure, SessionOnly }

/**
 * The single place that decides how secrets are persisted.
 *
 * Audit finding (2026-10-17): [KeychainStore] and [CookiePersistor] each carried their own
 * silent plaintext fallback — on `EncryptedSharedPreferences.create` failure they wrote
 * credentials and live session cookies into ordinary SharedPreferences while the Settings screen
 * kept saying "Saved credentials are encrypted on this device". A Keystore failure is exactly
 * what happens after a device transfer or a restore, i.e. the common case, not the exotic one.
 *
 * Fail closed instead: try encrypted, delete the broken artifacts, retry once with a fresh key,
 * and if that still fails keep secrets **in memory only** and report [SecretStorageMode.SessionOnly].
 * Nothing sensitive ever reaches disk unencrypted.
 */
object SecureStore {
    private const val TAG = "Hermex"

    @Volatile
    var mode: SecretStorageMode = SecretStorageMode.Secure
        private set

    /**
     * Open [prefsName] encrypted at rest. Never returns a plaintext-backed store: on repeated
     * failure it returns an in-memory store and flips [mode] to SessionOnly.
     */
    @Synchronized
    fun open(context: Context, prefsName: String): SharedPreferences {
        val app = context.applicationContext
        repeat(2) { attempt ->
            try {
                val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                val prefs = EncryptedSharedPreferences.create(
                    prefsName,
                    alias,
                    app,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                // Leftovers from the pre-fix plaintext fallback: shared_prefs/<name>.xml held
                // credentials in the clear. Delete them whenever encryption works.
                deletePlainPrefs(app, prefsName)
                mode = SecretStorageMode.Secure
                return prefs
            } catch (e: Exception) {
                Log.e(
                    "Hermex",
                    "SecureStore($prefsName): attempt ${attempt + 1} failed " +
                        "${e.javaClass.simpleName}: ${e.message}",
                )
                DebugLog.log("SEC", "$prefsName:open_attempt_${attempt + 1}", e.javaClass.simpleName)
                // A file written by the old plaintext fallback (or a half-written encrypted file)
                // is what makes create() throw; deleting it is what lets the retry succeed.
                runCatching { File(app.noBackupFilesDir, "$prefsName.xml").delete() }
            }
        }
        mode = SecretStorageMode.SessionOnly
        deletePlainPrefs(app, prefsName)
        DebugLog.log(
            "SEC",
            "$prefsName:storage_mode",
            "SessionOnly (in-memory only; nothing persisted, nothing on disk unencrypted)",
        )
        return InMemoryPreferences()
    }

    /** The file the old fallback wrote: shared_prefs/<prefsName>.xml. */
    private fun deletePlainPrefs(app: Context, prefsName: String) {
        runCatching { app.deleteSharedPreferences(prefsName) }
    }
}

/**
 * SharedPreferences backed by a map that lives as long as the process. Used only when the
 * Keystore is unavailable, so "not persisted" is a property of the store, not of call sites.
 */
class InMemoryPreferences : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap<String, Any?>(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (values[key] as MutableSet<String>?) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = (values[key] as Int?) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (values[key] as Long?) ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (values[key] as Float?) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = (values[key] as Boolean?) ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed.add(key); pending.remove(key) }
        override fun clear() = apply { clearAll = true; pending.clear(); removed.clear() }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            removed.forEach { values.remove(it) }
            pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
