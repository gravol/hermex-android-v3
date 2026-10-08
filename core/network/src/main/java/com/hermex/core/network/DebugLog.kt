package com.hermex.core.network

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe debug log: in-memory ring buffer + rolling on-disk journal.
 *
 * v0.1.82 disk persistence: every entry appends to `filesDir/debuglog/hermex.log`
 * via a write-behind writer (flush ~every 2s), so the log survives crashes and
 * swipe-up force-kills — the exact moment a log is most needed. On process start
 * the journal tail is reloaded into the memory buffer. Disk use is HARD-CAPPED:
 * the journal rotates at [MAX_FILE_BYTES] into one `.1` file (oldest dropped), so
 * total footprint is ~2 x 2 MB forever, and the last [KEEP_EXPORTS] exported
 * files are kept. Same ring-buffer philosophy as the memory buffer: bounded,
 * self-trimming, newest always present.
 *
 * Filters ([setLevelEnabled], [setSectionEnabled], [setSearch]) only affect
 * EXPORTS — everything is always captured.
 */
object DebugLog {

    private const val MAX_ENTRIES = 4000
    private const val MAX_FILE_BYTES = 2_000_000L      // journal rotation size
    private const val FLUSH_INTERVAL_MS = 2_000L
    private const val KEEP_LOG_FILES = 2               // hermex.log + hermex.log.1
    private const val KEEP_EXPORTS = 10
    private const val RELOAD_MAX_BYTES = 6_000_000L

    private val buffer = ConcurrentLinkedDeque<Entry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

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

    // ── Disk journal (write-behind) ────────────────────────────────────
    @Volatile private var dir: File? = null
    private val pending = StringBuilder()
    private val pendingLen = AtomicLong(0)
    @Volatile private var writerRunning = false
    @Volatile private var journalBytes = 0L

    /**
     * Wire the disk journal and reload the previous session's tail. Call once
     * from Application.onCreate before any log lines matter. Idempotent.
     */
    fun init(context: Context) {
        if (writerRunning) return
        val d = File(context.filesDir, "debuglog").apply { mkdirs() }
        dir = d
        pruneLogFiles()
        val main = File(d, "hermex.log")
        journalBytes = if (main.exists()) main.length() else 0L
        reloadFromDisk(d)
        startWriter()
    }

