package com.hermex.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermex.core.network.DebugLog
import com.hermex.core.network.JsonRpcClient
import com.hermex.core.network.RpcNotification
import com.hermex.core.network.WsConnectionManager
import com.hermex.v3.di.AppModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Children sheet (v0.5.9): live delegated children of THIS chat's session.
 *
 * Authority model (verified against server.py::_current_session_steer_authority):
 * subagent.list/tail/steer/interrupt resolve `_sessions.get(session_id)` and require
 * the CALLING transport to be attached to the live record — live sid only, never the
 * DB key. So the sheet first does a lightweight `session.resume(omit_messages=true)`
 * on the shared app-wide socket (the chat VM already attached that transport) to
 * obtain the live sid, then sends THAT on every subagent.* call.
 *
 * Live updates: `subagent.start|thinking|tool|complete` frames arrive on the parent
 * sid (subagent.text deliberately does not); each one triggers a debounced re-list.
 * `child_session_id` is NOT in the list snapshot — it rides on subagent.start, so the
 * Watch button appears once a child's start frame has been seen in this sheet's life.
 */
class ChildrenSheetState(
    private val sessionDbKey: String,
    private val scope: CoroutineScope,
    private val ws: WsConnectionManager,
    private val rpc: JsonRpcClient,
) {
    data class Child(
        val subagentId: String,
        val goal: String,
        val model: String,
        val status: String,
        val toolCount: Int,
        val lastTool: String,
        val acceptingSteer: Boolean,
        var childSessionId: String? = null,
    )

    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var children by mutableStateOf<List<Child>>(emptyList())
    var failed by mutableStateOf<List<String>>(emptyList())
    var tailFor by mutableStateOf<String?>(null)
    var tailText by mutableStateOf<String?>(null)
    var steerFor by mutableStateOf<Child?>(null)
    var steerText by mutableStateOf("")
    var busy by mutableStateOf<String?>(null)   // subagentId of in-flight action
    var toast by mutableStateOf<String?>(null)

    private var liveSid: String? = null
    private var pollJob: Job? = null
    private var eventsJob: Job? = null
    private var refreshPending = false

    fun start() {
        rpc.start()
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(3_000)
            }
        }
        // Live frames for THIS session nudge an immediate re-list (debounced to one per 500 ms).
        eventsJob = scope.launch {
            rpc.notifications.collect { n ->
                if (n is RpcNotification.SubagentUpdate && n.sessionId == liveSid) {
                    // Capture watch handles from start frames while we're here.
                    if (n.kind == "start" && n.subagentId.isNotBlank() && !n.childSessionId.isNullOrBlank()) {
                        children.firstOrNull { it.subagentId == n.subagentId }?.childSessionId = n.childSessionId
                    }
                    if (!refreshPending) {
                        refreshPending = true
                        launch { delay(500); refreshPending = false; refresh() }
                    }
                }
            }
        }
    }

    fun stop() {
        pollJob?.cancel(); eventsJob?.cancel()
        pollJob = null; eventsJob = null
    }

    private suspend fun ensure(): String? {
        ws.connect()
        rpc.start()
        liveSid?.let { return it }
        val resumed = rpc.sessionResume(sessionDbKey, omitMessages = true)
        return resumed.session_id.also { liveSid = it }
    }

    fun refresh() {
        scope.launch {
            try {
                val sid = ensure() ?: return@launch
                val r = rpc.subagentList(sid)
                children = r["subagents"]?.jsonArray?.map { el ->
                    val o = el.jsonObject
                    Child(
                        subagentId = o["subagent_id"]?.jsonPrimitive?.contentOrNull ?: "",
                        goal = o["goal"]?.jsonPrimitive?.contentOrNull ?: "",
                        model = o["model"]?.jsonPrimitive?.contentOrNull ?: "",
                        status = o["status"]?.jsonPrimitive?.contentOrNull ?: "",
                        toolCount = o["tool_count"]?.jsonPrimitive?.intOrNull ?: 0,
                        lastTool = o["last_tool"]?.jsonPrimitive?.contentOrNull ?: "",
                        acceptingSteer = o["accepting_steer"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
                    )
                }?.map { c ->
                    // Preserve watch handles captured from events across re-lists.
                    c.copy(childSessionId = children.firstOrNull { it.subagentId == c.subagentId }?.childSessionId)
                }.orEmpty()
                failed = r["delegations"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val goal = o["goal"]?.jsonPrimitive?.contentOrNull ?: ""
                    val err = o["error"]?.jsonPrimitive?.contentOrNull ?: "failed"
                    if (goal.isNotBlank()) "$goal — $err" else err
                }.orEmpty()
                error = null
            } catch (e: Exception) {
                DebugLog.log("RPC", "Children", "list failed: ${e.message}")
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    fun loadTail(child: Child) {
        scope.launch {
            if (tailFor == child.subagentId) { tailFor = null; tailText = null; return@launch }
            tailFor = child.subagentId; tailText = null
            try {
                val sid = ensure() ?: return@launch
                val r = rpc.subagentTail(sid, child.subagentId)
                tailText = if (r["available"]?.jsonPrimitive?.contentOrNull?.toBoolean() == false)
                    "(no transcript file for this child)"
                else
                    (r["text"]?.jsonPrimitive?.contentOrNull ?: "") +
                        if (r["truncated"]?.jsonPrimitive?.contentOrNull?.toBoolean() == true) "\n… (truncated)" else ""
            } catch (e: Exception) {
                tailText = "tail failed: ${e.message}"
            }
        }
    }

    fun submitSteer() {
        val child = steerFor ?: return
        val text = steerText.trim()
        if (text.isEmpty()) return
        scope.launch {
            busy = child.subagentId
            try {
                val sid = ensure() ?: return@launch
                val r = rpc.subagentSteer(sid, child.subagentId, text)
                val status = r["status"]?.jsonPrimitive?.contentOrNull ?: "?"
                toast = if (status == "queued") "Steer queued" else "Steer $status"
                steerFor = null; steerText = ""
            } catch (e: Exception) {
                toast = "steer failed: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun interrupt(child: Child) {
        scope.launch {
            busy = child.subagentId
            try {
                val sid = ensure() ?: return@launch
                val r = rpc.subagentInterrupt(sid, child.subagentId)
                toast = if (r["found"]?.jsonPrimitive?.contentOrNull?.toBoolean() == true)
                    "Interrupt requested" else "Child already gone"
                refresh()
            } catch (e: Exception) {
                toast = "interrupt failed: ${e.message}"
            } finally {
                busy = null
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildrenSheet(
    sessionDbKey: String,
    onDismiss: () -> Unit,
    onWatch: (childSessionId: String, goal: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember(sessionDbKey) {
        ChildrenSheetState(sessionDbKey, scope, AppModule.provideWsConnection(), AppModule.provideRpcClient())
    }
    DisposableEffect(Unit) {
        state.start()
        onDispose { state.stop() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Delegated children", style = MaterialTheme.typography.titleMedium)
            Text(
                "Steer queues a course-correction; interrupt ends one child.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            state.toast?.let {
                Text(it, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
            }

            when {
                state.loading && state.error != null ->
                    Text("Failed: ${state.error}", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                }
                state.children.isEmpty() -> Text(
                    if (state.failed.isEmpty()) "No live children for this session."
                    else "No live children — recent failures below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.children.forEach { child ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(child.goal.ifBlank { child.subagentId },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append(child.status.ifBlank { "running" })
                                if (child.model.isNotBlank()) append(" · ${child.model}")
                                append(" · ${child.toolCount} tools")
                                if (child.lastTool.isNotBlank()) append(" · ${child.lastTool}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (child.childSessionId != null) {
                        IconButton(onClick = { onWatch(child.childSessionId!!, child.goal) }) {
                            Icon(Icons.Outlined.Visibility, contentDescription = "Watch",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(
                        enabled = state.busy != child.subagentId,
                        onClick = { state.steerFor = child; state.steerText = "" },
                    ) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Steer",
                            tint = if (child.acceptingSteer) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(
                        enabled = state.busy != child.subagentId,
                        onClick = { state.interrupt(child) },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Interrupt",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = { state.loadTail(child) }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (state.tailFor == child.subagentId) "Hide transcript" else "Show transcript",
                        style = MaterialTheme.typography.labelMedium)
                }
                if (state.tailFor == child.subagentId) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            state.tailText ?: "loading…",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                        )
                    }
                }
            }

            if (state.failed.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text("Recent failed delegations", style = MaterialTheme.typography.labelMedium)
                state.failed.forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    state.steerFor?.let { child ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { state.steerFor = null },
            title = { Text("Steer child") },
            text = {
                OutlinedTextField(
                    value = state.steerText,
                    onValueChange = { state.steerText = it },
                    placeholder = { Text("Course correction for: ${child.goal.take(40)}") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { Button(onClick = { state.submitSteer() }) { Text("Queue") } },
            dismissButton = { TextButton(onClick = { state.steerFor = null }) { Text("Cancel") } },
        )
    }
}
