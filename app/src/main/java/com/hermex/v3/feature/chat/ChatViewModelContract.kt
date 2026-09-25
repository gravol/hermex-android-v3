package com.hermex.v3.feature.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.hermex.core.network.JsonRpcClient

/**
 * Shared contract for chat ViewModels — both the legacy SSE [ChatViewModel]
 * and the new dashboard [DashboardChatViewModel] implement this.
 * Allows [ChatScreen] to work with either backend without changes.
 */
abstract class ChatViewModelContract(application: Application) : AndroidViewModel(application) {
    abstract var uiState: ChatUiState
    /** Set by /new or /reset — ChatScreen observes and navigates to the fresh session. */
    open var resetTargetSession: String? by mutableStateOf(null)
    abstract fun init(sessionId: String, title: String?)
    abstract fun loadMessages()
    abstract fun sendMessage(text: String)
    abstract fun stopStreaming()
    /** Retry the last assistant response — re-send the previous user prompt. */
    abstract fun retry()
    abstract fun toggleThinking(messageId: String)
    /** Approve the current pending tool approval request. */
    open fun approveCurrentTool(approveAll: Boolean = false) {}
    /** Deny the current pending tool approval request. */
    open fun denyCurrentTool(denyAll: Boolean = false) {}
    /** Respond to the current pending clarify request. */
    open fun respondToClarify(answer: String) {}
    /** Toggle the Tasks card (agent todo list) expanded state. */
    open fun toggleTodosExpanded() {}
    /** Send a message with an attached image (base64, pre-downscaled). */
    open fun sendMessageWithImage(text: String, imageBase64: String, filename: String?) {}
    /** Send a message with an attached file (base64 + mime), uploaded via `file.attach`. */
    open fun sendMessageWithFile(text: String, fileBase64: String, mimeType: String, filename: String?) {}
    /** Report whether the chat screen is currently visible (background-turn tracking). */
    open fun setScreenVisible(visible: Boolean) {}
    /** Dismiss the "completed while away" banner. */
    open fun clearCompletedWhileAway() {}
    /** Slash-command completion (server-ranked). */
    open suspend fun completeSlash(text: String): List<JsonRpcClient.SlashItem> = emptyList()

    /** Model picker (v0.1.88): model options + persisting the pick. */
    open suspend fun loadModelOptions(): JsonRpcClient.ModelOptionsResult =
        JsonRpcClient.ModelOptionsResult()

    open fun saveModelPick(model: String, reasoning: String, thinkingOn: Boolean = true) {}

    /** v0.1.89: mid-session switch via /model + /reasoning slash commands. */
    open fun applyModelToSession(model: String, reasoning: String, thinkingOn: Boolean = true) {}

    // ── YOLO mode (v0.1.4) — session-scoped approval-skip toggle ──
    /** Load the current YOLO state from the server (for the composer button). */
    open fun loadYoloStatus(desiredEnabled: Boolean = false) {}
    /** Toggle YOLO mode on/off for this session. */
    open fun setYolo(enabled: Boolean) {}
}
