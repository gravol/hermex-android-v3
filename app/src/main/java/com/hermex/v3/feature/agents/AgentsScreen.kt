package com.hermex.v3.feature.agents

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermex.core.network.DebugLog
import com.hermex.core.network.JsonRpcClient
import com.hermex.core.network.WsConnectionManager
import com.hermex.v3.di.AppModule
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One live delegated child, from `delegation.status` → active[].
 * (contracts/billing_delegation_pets.py::ActiveSubagent — every field but the id is optional.)
 */
data class ChildRow(
    val subagentId: String,
    val goal: String,
    val model: String,
    val status: String,
    val toolCount: Int,
    val startedAt: Double,
    val depth: Int,
    val parentHint: String,
)

/**
 * Agents (v0.5.9): global view of delegated children across ALL sessions —
 * the gateway's `delegation.status` roster, 5 s poll while the screen is open.
 * Steering/interrupting a child needs its PARENT session's authority, so those
 * actions live on the chat screen's Children sheet, not here. This screen is
 * the "all my agents on one screen" view plus the global spawn-pause switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(onBack: () -> Unit) {
    val vm: AgentsViewModel = viewModel()
    val state = vm.uiState

    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agents") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(
                        onClick = { vm.togglePause() },
                        enabled = !state.pausedToggling,
                    ) {
                        Icon(
                            if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                            contentDescription = if (state.paused) "Resume spawns" else "Pause spawns",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                state.error != null && state.rows == null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { vm.refresh() }) { Text("Retry") }
                    }
                }
                state.rows == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    val rows = state.rows!!
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(
                                    selected = state.paused,
                                    enabled = !state.pausedToggling,
                                    onClick = { vm.togglePause() },
                                    label = { Text(if (state.paused) "Spawns paused" else "Spawns live") },
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "${rows.size} running · max ${state.maxConcurrent} concurrent",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (rows.isEmpty()) {
                            item {
                                Text(
                                    "No delegated children running right now.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )
                            }
                        }
                        items(rows, key = { it.subagentId }) { row ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                ),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        row.goal.ifBlank { row.subagentId },
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        buildString {
                                            append(row.status.ifBlank { "running" })
                                            if (row.model.isNotBlank()) append(" · ${row.model}")
                                            append(" · ${row.toolCount} tools")
                                            if (row.startedAt > 0) append(" · ${vm.elapsed(row.startedAt)}")
                                            if (row.depth > 1) append(" · depth ${row.depth}")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (row.parentHint.isNotBlank()) {
                                        Text(
                                            "in session ${row.parentHint}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
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
}

class AgentsViewModel(application: Application) : AndroidViewModel(application) {
    data class State(
        val rows: List<ChildRow>? = null,
        val paused: Boolean = false,
        val pausedToggling: Boolean = false,
        val maxConcurrent: Int = 0,
        val error: String? = null,
    )

    var uiState by mutableStateOf(State())
    private var pollJob: Job? = null

    private val wsConnection = AppModule.provideWsConnection()
    private val rpcClient = AppModule.provideRpcClient()

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(5_000)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                wsConnection.connect()
                rpcClient.start()
                val r: JsonObject = rpcClient.delegationStatus()
                val rows = r["active"]?.jsonArray?.map { el ->
                    val o = el.jsonObject
                    ChildRow(
                        subagentId = o["subagent_id"]?.jsonPrimitive?.contentOrNull ?: "",
                        goal = o["goal"]?.jsonPrimitive?.contentOrNull ?: "",
                        model = o["model"]?.jsonPrimitive?.contentOrNull ?: "",
                        status = o["status"]?.jsonPrimitive?.contentOrNull ?: "",
                        toolCount = o["tool_count"]?.jsonPrimitive?.intOrNull ?: 0,
                        startedAt = o["started_at"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        depth = o["depth"]?.jsonPrimitive?.intOrNull ?: 1,
                        parentHint = o["owner_agent_session_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                }.orEmpty()
                uiState = uiState.copy(
                    rows = rows.sortedByDescending { it.startedAt },
                    paused = r["paused"]?.jsonPrimitive?.booleanOrNull ?: false,
                    maxConcurrent = r["max_concurrent_children"]?.jsonPrimitive?.intOrNull ?: 0,
                    error = null,
                )
            } catch (e: Exception) {
                DebugLog.log("RPC", "Agents", "delegation.status failed: ${e.message}")
                uiState = uiState.copy(error = e.message ?: "delegation.status failed")
            }
        }
    }

    fun togglePause() {
        viewModelScope.launch {
            uiState = uiState.copy(pausedToggling = true)
            try {
                wsConnection.connect()
                rpcClient.start()
                rpcClient.delegationPause(paused = !uiState.paused)
            } catch (e: Exception) {
                DebugLog.log("RPC", "Agents", "delegation.pause failed: ${e.message}")
                uiState = uiState.copy(error = e.message)
            }
            uiState = uiState.copy(pausedToggling = false)
            // Roster refresh picks up the new flag; don't trust the local flip alone.
            refresh()
        }
    }

    fun elapsed(startedAt: Double): String {
        val secs = (System.currentTimeMillis() / 1000.0 - startedAt).toLong().coerceAtLeast(0)
        return if (secs < 60) "${secs}s" else if (secs < 3600) "${secs / 60}m" else "${secs / 3600}h${(secs % 3600) / 60}m"
    }
}
