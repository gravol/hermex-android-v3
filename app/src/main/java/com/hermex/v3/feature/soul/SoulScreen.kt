package com.hermex.v3.feature.soul

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.hermex.core.network.DebugLog
import com.hermex.core.network.JsonRpcClient
import com.hermex.core.network.WsConnectionManager
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent Soul editor (v0.1.128): read/edit the SOUL.md (Agent identity injected
 * into the system prompt) for the active (`default`) profile via the gateway's
 * live `profiles.describe` / `profiles.configure` RPCs. No server patch needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoulScreen(onBack: () -> Unit) {
    val vm: SoulViewModel = viewModel()
    val state = vm.uiState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Soul") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(
                "Edit SOUL.md — the agent's identity, injected into the system prompt (CLI/WebUI 1:1).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            when {
                state.loading -> {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                }
                state.error != null -> {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::reload) { Text("Retry") }
                }
                else -> {
                    OutlinedTextField(
                        value = state.soul,
                        onValueChange = { vm.soul = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        placeholder = { Text("No soul defined yet.") },
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = vm::save,
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.saving) "Saving…" else "Save")
                    }
                    if (state.saved) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Saved — applies to new sessions.",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

class SoulViewModel(application: Application) : AndroidViewModel(application) {
    data class State(
        val soul: String = "",
        val loading: Boolean = true,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val error: String? = null,
    )

    var uiState by mutableStateOf(State())
    var soul: String
        get() = uiState.soul
        set(v) { uiState = uiState.copy(soul = v, saved = false) }

    private val wsConnection = WsConnectionManager(viewModelScope)
    private val rpcClient = JsonRpcClient(wsConnection, viewModelScope)

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            uiState = uiState.copy(loading = true, error = null, saved = false)
            try {
                wsConnection.connect()
                // v0.1.136: start the JSON-RPC frame consumer so the server's
                // response to profiles.describe is actually read off the socket.
                // connect() alone only opens the WS — without start() the pending
                // request never completes and times out after 30s ("error -1 ...
                // timed out"). Every other JsonRpcClient caller starts it too.
                rpcClient.start()
                val r = rpcClient.profileDescribe("default")
                val soul = r["soul"]?.jsonPrimitive?.contentOrNull ?: ""
                uiState = State(soul = soul, loading = false)
                DebugLog.log("RPC", "Soul", "soul loaded (${soul.length} chars)")
            } catch (e: Exception) {
                DebugLog.log("RPC", "Soul", "soul load failed: ${e.message}")
                uiState = uiState.copy(loading = false, error = e.message ?: "Failed to load soul")
            }
        }
    }

    fun save() {
        viewModelScope.launch {
            uiState = uiState.copy(saving = true, saved = false, error = null)
            try {
                wsConnection.connect()
                // v0.1.136: same as reload — the consumer must be running for the
                // profiles.configure response to be consumed (else 30s timeout).
                rpcClient.start()
                rpcClient.profileConfigure("default", soul)
                uiState = uiState.copy(saving = false, saved = true)
                DebugLog.log("RPC", "Soul", "soul saved")
            } catch (e: Exception) {
                DebugLog.log("RPC", "Soul", "soul save failed: ${e.message}")
                uiState = uiState.copy(saving = false, error = e.message ?: "Failed to save soul")
            }
        }
    }

    override fun onCleared() {
        rpcClient.stop()
        wsConnection.disconnect()
        super.onCleared()
    }
}
