package com.hermex.v3.feature.menu

import com.hermex.core.network.InsightsUsage
import com.hermex.core.network.SessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Pure Insights aggregation (v0.1.902): window bucketing + per-model rollups.
 *
 * Extracted from [InsightsPanel] so the date math is testable without a device or a Compose
 * runtime — this is exactly the logic that silently went wrong twice: once when the gateway
 * stopped returning `model` on session.list (the panel grouped everything into one row), and
 * again in v0.1.901 when the drawer's default page dropped to 25 sessions, which made the
 * DAY/WEEK/MONTH totals quietly count only the newest page instead of the window. The panel
 * now owns its own fetch; this object just does the math on whatever it is given, and reports
 * how much of the list it was actually given so the UI can say when a total is short.
 */
object InsightsAggregate {

    const val DAY = 0
    const val WEEK = 1
    const val MONTH = 2

    data class ModelUsageRow(
        val model: String,
        val inputTokens: Long,
        val outputTokens: Long,
        val totalTokens: Long,
        val sessionCount: Int,
    )

    data class Result(
        /** Sessions whose usage falls inside the selected window. */
        val inWindow: List<SessionSummary>,
        val modelRows: List<ModelUsageRow>,
        val grandInput: Long,
        val grandOutput: Long,
        /** Rows we were given that carry NO usage at all (input/output both unknown). */
        val rowsWithoutUsage: Int,
        /** Total rows we were given — 0 means the caller handed us an empty list, which is a */
        val totalRows: Int,
    ) {
        val grandTotal: Long get() = grandInput + grandOutput
    }

