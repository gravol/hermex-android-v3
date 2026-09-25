package com.hermex.v3.feature.chat

/**
 * Shared UI data models used by both DashboardChatViewModel and ChatScreen.
 * Originally defined in ChatViewModel.kt (legacy SSE ViewModel), extracted during legacy stack cleanup.
 */

data class UiMessage(
    val id: String,
    val role: String,      // "user" | "assistant"
    val content: String = "",
    val isStreaming: Boolean = false,
    val isWaitingForFirstEvent: Boolean = false,  // true from send until first SSE event
    val isCommandAck: Boolean = false,  // system-ish acknowledgement line (e.g. /steer) — rendered distinctly
    val thinkingText: String? = null,
    val thinkingExpanded: Boolean = true,
    val thinkingHasContent: Boolean = false,  // true once first assistant.delta arrives
    val toolCalls: List<UiToolCall> = emptyList(),
    val usage: UiUsage? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class UiToolCall(
    val id: String,
    val toolName: String,
    val preview: String? = null,
    val args: String? = null,
    val result: String? = null,
    val summary: String? = null,
    val startedAt: Long? = null,
    val completed: Boolean = false,
    val inlineDiff: String? = null,
)

data class UiUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val estimatedCostUsd: Double? = null,
    /** Server-reported predicted tokens per second (e.g. Ollama timings). */
    val predictedPerSecond: Float? = null,
)

/** One item in the agent's live task list (todo tool state). */
data class UiTodo(
    val id: String,
    val content: String,
    val status: String = "pending",  // pending | in_progress | completed | cancelled
) {
    val isDone: Boolean get() = status == "completed" || status == "cancelled"
    val isActive: Boolean get() = status == "in_progress"
}

data class ChatUiState(
    val sessionTitle: String = "",
    val messages: List<UiMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val error: String? = null,
    val scrollGeneration: Long = 0L,  // bumped on every SSE-driven list mutation; triggers auto-scroll
    val pendingApproval: PendingApproval? = null,  // non-null when tool needs approval
    val pendingClarify: PendingClarify? = null,    // non-null when clarification is needed
    // Live context-window occupancy (from session.info usage.context_used/context_max).
    // Null until the server reports a real reading.
    val contextUsed: Long? = null,
    val contextMax: Long? = null,
    // v0.1.88: current model + reasoning effort (from resume info / config).
    val currentModel: String? = null,
    val currentReasoning: String? = null,
    // Agent task list (todo tool state, from tool.complete events / history replay).
    // Non-empty = the Tasks card shows above the message list.
    val todos: List<UiTodo> = emptyList(),
    val todosExpanded: Boolean = false,
    // True when a turn completed while the chat screen wasn't visible (v0.1.60).
    // Shown as a banner on re-entry until dismissed.
    val completedWhileAway: Boolean = false,
    // v0.1.110: server-reported live tokens/sec during streaming (from
    // /v1/chat/completions timings.predicted_per_second). Replaces the
    // stale-char-count estimate in the live panel header.
    val liveTokPerSec: Float? = null,
)

/**
 * A tool call waiting for user approval.
 * @property toolName Name of the tool being called.
 * @property toolArgs Formatted arguments/params for the tool.
 * @property description Human-readable description of what the command will do.
 * @property requestId Server-side request ID for correlation.
 */
data class PendingApproval(
    val toolName: String,
    val toolArgs: String = "",
    val description: String = "",
    val requestId: String = "",
)

/**
 * A clarification request waiting for user input.
 * @property requestId Server-side ID for the clarification request.
 * @property question The question the server is asking the user.
 * @property choices Optional preset answer choices offered by the server (rendered as chips).
 */
data class PendingClarify(
    val requestId: String,
    val question: String = "",
    val choices: List<String> = emptyList(),
)
