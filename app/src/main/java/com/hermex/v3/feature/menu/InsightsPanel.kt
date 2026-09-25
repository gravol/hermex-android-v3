package com.hermex.v3.feature.menu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermex.core.network.SessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Insights panel (v0.1.146): token-usage summary over the session list,
 * bucketed by day / week / month, broken down per model. Pure client-side
 * aggregation — reads input_tokens / output_tokens that session.list already
 * returns and the per-session model id. Sessions with unknown usage
 * (inputTokens < 0) are skipped so a partial payload never skews totals.
 */
@Composable
fun InsightsPanel(
    sessions: List<SessionSummary>,
    onClose: () -> Unit,
) {
    var window by rememberSaveable { mutableIntStateOf(INSIGHTS_DAY) }

    val usable = remember(sessions) {
        sessions.filter { it.inputTokens >= 0 || it.outputTokens >= 0 }
    }

    // Sessions whose last activity falls inside the selected window.
    val inWindow = remember(usable, window) {
        usable.filter { s ->
            val ts = s.lastActivityAt ?: s.lastActive ?: s.endedAt ?: s.startedAt
            ts != null && isInside(ts.toLong(), window)
        }
    }

    // Per-model rollups within the window: input + output + total tokens + session count.
    val modelRows = remember(inWindow) { computeModelRows(inWindow) }
    val grandInput = remember(inWindow) { inWindow.sumOf { it.inputTokens.coerceAtLeast(0) } }
    val grandOutput = remember(inWindow) { inWindow.sumOf { it.outputTokens.coerceAtLeast(0) } }
    val grandTotal = grandInput + grandOutput

    Column(modifier = Modifier.fillMaxSize()) {
        // Header: INSIGHTS + close
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "INSIGHTS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC0C0C0),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close menu", tint = Color(0xFFC0C0C0))
            }
        }

        // Window segmented control: DAY / WEEK / MONTH — uniform size, only color changes.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val labels = listOf("DAY" to INSIGHTS_DAY, "WEEK" to INSIGHTS_WEEK, "MONTH" to INSIGHTS_MONTH)
            labels.forEach { (label, w) ->
                Button(
                    onClick = { window = w },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (window == w) MaterialTheme.colorScheme.primary
                                          else Color.Transparent,
                        contentColor = if (window == w) MaterialTheme.colorScheme.onPrimary
                                       else MaterialTheme.colorScheme.primary,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Grand total for the window.
        HorizontalDivider(color = Color(0xFF2A2A45))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tokens used",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF888888),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${formatTokens(grandInput)} in / ${formatTokens(grandOutput)} out",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE8E8E8),
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = "Total ${formatTokens(grandTotal)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                imageVector = Icons.Filled.BarChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }

        HorizontalDivider(color = Color(0xFF2A2A45))

        if (modelRows.isEmpty()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "No usage data yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFC0C0C0),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Token totals appear here once sessions report input/output tokens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF888888),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = "PER MODEL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFC0C0C0),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(modelRows, key = { it.model }) { row ->
                    ModelRow(row)
                }
            }
        }
    }
}

private data class ModelUsageRow(
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val sessionCount: Int,
)

/** Normalize the model id into a readable label (strip provider prefixes). */
private fun modelLabel(model: String?): String {
    if (model.isNullOrBlank()) return "Unknown"
    // e.g. "deepseek-v4-pro" or "custom:clerk-genesis" -> show after the slash.
    return model.substringAfterLast('/', model)
}

private fun computeModelRows(sessions: List<SessionSummary>): List<ModelUsageRow> {
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
    }.sortedByDescending { it.totalTokens }
}

@Composable
private fun ModelRow(row: ModelUsageRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.model,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE8E8E8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${row.sessionCount} session${if (row.sessionCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF888888),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${formatTokens(row.inputTokens)} in / ${formatTokens(row.outputTokens)} out",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFA0A0A0),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatTokens(row.totalTokens),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "total",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF888888),
            )
        }
    }
    HorizontalDivider(color = Color(0xFF2A2A45))
}

// ── Window math ──

private const val INSIGHTS_DAY = 0
private const val INSIGHTS_WEEK = 1
private const val INSIGHTS_MONTH = 2

/** True if [epochSeconds] falls within the selected window ending now. */
private fun isInside(epochSeconds: Long, window: Int): Boolean {
    if (epochSeconds <= 0) return false
    val zone = ZoneId.systemDefault()
    val now = Instant.ofEpochSecond(epochSeconds).atZone(zone)
    return when (window) {
        INSIGHTS_DAY -> now.toLocalDate() == LocalDate.now(zone)
        INSIGHTS_WEEK -> !now.toLocalDate().isBefore(LocalDate.now(zone).minusDays(6))
        INSIGHTS_MONTH -> now.year == LocalDate.now(zone).year &&
            now.month == LocalDate.now(zone).month
        else -> false
    }
}

/** Compact token formatting: 85123 → "85.1k", 1048576 → "1.0M". */
private fun formatTokens(tokens: Long): String {
    return when {
        tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000f)
        tokens >= 1_000 -> String.format(Locale.US, "%.1fk", tokens / 1_000f)
        else -> tokens.toString()
    }
}
