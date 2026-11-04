package com.hermex.v3.feature.mcp

import android.app.Application
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermex.core.network.DebugLog
import com.hermex.core.network.WsConnectionManager
import com.hermex.v3.di.AppModule
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One MCP server row: `mcp.servers.list` merged with `mcp.servers.status` by name.
 * (contracts/tools_mcp_plugins.py::McpServerSummary + McpServerRuntimeRow.)
 */
data class McpRow(
    val name: String,
    val transport: String,
    val enabled: Boolean,
    val source: String,          // "config" | "plugin"
    val plugin: String,
    val status: String,          // connected | disabled | connecting | failed | lazy | configured
    val tools: Int,              // -1 = runtime unknown
    val command: String,
    val url: String,
)

/**
 * MCP (v0.5.9): per-profile Model Context Protocol servers — configured rows plus
 * cached runtime state, with a live Test probe per server (`mcp.servers.test`).
 * Read/observe/probe only: adding, removing and enabling servers stays a
 * WebUI/CLI operation (1:1 parity: the dashboard shows the same list).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(onBack: () -> Unit) {
    val vm: McpViewModel = viewModel()
    val state = vm.uiState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP Servers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
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
                            Text(
                                "Runtime state is the gateway's cache — Test probes live. Add/edit servers from the WebUI or CLI.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        if (rows.isEmpty()) {
                            item {
                                Text(
                                    "No MCP servers configured for this profile.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )
                            }
                        }
                        items(rows, key = { it.name }) { row ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                ),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                row.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                buildString {
                                                    append(row.transport)
                                                    append(" · ")
                                                    append(if (row.source == "plugin") "plugin:${row.plugin}" else "config")
                                                    if (!row.enabled) append(" · disabled")
                                                    if (row.tools >= 0) append(" · ${row.tools} tools")
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            if (row.command.isNotBlank() || row.url.isNotBlank()) {
                                                Text(
                                                    row.url.ifBlank { row.command },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        StatusChip(row.status)
                                        Spacer(Modifier.width(8.dp))
                        TextButton(
                                            enabled = state.testing == null,
                                            onClick = { vm.test(row.name) },
                                        ) {
                                            if (state.testing == row.name) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.width(16.dp).height(16.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            } else {
                                                Text("Test")
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
    }

    state.lastTest?.let { result ->
        AlertDialog(
            onDismissRequest = { vm.clearTestResult() },
            confirmButton = {
                TextButton(onClick = { vm.clearTestResult() }) { Text("Close") }
            },
            title = { Text("${result.name} — ${if (result.ok) "connected" else "failed"}") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .height(320.dp),
                ) {
                    result.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (result.oauthNeeded) {
                        Text("OAuth required — authorize this server from the WebUI first.",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (result.tools.isNotEmpty()) {
                        Text("${result.tools.size} tools exposed:", style = MaterialTheme.typography.labelMedium)
                        result.tools.forEach { (n, d) ->
                            Text(
                                "• $n${if (d.isNotBlank()) " — $d" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun StatusChip(status: String) {
    val color = when (status) {
        "connected" -> MaterialTheme.colorScheme.primary
        "failed" -> MaterialTheme.colorScheme.error
        "connecting" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            status.ifBlank { "unknown" },
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

class McpViewModel(application: Application) : AndroidViewModel(application) {
    data class TestResult(
        val name: String,
        val ok: Boolean,
        val error: String?,
        val oauthNeeded: Boolean,
        val tools: List<Pair<String, String>>,
    )

    data class State(
        val rows: List<McpRow>? = null,
        val error: String? = null,
        val testing: String? = null,
        val lastTest: TestResult? = null,
    )

    var uiState by mutableStateOf(State())

    private val wsConnection = AppModule.provideWsConnection()
    private val rpcClient = AppModule.provideRpcClient()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            try {
                wsConnection.connect()
                rpcClient.start()
                val configured = rpcClient.mcpServersList()["servers"]?.jsonArray.orEmpty()
                val runtime = rpcClient.mcpServersStatus()["servers"]?.jsonArray.orEmpty()
                val runtimeByName = runtime.mapNotNull { el ->
                    val o = el.jsonObject
                    o["name"]?.jsonPrimitive?.contentOrNull?.let { o["name"]!!.jsonPrimitive.content to o }
                }.toMap()
                val rows = configured.map { el ->
                    val o = el.jsonObject
                    val name = o["name"]?.jsonPrimitive?.contentOrNull ?: "?"
                    val rt = runtimeByName[name]
                    McpRow(
                        name = name,
                        transport = o["transport"]?.jsonPrimitive?.contentOrNull ?: "",
                        enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        source = o["source"]?.jsonPrimitive?.contentOrNull ?: "config",
                        plugin = o["plugin"]?.jsonPrimitive?.contentOrNull ?: "",
                        status = rt?.get("status")?.jsonPrimitive?.contentOrNull
                            ?: if (o["enabled"]?.jsonPrimitive?.booleanOrNull == false) "disabled" else "configured",
                        tools = rt?.get("tools")?.jsonPrimitive?.intOrNull ?: -1,
                        command = o["command"]?.jsonPrimitive?.contentOrNull ?: "",
                        url = o["url"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                }.sortedBy { it.name }
                uiState = uiState.copy(rows = rows, error = null)
            } catch (e: Exception) {
                DebugLog.log("RPC", "Mcp", "servers list/status failed: ${e.message}")
                uiState = uiState.copy(error = e.message ?: "MCP load failed")
            }
        }
    }

    fun test(name: String) {
        viewModelScope.launch {
            uiState = uiState.copy(testing = name)
            try {
                wsConnection.connect()
                rpcClient.start()
                val r: JsonObject = rpcClient.mcpServersTest(name)
                val tools = r["tools"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val n = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    n to (o["description"]?.jsonPrimitive?.contentOrNull ?: "")
                }.orEmpty()
                uiState = uiState.copy(
                    testing = null,
                    lastTest = TestResult(
                        name = name,
                        ok = r["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
                        error = r["error"]?.jsonPrimitive?.contentOrNull,
                        oauthNeeded = r["oauth_needed"]?.jsonPrimitive?.booleanOrNull ?: false,
                        tools = tools,
                    ),
                )
            } catch (e: Exception) {
                DebugLog.log("RPC", "Mcp", "test($name) failed: ${e.message}")
                uiState = uiState.copy(
                    testing = null,
                    lastTest = TestResult(name, ok = false, error = e.message, oauthNeeded = false, tools = emptyList()),
                )
            }
        }
    }

    fun clearTestResult() { uiState = uiState.copy(lastTest = null) }

    /** Consume a test result exactly once so rotation/recomposition doesn't re-show it. */
    fun peekTestResult(): TestResult? = uiState.lastTest
}
