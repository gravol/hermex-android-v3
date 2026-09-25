package com.hermex.v3.feature.system

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermex.v3.notify.CronWatcher
import com.hermex.core.network.DashboardApiClient
import com.hermex.core.network.NetworkResult
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
// Cron jobs
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf<List<DashboardApiClient.CronJob>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // v0.1.80: editor state — null = list, else editing (or creating when id empty)
    var editorJob by remember { mutableStateOf<DashboardApiClient.CronJob?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            when (val r = DashboardApiClient.cronJobs()) {
                is NetworkResult.Success -> { jobs = r.data; error = null }
                is NetworkResult.HttpError -> error = "Server error (${r.code})"
                is NetworkResult.Error -> error = r.exception.message
            }
            // v0.1.82: viewing the cron list re-arms the alarm watcher. Jobs
            // created elsewhere (API/Telegram/desktop) arm here — previously
            // only a chat connect did, so a job created while the app was
            // closed never got an alarm (the 1-min test that didn't ping).
            CronWatcher.sync(context)
        }
    }
    LaunchedEffect(Unit) { load() }

    val editorOpen = editorJob != null || showCreate

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editorOpen) (if (showCreate) "New Cron Job" else "Edit Cron Job") else "Cron Jobs") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (editorOpen) { editorJob = null; showCreate = false }
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!editorOpen) {
                        IconButton(onClick = { load() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        // v0.1.80: create new job
                        IconButton(onClick = { showCreate = true }) {
                            Icon(Icons.Default.Add, contentDescription = "New job")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                editorOpen -> {
                    CronEditScreen(
                        job = editorJob,
                        onDone = {
                            editorJob = null
                            showCreate = false
                            load()
                            // Re-arm alarm watcher with the new schedule
                            CronWatcher.sync(context)
                        },
                    )
                }
                error != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { load() }) { Text("Retry") }
                    }
                }
                jobs == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                jobs!!.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No cron jobs — tap + to create one", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(jobs!!, key = { it.id }) { job ->
                            CronJobRow(
                                job = job,
                                onAction = { action ->
                                    scope.launch {
                                        when (val r = DashboardApiClient.cronAction(job.id, action)) {
                                            is NetworkResult.Success -> {
                                                Toast.makeText(context, "Done", Toast.LENGTH_SHORT).show()
                                                load()
                                            }
                                            is NetworkResult.HttpError ->
                                                Toast.makeText(context, "Server error (${r.code})", Toast.LENGTH_SHORT).show()
                                            is NetworkResult.Error ->
                                                Toast.makeText(context, "Failed: ${r.exception.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onEdit = { editorJob = job },
                                onDelete = {
                                    scope.launch {
                                        when (val r = DashboardApiClient.cronDelete(job.id)) {
                                            is NetworkResult.Success -> {
                                                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                                                load()
                                                CronWatcher.sync(context)
                                            }
                                            is NetworkResult.HttpError ->
                                                Toast.makeText(context, "Server error (${r.code})", Toast.LENGTH_SHORT).show()
                                            is NetworkResult.Error ->
                                                Toast.makeText(context, "Failed: ${r.exception.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CronJobRow(
    job: DashboardApiClient.CronJob,
    onAction: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val paused = job.state == "paused" || job.pausedAt != null
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = job.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (paused) "Paused" else "Scheduled",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (paused) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                    ),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = job.scheduleDisplayLocal() ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            job.repeat?.let { rep ->
                if (rep.times != null || (rep.completed ?: 0) > 0) {
                    Text(
                        text = "Runs: ${rep.completed ?: 0}${rep.times?.let { " / $it" } ?: ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onAction(if (paused) "resume" else "pause") },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(
                        if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (paused) "Resume" else "Pause", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = { onAction("trigger") },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text("Run now", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                // v0.1.80: edit + delete
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    // Destructive action — always confirm (v0.1.80)
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete cron job?") },
            text = { Text("Delete \"${job.name}\"? Its run history stays, but the schedule is removed permanently.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Cron editor (v0.1.80): create + edit
// ─────────────────────────────────────────────────────────────

private val cronLocalDateFormat = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).apply {
    timeZone = java.util.TimeZone.getDefault()
}

/**
 * Schedule text in the PHONE's timezone (v0.1.83). The server's
 * schedule_display renders one-shot "once at ..." timestamps UTC-naive;
 * cron expressions and intervals carry no timezone, so they pass through.
 */
private fun DashboardApiClient.CronJob.scheduleDisplayLocal(): String? {
    val s = schedule ?: return scheduleDisplay
    return when (s.kind) {
        "once" -> {
            val runAt = s.runAt?.let { raw ->
                runCatching {
                    val ts = java.time.Instant.parse(raw).toEpochMilli()
                    cronLocalDateFormat.format(java.util.Date(ts))
                }.getOrNull()
            }
            if (runAt != null) "Once: $runAt" else scheduleDisplay
        }
        "interval" -> s.minutes?.let { m ->
            if (m % (24 * 60) == 0) "Every ${m / (24 * 60)} day(s)"
            else if (m % 60 == 0) "Every ${m / 60} hour(s)"
            else "Every $m minute(s)"
        } ?: scheduleDisplay
        "cron" -> s.expr ?: scheduleDisplay
        else -> scheduleDisplay
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CronEditScreen(
    job: DashboardApiClient.CronJob?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isCreate = job == null

    var name by remember { mutableStateOf(job?.name ?: "") }
    var prompt by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf(job?.scheduleDisplay ?: "") }
    var deliver by remember { mutableStateOf("local") }
    var saving by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var targets by remember { mutableStateOf<List<DashboardApiClient.CronDeliveryTarget>>(emptyList()) }
    var showDeliverMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        when (val r = DashboardApiClient.cronDeliveryTargets()) {
            is NetworkResult.Success -> targets = r.data.targets
            else -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; formError = null },
            label = { Text("Name") },
            placeholder = { Text("e.g. Morning weather check") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = schedule,
            onValueChange = { schedule = it; formError = null },
            label = { Text("Schedule") },
            placeholder = { Text("0 16 * * 1-5  or  every 90m") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(
                    "Cron expression (min hour dom month dow) or interval shorthand.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            placeholder = { Text("What should the job do? (leave empty to reuse the name)") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )

        // Deliver target dropdown
        Box {
            OutlinedButton(
                onClick = { showDeliverMenu = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Deliver to: ${targets.firstOrNull { it.id == deliver }?.name ?: deliver}")
            }
            DropdownMenu(expanded = showDeliverMenu, onDismissRequest = { showDeliverMenu = false }) {
                targets.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.name ?: t.id) },
                        onClick = { deliver = t.id; showDeliverMenu = false },
                    )
                }
            }
        }

        formError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = {
                scope.launch {
                    saving = true
                    formError = null
                    try {
                        val sched = schedule.trim()
                        if (sched.isBlank()) {
                            formError = "Schedule is required"
                            saving = false
                            return@launch
                        }
                        val result = if (isCreate) {
                            DashboardApiClient.cronCreate(prompt, sched, name, deliver)
                        } else {
                            val updates = buildMap {
                                put("schedule", sched)
                                if (name.isNotBlank()) put("name", name)
                                put("deliver", deliver)
                                if (prompt.isNotBlank()) put("prompt", prompt)
                            }
                            DashboardApiClient.cronUpdate(job!!.id, updates)
                        }
                        when (result) {
                            is NetworkResult.Success -> {
                                Toast.makeText(context, if (isCreate) "Created" else "Saved", Toast.LENGTH_SHORT).show()
                                onDone()
                            }
                            is NetworkResult.HttpError ->
                                formError = "Server error (${result.code}) — check the schedule format"
                            is NetworkResult.Error ->
                                formError = result.exception.message ?: "Save failed"
                        }
                    } catch (e: Exception) {
                        formError = e.message ?: "Save failed"
                    }
                    saving = false
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(if (isCreate) "Create job" else "Save changes")
            }
        }

        if (!isCreate) {
            Text(
                "Run history: pause/resume/run from the list. Deleting is done from the list (with confirmation).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Skills
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onBack: () -> Unit,
    onOpenSkill: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<DashboardApiClient.SkillInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            when (val r = DashboardApiClient.skillsList()) {
                is NetworkResult.Success -> { skills = r.data; error = null }
                is NetworkResult.HttpError -> error = "Server error (${r.code})"
                is NetworkResult.Error -> error = r.exception.message
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skills & Tools") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { load() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                error != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { load() }) { Text("Retry") }
                    }
                }
                skills == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(skills!!, key = { it.name }) { skill ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenSkill(skill.name) },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = skill.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            skill.category?.let { cat ->
                                                Text(
                                                    text = cat,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                        skill.description?.let { d ->
                                            if (d.isNotBlank()) {
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    text = d,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        skill.usage?.let { u ->
                                            if (u > 0) {
                                                Text(
                                                    text = "Used $u×",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                    Switch(
                                        checked = skill.enabled,
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                when (val r = DashboardApiClient.toggleSkill(skill.name, enabled)) {
                                                    is NetworkResult.Success -> load()
                                                    is NetworkResult.HttpError ->
                                                        Toast.makeText(context, "Server error (${r.code})", Toast.LENGTH_SHORT).show()
                                                    is NetworkResult.Error ->
                                                        Toast.makeText(context, "Failed: ${r.exception.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailScreen(skillName: String, onBack: () -> Unit) {
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun load() {
        scope.launch {
            when (val r = DashboardApiClient.skillContent(skillName)) {
                is NetworkResult.Success -> { content = r.data.content; error = null }
                is NetworkResult.HttpError -> error = "Server error (${r.code})"
                is NetworkResult.Error -> error = r.exception.message
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(skillName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                error != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { load() }) { Text("Retry") }
                    }
                }
                content == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        Text(
                            text = content!!,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Config (core files / soul)
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            when (val r = DashboardApiClient.configRaw()) {
                is NetworkResult.Success -> {
                    loaded = r.data.yaml
                    draft = r.data.yaml.orEmpty()
                    error = null
                }
                is NetworkResult.HttpError -> error = "Server error (${r.code})"
                is NetworkResult.Error -> error = r.exception.message
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    fun save() {
        scope.launch {
            saving = true
            when (val r = DashboardApiClient.saveConfigRaw(draft)) {
                is NetworkResult.Success -> {
                    loaded = r.data.yaml ?: draft
                    Toast.makeText(context, "Config saved", Toast.LENGTH_SHORT).show()
                    error = null
                }
                is NetworkResult.HttpError ->
                    Toast.makeText(context, "Save failed (${r.code}): ${r.message.take(120)}", Toast.LENGTH_LONG).show()
                is NetworkResult.Error ->
                    Toast.makeText(context, "Save failed: ${r.exception.message}", Toast.LENGTH_LONG).show()
            }
            saving = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Config (core / soul)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { load() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(10.dp)) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "This is the live config.yaml — personality (soul), providers, tools. Bad YAML or values can break Hermes. Save writes immediately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                error != null && loaded == null -> {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { load() }) { Text("Retry") }
                    }
                }
                loaded == null -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                        ),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { save() },
                        enabled = !saving && draft != loaded,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Save, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (saving) "Saving…" else "Save config")
                    }
                }
            }
        }
    }
}
