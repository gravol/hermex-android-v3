package com.hermex.core.network

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Thread-safe in-memory ring buffer for debug logging.
 * All networking calls and SSE events write here automatically.
 * Call [export] to dump the full buffer as a string for sharing.
 *
 * Entries are grouped into sections (see [sectionOf]) so logs can be
 * filtered before export: split the dump by connection vs app vs system,
 * or by log level. Everything is still captured — filters only decide what
 * gets written to the shared/copied output.
 */
object DebugLog {

    private const val MAX_ENTRIES = 1000
    private val buffer = ConcurrentLinkedDeque<Entry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Timestamp of first and last entry for export header
    private var firstTs: Long = System.currentTimeMillis()
    @Volatile private var lastTs: Long = System.currentTimeMillis()

    data class Entry(
        val timestamp: Long,
        val level: String,    // "REQ", "RESP", "SSE", "ERROR", "INFO"
        val tag: String,
        val message: String,
    ) {
        /** Coarse grouping so dumps can be split into smaller sections. */
        val section: Section get() = sectionOf(level, tag)
    }

    // ── Filters ────────────────────────────────────────────────────────
    // Sections a caller may include in an export. "ALL" is implicit — it
    // means every section not explicitly excluded.
    enum class Section { CONNECTION, APP, SYSTEM }

    private val activeLevels = mutableSetOf("REQ", "RESP", "SSE", "ERROR", "INFO")
    private val activeSections = mutableMapOf(Section.CONNECTION to true, Section.APP to true, Section.SYSTEM to true)
    private var searchQuery: String? = null

    /** Toggle whether a log level is included in exports. */
    fun setLevelEnabled(level: String, enabled: Boolean) {
        if (enabled) activeLevels.add(level); else activeLevels.remove(level)
    }

    /** Toggle whether a section is included in exports. */
    fun setSectionEnabled(section: Section, enabled: Boolean) {
        activeSections[section] = enabled
    }

    /** Clear all sections/levels back to "show everything". */
    fun resetFilters() {
        activeLevels.clear(); activeLevels.addAll(listOf("REQ", "RESP", "SSE", "ERROR", "INFO"))
        activeSections[Section.CONNECTION] = true
        activeSections[Section.APP] = true
        activeSections[Section.SYSTEM] = true
        searchQuery = null
    }

    /** Apply a case-insensitive substring filter. Null/blank clears it. */
    fun setSearch(query: String?) {
        searchQuery = if (query.isNullOrBlank()) null else query.trim().lowercase()
    }

    private fun matchesFilters(entry: Entry): Boolean {
        if (!activeLevels.contains(entry.level)) return false
        if (!activeSections.getValue(entry.section)) return false
        val q = searchQuery ?: return true
        return entry.message.lowercase().contains(q) ||
            entry.tag.lowercase().contains(q) ||
            entry.section.name.lowercase().contains(q)
    }

    // ── Section routing ────────────────────────────────────────────────
    private fun sectionOf(level: String, tag: String): Section {
        return when (tag) {
            // Connection / transport layer
            "WS", "HTTP", "ROUTE" -> Section.CONNECTION
            // Background / system services
            "NOTIF", "CRON", "REPLY", "Service" -> Section.SYSTEM
            // Everything else — app + UI logic
            else -> Section.APP
        }
    }

    /** Convenience: classify a tag without needing the level. */
    fun sectionOf(tag: String): Section = sectionOf("INFO", tag)

    /** Log a message. Thread-safe. */
    fun log(level: String, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        synchronized(buffer) {
            if (buffer.isEmpty()) firstTs = entry.timestamp
            while (buffer.size >= MAX_ENTRIES) buffer.pollFirst()
            buffer.addLast(entry)
        }
        lastTs = entry.timestamp
    }

    /** Log with a throwable — captures full stack trace. */
    fun log(level: String, tag: String, message: String, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        log(level, tag, "$message\n${sw}")
    }

    /** Shortcut for request logging. */
    fun req(method: String, url: String, headers: String) {
        log("REQ", "HTTP", "$method $url\n$headers")
    }

    /** Shortcut for response logging. */
    fun resp(code: Int, url: String, body: String?) {
        val truncated = if (body != null && body.length > 2000) body.take(2000) + "\n... [truncated ${body.length} total]" else body
        log("RESP", "HTTP", "$code $url${if (truncated != null) "\n$truncated" else ""}")
    }

