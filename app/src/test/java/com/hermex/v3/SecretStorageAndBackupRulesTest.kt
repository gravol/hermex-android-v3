package com.hermex.v3

import com.hermex.core.network.SecureStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards for the 2026-10-17 audit findings on secret storage and cloud backup (Phase 3).
 *
 * These are source/manifest assertions, not device tests: they exist so the three regressions
 * that produced the finding cannot come back silently — a plaintext storage fallback, an
 * `allowBackup` that ships the chat history, or an `<include>` re-added to a rule file.
 */
class SecretStorageAndBackupRulesTest {

    private val repoRoot: File
        get() {
            // Gradle runs unit tests with the module dir as working directory.
            val candidates = listOf(
                File(".."),
                File("."),
                File("../.."),
            )
            return candidates.firstOrNull { File(it, "settings.gradle.kts").exists() }
                ?: throw IllegalStateException("repo root not found from ${File(".").absolutePath}")
        }

    private fun read(vararg parts: String): String =
        File(repoRoot, parts.joinToString(File.separator)).readText()

    @Test
    fun appDoesNotOptIntoCloudBackup() {
        val manifest = read("app", "src", "main", "AndroidManifest.xml")
        assertTrue("allowBackup must be false", manifest.contains("android:allowBackup=\"false\""))
        assertFalse("allowBackup=true is gone", manifest.contains("android:allowBackup=\"true\""))
    }

    @Test
    fun backupRuleFilesContainNoIncludes() {
        for (path in arrayOf(
            arrayOf("app", "src", "main", "res", "xml", "backup_rules.xml"),
            arrayOf("app", "src", "main", "res", "xml", "data_extraction_rules.xml"),
        )) {
            val xml = read(*path)
            assertFalse("${path.last()} must not include sharedpref", xml.contains("<include domain=\"sharedpref\""))
            assertFalse("${path.last()} must not include database", xml.contains("<include domain=\"database\""))
        }
    }

    @Test
    fun secretStoresHaveNoPlaintextFallback() {
        val keychain = read(
            "core", "data", "src", "main", "java", "com", "hermex", "core", "data", "auth", "KeychainStore.kt",
        )
        val cookies = read(
            "core", "network", "src", "main", "java", "com", "hermex", "core", "network", "CookiePersistor.kt",
        )
        // getSharedPreferences(...) is the plaintext store. Neither file may open one.
        assertFalse("KeychainStore opened plain SharedPreferences", keychain.contains("getSharedPreferences("))
        assertFalse("CookiePersistor opened plain SharedPreferences", cookies.contains("getSharedPreferences("))
        assertTrue("KeychainStore delegates to SecureStore", keychain.contains("SecureStore.open("))
        assertTrue("CookiePersistor delegates to SecureStore", cookies.contains("SecureStore.open("))
    }

    @Test
    fun settingsScreenDoesNotAssertEncryptionUnconditionally() {
        val screen = read(
            "app", "src", "main", "java", "com", "hermex", "v3", "feature", "settings", "SettingsScreen.kt",
        )
        assertTrue(
            "the encryption claim must be derived from the storage mode",
            screen.contains("KeychainStore.isStorageEncrypted()"),
        )
    }

    /**
     * v0.1.891 shipped a cleanup that deleted `shared_prefs/<name>.xml` after a **successful**
     * encrypted open. That file is the encrypted store, so credentials survived exactly one
     * restart (measured on Pixel 8: login → force-stop → restored → force-stop → unconfigured).
     * The success path may never delete the store file; only the failure path may.
     */
    @Test
    fun encryptedStoreIsNotDeletedOnTheSuccessPath() {
        val src = read(
            "core", "network", "src", "main", "java", "com", "hermex", "core", "network", "SecureStore.kt",
        )
        val start = src.indexOf("EncryptedSharedPreferences.create(")
        val end = src.indexOf("return prefs", start)
        assertTrue("open() success block found", start > 0 && end > start)
        val successBlock = src.substring(start, end)
        assertFalse(
            "success path must not delete the store file",
            successBlock.contains("deleteSharedPreferences") || successBlock.contains("deleteStoreFile"),
        )
        // Deletion must still exist for the failure path, or the plaintext-leftover retry breaks.
        assertTrue("failure path still deletes the store", src.contains("deleteStoreFile(app, prefsName)"))
    }

    @Test
    fun plaintextLeftoverDetectionKeepsTheEncryptedStore() {
        val encrypted = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="AWEi/TqN9VMbY+mJheBQPIxtvXHQ61RG/WER/B/k3/XqWQ==">AVbFLjVudsDfntcgP4iRb2iAwgI</string>
                <string name="__androidx_security_crypto_encrypted_prefs_key_keyset__">12a901224e7d5b576d92</string>
            </map>
        """.trimIndent()
        val plaintext = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="dashboard_password">hunter2</string>
                <string name="dashboard_username">jeff</string>
            </map>
        """.trimIndent()
        assertFalse("the encrypted store is never a leftover", SecureStore.isPlaintextLeftover(encrypted))
        assertTrue("the old plaintext fallback IS a leftover", SecureStore.isPlaintextLeftover(plaintext))
        assertFalse("an unrelated file is not a leftover", SecureStore.isPlaintextLeftover("<map><int name=\"x\" value=\"1\"/></map>"))
    }
}
