package com.hermex.v3.feature.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermex.core.network.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).apply {
    timeZone = TimeZone.getDefault()
}

/** Best available "last message received" timestamp for a session (v0.1.82). */
private fun SessionSummary.lastActivityTs(): Double? =
    lastActivityAt ?: lastActive ?: startedAt

/** Compact relative time: 5m ago / 3h ago / 2d ago / absolute after 7d. */
private fun relativeTime(epochSeconds: Double): String {
    val now = System.currentTimeMillis()
    val ts = (epochSeconds * 1000).toLong()
    val diffMs = now - ts
    val minutes = diffMs / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
        else -> dateFormat.format(Date(ts))
    }
}

/** How many recent sessions show before the "All sessions" expander. */
private const val RECENT_LIMIT = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onSessionTap: (SessionSummary) -> Unit = {},
    onSettings: () -> Unit = {},
    activeSessions: StateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
    onOpenCron: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenConfig: () -> Unit = {},
    viewModel: SessionsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val pinnedIds by viewModel.pinnedIds.collectAsState()
    val activeMap by activeSessions.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var showAllSessions by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by rememberSaveable { mutableStateOf<SessionSummary?>(null) }

    fun openSession(session: SessionSummary) {
        scope.launch { drawerState.close() }
        onSessionTap(session)
    }

    fun onDeleteSession(session: SessionSummary) {
        scope.launch { drawerState.close() }
        deleteTarget = session
        showDeleteConfirm = true
    }

    // Client-side search over the loaded list (mirrors desktop local filtering).
    // While searching, show ALL matches — the RECENT_LIMIT only applies to browsing.
    val q = query.trim()
    val visible = remember(state.sessions, q) {
        if (q.isEmpty()) {
            state.sessions
        } else {
            state.sessions.filter { s ->
                (s.title ?: "").contains(q, ignoreCase = true) ||
                    (s.preview ?: "").contains(q, ignoreCase = true) ||
                    s.id.contains(q, ignoreCase = true)
            }
        }
    }
    // Server orders session.list by last_active desc, so take(5) = 5 most recent.
    val pinned = visible.filter { it.id in pinnedIds }
    val unpinned = visible.filter { it.id !in pinnedIds }
    val recent = if (q.isEmpty()) unpinned.take(RECENT_LIMIT) else emptyList()
    val groups = remember(unpinned) {
        unpinned
            .groupBy { (it.source ?: "other").uppercase().ifEmpty { "OTHER" } }
            .entries
            .sortedByDescending { it.value.size }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Header
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        Text(
                            text = "Hermex",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${state.sessions.size} sessions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // New session
                    ListItem(
                        headlineContent = { Text("New session") },
                        leadingContent = {
                            Icon(Icons.Default.Add, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            if (!state.isCreating) {
                                viewModel.createSession { sid ->
                                    if (sid != null) {
                                        openSession(SessionSummary(id = sid, title = "New session"))
                                    }
                                }
                            }
                        },
                    )

                    // Search
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            // A new search resets the expander so matches are visible
                            if (it.isNotEmpty()) showAllSessions = false
                        },
                        placeholder = { Text("Search sessions…") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = MaterialTheme.shapes.large,
                    )

                    HorizontalDivider()

                    if (q.isNotEmpty()) {
                        // ── Search results: all matches, grouped ──
                        if (visible.isEmpty()) {
                            Text(
                                text = "No matches for \"$q\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp),
                            )
                        } else {
                            if (pinned.isNotEmpty()) {
                                SectionHeader("PINNED (${pinned.size})")
                                pinned.forEach { session ->
                                    DrawerSessionRow(
                                        session = session,
                                        pinned = true,
                                        onClick = { openSession(session) },
                                        onTogglePin = { viewModel.togglePin(session.id) },
                                        onDeleted = { onDeleteSession(session) },
                                    isActive = activeMap[session.id] == true,
                                    )
                                }
                            }
                            groups.forEach { (source, sessions) ->
                                SectionHeader("$source (${sessions.size})")
                                sessions.forEach { session ->
                                    DrawerSessionRow(
                                        session = session,
                                        pinned = false,
                                        onClick = { openSession(session) },
                                        onTogglePin = { viewModel.togglePin(session.id) },
                                        onDeleted = { onDeleteSession(session) },
                                    isActive = activeMap[session.id] == true,
                                    )
                                }
                            }
                        }
                    } else {
                        // ── Browse mode: pinned + recent 5 + expandable All ──
                        if (pinned.isNotEmpty()) {
                            SectionHeader("PINNED (${pinned.size})")
                            pinned.forEach { session ->
                                DrawerSessionRow(
                                    session = session,
                                    pinned = true,
                                    onClick = { openSession(session) },
                                    onTogglePin = { viewModel.togglePin(session.id) },
                                    onDeleted = { onDeleteSession(session) },
                                    isActive = activeMap[session.id] == true,
                                )
                            }
                            HorizontalDivider()
                        }

                        if (recent.isNotEmpty()) {
                            SectionHeader("RECENT")
                            recent.forEach { session ->
                                DrawerSessionRow(
                                    session = session,
                                    pinned = false,
                                    onClick = { openSession(session) },
                                    onTogglePin = { viewModel.togglePin(session.id) },
                                    onDeleted = { onDeleteSession(session) },
                                    isActive = activeMap[session.id] == true,
                                )
                            }
                            HorizontalDivider()
                        }

                        // Expandable full list
                        if (!showAllSessions) {
                            ListItem(
                                headlineContent = { Text("All sessions") },
                                supportingContent = { Text("${visible.size} total") },
                                trailingContent = {
                                    Icon(Icons.Default.ExpandMore, contentDescription = "Show all")
                                },
                                modifier = Modifier.clickable { showAllSessions = true },
                            )
                        } else {
                            groups.forEach { (source, sessions) ->
                                SectionHeader("$source (${sessions.size})")
                                sessions.forEach { session ->
                                    DrawerSessionRow(
                                        session = session,
                                        pinned = false,
                                        onClick = { openSession(session) },
                                        onTogglePin = { viewModel.togglePin(session.id) },
                                        onDeleted = { onDeleteSession(session) },
                                    isActive = activeMap[session.id] == true,
                                    )
                                }
                                HorizontalDivider()
                            }
                            ListItem(
                                headlineContent = { Text("Show less") },
                                trailingContent = {
                                    Icon(Icons.Default.ExpandLess, contentDescription = "Show less")
                                },
                                modifier = Modifier.clickable { showAllSessions = false },
                            )
                        }
                    }

                    // System panels (v0.1.61)
                    HorizontalDivider()
                    SectionHeader("SYSTEM")
                    ListItem(
                        headlineContent = { Text("Cron Jobs") },
                        leadingContent = {
                            Icon(Icons.Outlined.Schedule, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            scope.launch { drawerState.close() }
                            onOpenCron()
                        },
                    )
                    ListItem(
                        headlineContent = { Text("Skills & Tools") },
                        leadingContent = {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            scope.launch { drawerState.close() }
                            onOpenSkills()
                        },
                    )
                    ListItem(
                        headlineContent = { Text("Config (core / soul)") },
                        leadingContent = {
                            Icon(Icons.Outlined.Settings, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            scope.launch { drawerState.close() }
                            onOpenConfig()
                        },
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Hermex") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                        IconButton(onClick = viewModel::loadSessions, enabled = !state.isLoading) {
                            if (state.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when {
                    state.error != null -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = state.error!!,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = viewModel::loadSessions) {
                                Text("Retry")
                            }
                        }
                    }
                    // Main page = quick access only (pinned + recent 5) — the full
                    // browser lives in the menu. Empty state when nothing at all.
                    pinned.isEmpty() && recent.isEmpty() && !state.isLoading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "No sessions yet",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (!state.isCreating) {
                                        viewModel.createSession { sid ->
                                            if (sid != null) {
                                                onSessionTap(SessionSummary(id = sid, title = "New session"))
                                            }
                                        }
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("New session")
                            }
                        }
                    }
                    else -> {
                        LazyColumn {
                            if (pinned.isNotEmpty()) {
                                item { SectionHeader("PINNED (${pinned.size})") }
                                items(pinned, key = { it.id }) { session ->
                                    SessionRow(
                                        session = session,
                                        pinned = true,
                                        onClick = { onSessionTap(session) },
                                        onTogglePin = { viewModel.togglePin(session.id) },
                                        onDeleted = { onDeleteSession(session) },
                                    isActive = activeMap[session.id] == true,
                                    )
                                }
                            }
                            if (recent.isNotEmpty()) {
                                item { SectionHeader("RECENT") }
                                items(recent, key = { it.id }) { session ->
                                    SessionRow(
                                        session = session,
                                        pinned = false,
                                        onClick = { onSessionTap(session) },
                                        onTogglePin = { viewModel.togglePin(session.id) },
                                        onDeleted = { onDeleteSession(session) },
                                    isActive = activeMap[session.id] == true,
                                    )
                                }
                            }
                            item {
                                ListItem(
                                    headlineContent = { Text("Browse all sessions") },
                                    supportingContent = { Text("${state.sessions.size} in the menu") },
                                    leadingContent = {
                                        Icon(Icons.Default.Menu, contentDescription = null)
                                    },
                                    modifier = Modifier.clickable {
                                        scope.launch { drawerState.open() }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Delete confirmation dialog ──
    if (showDeleteConfirm && deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete session?") },
            text = {
                Text(
                    "Delete \"${deleteTarget!!.title ?: deleteTarget!!.id.take(16)}\"? " +
                    "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = deleteTarget!!
                        showDeleteConfirm = false
                        viewModel.deleteSession(target.id) {
                            // list will reload automatically
                        }
                    },
                    enabled = !state.deleting,
                ) {
                    if (state.deleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 1.5.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Deleting…")
                    } else {
                        Text("Delete")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDeleted: () -> Unit,
    isActive: Boolean = false,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = session.title ?: session.id.take(16),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    session.lastActivityTs()?.let { ts ->
                        Text(
                            text = relativeTime(ts),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                session.preview?.let { p ->
                    if (p.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = p,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                session.lastActivityDescription?.let { desc ->
                    if (desc.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Last message: $desc",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isActive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "working",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    session.model?.let { m ->
                        Text(
                            text = m,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = "${session.messageCount} msgs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    session.source?.let { src ->
                        Text(
                            text = src,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (pinned) "Unpin" else "Pin",
                    tint = if (pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(
                onClick = onDeleted,
                enabled = !isActive,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = if (isActive) "Cannot delete active session" else "Delete",
                    tint = if (isActive) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f) else MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun DrawerSessionRow(
    session: SessionSummary,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDeleted: () -> Unit,
    isActive: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title ?: session.id.take(16),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isActive) {
                Text(
                    text = "● working…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            session.preview?.let { p ->
                if (p.isNotBlank()) {
                    Text(
                        text = p,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (isActive) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (pinned) "Unpin" else "Pin",
                tint = if (pinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(
            onClick = onDeleted,
            enabled = !isActive,
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = if (isActive) "Cannot delete active session" else "Delete",
                tint = if (isActive) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f) else MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
            )
        }
    }
}