    /** Shortcut for SSE event logging. */
    fun sse(tag: String, message: String) {
        log("SSE", tag, message)
    }

    /** Shortcut for error logging. */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) log("ERROR", tag, message, throwable)
        else log("ERROR", tag, message)
    }

    // ── Exporting with filters ─────────────────────────────────────────

    /**
     * Build a header line for the given filter state so a shared log is
     * self-describing (what sections/levels/search are active).
     */
    private fun filterHeader(): String {
        val sb = StringBuilder()
        val onLevels = activeLevels.joinToString(", ")
        val onSections = activeSections.filterValues { it }.keys.joinToString(", ") { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
        sb.appendLine("Filters: sections=[$onSections] levels=[$onLevels]")
        if (searchQuery != null) sb.appendLine("Search: \"$searchQuery\"")
        return sb.toString()
    }

    /** Export the entire buffer as a single string. */
    fun export(appVersion: String, deviceInfo: String, serverUrl: String): String {
        val sb = StringBuilder()
        sb.appendLine("=== Hermex Debug Log ===")
        sb.appendLine("App version: $appVersion")
        sb.appendLine("Device: $deviceInfo")
        sb.appendLine("Server: $serverUrl")
        sb.appendLine("Period: ${dateFormat.format(Date(firstTs))} — ${dateFormat.format(Date(lastTs))}")

        synchronized(buffer) {
            val visible = buffer.filter { matchesFilters(it) }
            sb.appendLine("Entries: ${visible.size} shown / ${buffer.size} total (${activeSections.values.count { !it }} hidden sections)")
        }
        sb.appendLine(filterHeader())
        sb.appendLine("=".repeat(60))
        sb.appendLine()

        synchronized(buffer) {
            for (entry in buffer) {
                if (!matchesFilters(entry)) continue
                sb.appendLine("[${dateFormat.format(Date(entry.timestamp))}] [${entry.level}] ${entry.section}/${entry.tag}")
                if (entry.message.contains('\n')) {
                    entry.message.lines().forEach { line -> sb.appendLine("  $line") }
                } else {
                    sb.appendLine("  ${entry.message}")
                }
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    /** Export only the given section(s), filtered. Handy for focused shares. */
    fun exportSections(vararg sections: Section, appVersion: String, deviceInfo: String, serverUrl: String): String {
        val want = sections.toSet()
        synchronized(buffer) {
            // Temporarily restrict active sections to the requested set.
            val saved = activeSections.toMap()
            activeSections.clear()
            for (s in Section.values()) if (s in want) activeSections[s] = true else activeSections[s] = false
            try {
                return export(appVersion, deviceInfo, serverUrl)
            } finally {
                activeSections.clear(); activeSections.putAll(saved)
            }
        }
    }

    /** Return buffer contents as a short string (for clipboard). */
    fun exportShort(appVersion: String, serverUrl: String): String {
        val sb = StringBuilder()
        sb.appendLine("Hermex v$appVersion | $serverUrl")
        synchronized(buffer) {
            val visible = buffer.filter { matchesFilters(it) }
            sb.appendLine("${visible.size} entries (${activeSections.values.count { !it }} sections hidden)")
        }
        sb.appendLine(filterHeader())
        sb.appendLine("=".repeat(40))
        synchronized(buffer) {
            for (entry in buffer) {
                if (!matchesFilters(entry)) continue
                sb.appendLine("[${entry.level}] ${entry.section}/${entry.tag}: ${entry.message.take(200)}")
            }
        }
        return sb.toString()
    }

    /** Clear the buffer (for testing). */
    fun clear() {
        synchronized(buffer) { buffer.clear() }
        firstTs = System.currentTimeMillis()
    }

    /** Number of entries in the buffer. */
    fun entryCount(): Int = buffer.size

    /** Snapshot counts per section for UI display. */
    fun countsBySection(): Map<Section, Int> {
        synchronized(buffer) {
            return buffer.groupingBy { it.section }.eachCount()
        }
    }

    // ── Public read accessors for the Settings filter panel ────────────
    fun isSectionEnabled(section: Section): Boolean = activeSections[section] == true
    fun isLevelEnabled(level: String): Boolean = activeLevels.contains(level)
    fun searchQuery(): String? = searchQuery
}
