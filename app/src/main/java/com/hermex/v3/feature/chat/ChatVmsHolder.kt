package com.hermex.v3.feature.chat

import android.app.Application
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermex.core.network.DebugLog
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

    /**
     * Use order for eviction only — least-recently-used first. Behaviour never depends on this list;
     * it exists so [enforceLimit] can name a victim instead of letting [vms] grow without bound.
     */
    private val lru = mutableListOf<String>()

    private val _activeSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** sessionId → isStreaming, updated live for every held chat VM. */
    val activeSessions: StateFlow<Map<String, Boolean>> = _activeSessions.asStateFlow()

    /** Get (or create) the chat ViewModel for a session. One VM per session. */
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
        }.also {
            touch(sessionId)
            enforceLimit(sessionId)
        }

    /**
     * Bound the number of live chat ViewModels.
     *
     * Holding one VM per session is deliberate (a turn must keep running after you leave the chat),
     * but until now nothing ever released them: opening ten chats in a day kept ten VMs alive at
     * Activity scope, each holding its full transcript, tool cards and thinking text, plus two live
     * collectors. On long transcripts that is exactly the memory this app already struggles with.
     *
     * Eviction therefore protects what the user could be looking at and what is still working: the
     * chat just opened, and any chat reporting `isStreaming`. Disposing a VM cancels its collectors
     * only — the WebSocket is app-wide and shared (v0.1.885), so evicting never drops anyone's
     * connection or interrupts a server-side turn; reopening the session rebuilds the VM and reloads
     * history, which is the same path a cold start already takes.
     */
    private fun enforceLimit(current: String?) {
        while (true) {
            val victim = pickToEvict(lru, streamingIds(), current, MAX_HELD_CHATS) ?: return
            val vm = vms.remove(victim) ?: break
            lru.remove(victim)
            _activeSessions.update { it - victim }
            DebugLog.log("TRACE", "identity",
                "event=evict_chat_vm dbKey=$victim vm=${System.identityHashCode(vm)} " +
                "held_after=${vms.size} limit=$MAX_HELD_CHATS reason=lru_cap")
        }
    }

    private fun streamingIds(): Set<String> =
        _activeSessions.value.filterValues { it }.keys.toSet()

    private fun touch(sessionId: String) {
        lru.remove(sessionId)
        lru.add(sessionId)
    }

    /** Dispose all held chat ViewModels and stop the keepalive service. */
    fun disposeAll() {
        vms.values.forEach { it.dispose() }
        vms.clear()
        lru.clear()
        _activeSessions.value = emptyMap()
        WsKeepaliveService.stop(getApplication())
    }

    override fun onCleared() {
        disposeAll()
        super.onCleared()
    }

    companion object {
        /**
         * How many chat ViewModels stay alive at once. Generous against real use (the chat you are
         * in plus a handful of background turns) while putting a ceiling on transcript memory.
         */
        const val MAX_HELD_CHATS = 6

        /**
         * Which session to release, or null for "keep everything". Pure so the policy can be tested
         * without an Activity: [lru] is least-recently-used first, [streaming] holds sessions whose
         * turn is still running, [current] is the chat just touched.
         */
        fun pickToEvict(
            lru: List<String>,
            streaming: Set<String>,
            current: String?,
            limit: Int = MAX_HELD_CHATS,
        ): String? {
            if (lru.size <= limit) return null
            return lru.firstOrNull { it != current && it !in streaming }
        }
    }
}