    private fun startWriter() {
        writerRunning = true
        Thread {
            while (writerRunning) {
                try { Thread.sleep(FLUSH_INTERVAL_MS) } catch (_: InterruptedException) { break }
                flushToDisk()
            }
        }.apply {
            name = "DebugLogWriter"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun flushToDisk() {
        val d = dir ?: return
        val text: String
        synchronized(pending) {
            if (pending.isEmpty()) return
            text = pending.toString()
            pending.setLength(0)
            pendingLen.set(0)
        }
        try {
            val main = File(d, "hermex.log")
            if (journalBytes + text.length > MAX_FILE_BYTES) {
                // Rotation: hermex.log -> hermex.log.1 (oldest .1 dropped).
                val rot = File(d, "hermex.log.1")
                if (rot.exists()) rot.delete()
                if (main.exists()) main.renameTo(rot)
                journalBytes = 0L
            }
            main.appendText(text)
            journalBytes += text.length
        } catch (_: Exception) {
            // Disk trouble must never take the app down; entries stay in memory.
        }
    }

    /** Flush queued entries synchronously (crash handler, shutdown). */
    fun flushNow() {
        flushToDisk()
    }

    private fun reloadFromDisk(d: File) {
        try {
            val parts = mutableListOf<List<Entry>>()
            var budget = RELOAD_MAX_BYTES
            // Oldest first: rotated .1 then current.
            for (name in listOf("hermex.log.1", "hermex.log")) {
                val f = File(d, name)
                if (!f.exists()) continue
                val size = f.length()
                if (budget - size < 0) { parts.add(readTail(f, budget)); budget = 0 }
                else { parts.add(readTail(f, size)); budget -= size }
            }
            val entries = parts.flatten()
            if (entries.isEmpty()) return
            synchronized(buffer) {
                entries.forEach { buffer.addLast(it) }
                while (buffer.size > MAX_ENTRIES) buffer.pollFirst()
                buffer.peekFirst()?.let { firstTs = it.timestamp }
                buffer.peekLast()?.let { lastTs = it.timestamp }
            }
            log("INFO", "HermexApp",
                "journal reloaded: ${entries.size} entries from previous session(s)")
        } catch (_: Exception) {
            // Corrupt journal: start fresh.
            try { File(d, "hermex.log").delete(); File(d, "hermex.log.1").delete() } catch (_: Exception) {}
        }
    }

    /** Read the trailing [maxBytes] of a journal file as entries (oldest first). */
    private fun readTail(f: File, maxBytes: Long): List<Entry> {
        if (maxBytes <= 0) return emptyList()
        val all = f.readLines()
        var bytes = 0L
        var startIdx = 0
        for (i in all.indices) {
            bytes += all[i].length + 1
            if (bytes > maxBytes) { startIdx = i; break }
            startIdx = i + 1
        }
        return all.subList(startIdx, all.size).mapNotNull { parseLine(it) }
    }

    private fun parseLine(line: String): Entry? {
        // ts | level | tag | message  (newlines encoded as \u0001)
        val p = line.split('|', limit = 4)
        if (p.size < 4) return null
        val ts = p[0].toLongOrNull() ?: return null
        return Entry(ts, p[1], p[2], p[3].replace('\u0001', '\n'))
    }

    private fun pruneLogFiles() {
        try {
            val d = dir ?: return
            val logs = d.listFiles { f -> f.name.startsWith("hermex.log") }?.toList().orEmpty()
            logs.sortedBy { it.lastModified() }.dropLast(KEEP_LOG_FILES).forEach { it.delete() }
            val exports = exportsDir()
            val files = exports.listFiles()?.toList().orEmpty()
            files.sortedBy { it.lastModified() }.dropLast(KEEP_EXPORTS).forEach { it.delete() }
        } catch (_: Exception) {}
    }

    private fun exportsDir(): File {
        val base = dir?.parentFile ?: return File(".")
        return File(base, "debugexports").apply { mkdirs() }
    }

    // ── Logging ────────────────────────────────────────────────────────

    /** Log a message. Thread-safe; append-only. Secrets are redacted at entry —
     * the journal is shareable and REQ/RESP paths dump bodies verbatim. */
    fun log(level: String, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, redactSecrets(message))
        synchronized(buffer) {
            if (buffer.isEmpty()) firstTs = entry.timestamp
            while (buffer.size >= MAX_ENTRIES) buffer.pollFirst()
            buffer.addLast(entry)
        }
        lastTs = entry.timestamp
        val line = "${entry.timestamp}|${entry.level}|$tag|${message.replace('\n', '\u0001')}\n"
        synchronized(pending) {
            // Bound the unflushed tail (writer stalled / app killed fast).
            if (pendingLen.get() < 4L * MAX_FILE_BYTES) {
                pending.append(line)
                pendingLen.addAndGet(line.length.toLong())
            }
        }
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

    // ── Secret redaction ──
    // v0.1.83: REQ/RESP logging dumps request/response bodies verbatim, which
    // put the dashboard password, session cookies, ws-tickets and Authorization
    // headers straight into the journal — and the journal is SHAREABLE. Redact
    // at the single choke point so every logging path is covered, not just the
    // OkHttp interceptor.
    private val secretPatterns = listOf(
        Regex("(\"(?:password|passphrase|token|api_key|apikey|secret)\"\\s*:\\s*\")[^\"]*(\")", RegexOption.IGNORE_CASE),
        Regex("(\"ticket\"\\s*:\\s*\")[^\"]*(\")", RegexOption.IGNORE_CASE),
        Regex("(?i)^cookie:.*$", RegexOption.MULTILINE),
        Regex("(?i)^set-cookie:.*$", RegexOption.MULTILINE),
        Regex("(?i)^authorization:.*$", RegexOption.MULTILINE),
        Regex("[?&]ticket=[^&\\s]*"),
    )

    private fun redactSecrets(s: String): String {
        var out = s
        out = secretPatterns[0].replace(out) { m -> "${m.groupValues[1]}***${m.groupValues[2]}" }
        out = secretPatterns[1].replace(out) { m -> "${m.groupValues[1]}***${m.groupValues[2]}" }
        out = secretPatterns[2].replace(out, "Cookie: ***")
        out = secretPatterns[3].replace(out, "Set-Cookie: ***")
        out = secretPatterns[4].replace(out, "Authorization: ***")
        out = secretPatterns[5].replace(out, "&ticket=***")
        return out
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
        flushToDisk()  // include anything queued but not yet written out
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

    /**
     * Write an export to `filesDir/debugexports/hermex_debug_<stamp>.txt`
     * (survives force-kill; last [KEEP_EXPORTS] kept, oldest pruned) and
     * return the file. This is the share/keep path — Settings calls it before
     * handing the file to the share sheet, so every share also lands a copy
     * on disk.
     */
    fun writeExportFile(content: String): File {
        val exports = exportsDir()
        val stamp = fileStampFormat.format(Date())
        val file = File(exports, "hermex_debug_$stamp.txt")
        file.writeText(content)
        pruneLogFiles()
        return file
    }

    /** Clear the buffer AND the disk journal. */
    fun clear() {
        synchronized(buffer) { buffer.clear() }
        firstTs = System.currentTimeMillis()
        synchronized(pending) { pending.setLength(0); pendingLen.set(0) }
        try {
            val d = dir
            if (d != null) {
                File(d, "hermex.log").delete()
                File(d, "hermex.log.1").delete()
                journalBytes = 0L
            }
        } catch (_: Exception) {}
    }

    /** Number of entries in the buffer. */
    fun entryCount(): Int = buffer.size

    /** Snapshot counts per section for UI display. */
    fun countsBySection(): Map<Section, Int> {
        synchronized(buffer) {
            return buffer.groupingBy { it.section }.eachCount()
        }
    }

    /** On-disk journal size in bytes (rotation files only). */
    fun diskSizeBytes(): Long = try {
        val d = dir ?: return 0L
        d.listFiles { f -> f.name.startsWith("hermex.log") }?.sumOf { it.length() } ?: 0L
    } catch (_: Exception) { 0L }

    // ── Public read accessors for the Settings filter panel ────────────
    fun isSectionEnabled(section: Section): Boolean = activeSections[section] == true
    fun isLevelEnabled(level: String): Boolean = activeLevels.contains(level)
    fun searchQuery(): String? = searchQuery
}
