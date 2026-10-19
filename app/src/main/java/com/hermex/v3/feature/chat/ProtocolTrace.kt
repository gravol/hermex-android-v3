package com.hermex.v3.feature.chat

import com.hermex.core.network.RpcNotification

/**
 * One structured protocol-trace line for a gateway event that the client PARSES but does not
 * RENDER: `TRACE|proto|name=… count=… sid=… seq=… kind=… textLen=…`.
 *
 * Why this exists: five events (`status.update`, `agent.terminal.output`, `review.summary`,
 * `cron.changed`, `platforms.changed`) were previously dropped by the parser and logged as
 * `unknown event` with up to 200 chars of raw payload interpolated into the journal. Now that
 * they are typed, the raw-params line is gone — but silently swallowing them would be worse
 * than logging them, because then a future "why didn't X happen?" investigation has no evidence
 * that X ever arrived. A trace line with ids and counts keeps the event auditable without
 * putting payload text in a shareable log: the debug journal is exported through
 * `file_paths.xml`, and these payloads carry raw background-process output, self-improvement
 * review text and provider-retry status lines.
 *
 * The `count=` field is what makes suppression honest: a suppressed burst is still counted, so
 * the line that does print says "count=25" — 24 seen and not shown, not lost.
 */
data class ProtocolTraceLine(val name: String, val fields: List<String>)

object ProtocolTrace {

    /** Plan the trace line for [n], or null when this event has a real UI path (or none of
     *  its own logging) and must not double-log here. */
    fun plan(n: RpcNotification): ProtocolTraceLine? = when (n) {
        // Transient turn status. No transient-status surface exists in the chat UI today, so
        // this stays unrendered; kind + length only, so the trend is visible without the text.
        is RpcNotification.StatusUpdate ->
            ProtocolTraceLine("status.update", listOf("kind=${n.kind}", "textLen=${n.text.length}"))

        // Background-process stdout. No process surface exists; the process id and chunk size
        // are enough to correlate against the gateway's own process logs.
        is RpcNotification.TerminalOutput ->
            ProtocolTraceLine(
                "agent.terminal.output",
                listOf("proc=${n.processId}", "chunks+=1", "chunkLen=${n.chunk.length}"),
            )

        // Agent self-improvement review. Not transcript content, no surface; length only.
        is RpcNotification.ReviewSummary ->
            ProtocolTraceLine("review.summary", listOf("textLen=${n.text.length}"))

        // Filesystem change signals: the payload is `{}` by contract, so there is nothing to
        // report but the fact that it fired. (A live refetch would need a state holder to
        // invalidate — see the KDoc on CronChanged / PlatformsChanged for why there isn't one.)
        is RpcNotification.CronChanged -> ProtocolTraceLine("cron.changed", listOf("signal"))
        is RpcNotification.PlatformsChanged -> ProtocolTraceLine("platforms.changed", listOf("signal"))

        else -> null
    }

    /** Compose the journal line. Frame ids come from the stamped notification, never the payload. */
    fun format(line: ProtocolTraceLine, count: Int, n: RpcNotification): String =
        "name=${line.name} count=$count " +
            "sid=${n.sessionId?.take(8).orEmpty().ifBlank { "-" }} seq=${n.frameSeq} " +
            line.fields.joinToString(" ")
}

/**
 * Per-name volume gate for [ProtocolTrace] lines.
 *
 * `platforms.changed` fires on a 5 s floor (`change_watcher._CHANGE_BROADCAST_FLOOR_S`) — 1633
 * occurrences across the D_* trial journals, i.e. ~200/hour sustained. One journal line per
 * event would evict DebugLog's 1000-entry ring in minutes and push out the lines people
 * actually go looking for, which is the same failure mode the delta-batching code in
 * DashboardChatViewModel was written to avoid. So: first occurrence of each name always logs,
 * then every [everyN]-th. Counts keep accruing in between (see [countOf]).
 */
class ProtoTraceGate(private val everyN: Int = DEFAULT_EVERY_N) {

    private val counts = HashMap<String, Int>()

    /** True = log this occurrence. Always the first for a name, then every [everyN]-th. */
    @Synchronized
    fun shouldLog(name: String): Boolean {
        val n = (counts[name] ?: 0) + 1
        counts[name] = n
        return n == 1 || n % everyN.coerceAtLeast(1) == 0
    }

    /** How many of this event have been seen, logged or not. */
    fun countOf(name: String): Int = counts[name] ?: 0

    companion object {
        const val DEFAULT_EVERY_N = 25
    }
}
