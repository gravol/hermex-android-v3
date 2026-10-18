package com.hermex.v3

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
}
