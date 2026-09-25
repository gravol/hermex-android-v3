package com.hermex.v3.feature.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermex.v3.feature.sessions.SessionsUiState
import com.hermex.v3.feature.sessions.SessionsViewModel
import com.hermex.core.network.SessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * WebUI-style drawer (v0.1.126): a narrow icon rail on the left + a panel
 * content area on the right, mirroring the Hermes WebUI mobile sidebar.
 * Panels wired to real gateway-backed screens: Chat (sessions), Tasks (cron),
 * Skills, Settings. Items without a gateway backend are intentionally omitted
 * (no dead buttons).
 */
@Composable
fun WebuiMenuDrawer(
    drawerState: DrawerState,
    onNavigate: (String) -> Unit,
    onOpenSession: (SessionSummary) -> Unit,
    sessionsVM: SessionsViewModel = viewModel(),
    content: @Composable () -> Unit = {},
) {
    val state by sessionsVM.uiState.collectAsState()
    var panel by rememberSaveable { mutableStateOf("chat") }
    var query by rememberSaveable { mutableStateOf("") }
    var sourceFilter by rememberSaveable { mutableStateOf<String?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF141425),
                modifier = Modifier.width(340.dp),
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // ── Icon rail (left) ──
                    Column(
                        modifier = Modifier
                            .width(56.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF141425)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(12.dp))
                        RailButton(
                            icon = { Icons.Outlined.ChatBubble },
                            label = "Chat",
                            selected = panel == "chat",
                            onClick = { panel = "chat" },
                        )
                        RailButton(
                            icon = { Icons.Outlined.Schedule },
                            label = "Tasks",
                            selected = panel == "tasks",
                            onClick = { panel = "tasks" },
                        )
                        RailButton(
                            icon = { Icons.Filled.BarChart },
                            label = "Insights",
                            selected = panel == "insights",
                            onClick = { panel = "insights" },
                        )
                        RailButton(
                            icon = { Icons.Outlined.Handyman },
                            label = "Skills",
                            selected = panel == "skills",
                            onClick = { panel = "skills" },
                        )
                        Spacer(Modifier.weight(1f))
                        RailButton(
                            icon = { Icons.Outlined.Settings },
                            label = "Settings",
                            selected = panel == "settings",
                            onClick = { panel = "settings" },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    // ── Panel content (right) ──
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color(0xFF1A1A2E)),
                    ) {
                        when (panel) {
                            "chat" -> ChatPanel(
                                state = state,
                                query = query,
                                onQueryChange = { query = it },
                                sourceFilter = sourceFilter,
                                onSourceFilter = { sourceFilter = it },
                                onNewSession = {
                                    if (!state.isCreating) {
                                        sessionsVM.createSession { sid ->
                                            if (sid != null) {
                                                onOpenSession(
                                                    SessionSummary(id = sid, title = "New session"),
                                                )
                                            }
                                        }
                                    }
                                },
                                onOpenSession = onOpenSession,
                            )
                            "tasks" -> OpenPanel(
                                title = "TASKS",
                                subtitle = "Scheduled cron jobs — create, pause, edit, delete.",
                                actionLabel = "Open Cron",
                                onAction = { onNavigate("cron") },
                                onClose = { panel = "chat" },
                            )
                            "skills" -> OpenPanel(
                                title = "SKILLS",
                                subtitle = "Hermes skills — browse and inspect.",
                                actionLabel = "Open Skills",
                                onAction = { onNavigate("skills") },
                                onClose = { panel = "chat" },
                            )
                            "insights" -> InsightsPanel(
                                sessions = state.sessions,
                                onClose = { panel = "chat" },
                            )
                            "settings" -> SettingsPanel(onNavigate = onNavigate)
                        }
                    }
                }
            }
        },
    ) { content() }
}

@Composable
private fun RailButton(
    icon: @Composable () -> androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val tint = if (selected) accent else Color(0xFFC0C0C0)
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon(), contentDescription = label, tint = tint)
    }
}