    fun aggregate(
        sessions: List<SessionSummary>,
        window: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Result {
        // A row counts only if it carries real usage; -1 means "the server never reported it",
        // and folding those in as 0 would understate a model that is in use right now.
        val usable = sessions.filter { it.inputTokens >= 0 || it.outputTokens >= 0 }
        val inWindow = usable.filter { s ->
            val ts = s.lastActivityAt ?: s.lastActive ?: s.endedAt ?: s.startedAt
            ts != null && isInside(ts.toLong(), window, zone, nowEpochSeconds)
        }
        val rows = computeModelRows(inWindow)
        return Result(
            inWindow = inWindow,
            modelRows = rows,
            grandInput = inWindow.sumOf { it.inputTokens.coerceAtLeast(0) },
            grandOutput = inWindow.sumOf { it.outputTokens.coerceAtLeast(0) },
            rowsWithoutUsage = sessions.size - usable.size,
            totalRows = sessions.size,
        )
    }

    /** True if [epochSeconds] falls within the selected window ending now. */
    fun isInside(
        epochSeconds: Long,
        window: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Boolean {
        if (epochSeconds <= 0) return false
        val today = Instant.ofEpochSecond(nowEpochSeconds).atZone(zone).toLocalDate()
        val at = Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()
        return when (window) {
            DAY -> at == today
            WEEK -> !at.isBefore(today.minusDays(6)) && !at.isAfter(today)
            MONTH -> at.year == today.year && at.month == today.month
            else -> false
        }
    }

    fun computeModelRows(sessions: List<SessionSummary>): List<ModelUsageRow> {
        val grouped = sessions.filter { it.inputTokens >= 0 && it.outputTokens >= 0 }
            .groupBy { modelLabel(it.model) }
        return grouped.entries.map { (model, list) ->
            val input = list.sumOf { it.inputTokens.coerceAtLeast(0) }
            val output = list.sumOf { it.outputTokens.coerceAtLeast(0) }
            ModelUsageRow(
                model = model,
                inputTokens = input,
                outputTokens = output,
                totalTokens = input + output,
                sessionCount = list.size,
            )
        }.sortedWith(compareByDescending<ModelUsageRow> { it.totalTokens }.thenBy { it.model })
    }

    /**
     * Normalize the model id into a readable label. Ids in this estate take two shapes:
     * `provider/model` (gateway routing) and `scheme:model` (`custom:clerk-flashnext-uncensored`).
     * Both prefixes are display noise; stripping them is what the old comment claimed the code did
     * and what the v0.1.902 test now pins. Deliberate consequence: the same model reached through
     * two providers shows as one row — the panel answers "how much did this model cost", not
     * "which route served it".
     */
    fun modelLabel(model: String?): String {
        if (model.isNullOrBlank()) return "Unknown"
        val afterSlash = model.substringAfterLast('/')
        return afterSlash.substringAfterLast(':')
    }

    /** Compact token formatting: 85123 → "85.1k", 1048576 → "1.0M", 2.1e9 → "2.1B". */
    fun formatTokens(tokens: Long): String = when {
        // v0.5.1: the billion branch is not decoration. A month of a long-context agent really does
        // send billions once cached prompt tokens are counted (2.1B measured over 30 days), and
        // "2137.3M" is the kind of figure that gets misread as a bug.
        tokens >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", tokens / 1_000_000_000f)
        tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000f)
        tokens >= 1_000 -> String.format(Locale.US, "%.1fk", tokens / 1_000f)
        else -> tokens.toString()
    }

    /** The window selector's period, in days, for the gateway-side rollup (`insights.get`). */
    fun daysFor(window: Int): Int = when (window) {
        DAY -> 1
        WEEK -> 7
        MONTH -> 30
        else -> 30
    }

    data class UsageLine(val label: String, val value: String)

    data class UsageDisplay(
        /** One-line summary: what the model was shown vs what it produced. */
        val headline: String,
        val lines: List<UsageLine>,
        /** Auxiliary work — memory review, compaction, titling, vision — that the session-row
         *  totals never counted. */
        val overhead: List<UsageLine>,
        val models: List<ModelUsageRow>,
    )

    /**
     * Turn a server [InsightsUsage] into display strings. Kept pure and separate from the composable
     * because the whole point of this figure is honesty about what each number means: prompt volume
     * includes cached tokens, "input" is only the cache misses, and output may contain thinking
     * tokens. Rendering that inline invites the next reader to flatten it back into one number.
     */
    fun renderUsage(usage: InsightsUsage): UsageDisplay {
        val t = usage.totals
        val cached = t.cacheReadTokens + t.cacheWriteTokens
        val lines = buildList {
            add(UsageLine("Prompt (incl. cache)", formatTokens(t.promptTokens)))
            add(UsageLine("  uncached", formatTokens(t.inputTokens)))
            add(UsageLine("  cached", formatTokens(cached)))
            val out = StringBuilder(formatTokens(t.outputTokens))
            if (t.reasoningTokens > 0) out.append(" (${formatTokens(t.reasoningTokens)} thinking)")
            add(UsageLine("Output", out.toString()))
            add(UsageLine("API calls / sessions", "${t.apiCallCount} / ${t.sessions}"))
            val cost = t.actualCost.takeIf { it > 0.0 } ?: t.estimatedCost.takeIf { it > 0.0 }
            if (cost != null) add(UsageLine("Estimated cost", formatCost(cost)))
        }
        val overhead = usage.tasks
            .sortedByDescending { it.inputTokens + it.outputTokens + it.cacheReadTokens }
            .map { UsageLine(taskLabel(it.task), "${formatTokens(it.inputTokens + it.outputTokens + it.cacheReadTokens)} · ${it.apiCallCount} calls") }
        val models = usage.models.map { m ->
            ModelUsageRow(
                model = modelLabel(m.model),
                inputTokens = m.inputTokens,
                outputTokens = m.outputTokens,
                totalTokens = m.totalTokens,
                sessionCount = 0,
            )
        }
        return UsageDisplay(
            headline = "${formatTokens(t.promptTokens)} prompt / ${formatTokens(t.outputTokens)} out",
            lines = lines,
            overhead = overhead,
            models = models,
        )
    }

    /** The gateway's task ids are internal names; say what the work actually is. */
    fun taskLabel(task: String): String = when (task) {
        "background_review" -> "memory review"
        "compression" -> "context compaction"
        "title_generation" -> "titling"
        "vision" -> "image description"
        "" -> "unlabeled"
        else -> task.replace('_', ' ')
    }

    /** Money, at a precision that doesn't lie: sub-cent figures are real here (local routes are 0). */
    fun formatCost(usd: Double): String = when {
        usd >= 1.0 -> String.format(Locale.US, "$%.2f", usd)
        usd > 0.0 -> String.format(Locale.US, "$%.4f", usd)
        else -> "$0.00"
    }
}
