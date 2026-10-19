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

    /**
     * P5 A2 diagnostics, stamped by `JsonRpcClient.handleNotification` from the frame
     * this event was parsed out of: the gateway's per-session monotonic `seq` (0 when
     * the frame carried none) and the wire event name (`params.type`). They exist so a
     * consumer can log one disposition line per frame (`TRACE|disp cid= sid= seq=
     * type= disp=…`) instead of the reader inferring it from absence.
     *
     * Deliberately NOT constructor parameters: data-class equals/hashCode/copy over the
     * parsed values is unchanged, and no call site has to thread them through.
     */
    var frameSeq: Long = 0L
    var wireType: String = ""

    /**
     * P5 A2: the id of the socket this frame was parsed off. Stamped at parse time, next
     * to `frameSeq`, so a consumer's disposition line reports the connection the frame
     * actually arrived on rather than whichever connection happens to be current when the
     * consumer gets around to running — after a reconnect those differ, and the whole
     * point of these lines is to join client-side counts against the gateway's per-transport
     * frame trace.
     */
    var frameCid: String = ""

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

    /**
     * Auto-titling renamed a session (`session.title`).
     *
     * Server: `tui_gateway/prompt_turn.py:804` — the `_on_session_title` hook after the first
     * turn names the session. Contract: `contracts/events.py::SessionTitlePayload` —
     * `{session_id, title}`, both required, and `session_id` is the STORED KEY (the same value
     * this client uses as its `sessionId`), not the live sid.
     *
     * This one has a real surface, so it is rendered rather than traced: without it the phone
     * keeps showing the placeholder title it made for itself (`sessionId.take(16)`) after the
     * agent named the chat, until the app is reopened. Observed 1x in the trial journals — rare
     * because it fires once per new session, which is exactly when the user is looking at it.
     */
    data class SessionTitle(
        override val sessionId: String?,
        val title: String,
        /** The server's stored session key (`session_key`), which is NOT the id the frame is addressed
         *  to. Kept separately on purpose — see the KDoc above and the device note in the changelog. */
        val storedKey: String? = null,
    ) : RpcNotification()

    // ── Transient status line (status.update) ──

    /**
     * A transient status line about the turn — explicitly NOT transcript content, so it must
     * never be appended to a message bubble.
     *
     * Server: `tui_gateway/server.py::_status_update` → `_emit("status.update", sid,
     * {kind, text})`, plus direct emitters in `prompt_turn.py` (goal / loop) and
     * `session_notifications.py` (loop / heartbeat / background-process notices).
     * Contract: `contracts/events.py::StatusUpdatePayload` — `kind` and `text` are both required.
     * Kinds actually observed in the D_* trial journals: process, lifecycle, compacting,
     * compacted; the contract also names status, goal, loop, heartbeat.
     *
     * Hermex has no transient-status surface today, so this is parsed for the protocol trace
     * only (see `feature/chat/ProtocolTrace.kt`) — not rendered.
     */
    data class StatusUpdate(
        override val sessionId: String?,
        val kind: String,
        val text: String,
    ) : RpcNotification()

    // ── Agent-owned background process output (agent.terminal.output) ──

    /**
     * One output chunk from a background process the agent started.
     *
     * Server: `session_notifications.py:838` — `process_registry.on_output` →
     * `_emit("agent.terminal.output", _owner_sid(session), {process_id, chunk})`.
     * Contract: `contracts/events.py::TerminalOutputPayload` — both fields required; `chunk`
     * is raw stdout/stderr, so it may be any bytes including newlines, and may be empty.
     * Note `_owner_sid()` returns "" when the process was already pruned, so `sessionId` is
     * nullable here. Paired with `terminal.close` (`{process_id}`), which the trials never
     * emitted and which stays unparsed until there is a process surface to close.
     *
     * No UI surface exists for this in Hermex v3 — parsed for trace only, not rendered.
     */
    data class TerminalOutput(
        override val sessionId: String?,
        val processId: String,
        val chunk: String,
    ) : RpcNotification()

    // ── Background self-improvement review (review.summary) ──

    /**
     * The agent's background review of the last turn finished (e.g. "patched a skill").
     *
     * Server: `server.py:1170` — `agent.background_review_callback` →
     * `_emit("review.summary", sid, {text})`. Contract:
     * `contracts/events.py::ReviewSummaryPayload` — `text` required.
     *
     * Not transcript content: it describes work done on the agent's own memory, not the
     * answer. No surface exists for it in Hermex v3, so it is parsed for trace only.
     */
    data class ReviewSummary(
        override val sessionId: String?,
        val text: String,
    ) : RpcNotification()

    // ── Filesystem change signals (change_watcher broadcasts) ──

    /**
     * `cron/jobs.json` moved — the cron job definitions changed server-side.
     * Like [SessionChanged] this is a global broadcast with an EMPTY `session_id`, and its
     * payload is `{}` by contract (`contracts/events.py::ChangeSignalPayload`: no required
     * fields, extra=allow). Emitted by `tui_gateway/change_watcher.py:287` on an mtime poll
     * floored at 1 s.
     *
     * The client's Cron screen fetches jobs imperatively when opened
     * (`DashboardApiClient.cronJobs()` from a Composable `remember` block); there is no state
     * holder to invalidate, so this is parsed for trace only. See ProtocolTrace.kt.
     */
    data class CronChanged(
        override val sessionId: String? = null,
    ) : RpcNotification()

    /**
     * `gateway_state.json` moved — platform (Telegram/Discord/…) connection state changed.
     * Same broadcast shape as [CronChanged]; emitted by `change_watcher.py:292` with a 5 s
     * floor, which is why it dominates the unknown-event counts in the trial journals.
     *
     * Hermex v3 has no platform-status surface (the only related call is
     * `DashboardApiClient.cronDeliveryTargets()`, inside the cron editor), so this is parsed
     * for trace only, not rendered.
     */
    data class PlatformsChanged(
        override val sessionId: String? = null,
    ) : RpcNotification()

    // ── Fallback for unrecognized events ──

    data class Unknown(
        val eventType: String,
        val rawParams: JsonObject? = null,
        override val sessionId: String? = null,
    ) : RpcNotification()
}