@Composable
private fun ChatPanel(
    state: SessionsUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    sourceFilter: String?,
    onSourceFilter: (String?) -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (SessionSummary) -> Unit,
) {
    val q = query.trim()
    val filtered = remember(state.sessions, q, sourceFilter) {
        state.sessions.filter { s ->
            (sourceFilter == null || (s.source ?: "").uppercase() == sourceFilter) &&
                (q.isEmpty() ||
                    (s.title ?: "").contains(q, ignoreCase = true) ||
                    (s.preview ?: "").contains(q, ignoreCase = true))
        }
    }
    val sourceCounts = remember(state.sessions) {
        state.sessions.groupingBy { (it.source ?: "OTHER").uppercase().ifEmpty { "OTHER" } }
            .eachCount()
    }
    val groups = remember(filtered) {
        filtered.groupBy { groupKey(lastActiveOf(it)) }
            .toSortedMap(compareByDescending { groupRank(it) })
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header: CHAT + new + close
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "CHAT",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
                color = Color(0xFFC0C0C0),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNewSession) {
                Icon(Icons.Filled.Add, contentDescription = "New conversation", tint = Color(0xFFC0C0C0))
            }
        }
        // Search
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    "Filter conversations...",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF888888),
                )
            },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Color(0xFF888888)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF2A2A45),
                unfocusedBorderColor = Color(0xFF2A2A45),
                focusedContainerColor = Color(0xFF1F1F35),
                unfocusedContainerColor = Color(0xFF1F1F35),
            ),
        )
        // Source filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = sourceFilter == null,
                onClick = { onSourceFilter(null) },
                label = { Text("All", style = MaterialTheme.typography.labelSmall) },
            )
            listOf("WEBUI", "CLI").forEach { src ->
                FilterChip(
                    selected = sourceFilter == src,
                    onClick = { onSourceFilter(src) },
                    label = {
                        Text(
                            "$src (${sourceCounts[src] ?: 0})",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }
        HorizontalDivider(color = Color(0xFF2A2A45))
        // Time-grouped session list
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = "No conversations",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF888888),
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
            groups.forEach { (group, sessions) ->
                item(key = "header_$group") {
                    Text(
                        text = "• $group",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFC0C0C0),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(sessions, key = { it.id }) { session ->
                    ChatSessionRow(session = session, onClick = { onOpenSession(session) })
                }
            }
        }
    }
}

@Composable
private fun ChatSessionRow(session: SessionSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title ?: session.id.take(16),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE8E8E8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            session.preview?.let { p ->
                if (p.isNotBlank()) {
                    Text(
                        text = p,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF888888),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        session.source?.let { src ->
            if (src.isNotBlank()) {
                Text(
                    text = src.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x1FA0A0A0))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = relativeTime(lastActiveOf(session)),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF888888),
        )
    }
}

@Composable
private fun OpenPanel(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC0C0C0),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close menu", tint = Color(0xFFC0C0C0))
            }
        }
        HorizontalDivider(color = Color(0xFF2A2A45))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF888888),
            modifier = Modifier.padding(16.dp),
        )
        Button(
            onClick = onAction,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun SettingsPanel(onNavigate: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "SETTINGS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC0C0C0),
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = Color(0xFF2A2A45))
        SettingItem("Agent Soul", "Edit SOUL.md — the agent's identity") {
            onNavigate("soul")
        }
        SettingItem("Appearance & chat", "Theme, colors, text size, tool/thinking toggles") {
            onNavigate("settings")
        }
        SettingItem("Config", "Hermes config (JSON-RPC config.set)") {
            onNavigate("config")
        }
    }
}

@Composable
private fun SettingItem(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE8E8E8))
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF888888))
    }
}

// ── Time helpers (WebUI-style grouping) ──
private fun lastActiveOf(s: SessionSummary): Double? =
    s.lastActivityAt ?: s.lastActive ?: s.endedAt ?: s.startedAt

private fun groupKey(ts: Double?): String {
    if (ts == null) return "OLDER"
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochSecond(ts.toLong()).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        date == today -> "TODAY"
        date == today.minusDays(1) -> "YESTERDAY"
        !date.isBefore(today.minusDays(6)) -> "THIS WEEK"
        !date.isBefore(today.minusDays(13)) -> "LAST WEEK"
        else -> "OLDER"
    }
}

private fun groupRank(key: String): Int = when (key) {
    "TODAY" -> 0
    "YESTERDAY" -> 1
    "THIS WEEK" -> 2
    "LAST WEEK" -> 3
    else -> 4
}

private fun relativeTime(ts: Double?): String {
    if (ts == null) return ""
    val diff = System.currentTimeMillis() / 1000L - ts.toLong()
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        diff < 86400 * 7 -> "${diff / 86400}d"
        else -> "${diff / (86400 * 7)}w"
    }
}
