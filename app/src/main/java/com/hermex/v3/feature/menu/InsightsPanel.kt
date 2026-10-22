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

/**
 * Insights panel (v0.1.146): token-usage summary, bucketed by day / week / month and broken
 * down per model. The math lives in [InsightsAggregate] (pure, unit-tested).
 *
 * v0.1.902 — the panel no longer reads the drawer's page. It used to aggregate whatever
 * `state.sessions` happened to hold, which after the v0.1.901 tiered default (25 rows) meant a
 * MONTH figure that could only ever see 25 conversations, and an empty drawer rendering a
 * blank panel with nothing to explain itself. The caller now hands it its own window-sized
 * snapshot plus the two facts the UI must be honest about: still loading, and whether the
 * snapshot stopped short of the full history.
 */
@Composable
fun InsightsPanel(
    sessions: List<SessionSummary>,
    onClose: () -> Unit,
    loading: Boolean = false,
    truncated: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    var window by rememberSaveable { mutableIntStateOf(InsightsAggregate.DAY) }

    val result = remember(sessions, window) { InsightsAggregate.aggregate(sessions, window) }
    val modelRows = result.modelRows
    val grandInput = result.grandInput
    val grandOutput = result.grandOutput
    val grandTotal = result.grandTotal

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
            val labels = listOf(
                "DAY" to InsightsAggregate.DAY,
                "WEEK" to InsightsAggregate.WEEK,
                "MONTH" to InsightsAggregate.MONTH,
            )
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

        // v0.1.902: three different things used to render as the same blank panel. They are now
        // named, because "nothing is populated" is unanswerable from the phone — the user cannot
        // tell a fetch that has not run from a window that genuinely has no sessions in it.
        if (truncated && modelRows.isNotEmpty()) {
            Text(
                text = "Older conversations exist beyond this snapshot — totals cover what was fetched.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFB8860B),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        when {
            loading && result.totalRows == 0 -> PanelNotice(
                title = "Loading…",
                body = "Fetching the conversations this window covers.",
            )

            result.totalRows == 0 -> PanelNotice(
                title = "No conversations loaded",
                body = "The session list came back empty, so there is nothing to total. " +
                    "This is a connection problem, not a quiet period.",
                actionLabel = if (onRetry != null) "Try again" else null,
                onAction = onRetry,
            )

            modelRows.isEmpty() -> PanelNotice(
                title = "No usage in this window",
                body = when {
                    result.rowsWithoutUsage > 0 ->
                        "${result.rowsWithoutUsage} session${if (result.rowsWithoutUsage == 1) "" else "s"} " +
                            "loaded but none reported token usage."
                    else -> "${result.totalRows} session${if (result.totalRows == 1) "" else "s"} " +
                        "available; none of them are active in the selected window."
                },
                actionLabel = if (onRetry != null) "Refresh" else null,
                onAction = onRetry,
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
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

@Composable
private fun PanelNotice(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFC0C0C0),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF888888),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = onAction, shape = RoundedCornerShape(8.dp)) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private typealias ModelUsageRow = InsightsAggregate.ModelUsageRow

/** Compact token formatting lives in the aggregate so tests assert the same string the UI shows. */
private fun formatTokens(tokens: Long): String = InsightsAggregate.formatTokens(tokens)

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
