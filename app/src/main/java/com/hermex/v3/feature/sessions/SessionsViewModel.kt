package com.hermex.v3.feature.sessions

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermex.v3.feature.settings.SettingsRepository
import com.hermex.core.network.DebugLog
import com.hermex.core.network.JsonRpcClient
import com.hermex.core.network.RpcNotification
import com.hermex.core.network.SessionSummary
import com.hermex.core.network.WsConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class SessionsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    val error: String? = null,
    val deleting: Boolean = false,
)

class SessionsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val settingsRepo = SettingsRepository(application)

    /**
     * Persistent RPC client used to observe `sessions.changed` broadcasts. A
     * dedicated WS connection lives for the ViewModel's lifetime so session-list
     * changes reach us without a full reconnect (see [init]).
     */
    private var observerWs: WsConnectionManager? = null
    private var observerClient: JsonRpcClient? = null
    private var observerReady = false

    /** Locally pinned session ids (desktop-style client-side pinning). */
    val pinnedIds: StateFlow<Set<String>> = settingsRepo.pinnedSessionIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        loadSessions()
        // Establish ONE persistent observer connection for the ViewModel's
        // lifetime and keep watching for `sessions.changed` broadcasts. The
        // dashboard fires this after any external session-list change (cron
        // runs, other clients, turn completion). Without refetching on it the
        // cached list goes stale — which is exactly why Insights showed "no
        // usage data" even after the gateway started returning token counts.
        //
        // v0.1.172: this connection lives for the ViewModel's lifetime and is
        // REUSED across every reload. The old code opened a fresh throwaway WS
        // + ticket on each `sessions.changed` when the observer wasn't instantly
        // live, then disconnected — so frequent broadcasts spun up one socket per
        // reload, racing the reap window and thrashing the brute-force throttle.
        // Reusing one socket (reconnecting in place if it dies) removes that churn.
        viewModelScope.launch {
            try {
                val ws = WsConnectionManager(viewModelScope)
                ws.connect()
                observerClient = JsonRpcClient(ws, viewModelScope).apply { start() }
                observerWs = ws
                observerReady = true
            } catch (_: Exception) {
                observerClient = null
                observerWs = null
                observerReady = false
            }
            if (observerClient != null) {
                observerClient!!.notifications.collect { n ->
                    if (n is RpcNotification.SessionChanged) {
                        DebugLog.log("INFO", "SessionsVM", "sessions.changed → reload")
                        loadDashboardSessions()
                    }
                }
            }
        }
    }

    /** Ensure a live observer socket, reconnecting in place if the old one died. */
    private suspend fun ensureObserver(): WsConnectionManager? {
        val existing = observerWs
        if (existing != null && existing.isConnected) return existing
        try {
            val ws = WsConnectionManager(viewModelScope)
            ws.connect()
            observerClient = JsonRpcClient(ws, viewModelScope).apply { start() }
            observerWs = ws
            observerReady = true
            DebugLog.log("INFO", "SessionsVM", "observer reconnected — live socket")
            return ws
        } catch (e: Exception) {
            Log.e("Hermex", "SessionsViewModel: observer reconnect failed", e)
            DebugLog.log("ERROR", "SessionsVM", "observer reconnect failed: ${e.message}")
            observerClient = null
            observerWs = null
            observerReady = false
            return null
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            loadDashboardSessions()
        }
    }

    fun togglePin(sessionId: String) {
        viewModelScope.launch {
            settingsRepo.togglePinned(sessionId)
        }
    }

    /** Create a new session server-side, then hand the live session id to [onDone]. */
    fun createSession(onDone: (String?) -> Unit) {
        if (_uiState.value.isCreating) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreating = true, error = null)
            val wsConnection = WsConnectionManager(viewModelScope)
            try {
                wsConnection.connect()
                val rpcClient = JsonRpcClient(wsConnection, viewModelScope)
                rpcClient.start()
                // v0.1.88: apply the user's model/effort pick (sticky, like the
                // desktop composer) to the new session.
                val modelPick = settingsRepo.modelPick.first().ifBlank { null }
                val reasoningPick = settingsRepo.reasoningPick.first().ifBlank { null }
                val sid = rpcClient.createSession(model = modelPick, reasoningEffort = reasoningPick)
                DebugLog.log("INFO", "SessionsVM",
                    "session.create → $sid (model=$modelPick effort=$reasoningPick)")
                onDone(sid)
            } catch (e: Exception) {
                Log.e("Hermex", "SessionsViewModel: createSession failed", e)
                DebugLog.log("ERROR", "SessionsVM", "session.create failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to create session",
                )
                onDone(null)
            } finally {
                wsConnection.disconnect()
                _uiState.value = _uiState.value.copy(isCreating = false)
            }
        }
    }

    /** Delete a session server-side, then reload the session list. */
    fun deleteSession(sessionId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(deleting = true, error = null)
            val wsConnection = WsConnectionManager(viewModelScope)
            try {
                wsConnection.connect()
                val rpcClient = JsonRpcClient(wsConnection, viewModelScope)
                rpcClient.start()
                rpcClient.sessionDelete(sessionId)
                DebugLog.log("INFO", "SessionsVM", "session.delete → $sessionId")
                _uiState.value = _uiState.value.copy(deleting = false)
                loadSessions()
                onDone()
            } catch (e: Exception) {
                Log.e("Hermex", "SessionsViewModel: deleteSession failed", e)
                DebugLog.log("ERROR", "SessionsVM", "session.delete failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    deleting = false,
                    error = e.message ?: "Failed to delete session",
                )
                onDone()
            } finally {
                wsConnection.disconnect()
            }
        }
    }

    /**
     * Load the session list from the dashboard via JSON-RPC `session.list`.
     *
     * v0.1.151: reuse the persistent observer connection (established in [init])
     * when it's already live, instead of opening a fresh WS + login on every
     * reload. `sessions.changed` fires constantly (cron runs, other clients, turn
     * completion); each reload spinning up a new WS previously triggered a re-login
     * that tripped the server's brute-force throttle and churned reconnects. The
     * observer holds one long-lived connection for all reloads. Falls back to a
     * fresh connection only if the observer isn't available (e.g. it errored out).
     */
    /**
     * Cold-start bug (v0.1.157): the fallback path opened a throwaway WS
     * connection and `return`ed WITHOUT ever calling sessionList(), so on a
     * first launch — when the observer connection isn't live yet — the list
     * stayed stuck on the loading spinner until an unrelated `sessions.changed`
     * broadcast happened to fire minutes later. Now the fresh connection actually
     * fetches before it disconnects, and both paths share one apply function.
     */
    /**
     * v0.1.172: stop opening a throwaway socket per reload. The old fallback path
     * opened a fresh WS + ticket on every `sessions.changed` when the observer
     * wasn't instantly live, then disconnected — so frequent broadcasts spun up
     * one socket per reload, racing the reap window and thrashing the brute-force
     * throttle (the exact "observer unavailable — opening fresh WS connection"
     * churn in the crash loop). Now we REUSE the persistent observer socket and
     * only reconnect it IN PLACE if it died. No throwaway sockets, no per-reload
     * ticket fetch.
     */
    private suspend fun loadDashboardSessions() {
        DebugLog.log("INFO", "SessionsVM", "loadSessions via DASHBOARD JsonRpcClient.sessionList()")
        Log.d("Hermex", "SessionsViewModel: loading dashboard sessions")

        val liveClient = observerClient?.takeIf { it.isConnected }
            ?: ensureObserver()?.let { JsonRpcClient(it, viewModelScope) }
        if (liveClient == null) {
            DebugLog.log("ERROR", "SessionsVM", "no observer socket — session.list deferred")
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Dashboard: observer connection unavailable",
            )
            return
        }

        try {
            applySessionList(liveClient)
        } catch (e: Exception) {
            Log.e("Hermex", "SessionsViewModel: session.list failed", e)
            DebugLog.log("ERROR", "SessionsVM", "session.list failed: ${e.message}")
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Dashboard: ${e.message ?: "Session load failed"}",
            )
        }
    }

    /**
     * Fetch session.list and fold the result into [uiState]. Shared by the
     * live-observer path and the cold-start fresh-connection fallback so both
     * actually populate the list (the old fresh path silently returned without
     * fetching — see loadDashboardSessions).
     */
    private suspend fun applySessionList(client: JsonRpcClient) {
        try {
            val rpcSessions = client.sessionList()
            DebugLog.log("INFO", "SessionsVM", "session.list → ${rpcSessions.size} sessions")

            val mapped = rpcSessions.map { it.toSessionSummary() }
            val filtered = mapped.filter { it.messageCount > 0 }
            val filteredCount = mapped.size - filtered.size
            if (filteredCount > 0) {
                DebugLog.log("INFO", "SessionsVM", "filtered out $filteredCount empty sessions")
            }
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                sessions = filtered,
                error = null,
            )
        } catch (e: Exception) {
            Log.e("Hermex", "SessionsViewModel: dashboard session load failed", e)
            DebugLog.log("ERROR", "SessionsVM", "dashboard session.list failed: ${e.message}")
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Dashboard: ${e.message ?: "Session load failed"}",
            )
        }
    }

    // ── Mapping: JsonRpcClient.SessionInfo → SessionSummary ──

    private fun JsonRpcClient.SessionInfo.toSessionSummary(): SessionSummary {
        return SessionSummary(
            id = id,
            title = title,
            source = source,
            model = model,
            // Server sends started_at/ended_at as epoch seconds (Double), not ISO strings.
            startedAt = started_at,
            endedAt = ended_at,
            messageCount = message_count ?: 0,
            preview = preview,
            inputTokens = input_tokens ?: -1,
            outputTokens = output_tokens ?: -1,
        )
    }

    companion object {
    }
}
