package com.hermex.v3.feature.chat

import android.app.Application
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermex.v3.service.WsKeepaliveService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Activity-scoped holder for per-session chat ViewModels.
 *
 * Chat ViewModels are normally scoped to the nav back-stack entry, which means
 * backing out of a chat destroys the ViewModel → WebSocket disconnect → the
 * server's orphan-reaper tears the session down after a 20s grace window and
 * kills the running turn ("I left the chat and it stopped doing what I asked").
 *
 * Keeping one VM per session at Activity scope means the WebSocket stays
 * connected in the background: turns keep running after you leave the chat,
 * and reopening the session shows the live (possibly already-completed) state.
 * All VMs are disposed when the Activity finishes (holder cleared).
 *
 * Also exposes [activeSessions] — per-session streaming flags (v0.1.60) — so
 * the session list can show which chats are still working in the background.
 */
class ChatVmsHolder(application: Application) : AndroidViewModel(application) {

    private val vms = mutableMapOf<String, DashboardChatViewModel>()

    private val _activeSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** sessionId → isStreaming, updated live for every held chat VM. */
    val activeSessions: StateFlow<Map<String, Boolean>> = _activeSessions.asStateFlow()

    /** Get (or create) the chat ViewModel for a session. One WS per session. */
    fun getOrCreate(sessionId: String): DashboardChatViewModel =
        vms.getOrPut(sessionId) {
            DashboardChatViewModel(getApplication()).also { vm ->
                // Mirror each VM's streaming state into the active-sessions map
                // (uiState is Compose snapshot state — snapshotFlow bridges it).
                viewModelScope.launch {
                    snapshotFlow { vm.uiState.isStreaming }
                        .distinctUntilChanged()
                        .collect { streaming ->
                            _activeSessions.update { it + (sessionId to streaming) }
                        }
                }
            }
        }

    /** Dispose all held chat ViewModels and stop the keepalive service. */
    fun disposeAll() {
        vms.values.forEach { it.dispose() }
        vms.clear()
        _activeSessions.value = emptyMap()
        WsKeepaliveService.stop(getApplication())
    }

    override fun onCleared() {
        disposeAll()
        super.onCleared()
    }
}
