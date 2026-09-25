package com.hermex.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Server-pushed JSON-RPC notifications (no "id" field).
 * Parsed from {"jsonrpc":"2.0","method":"event","params":{...}} frames.
 *
 * Every event carries [sessionId] in params (confirmed from _emit() in server.py).
 * Route by session ID, not by type alone.
 */
sealed class RpcNotification {
    /** Extracted from params.session_id on every event. */
    abstract val sessionId: String?

    // ── Connection lifecycle ──

    data class GatewayReady(
        val agentId: String? = null,
        val version: String? = null,
        override val sessionId: String? = null,
    ) : RpcNotification()

    // ── Streaming deltas (APPEND-ONLY — client concatenates) ──

    /** Token-level text delta for the assistant response. Append-only, order guaranteed by WS. */
    data class MessageDelta(
        override val sessionId: String,
        val text: String,
        /** Server-reported predicted tokens per second from the LLM timings (may be null). */
        val predictedPerSecond: Float? = null,
    ) : RpcNotification()

    /** Thinking/reasoning stream (shown in collapsible block). Append-only. */
    data class ThinkingDelta(
        override val sessionId: String,
        val text: String,
    ) : RpcNotification()

    data class ReasoningDelta(
        override val sessionId: String,
        val text: String,
    ) : RpcNotification()

    /** Signals that reasoning/thinking content is available for display. Triggers the collapsible thinking toggle in UI. */
    data class ReasoningAvailable(
        override val sessionId: String?,
    ) : RpcNotification()

    // ── Tool events ──
    // Server emits: tool.generating, tool.start, tool.complete
    // (NOT tool.started, tool.progress, tool.completed as earlier assumed)

    /** Model is generating a tool call (tool.generating). Payload: {name}. */
    data class ToolGenerating(
        override val sessionId: String,
        val toolName: String,
    ) : RpcNotification()

    /** Tool is actually running (tool.start). Payload: {tool_id, name, context}. */
    data class ToolStart(
        override val sessionId: String,
        val toolId: String,
        val toolName: String,
        val context: String? = null,
    ) : RpcNotification()

    /** Tool finished (tool.complete). Payload: {tool_id, name, args, result, summary}. */
    data class ToolComplete(
        override val sessionId: String,
        val toolId: String,
        val toolName: String,
        val args: JsonElement? = null,
        val result: JsonElement? = null,
        val summary: String? = null,
        val inlineDiff: String? = null,
    ) : RpcNotification()

    // ── Run lifecycle ──

    // ── Message started (new assistant message beginning) ──

    /** Signals the start of a new assistant message. Server-provided message ID available. */
    data class MessageStarted(
        override val sessionId: String?,
        val messageId: String? = null,
    ) : RpcNotification()

    data class RunStarted(
        override val sessionId: String,
    ) : RpcNotification()

    data class RunCompleted(
        override val sessionId: String,
    ) : RpcNotification()

    // ── Message completion (final state from server) ──

    data class MessageCompleted(
        override val sessionId: String,
        val messageId: String? = null,
        val content: String? = null,
        val toolCalls: List<ToolCallInfo>? = null,
        val usage: UsageInfo? = null,
    ) : RpcNotification()

    @Serializable
    data class ToolCallInfo(
        val id: String? = null,
        val function: FunctionInfo? = null,
    )

    @Serializable
    data class FunctionInfo(
        val name: String? = null,
        val arguments: String? = null,
    )

    @Serializable
    data class UsageInfo(
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val totalTokens: Int? = null,
        val estimatedCostUsd: Double? = null,
        /** Server-reported predicted tokens per second (e.g. Ollama /v1/chat/completions timings). */
        val predictedPerSecond: Float? = null,
    )

    // ── User interaction requests (v1: auto-deny + visible notice) ──

    /**
     * Server needs approval for a tool call. Correlated by session_key (FIFO queue).
     * v1 behavior: auto-deny with toast. Unhandled = hung turn.
     */
    data class ApprovalRequest(
        override val sessionId: String,
        val sessionKey: String,
        val toolName: String? = null,
        val args: JsonElement? = null,
        /** The actual command string the server sent at params.command (redacted). */
        val command: String? = null,
        /** Human-readable description of what the tool call will do. */
        val description: String? = null,
        /** Approval choices offered by the server, e.g. ["once","session","always","deny"]. */
        val choices: List<String>? = null,
    ) : RpcNotification()

    /**
     * One item in a batch clarify. Correlated back to the tool by qid.
     */
    data class ClarifyQuestion(
        val qid: String,
        val question: String,
        val choices: List<String> = emptyList(),
        val multiSelect: Boolean = false,
    )

    /**
     * Server needs clarification from the user. Correlated by request_id.
     * Chooses the single-question payload shape: `{request_id, question,
     * choices?, multi_select?}`; for a batch, `questions` carries a
     * list of [ClarifyQuestion].
     */
    data class ClarifyRequest(
        override val sessionId: String,
        val requestId: String,
        val question: String? = null,
        val choices: List<String>? = null,
        val questions: List<ClarifyQuestion>? = null,
    ) : RpcNotification()

    // ── Session info ──

    data class SessionInfo(
        override val sessionId: String,
        val info: JsonObject? = null,
    ) : RpcNotification()

    /**
     * The dashboard broadcast a `sessions.changed` event — the session list on
     * the server changed (cron run, other client, turn completion). Clients that
     * hold a cached session list (Chat panel, Insights) should refetch so they
     * never show stale data. Carries no session_id; it's a global broadcast.
     */
    data class SessionChanged(
        override val sessionId: String? = null,
    ) : RpcNotification()

    // ── Fallback for unrecognized events ──

    data class Unknown(
        val eventType: String,
        val rawParams: JsonObject? = null,
        override val sessionId: String? = null,
    ) : RpcNotification()
}
