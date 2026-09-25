package com.hermex.v3.feature.chat

import android.app.Application
import android.util.Log
import android.widget.Toast
import com.hermex.v3.AppState
import kotlin.runCatching
import com.hermex.v3.feature.settings.SettingsRepository
import com.hermex.v3.service.WsKeepaliveService
import com.hermex.v3.notify.CronWatcher
import com.hermex.v3.notify.TurnWatcher
import com.hermex.v3.notify.NotificationHelper
import com.hermex.core.network.DashboardApiClient
import com.hermex.core.network.NetworkResult
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.hermex.core.network.DebugLog
import com.hermex.core.network.JsonRpcClient
import com.hermex.core.network.JsonRpcException
import com.hermex.core.network.RpcNotification
import com.hermex.core.network.WsConnectionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dashboard-backed ChatViewModel using JSON-RPC over WebSocket (port 9119).
 *
 * Reuses the same UI models ([UiMessage], [UiToolCall], [UiUsage], [ChatUiState])
 * and the same [mutableStateOf] snapshot-state pattern as the legacy SSE [ChatViewModel].
 * [ChatScreen] requires no changes — data flows underneath the existing Compose UI.
 *
 * SESSION ID LIFECYCLE RULES (Phase 4L.1):
 * - sessionId (DB key, e.g. "20260717_205748_97c893e8") is persistent and is the
 *   ONLY value allowed for ALL RPC calls: session.resume, prompt.submit,
 *   session.interrupt, session.info.
 * - liveSid (transient live RPC SID, e.g. "0b6c225e") is returned by session.resume
 *   and is for debug logging ONLY. NEVER assign it to sessionId.
 * - resumedSessionId stores the "resumed" field from session.resume for logging.
 */
class DashboardChatViewModel(application: Application) : ChatViewModelContract(application) {

    // Compose snapshot state — identical pattern to ChatViewModel
    override var uiState by mutableStateOf(ChatUiState())

    private var sessionId: String = ""         // stable DB session key — ONLY value for RPC calls
    private var liveSid: String = ""           // transient live RPC sid — DEBUG ONLY, NEVER used in RPC
    private var resumedSessionId: String = ""   // "resumed" field from session.resume response
    private var sessionTitle: String = ""
    private val tempIdCounter = AtomicInteger(0)
    private var resumeCount: Int = 0            // how many times session.resume was called

    // v0.1.137 — tracks whether the current turn has been told it is done
    // server-side (any completion event seen). Lets us clear `isStreaming`
    // IMMEDIATELY instead of waiting for the stale-stream watchdog, so a
    // dropped/late/out-of-order completion signal can no longer strand a
    // spinner on a short "status check" turn forever.
    private var turnDoneSeen = false
    // v0.1.140: suppress duplicate turn-finished notifications for a single
    // completed turn. The reconnect reconciliation and the live RunCompleted
    // path can both fire for the same completion when the WS drops during a
    // lock; without this you'd get two pings. Cleared when a new turn starts.
    private var turnNotifPosted = false

    // WebSocket + JSON-RPC infrastructure
    private val wsConnection = WsConnectionManager(viewModelScope)
    private val rpcClient = JsonRpcClient(wsConnection, viewModelScope)
    /** Set by /new or /reset — ChatScreen observes it and navigates to the fresh session. */
    override var resetTargetSession: String? by mutableStateOf(null)
    private var notificationCollectorJob: Job? = null
    // v0.1.170: server→client requests (approval/clarify/sudo/…) arrive with a string id
    // (`srq-<hex>`) and need a JSON-RPC response frame on that id — NOT a notification.
    // Route them into the same UI state as notifications, but capture the request id so we
    // can answer via respondRequest(...). Without this every approval/clarify stalls out
    // the gateway deadline and the agent idles.
    private var serverRequestCollectorJob: Job? = null

    // ── v0.1.123 stuck-spinner watchdog ──
    // If a streaming assistant message is still marked isStreaming after this
    // many ms with no new event, the completion signal was lost → clear it so
    // the UI doesn't spin forever. Re-armed on every incoming event.
    private var staleStreamTimeoutJob: Job? = null
    // Tracks whether ANY streaming content event has arrived for the current
    // still-streaming message. If we've seen deltas, a long gap is just a
    // cold-start/agent-warmup pause — NOT a lost completion — so the watchdog
    // must NOT nuke the stream (see armStaleStreamGuard). Set true on the first
    // streaming event; reset in ensureStreamingPlaceholder.
    private var streamingContentSeen = false
    // v0.1.137: was 45s (far too slow — a short turn freezes until this fires).
    // Now ~10s, and it clears immediately if a completion signal was already
    // seen for the still-streaming message (turnDoneSeen). Primary recovery is
    // immediate in handleNotification; this is belt-and-suspenders.
    private val STALE_STREAM_GRACE_MS = 10_000L

    // ─── Debug: timing & state tracking ───
    private var connectStartTime = 0L
    private var sessionLoadStartTime = 0L

    // Recovery budget for a reaped/stale session (JSON-RPC 4007 "session no longer
    // live; retry resume"). After a gateway restart/update the in-memory live
    // registry is empty and session.resume returns 4007 until the DB row
    // re-materializes — which can take many seconds. The old budget (2 tries,
    // ~4.5s total) exhausted before recovery finished, so sendMessage failed with
    // "session no longer live; retry resume" and the red-panel Retry just emptied
    // the chat. Exponential backoff capped here gives a generous window without an
    // absurd wait (usually recovers on the first or second try). Shared by
    // submitWithSelfHeal (heavy) and loadMessages (lighter via maxAttempts).
    private val selfHealRetries = 6
    private val selfHealBaseDelayMs = 3_000L
    private val selfHealMaxDelayMs = 15_000L

    // ── Public API ──

    /** Initialize with a session. Call once from the composable. */
    override fun init(sessionId: String, title: String?) {
        if (this.sessionId == sessionId) {
            DebugLog.log("STATE", "SessionID",
                "init() — unchanged sessionId=$sessionId, skipped")
            return
        }
        val oldSid = this.sessionId.takeIf { it.isNotEmpty() } ?: "(none)"
        this.sessionId = sessionId           // KEEP as stable DB session key — NEVER overwrite with live sid
        this.liveSid = ""                    // reset until session.resume returns
        this.resumedSessionId = ""           // reset until session.resume returns
        this.resumeCount = 0
        // Fresh session VM — clear any leftover completion flag from a previous
        // chat so the first turn's placeholder isn't immediately finalized.
        this.turnDoneSeen = false
        // v0.1.140: a new chat/session — allow the next completion to ping again.
        this.turnNotifPosted = false
        this.sessionTitle = title ?: sessionId.take(16)
        uiState = ChatUiState(sessionTitle = this.sessionTitle)
        DebugLog.log("STATE", "SessionID",
            "init() — sessionId ASSIGNED: old=$oldSid new=$sessionId (DB key) title=$sessionTitle")
        connectWsAndStart()
        loadReasoningFromConfig()
        loadYoloStatus(false)
    }

    override fun loadMessages() {
        if (sessionId.isEmpty()) return
        val callNum = resumeCount + 1
        DebugLog.log("STATE", "SessionID",
            "loadMessages(#$callNum) — entering with sessionId=$sessionId")
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, error = null)
            try {
                val result = rpcClient.sessionResume(sessionId)
                resumeCount++
                // liveSid is DEBUG ONLY — never write into sessionId
                val newLiveSid = result.session_id
                val newResumed = result.resumed ?: sessionId
                val newSessionKey = result.session_key

                DebugLog.log("STATE", "SessionID",
                    "loadMessages(#${resumeCount}) RESULT: " +
                    "input_sessionId=$sessionId " +
                    "result.session_id=$newLiveSid " +
                    "result.resumed=$newResumed " +
                    "result.session_key=$newSessionKey " +
                    "result.message_count=${result.message_count}")

                liveSid = newLiveSid           // DEBUG ONLY — see Phase 4L.1 rule
                resumedSessionId = newResumed   // for logging/reference

                DebugLog.log("STATE", "SessionID",
                    "loadMessages(#${resumeCount}) ASSIGNMENTS: " +
                    "liveSid=$liveSid (debug only) " +
                    "resumedSessionId=$resumedSessionId " +
                    "sessionId UNCHANGED=$sessionId (DB key preserved)")

                if (newSessionKey != null && newSessionKey != sessionId) {
                    DebugLog.log("STATE", "SessionID",
                        "loadMessages(#${resumeCount}) WARNING: " +
                        "session_key=$newSessionKey differs from dbKey=$sessionId")
                }

                // v0.1.73: history stores tool activity as SEPARATE role="tool"
                // rows ({name, context}) and assistant thinking in `reasoning`.
                // Merge tool rows into the preceding assistant message's tool
                // box instead of rendering them as jumbled standalone bubbles;
                // carry reasoning into the thinking box.
                val messages = result.messages?.let { raw ->
                    val out = mutableListOf<UiMessage>()
                    for (it in raw) {
                        val role = it.role ?: "user"
                        if (role == "tool") {
                            val prev = out.lastOrNull()
                            if (prev != null && prev.role == "assistant") {
                                val idx = out.size - 1
                                out[idx] = prev.copy(
                                    toolCalls = prev.toolCalls + UiToolCall(
                                        id = it.id ?: "tc_${tempIdCounter.incrementAndGet()}",
                                        toolName = it.name ?: "tool",
                                        preview = it.context,
                                        completed = true,
                                    )
                                )
                            }
                            continue
                        }
                        val thinking = it.resolvedThinking
                        out.add(
                            UiMessage(
                                id = it.id ?: "msg_${tempIdCounter.incrementAndGet()}",
                                role = role,
                                content = it.resolvedContent ?: "",
                                thinkingExpanded = false,
                                thinkingText = thinking,
                                thinkingHasContent = !thinking.isNullOrBlank(),
                                toolCalls = it.tool_calls?.map { tc ->
                                    UiToolCall(
                                        id = tc.id ?: "tc",
                                        toolName = tc.function?.name ?: "unknown",
                                        args = tc.function?.arguments,
                                        completed = true,
                                    )
                                } ?: emptyList(),
                                timestamp = System.currentTimeMillis(),
                            )
                        )
                    }
                    out
                } ?: emptyList()
                val context = parseContextUsage(result.info)
                // Replay the task list: the last todo tool result in history is
                // the authoritative state (same source the desktop Tasks panel uses).
                val todos = result.messages
                    ?.filter { it.role == "tool" && it.name == "todo" }
                    ?.lastOrNull()
                    ?.let { parseTodosFromString(it.context) }
                uiState = uiState.copy(
                    isLoading = false,
                    isStreaming = false,
                    messages = messages,
                    error = null,
                    contextUsed = context.first,
                    contextMax = context.second,
                    // v0.1.88: current model from the resume payload
                    currentModel = (result.info?.get("model") as? JsonObject)?.get("id")
                        ?.jsonPrimitive?.contentOrNull
                        ?: result.info?.get("model")?.jsonPrimitive?.contentOrNull
                        ?: uiState.currentModel,
                    // v0.1.91: reasoning_effort comes straight from session.info
                    // (authoritative per-session) — fall back to the saved pick.
                    currentReasoning = result.info?.get("reasoning_effort")
                        ?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?: uiState.currentReasoning,
                    todos = todos.orEmpty(),
                )
                val loadDuration = if (sessionLoadStartTime > 0) System.currentTimeMillis() - sessionLoadStartTime else -1L
                DebugLog.log("RPC", "DashboardChat",
                    "session.resume → ${messages.size} messages in ${loadDuration}ms" +
                    " (message_count=${result.message_count})")
                sessionLoadStartTime = 0L

                // v0.1.138: turn finished while disconnected. When the phone locks
                // or Hermex is closed, the WS dies and the completion event never
                // reaches us — but the turn keeps running server-side and this
                // resume fetches the final answer (which is why it renders on return).
                // Detect that case here: if the server says the turn is done AND we
                // weren't watching, fire the turn-finished notification so you actually
                // get pinged. Without this, completed-while-away turns are silent.
                val lastAssistant = messages.lastOrNull { it.role == "assistant" }
                val turnDoneServerSide = result.running == false
                if (turnDoneServerSide && lastAssistant != null && !screenVisible) {
                    val preview = lastAssistant.content.ifBlank { "Turn finished" }.take(200)
                    DebugLog.log("NOTIF", "DashboardChat",
                        "loadMessages: turn finished while away — posting turn-finished notification")
                    runCatching {
                        NotificationHelper.postTurnFinished(
                            getApplication(), sessionId, uiState.sessionTitle, preview,
                        )
                    }
                }
            } catch (e: JsonRpcException) {
                if (e.code == 4007) {
                    // v0.1.89: 4007 = session not found. For a JUST-CREATED
                    // session that's NORMAL — the server only flushes the DB
                    // row on the first run, so resume can't find it yet. Show
                    // an empty chat; the first prompt.submit attaches the agent
                    // and persists (verified server-side). Also covers deleted
                    // sessions (nothing to show anyway). BUT a reaped/restarting
                    // session also 4007s until its DB row re-materializes, so we
                    // retry resume with capped backoff before giving up — if it
                    // recovers we load the real history instead of an empty chat.
                    DebugLog.log("RPC", "DashboardChat",
                        "loadMessages 4007 (fresh/reaped session) — backing off then retrying: $sessionId")
                    val recovered = resumeUntilLive(3, selfHealBaseDelayMs, selfHealMaxDelayMs)
                    if (recovered != null) {
                        resumeCount++
                        liveSid = recovered.session_id
                        resumedSessionId = recovered.resumed ?: sessionId
                        DebugLog.log("RPC", "DashboardChat",
                            "loadMessages 4007 recovered — reloading with fresh result: $sessionId")
                        loadMessages()  // retry with the recovered session
                    }
                    DebugLog.log("RPC", "DashboardChat",
                        "loadMessages 4007 (fresh/deleted session) — starting empty: $sessionId")
                    uiState = uiState.copy(isLoading = false, messages = emptyList(), error = null)
                } else if (e.code == 4001) {
                    // v0.1.169: 4001 = session REAPED by the gateway (ws_orphan_reap /
                    // idle_timeout / lru_evict). The live socket was orphaned and the
                    // in-memory registry dropped it, so session.resume can't find it.
                    // submitWithSelfHeal already self-heals 4001 on prompt.submit, but
                    // loadMessages did not — opening a reaped session just showed an error
                    // and died instead of recovering. Force a fresh connection first (a
                    // silent Tailscale drop can leave the WS unusable while the app still
                    // thinks it's connected), then resume with backoff so the DB row
                    // re-materializes and we load real history instead of an empty chat.
                    DebugLog.log("RPC", "DashboardChat",
                        "loadMessages 4001 (reaped session) — forceConnect then retrying: $sessionId")
                    if (!wsConnection.isConnected) {
                        wsConnection.forceConnect()
                    }
                    // resumeUntilLive only retries on 4007 and rethrows anything else
                    // (incl. a persistent 4001). Wrap it so a lingering 4001 can't escape
                    // this catch block and crash loadMessages — just start empty instead.
                    val recovered = runCatching {
                        resumeUntilLive(3, selfHealBaseDelayMs, selfHealMaxDelayMs)
                    }.getOrNull()
                    if (recovered != null) {
                        resumeCount++
                        liveSid = recovered.session_id
                        resumedSessionId = recovered.resumed ?: sessionId
                        DebugLog.log("RPC", "DashboardChat",
                            "loadMessages 4001 recovered — reloading with fresh result: $sessionId")
                        loadMessages()  // retry with the recovered session
                    } else {
                        DebugLog.log("RPC", "DashboardChat",
                            "loadMessages 4001 exhausted — starting empty: $sessionId")
                        uiState = uiState.copy(isLoading = false, messages = emptyList(), error = null)
                    }
                } else {
                    Log.e("Hermex", "DashboardChatViewModel: loadMessages failed", e)
                    uiState = uiState.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load messages",
                    )
                }
            } catch (e: Exception) {
                Log.e("Hermex", "DashboardChatViewModel: loadMessages failed", e)
                uiState = uiState.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load messages",
                )
            }
        }
    }

    /**
     * Submit a prompt, self-healing a reaped/stale session.
     *
     * The dashboard reaps live sessions whose WebSocket went orphaned
     * (ws_orphan_reap / idle_timeout / lru_evict) and the client gets no
     * signal — the next prompt.submit then fails with JSON-RPC 4001
     * "session not found". On 4001 we re-register via session.resume
     * (which re-materializes the session from the DB) and retry once.
     * If session.resume also returns 4007 (fresh/deleted/reaping session),
     * [resumeUntilLive] retries with exponential backoff until the DB row
     * re-materializes or the budget is exhausted, then falls back to sending
     * directly (the genuinely-fresh-session path, where the server creates the
     * row on first turn). Any other error propagates to the caller.
     */
    private suspend fun submitWithSelfHeal(text: String) {
        try {
            rpcClient.promptSubmit(sessionId, text)
        } catch (e: JsonRpcException) {
            if (e.code != 4001) throw e
            DebugLog.log("STATE", "SessionID",
                "prompt.submit 4001 (session reaped) — ensuring live socket before resume: dbKey=$sessionId")
            // The socket may be dead or mid-reconnect (a silent Tailscale drop leaves the WS
            // unusable while the app still thinks it's connected). session.resume over a dead
            // socket can't complete, so self-heal loops forever without recovering. Force a fresh
            // connection first so resume has a working transport to re-attach the live session to.
            if (!wsConnection.isConnected) {
                DebugLog.log("STATE", "SessionID",
                    "self-heal — socket not Connected, forceConnect() before resume: dbKey=$sessionId")
                wsConnection.forceConnect()
            }
            val result = try {
                rpcClient.sessionResume(sessionId)
            } catch (resume4007: JsonRpcException) {
                if (resume4007.code == 4007) {
                    DebugLog.log("STATE", "SessionID",
                        "self-heal resume 4007 (fresh/reaped) — backing off then retrying: dbKey=$sessionId")
                    // Generational backoff so a post-restart reap window (DB row
                    // still materializing) can recover instead of failing fast.
                    val recovered = resumeUntilLive(selfHealRetries, selfHealBaseDelayMs, selfHealMaxDelayMs)
                    recovered
                } else {
                    throw resume4007
                }
            }
            if (result != null) {
                resumeCount++
                liveSid = result.session_id                    // debug only — never write into sessionId
                resumedSessionId = result.resumed ?: sessionId
                DebugLog.log("STATE", "SessionID",
                    "self-heal resume(#$resumeCount): dbKey=$sessionId liveSid=$liveSid — retrying submit")
                rpcClient.promptSubmit(sessionId, text)
            } else {
                // Fresh session — server will create the DB row on first turn
                DebugLog.log("STATE", "SessionID",
                    "self-heal resume exhausted 4007 after backoff — sending prompt.submit directly: dbKey=$sessionId")
                rpcClient.promptSubmit(sessionId, text)
            }
        }
    }

    /**
     * Retry session.resume with capped exponential backoff until it succeeds or
     * the retry budget is exhausted. Returns the successful resume result, or
     * null if every attempt still 4007'd (caller then falls back to sending a
     * prompt directly, which materializes a fresh session on first turn). A
     * non-4007 error propagates immediately — it's not a reaping condition.
     */
    private suspend fun resumeUntilLive(
        maxAttempts: Int,
        baseDelayMs: Long,
        maxDelayMs: Long,
    ): JsonRpcClient.SessionResumeResult? {
        var recovered: JsonRpcClient.SessionResumeResult? = null
        repeat(maxAttempts) { attempt ->
            try {
                val delayMs = (baseDelayMs * (attempt + 1)).coerceAtMost(maxDelayMs)
                DebugLog.log("STATE", "SessionID",
                    "resumeUntilLive attempt ${attempt + 1}/$maxAttempts — waiting ${delayMs}ms before retry: dbKey=$sessionId")
                delay(delayMs)
                recovered = rpcClient.sessionResume(sessionId)
                if (recovered != null) {
                    DebugLog.log("STATE", "SessionID",
                        "resumeUntilLive succeeded on attempt ${attempt + 1}: dbKey=$sessionId liveSid=${recovered.session_id}")
                    return@repeat
                }
            } catch (retry4007: JsonRpcException) {
                if (retry4007.code != 4007) throw retry4007
                DebugLog.log("STATE", "SessionID",
                    "resumeUntilLive attempt ${attempt + 1}/$maxAttempts still 4007 — dbKey=$sessionId")
            }
        }
        return recovered
    }

    override fun sendMessage(text: String) {
        if (text.isBlank() || sessionId.isEmpty()) return
        val trimmed = text.trim()

        // /stop / /interrupt / /halt: end the turn via session.interrupt — NOT
        // slash.exec (the server's /stop kills background processes, it does not
        // interrupt the live turn).
        if (trimmed == "/stop" || trimmed == "/interrupt" || trimmed == "/halt") {
            stopStreaming()
            return
        }

        // /steer — native session.steer with an in-chat ack (v0.1.130).
        if (trimmed.startsWith("/steer ") || trimmed == "/steer") {
            handleSteer(trimmed.removePrefix("/steer").trim())
            return
        }
        // /queue — native prompt.submit (server auto-queues when busy) with ack (v0.1.131).
        if (trimmed.startsWith("/queue ") || trimmed == "/queue") {
            handleQueue(trimmed.removePrefix("/queue").trim())
            return
        }
        // /new and /reset — native fresh session via session.create (v0.1.130);
        // bypasses slash.exec entirely (the slash-worker 5030 flakiness).
        if (trimmed.equals("/new", ignoreCase = true) || trimmed.equals("/reset", ignoreCase = true) ||
            trimmed.startsWith("/new ") || trimmed.startsWith("/reset ")
        ) {
            handleNewSession()
            return
        }
        // Slash commands (v0.1.67): messages starting with "/" route to
        // slash.exec (same path as desktop/TUI) instead of the agent. The
        // output lands as an assistant message.
        if (trimmed.startsWith("/") && trimmed.length > 1 && !trimmed.startsWith("//")) {
            sendSlashCommand(trimmed)
            return
        }

        submitPrompt(trimmed)
    }

    /**
     * Submit a normal (non-slash) prompt. While a turn is already streaming the
     * server auto-queues it for the next turn (prompt.submit busy path), so we
     * add the user message but no second streaming placeholder — the queued
     * turn's first event creates one via [ensureStreamingPlaceholder].
     */
    private fun submitPrompt(text: String) {
        val userMsgId = "user_${tempIdCounter.incrementAndGet()}"
        val assistantMsgId = "asst_${tempIdCounter.incrementAndGet()}"
        val now = System.currentTimeMillis()

        DebugLog.log("STATE", "SessionID",
            "sendMessage ENTER: text=\"${text.take(50)}\" " +
            "sessionId=$sessionId (DB key) liveSid=$liveSid (debug) " +
            "resumedSessionId=$resumedSessionId " +
            "resumeCount=${resumeCount}")

        // Add user message immediately
        val userMsg = UiMessage(id = userMsgId, role = "user", content = text, timestamp = now)
        val current = uiState.messages.toMutableList()
        current.add(userMsg)

        // A fresh turn starts here — clear any leftover completion flag from a
        // previous turn so the new placeholder isn't immediately finalized.
        this.turnDoneSeen = false
        // v0.1.140: a new turn — allow the next completion to ping again.
        this.turnNotifPosted = false

        // Add an empty streaming placeholder only when this is the active turn
        // (not a queued prompt sent mid-turn — that one gets its placeholder
        // lazily when its deltas start arriving).
        if (!uiState.isStreaming) {
            val asstMsg = UiMessage(
                id = assistantMsgId, role = "assistant",
                isStreaming = true, isWaitingForFirstEvent = true, timestamp = now,
            )
            current.add(asstMsg)
        }

        uiState = uiState.copy(
            messages = current,
            isStreaming = true,
            error = null,
        )

        viewModelScope.launch {
            try {
                DebugLog.log("STATE", "SessionID",
                    "prompt.submit CALL: sessionId=$sessionId (DB key) " +
                    "liveSid=$liveSid (debug only) " +
                    "resumeCount=$resumeCount")
                DebugLog.log("RPC", "DashboardChat",
                    "prompt.submit → dbKey=$sessionId liveSid=$liveSid text=\"${text.take(50)}\"")
                submitWithSelfHeal(text)
                DebugLog.log("STATE", "SessionID",
                    "prompt.submit OK: sessionId=$sessionId")
                // v0.1.141 — arm an alarm to fire ~90s out so a turn-finished
                // notification can still be posted if the phone is locked/app
                // backgrounded and the WS completion event never arrives live.
                TurnWatcher.arm(getApplication(), sessionId)
            } catch (e: Exception) {
                Log.e("Hermex", "DashboardChatViewModel: promptSubmit failed", e)
                DebugLog.log("STATE", "SessionID",
                    "prompt.submit FAILED: sessionId=$sessionId error=${e.message}")
                val msgs = uiState.messages.toMutableList()
                val idx = msgs.indexOfLast { it.role == "assistant" && it.isStreaming }
                if (idx >= 0) {
                    msgs[idx] = msgs[idx].copy(isStreaming = false)
                }
                uiState = uiState.copy(
                    messages = msgs,
                    isStreaming = false,
                    error = e.message ?: "Send failed",
                )
            }
        }
    }

    /** Execute a slash command via slash.exec and surface its output. */
    private fun sendSlashCommand(command: String) {
        val userMsgId = "user_${tempIdCounter.incrementAndGet()}"
        val asstMsgId = "asst_${tempIdCounter.incrementAndGet()}"
        val now = System.currentTimeMillis()
        val userMsg = UiMessage(id = userMsgId, role = "user", content = command, timestamp = now)
        // Placeholder with spinner: slash commands (e.g. /compress) can take
        // minutes with no stream events — the user needs to see it working.
        // A slash command is a fresh turn; clear any leftover completion flag.
        this.turnDoneSeen = false
        // v0.1.140: a new turn — allow the next completion to ping again.
        this.turnNotifPosted = false
        val asstMsg = UiMessage(
            id = asstMsgId, role = "assistant",
            isStreaming = true, isWaitingForFirstEvent = true, timestamp = now,
        )
        val current = uiState.messages.toMutableList()
        current.add(userMsg)
        current.add(asstMsg)
        uiState = uiState.copy(messages = current, error = null)
        viewModelScope.launch {
            try {
                val result = execSlashWithFallbacks(command)
                applySlashResult(result, command)
                // Slash commands can change context (e.g. /compress) — refresh gauge
                try {
                    val res = rpcClient.sessionResume(sessionId, omitMessages = true)
                    val ctx = parseContextUsage(res.info)
                    if (ctx.first != null || ctx.second != null) {
                        uiState = uiState.copy(
                            contextUsed = ctx.first ?: uiState.contextUsed,
                            contextMax = ctx.second ?: uiState.contextMax,
                        )
                    }
                } catch (_: Exception) { }
            } catch (e: Exception) {
                Log.e("Hermex", "slash.exec failed", e)
                val msgs = uiState.messages.toMutableList()
                val idx = msgs.indexOfLast { it.role == "assistant" && it.isStreaming }
                val errText = "⚠️ Command failed: ${e.message}"
                if (idx >= 0) {
                    msgs[idx] = msgs[idx].copy(content = errText, isStreaming = false)
                } else {
                    msgs.add(
                        UiMessage(
                            id = "slash_err_${tempIdCounter.incrementAndGet()}",
                            role = "assistant",
                            content = errText,
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                }
                uiState = uiState.copy(messages = msgs)
            }
        }
    }

    /**
     * v0.1.99: run slash.exec with two server-side fallbacks:
     *  1. Skill/bundle commands are rejected by slash.exec with 4018
     *     "use command.dispatch" — route them through command.dispatch, which
     *     resolves `_sessions` by LIVE SID only (pass liveSid, not the DB key).
     *  2. 4001 session not found — the session was reaped; re-register it with
     *     session.resume and retry once (same self-heal as prompt.submit).
     */
    private suspend fun execSlashWithFallbacks(command: String): JsonObject {
        return try {
            // slash.exec resolves _sessions by LIVE SID only (no DB-key fallback —
            // proven live: a persisted DB key 4001s while the live SID works), so
            // pass the live SID when we have it, same rule as command.dispatch.
            rpcClient.slashExec(liveSid.ifBlank { sessionId }, command)
        } catch (e: JsonRpcException) {
            when {
                // v0.1.159: slash.exec routes through a server-side slash-worker
                // subprocess that can crash ("slash worker exited" / 5030). The
                // command itself is fine — only the worker transport died. Fall
                // back to command.dispatch, which runs these commands in-process
                // (e.g. _handle_yolo_command) and never touches the dead worker.
                e.code == 5030 || e.message?.contains("slash worker") == true -> {
                    val trimmed = command.trim().removePrefix("/")
                    val base = trimmed.substringBefore(' ').lowercase()
                    val arg = trimmed.substringAfter(' ', "").trim()
                    DebugLog.log("RPC", "DashboardChat",
                        "slash.exec slash-worker error → command.dispatch name=$base arg=$arg (liveSid=$liveSid)")
                    rpcClient.commandDispatch(liveSid.ifBlank { sessionId }, base, arg)
                }
                e.code == 4018 && e.message?.contains("command.dispatch") == true -> {
                    val trimmed = command.trim().removePrefix("/")
                    val base = trimmed.substringBefore(' ').lowercase()
                    val arg = trimmed.substringAfter(' ', "").trim()
                    DebugLog.log("RPC", "DashboardChat",
                        "slash.exec 4018 → command.dispatch name=$base arg=$arg (liveSid=$liveSid)")
                    rpcClient.commandDispatch(liveSid.ifBlank { sessionId }, base, arg)
                }
                e.code == 4001 -> {
                    DebugLog.log("STATE", "SessionID",
                        "slash.exec 4001 — self-heal resume + retry (dbKey=$sessionId)")
                    val resume = rpcClient.sessionResume(sessionId, omitMessages = true)
                    liveSid = resume.session_id
                    resumedSessionId = resume.resumed ?: sessionId
                    rpcClient.slashExec(liveSid.ifBlank { sessionId }, command)
                }
                else -> throw e
            }
        }
    }

    /**
     * Apply a slash.exec / command.dispatch result to the placeholder:
     *  - {"type":"send"|"skill","message":...} → submit the message as a real
     *    prompt (the server wants a turn: /queue, /steer, skill invocations)
     *  - {"output":...} → render as the assistant message
     *  - anything else → render the raw JSON (debuggable)
     */
    private fun applySlashResult(result: JsonObject, command: String) {
        val sendType = result["type"]?.jsonPrimitive?.contentOrNull
        val sendMsg = result["message"]?.jsonPrimitive?.contentOrNull
        if ((sendType == "send" || sendType == "skill") && sendMsg != null) {
            val msgs = uiState.messages.toMutableList()
            val phIdx = msgs.indexOfLast { it.role == "assistant" && it.isStreaming }
            if (phIdx >= 0) msgs.removeAt(phIdx)
            val cmdIdx = msgs.indexOfLast { it.role == "user" && it.content == command }
            if (cmdIdx >= 0) msgs.removeAt(cmdIdx)
            uiState = uiState.copy(messages = msgs)  // keep isStreaming as-is
            submitPrompt(sendMsg)
            return
        }
        val output = result["output"]?.jsonPrimitive?.contentOrNull
            ?: result.toString()
        val msgs = uiState.messages.toMutableList()
        val idx = msgs.indexOfLast { it.role == "assistant" && it.isStreaming }
        if (idx >= 0) {
            msgs[idx] = msgs[idx].copy(content = output, isStreaming = false)
        } else {
            msgs.add(
                UiMessage(
                    id = "slash_${tempIdCounter.incrementAndGet()}",
                    role = "assistant",
                    content = output,
                    timestamp = System.currentTimeMillis(),
                )
            )
        }
        uiState = uiState.copy(messages = msgs)
    }

    /** /steer <text> — inject into the next tool call via session.steer. */
    private fun handleSteer(text: String) {
        if (text.isBlank()) {
            addCommandAck("⚠️ /steer needs some text to steer with.")
            return
        }
        addCommandAck("⏳ Steering for next tool call…")
        viewModelScope.launch {
            try {
                wsConnection.connect()
                val r = rpcClient.sessionSteer(sessionId, text)
                val status = r["status"]?.jsonPrimitive?.contentOrNull ?: "queued"
                if (status == "queued") {
                    addCommandAck("✓ Steered for next tool call: $text")
                } else {
                    addCommandAck("⚠️ Steer rejected: $text")
                }
            } catch (e: JsonRpcException) {
                if (e.code == 4010) {
                    addCommandAck("Steer queued — no active turn; it'll apply on your next message: $text")
                } else {
                    addCommandAck("⚠️ Command failed: ${e.message}")
                }
            } catch (e: Exception) {
                addCommandAck("⚠️ Command failed: ${e.message}")
            }
        }
    }

    /** /new or /reset — create a fresh session and navigate to it. */
    private fun handleNewSession() {
        viewModelScope.launch {
            try {
                wsConnection.connect()
                val newSid = rpcClient.createSession()
                DebugLog.log("RPC", "DashboardChat", "/new → session.create sid=$newSid")
                resetTargetSession = newSid
            } catch (e: Exception) {
                addCommandAck("⚠️ New session failed: ${e.message}")
            }
        }
    }

    /** /queue <text> — send it so it runs after the current turn, with an ack. */
    private fun handleQueue(text: String) {
        if (text.isBlank()) {
            addCommandAck("⚠️ /queue needs some text to queue.")
            return
        }
        addCommandAck("⏳ Queuing for next turn…")
        viewModelScope.launch {
            try {
                wsConnection.connect()
                rpcClient.promptSubmit(sessionId, text)
                addCommandAck(if (uiState.isStreaming) "✓ Queued for next turn: $text" else "✓ Sent: $text")
            } catch (e: Exception) {
                addCommandAck("⚠️ Command failed: ${e.message}")
            }
        }
    }

    /** Append a lightweight, non-streaming acknowledgement line to the chat. */
    private fun addCommandAck(text: String) {
        val msgs = uiState.messages.toMutableList()
        msgs.add(
            UiMessage(
                id = "ack_${tempIdCounter.incrementAndGet()}",
                role = "assistant",
                content = text,
                isCommandAck = true,
                timestamp = System.currentTimeMillis(),
            )
        )
        uiState = uiState.copy(messages = msgs)
    }

    override fun sendMessageWithImage(text: String, imageBase64: String, filename: String?) {
        if (sessionId.isEmpty()) return
        if (imageBase64.isBlank()) {
            if (text.isNotBlank()) sendMessage(text)
            return
        }
        viewModelScope.launch {
            try {
                DebugLog.log("RPC", "DashboardChat", "image.attach_bytes → staging (${imageBase64.length} chars)")
                val attach = rpcClient.attachImage(sessionId, imageBase64, filename)
                val attachText = attach["text"]?.jsonPrimitive?.contentOrNull
                // Image rides along with the next prompt; blank text uses the
                // server's placeholder ("[User attached image: ...]").
                val finalText = text.ifBlank { attachText ?: "[User attached image]" }
                DebugLog.log("RPC", "DashboardChat", "image staged — submitting prompt")
                sendMessage(finalText)
            } catch (e: Exception) {
                Log.e("Hermex", "DashboardChatViewModel: attachImage failed", e)
                DebugLog.log("ERROR", "DashboardChat", "image.attach failed: ${e.message}")
                uiState = uiState.copy(
                    error = e.message ?: "Image attach failed",
                    isStreaming = false,
                )
            }
        }
    }

    override fun sendMessageWithFile(text: String, fileBase64: String, mimeType: String, filename: String?) {
        if (sessionId.isEmpty()) return
        if (fileBase64.isBlank()) {
            if (text.isNotBlank()) sendMessage(text)
            return
        }
        viewModelScope.launch {
            try {
                DebugLog.log("RPC", "DashboardChat", "file.attach → staging (${fileBase64.length} b64 chars)")
                val dataUrl = "data:$mimeType;base64,$fileBase64"
                val attach = rpcClient.attachFile(sessionId, dataUrl, filename)
                val refText = attach["ref_text"]?.jsonPrimitive?.contentOrNull
                val finalText = buildString {
                    if (text.isNotBlank()) append(text).append('\n')
                    append(refText ?: "[User attached file]")
                }
                sendMessage(finalText)
            } catch (e: Exception) {
                Log.e("Hermex", "DashboardChatViewModel: attachFile failed", e)
                DebugLog.log("ERROR", "DashboardChat", "file.attach failed: ${e.message}")
                uiState = uiState.copy(
                    error = e.message ?: "File attach failed",
                    isStreaming = false,
                )
            }
        }
    }

    override fun stopStreaming() {
        viewModelScope.launch {
            try {
                DebugLog.log("RPC", "DashboardChat",
                    "session.interrupt → dbKey=$sessionId liveSid=$liveSid")
                rpcClient.sessionInterrupt(sessionId)
            } catch (_: Exception) { /* best-effort */ }

            // Clear per-message streaming flag so the blinking cursor / thinking
            // ticker / typing dots disappear immediately (same pattern as the
            // error handler in sendMessage).
            val msgs = uiState.messages.toMutableList()
            val idx = msgs.indexOfLast { it.role == "assistant" && it.isStreaming }
            if (idx >= 0) {
                msgs[idx] = msgs[idx].copy(isStreaming = false)
            }

            uiState = uiState.copy(
                messages = msgs,
                isStreaming = false,
                pendingApproval = null,   // dismiss any visible approval dialog
                pendingClarify = null,    // dismiss any visible clarify dialog
            )
        }
    }

    /** Retry the last assistant response. Finds the previous user prompt,
     * removes the last assistant message, and re-submits the prompt. */
    override fun retry() {
        if (sessionId.isEmpty()) return

        val msgs = uiState.messages.toMutableList()
        val userIdx = msgs.indexOfLast { it.role == "user" }
        if (userIdx < 0) return
        val lastUserText = msgs[userIdx].content
        if (lastUserText.isBlank()) return

        // Remove the last assistant message (the one we're regenerating)
        val asstIdx = msgs.indexOfLast { it.role == "assistant" }
        if (asstIdx >= 0) {
            msgs.removeAt(asstIdx)
        }

        // Add streaming assistant placeholder
        // A retry re-submits the last user prompt — a fresh turn. Clear any
        // leftover completion flag so this regenerated placeholder isn't
        // immediately finalized by a stale turnDoneSeen.
        this.turnDoneSeen = false
        // v0.1.140: a fresh turn — allow the next completion to ping again.
        this.turnNotifPosted = false
        val assistantMsgId = "asst_${tempIdCounter.incrementAndGet()}"
        val now = System.currentTimeMillis()
        msgs.add(UiMessage(
            id = assistantMsgId, role = "assistant",
            isStreaming = true, isWaitingForFirstEvent = true, timestamp = now,
        ))

        uiState = uiState.copy(
            messages = msgs,
            isStreaming = true,
            error = null,
            pendingApproval = null,
            pendingClarify = null,
        )

        viewModelScope.launch {
            try {
                DebugLog.log("RPC", "DashboardChat",
                    "retry → dbKey=$sessionId liveSid=$liveSid text=\"${lastUserText.take(50)}\"")
                submitWithSelfHeal(lastUserText)
            } catch (e: Exception) {
                val m = uiState.messages.toMutableList()
                val idx = m.indexOfLast { it.role == "assistant" && it.isStreaming }
                if (idx >= 0) {
                    m[idx] = m[idx].copy(isStreaming = false)
                }
                uiState = uiState.copy(
                    messages = m,
                    isStreaming = false,
                    error = e.message ?: "Retry failed",
                )
            }
        }
    }

    /** Approve the current pending tool call. */
    override fun approveCurrentTool(approveAll: Boolean) {
        val pending = uiState.pendingApproval ?: return
        DebugLog.log("RPC", "DashboardChat",
            "approving tool: ${pending.toolName} all=$approveAll")
        rpcClient.approvalRespond(sessionId, "approve", pending.requestId, approveAll)
        uiState = uiState.copy(pendingApproval = null)
    }

    /** Deny the current pending tool call. */
    override fun denyCurrentTool(denyAll: Boolean) {
        val pending = uiState.pendingApproval ?: return
        DebugLog.log("RPC", "DashboardChat",
            "denying tool: ${pending.toolName} all=$denyAll")
        rpcClient.approvalRespond(sessionId, "deny", pending.requestId, denyAll)
        uiState = uiState.copy(pendingApproval = null)
    }

    /** Respond to the current pending clarify request. */
    override fun respondToClarify(answer: String) {
        val pending = uiState.pendingClarify ?: return
        DebugLog.log("RPC", "DashboardChat",
            "responding to clarify: requestId=${pending.requestId} answer=$answer")
        rpcClient.clarifyRespond(pending.requestId, answer)
        uiState = uiState.copy(pendingClarify = null)
    }

    override fun toggleThinking(messageId: String) {
        val msgs = uiState.messages.toMutableList()
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            msgs[idx] = msgs[idx].copy(thinkingExpanded = !msgs[idx].thinkingExpanded)
            uiState = uiState.copy(messages = msgs)
        }
    }

    override fun onCleared() {
        DebugLog.log("STATE", "SessionID",
            "onCleared: sessionId=$sessionId liveSid=$liveSid resumeCount=$resumeCount")
        super.onCleared()
        dispose()
    }

    /**
     * Tear down this chat ViewModel's live connection. Called by [ChatVmsHolder]
     * when the Activity finishes (chat VMs now outlive navigation so turns keep
     * running in the background). Keepalive service is NOT stopped here — the
     * holder stops it once every session's VM is disposed.
     */
    fun dispose() {
        notificationCollectorJob?.cancel()
        serverRequestCollectorJob?.cancel()
        staleStreamTimeoutJob?.cancel()
        wsConnection.disconnect()
    }

    // ── v0.1.123 stuck-spinner watchdog ──

    /**
     * Re-arm the stale-stream guard: cancel any pending timeout and start a new
     * one. Called on every incoming notification so a healthy stream never trips
     * it. If no event lands within the grace window while an assistant message is
     * still `isStreaming`, clear that flag so the UI doesn't spin forever.
     */
    private fun armStaleStreamGuard() {
        staleStreamTimeoutJob?.cancel()
        staleStreamTimeoutJob = viewModelScope.launch {
            delay(STALE_STREAM_GRACE_MS)
            // If we've seen ANY streaming content for this message, a long gap is
            // just agent-warmup (esp. on a fresh /new turn with no warm agent) —
            // NOT a lost completion. Only nuke the stream if zero content events
            // ever arrived (truly dead) or a completion was already seen server-side.
            val stillStreaming = uiState.messages.any {
                it.role == "assistant" && it.isStreaming
            }
            if (stillStreaming && !streamingContentSeen && !turnDoneSeen) {
                DebugLog.log("RPC", "DashboardChat",
                    "v0.1.137: stale-stream watchdog fired after ${STALE_STREAM_GRACE_MS}ms — " +
                    "assistant message still marked isStreaming; completion signal was lost" +
                    (if (turnDoneSeen) " (already completed server-side)" else "") + ", clearing")
                val msgs = uiState.messages.toMutableList()
                var cleared = 0
                for (i in msgs.indices) {
                    if (msgs[i].role == "assistant" && msgs[i].isStreaming) {
                        msgs[i] = msgs[i].copy(isStreaming = false, isWaitingForFirstEvent = false)
                        cleared++
                    }
                }
                if (cleared > 0) {
                    uiState = uiState.copy(messages = msgs, scrollGeneration = uiState.scrollGeneration + 1)
                }
            }
        }
    }

    // ── WebSocket lifecycle ──

    private fun connectWsAndStart() {
        viewModelScope.launch {
            connectStartTime = System.currentTimeMillis()
            try {
                DebugLog.log("WS", "DashboardChat", "connecting WebSocket")
                wsConnection.connect()
                val connectDuration = System.currentTimeMillis() - connectStartTime
                DebugLog.log("WS", "DashboardChat",
                    "WebSocket connected in ${connectDuration}ms — starting RPC " +
                    "(sessionId=$sessionId liveSid=$liveSid)")

                // Start foreground service to keep the process alive
                // (prevents WS disconnect when phone locks or app backgrounds)
                WsKeepaliveService.start(getApplication())

                // Monitor WS state transitions
                var previousWsState: Any? = null
                launch {
                    wsConnection.state.collect { wsState ->
                        DebugLog.log("WS", "ChatVM",
                            "state=$wsState (sessionId=$sessionId liveSid=$liveSid)")
                        // Detect reconnect: state changed TO Connected
                        if (wsState.toString().uppercase() == "CONNECTED"
                            && previousWsState != null
                            && previousWsState.toString().uppercase() != "CONNECTED") {
                            DebugLog.log("WS", "DashboardChat",
                                "reconnect detected — re-registering session $sessionId")
                            viewModelScope.launch {
                                try {
                                    // Fresh turn context after reconnect — clear the
                                    // completion flag so a re-attached placeholder isn't
                                    // immediately finalized by a stale turnDoneSeen.
                                    // v0.1.151: keep turnDoneSeen if we've already seen a
                                    // completion for this session, so a dropped/late completion
                                    // during reconnect churn still force-clears the spinner.
                                    if (!turnDoneSeen) turnDoneSeen = false
                                    val result = rpcClient.sessionResume(sessionId, omitMessages = true)
                                    resumeCount++
                                    liveSid = result.session_id
                                    resumedSessionId = result.resumed ?: sessionId
                                    DebugLog.log("STATE", "SessionID",
                                        "reconnect resume(#${resumeCount}): " +
                                        "dbKey=$sessionId liveSid=$liveSid " +
                                        "resumed=$resumedSessionId")
                                    // Refresh the context gauge on reconnect too
                                    val ctx = parseContextUsage(result.info)
                                    if (ctx.first != null || ctx.second != null) {
                                        uiState = uiState.copy(
                                            contextUsed = ctx.first ?: uiState.contextUsed,
                                            contextMax = ctx.second ?: uiState.contextMax,
                                        )
                                    }
                                    // v0.1.135: reconcile a turn that finished while the
                                    // socket was down. The lightweight re-attach above uses
                                    // omitMessages=true, so if the turn COMPLETED during the
                                    // disconnect the client never receives the completed
                                    // message — it's left with a stale placeholder still
                                    // marked isStreaming (UI looks "crashed" / frozen). The
                                    // server's resume payload tells us the turn is no longer
                                    // running; if we still think it is, do a FULL history
                                    // reload so the final answer appears without a hard
                                    // close + reopen.
                                    val turnDoneServerSide = result.running == false
                                    val localThinksStreaming = uiState.messages.any {
                                        it.role == "assistant" && it.isStreaming
                                    }
                                    // v0.1.137 — also reconcile when the server's `running`
                                    // is ambiguous (null) but we've already seen a turn-completion
                                    // signal locally and are still stuck streaming: a full history
                                    // reload surfaces the finished answer instead of a frozen spinner.
                                    // v0.1.151 — also reconcile if WE think we're still streaming
                                    // even with no completion signal at all: under heavy reconnect
                                    // churn (constant sessions.changed reloads) the lightweight
                                    // omitMessages resume can miss a turn that finished mid-churn,
                                    // leaving a stranded spinner with neither running=false nor
                                    // turnDoneSeen set. A full history reload is safe and idempotent.
                                    val reconcile = localThinksStreaming &&
                                        (turnDoneServerSide || turnDoneSeen || !streamingContentSeen)
                                    if (reconcile) {
                                        DebugLog.log("STATE", "SessionID",
                                            "reconnect: turn finished while disconnected (server running=false, " +
                                            "local still streaming=true) — reloading history to reconcile")
                                        // v0.1.140: the full-history reload surfaces the finished
                                        // answer, but only notifies if screenVisible was false at
                                        // this instant. Post explicitly here too (guarded so it
                                        // can't double-fire with Fix 1 on the same completion).
                                        val shouldNotify = !screenVisible || com.hermex.v3.AppState.isBackgrounded
                                        if (shouldNotify && !turnNotifPosted) {
                                            turnNotifPosted = true
                                            val last = uiState.messages.lastOrNull { it.role == "assistant" }
                                            val preview = last?.content?.ifBlank { "Turn finished" }?.take(200) ?: "Turn finished"
                                            runCatching {
                                                NotificationHelper.postTurnFinished(
                                                    getApplication(), sessionId, uiState.sessionTitle, preview,
                                                )
                                            }
                                        }
                                        loadMessages()
                                    }
                                } catch (e: Exception) {
                                    Log.e("Hermex", "DashboardChat: reconnect resume failed", e)
                                    DebugLog.log("ERROR", "DashboardChat",
                                        "reconnect resume failed: ${e.message}")
                                    // v0.1.85 (half-open fix): a failed lightweight
                                    // re-attach leaves the WS connected but the session
                                    // unattached — sends go into the void until the 30s
                                    // timeout ("jpc error"). Retry with the FULL
                                    // re-attach (resume + history reload), which is the
                                    // proven path and handles 4001 internally.
                                    loadMessages()
                                }
                            }
                        }
                        previousWsState = wsState
                    }
                }

                rpcClient.start()
                DebugLog.log("WS", "DashboardChat",
                    "RPC client started, total=${System.currentTimeMillis() - connectStartTime}ms " +
                    "(sessionId=$sessionId)")

                // Begin collecting notifications
                notificationCollectorJob = launch {
                    rpcClient.notifications.collect { notification ->
                        handleNotification(notification)
                    }
                }

                // v0.1.170: collect server→client requests (approval/clarify/sudo/…) and
                // route them into UI state, capturing the srq id so we can answer via
                // respondRequest(...). These arrive with a string id + method but no
                // result — distinct from notifications.
                serverRequestCollectorJob = launch {
                    rpcClient.serverRequests.collect { req ->
                        handleServerRequest(req)
                    }
                }

                // Load session history after connection
                sessionLoadStartTime = System.currentTimeMillis()
                loadMessages()
                // v0.1.77: re-arm the cron alarm watcher whenever the app
                // connects (catches schedule changes + missed runs)
                CronWatcher.sync(getApplication())
            } catch (e: Exception) {
                Log.e("Hermex", "DashboardChatViewModel: WebSocket connect failed", e)
                DebugLog.log("ERROR", "DashboardChat", "WS connect failed: ${e.message}")
                uiState = uiState.copy(
                    error = "Connection failed: ${e.message}",
                )
            }
        }
    }

    // ── RPC Notification handler ──

    /** Return the index of the live streaming assistant message, creating one
     * if a queued turn's first event arrives with no placeholder yet (a prompt
     * sent mid-turn gets its placeholder lazily when its deltas start). */
    private fun ensureStreamingPlaceholder(msgs: MutableList<UiMessage>): Int {
        val idx = msgs.indexOfLast { it.role == "assistant" && it.isStreaming }
        if (idx >= 0) return idx
        // New placeholder for a fresh message — reset the streaming-seen flag so
        // the stale-stream guard starts clean (a brand-new turn may have a long
        // cold-start gap before the first delta).
        streamingContentSeen = false
        msgs.add(
            UiMessage(
                id = "asst_${tempIdCounter.incrementAndGet()}",
                role = "assistant",
                isStreaming = true,
                isWaitingForFirstEvent = true,
                timestamp = System.currentTimeMillis(),
            )
        )
        return msgs.size - 1
    }

    private fun handleNotification(n: RpcNotification) {
        armStaleStreamGuard()
        // Filter: only process events for our session
        val nSid = n.sessionId
        if (nSid != null && nSid.isNotEmpty() && nSid != sessionId && nSid != liveSid) {
            // Notifications may carry either DB key or live SID — match against both
            DebugLog.log("STATE", "SessionID",
                "notification FILTERED: nSid=$nSid != dbKey=$sessionId != liveSid=$liveSid " +
                "event=${n::class.simpleName}")
            return
        }
        if (nSid != null && nSid.isNotEmpty()) {
            DebugLog.log("STATE", "SessionID",
                "notification MATCHED: nSid=$nSid dbKey=$sessionId liveSid=$liveSid " +
                "event=${n::class.simpleName}")
        }

        val msgs = uiState.messages.toMutableList()

        // v0.1.123/137 safety net — stuck-spinner guard. If a streaming content
        // event (delta / tool event) arrives but there is NO live streaming
        // assistant message, the completion event that was supposed to clear
        // `isStreaming` was dropped/late/out-of-order and we'd otherwise strand a
        // spinner forever (the "frozen" bug). Re-establish a placeholder so the
        // late content has somewhere to land and finalize on the next completed
        // event, instead of leaving the UI stuck.
        if (n is RpcNotification.MessageDelta ||
            n is RpcNotification.ThinkingDelta ||
            n is RpcNotification.ReasoningDelta ||
            n is RpcNotification.ToolGenerating ||
            n is RpcNotification.ToolStart ||
            n is RpcNotification.ToolComplete
        ) {
            val hasLiveStream = msgs.any { it.role == "assistant" && it.isStreaming }
            if (!hasLiveStream) {
                DebugLog.log("RPC", "DashboardChat",
                    "v0.1.137: ${n::class.simpleName} arrived with no live stream — " +
                    "completion was lost, re-establishing placeholder to avoid stuck spinner" +
                    (if (turnDoneSeen) " (turn already completed server-side)" else ""))
                ensureStreamingPlaceholder(msgs)
            }
        }

        when (n) {
            is RpcNotification.GatewayReady -> {
                DebugLog.log("RPC", "DashboardChat",
                    "gateway.ready — agent=${n.agentId} v${n.version} " +
                    "sessionId=${n.sessionId}")
                val gwSessionId = n.sessionId
                if (gwSessionId != null && gwSessionId.isNotEmpty()) {
                    DebugLog.log("STATE", "SessionID",
                        "gateway.ready gwSessionId=$gwSessionId " +
                        "(our dbKey=${this.sessionId} liveSid=$liveSid)")
                }
            }

            is RpcNotification.RunStarted -> {
                // No UI change needed — streaming placeholder already visible
            }

            is RpcNotification.MessageStarted -> {
                DebugLog.log("RPC", "DashboardChat",
                    "message.start — serverId=${n.messageId}")
                val serverId = n.messageId
                val idx = ensureStreamingPlaceholder(msgs)
                msgs[idx] = msgs[idx].copy(
                    id = serverId ?: msgs[idx].id,
                    isWaitingForFirstEvent = false,
                )
            }

            is RpcNotification.ReasoningAvailable -> {
                val idx = ensureStreamingPlaceholder(msgs)
                val cur = msgs[idx]
                val existing = cur.thinkingText ?: ""
                msgs[idx] = cur.copy(
                    thinkingHasContent = true,
                    thinkingText = existing,
                )
            }

            is RpcNotification.MessageDelta -> {
                val idx = ensureStreamingPlaceholder(msgs)
                val cur = msgs[idx]
                // First real streaming content for this message — mark so the
                // stale-stream guard won't mistake a cold-start gap for a lost
                // completion (see armStaleStreamGuard).
                streamingContentSeen = true
                msgs[idx] = cur.copy(
                    content = cur.content + n.text,
                    thinkingHasContent = true,
                    isWaitingForFirstEvent = false,
                )
                // v0.1.110: forward server-reported tok/s to UI for the live readout.
                n.predictedPerSecond?.let { pps ->
                    uiState = uiState.copy(liveTokPerSec = pps)
                }
            }

            is RpcNotification.ThinkingDelta -> {
                val idx = ensureStreamingPlaceholder(msgs)
                val cur = msgs[idx]
                val existing = cur.thinkingText ?: ""
                msgs[idx] = cur.copy(
                    thinkingText = existing + n.text,
                    isWaitingForFirstEvent = false,
                )
            }

            is RpcNotification.ReasoningDelta -> {
                // Treat reasoning same as thinking — append to thinkingText
                val idx = ensureStreamingPlaceholder(msgs)
                val cur = msgs[idx]
                val existing = cur.thinkingText ?: ""
                val text = n.text ?: ""
                msgs[idx] = cur.copy(
                    thinkingText = existing + text,
                    isWaitingForFirstEvent = false,
                )
            }

            is RpcNotification.ToolGenerating -> {
                DebugLog.log("RPC", "ToolEvent", "generating: ${n.toolName}")
                val idx = ensureStreamingPlaceholder(msgs)
                val cur = msgs[idx]
                val existing = cur.toolCalls.toMutableList()
                // _thinking and todo are rendered as dedicated UI (thinking
                // block / Tasks card), not as tool cards.
                if (n.toolName != "_thinking" && n.toolName != "todo") {
                    val tc = UiToolCall(
                        id = "tc_${existing.size}",
                        toolName = n.toolName,
                    )
                    existing.add(tc)
                }
                msgs[idx] = cur.copy(
                    toolCalls = existing,
                    isWaitingForFirstEvent = false,
                )
            }

            is RpcNotification.ToolStart -> {
                DebugLog.log("RPC", "ToolEvent", "start: toolId=${n.toolId} name=${n.toolName} context=${n.context ?: ""}")
                if (n.toolName == "todo") return
                val idx = ensureStreamingPlaceholder(msgs)
                val cur = msgs[idx]
                val updated = cur.toolCalls.map { tc ->
                    // Match by id, or the FIRST not-yet-started card with this
                    // name (a tool that runs twice must not overwrite the
                    // previous run's id — that created duplicate ids → crash).
                    if (tc.id == n.toolId || (tc.toolName == n.toolName && tc.startedAt == null)) tc.copy(
                        id = n.toolId,
                        preview = n.context,
                        startedAt = System.currentTimeMillis(),
                    ) else tc
                }
                msgs[idx] = cur.copy(toolCalls = updated)
            }

            is RpcNotification.ToolComplete -> {
                DebugLog.log("RPC", "ToolEvent", "complete: toolId=${n.toolId} name=${n.toolName} summary=${n.summary ?: ""} args=${n.args?.toString()?.take(100) ?: ""}")
                // todo tool.complete is the source of truth for the task list —
                // update the Tasks card, not a tool card.
                if (n.toolName == "todo") {
                    val todos = parseTodos(n.result)
                    if (todos != null) {
                        uiState = uiState.copy(
                            todos = todos,
                            // Auto-expand the first time a task list appears during
                            // a turn; once the user collapses it, respect that.
                            todosExpanded = uiState.todosExpanded ||
                                (uiState.todos.isEmpty() && uiState.isStreaming),
                        )
                    }
                    return
                }
                val idx = ensureStreamingPlaceholder(msgs)
                val cur = msgs[idx]
                val updated = cur.toolCalls.map { tc ->
                    // Match by id, or the first not-yet-completed card with
                    // this name (repeated tool runs must update THEIR card).
                    if (tc.id == n.toolId || (tc.toolName == n.toolName && !tc.completed)) tc.copy(
                        completed = true,
                        args = n.args?.toString() ?: tc.args,
                        preview = n.summary ?: tc.preview,
                        result = n.result?.toString(),
                        summary = n.summary,
                        inlineDiff = n.inlineDiff ?: tc.inlineDiff,
                    ) else tc
                }
                msgs[idx] = cur.copy(toolCalls = updated)
            }

            is RpcNotification.MessageCompleted -> {
                DebugLog.log("RPC", "DashboardChat", "message.completed — finalizing")
                // The turn is done server-side — record that so even if this
                // message's content was already applied, we clear the spinner
                // immediately (no 45s watchdog wait) and a later stray delta can
                // land on a fresh placeholder instead of stranding the UI.
                this.turnDoneSeen = true
                // v0.1.141 — turn done live, cancel the pending alarm so it
                // doesn't fire and double-notify (or wake the phone pointlessly).
                runCatching { TurnWatcher.cancel(getApplication(), sessionId) }
                val idx = msgs.indexOfLast { it.role == "assistant" }
                if (idx >= 0) {
                    val cur = msgs[idx]
                    val finalContent = n.content?.takeIf { it.isNotBlank() } ?: cur.content
                    // Keep live-accumulated tool calls (preserve preview/args from
                    // ToolGenerating/ToolStart/ToolComplete). Only copy server IDs
                    // from message.tool_calls so tool cards don't lose their context
                    // when MessageCompleted replaces the list wholesale.
                    val serverTools = n.toolCalls.orEmpty()
                    val finalTools = cur.toolCalls.map { liveTc ->
                        val serverMatch = serverTools.firstOrNull {
                            it.function?.name == liveTc.toolName
                        }
                        liveTc.copy(
                            id = serverMatch?.id ?: liveTc.id,
                            completed = true,
                        )
                    }
                    val usage = n.usage?.let {
                        UiUsage(
                            it.promptTokens ?: 0,
                            it.completionTokens ?: 0,
                            it.totalTokens ?: 0,
                            it.estimatedCostUsd,
                            it.predictedPerSecond,
                        )
                    }
                    msgs[idx] = cur.copy(
                        id = n.messageId ?: cur.id,
                        content = finalContent,
                        toolCalls = finalTools,
                        isStreaming = false,
                        thinkingExpanded = false,
                        thinkingHasContent = true,
                        usage = usage,
                    )
                    // v0.1.74/75: turn-finished notification — fire when the
                    // user is NOT watching this chat: navigated away (screen
                    // not visible) OR the app is backgrounded.
                    if (!screenVisible || com.hermex.v3.AppState.isBackgrounded) {
                        val title = uiState.sessionTitle.ifBlank { sessionId }
                        NotificationHelper.postTurnFinished(
                            getApplication(), sessionId, title, finalContent,
                        )
                    }
                }
                uiState = uiState.copy(
                    messages = msgs,
                    isStreaming = false,
                    liveTokPerSec = null,
                    scrollGeneration = uiState.scrollGeneration + 1,
                )
                onTurnFinished()
                return  // already set uiState
            }

            is RpcNotification.RunCompleted -> {
                // run.completed means the server finished the turn. If it arrived
                // before/without message.completed (out-of-order or dropped), we
                // still know the turn is done — record it so a later stray delta
                // re-establishes a placeholder rather than stranding this spinner.
                this.turnDoneSeen = true
                // v0.1.141 — turn done live, cancel the pending alarm so it
                // doesn't fire and double-notify (or wake the phone pointlessly).
                runCatching { TurnWatcher.cancel(getApplication(), sessionId) }
                // v0.1.140: run.completed is the primary "turn done" signal but
                // the block above never posted a turn-finished notification when
                // away. Mirror the MessageCompleted path so completed-while-away
                // turns actually ping. Guarded by turnNotifPosted so it can't
                // double-fire with the reconnect reconciliation on the same turn.
                val shouldNotify = !screenVisible || com.hermex.v3.AppState.isBackgrounded
                if (shouldNotify && !turnNotifPosted) {
                    turnNotifPosted = true
                    val preview = uiState.messages.lastOrNull { it.role == "assistant" }
                        ?.content?.ifBlank { "Turn finished" }?.take(200) ?: "Turn finished"
                    runCatching {
                        NotificationHelper.postTurnFinished(
                            getApplication(), sessionId, uiState.sessionTitle, preview,
                        )
                    }
                }
                onTurnFinished()
                uiState = uiState.copy(isStreaming = false)
                return
            }

            is RpcNotification.ApprovalRequest -> {
                val rawToolName = n.toolName
                val argsStr = n.args?.toString() ?: ""
                val trimmedArgs = argsStr.trim()

                // Primary source: the server sends the real (redacted) command at
                // params.command and a human-readable description. Use those directly.
                var commandLine = n.command?.trim().orEmpty()
                val serverDescription = n.description?.trim().orEmpty()
                var toolName = rawToolName.orEmpty().ifBlank { "command" }

                // Fallback for older servers that don't send params.command: still
                // try to pull a command out of the nested args blob.
                if (commandLine.isBlank()) {
                    if (trimmedArgs.startsWith("{") && trimmedArgs.endsWith("}")) {
                        try {
                            val json = Json.parseToJsonElement(trimmedArgs)
                            if (json is JsonObject) {
                                for (field in listOf("cmd", "command", "arguments", "arg", "query", "text", "content", "path", "file_path")) {
                                    if (field in json) {
                                        val valEl = json[field]!!
                                        if (valEl.jsonPrimitive != null) {
                                            commandLine = valEl.jsonPrimitive.contentOrNull ?: ""
                                            break
                                        } else if (valEl.jsonArray.isNotEmpty()) {
                                            commandLine = valEl.jsonArray.joinToString(" ") { it.toString() }
                                            break
                                        }
                                    }
                                }
                                if (toolName == "command" && "tool" in json) {
                                    val toolEl = json["tool"]
                                    if (toolEl?.jsonPrimitive != null) {
                                        toolName = toolEl.jsonPrimitive.contentOrNull ?: "command"
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                // Prefer the server's own description; fall back to a built one.
                val desc = if (serverDescription.isNotBlank()) {
                    serverDescription.take(1000)
                } else {
                    buildString {
                        append("Runs ")
                        append(toolName)
                        if (commandLine.isNotBlank()) {
                            append("\n")
                            append(commandLine.take(500))
                        } else if (trimmedArgs.isNotBlank()) {
                            append(" with: ")
                            append(trimmedArgs.take(300))
                        }
                    }
                }
                DebugLog.log("RPC", "DashboardChat",
                    "approval request received: tool=$toolName command=${commandLine.ifBlank { "<none>" }}")
                uiState = uiState.copy(
                    pendingApproval = PendingApproval(
                        toolName = toolName,
                        toolArgs = argsStr,
                        description = desc,
                    )
                )
                // v0.1.84: ping when the user isn't watching this chat —
                // otherwise approval requests sit unseen until timeout.
                if (!screenVisible || AppState.isBackgrounded) {
                    NotificationHelper.postApproval(
                        getApplication(),
                        sessionId,
                        toolName,
                        commandLine.ifBlank { argsStr }.take(200),
                    )
                }
            }

            is RpcNotification.ClarifyRequest -> {
                DebugLog.log("RPC", "DashboardChat", "clarify request received: requestId=${n.requestId} question=${n.question ?: ""} choices=${n.choices}")
                uiState = uiState.copy(
                    pendingClarify = PendingClarify(
                        requestId = n.requestId,
                        question = n.question ?: "",
                        choices = n.choices ?: emptyList(),
                    )
                )
            }

            is RpcNotification.SessionInfo -> {
                DebugLog.log("RPC", "DashboardChat", "session.info received")
                val context = parseContextUsage(n.info)
                // session.info also carries the live model + reasoning_effort —
                // refresh the chip so a config.set switch (or desktop/Telegram
                // switch) reflects here without a full resume.
                val infoModel = n.info?.get("model")?.jsonPrimitive?.contentOrNull
                val infoReasoning = n.info?.get("reasoning_effort")?.jsonPrimitive?.contentOrNull
                // session.info carries the authoritative yolo flag — the button
                // reads this instead of a separate REST call, and updates live
                // whenever yolo is toggled from any surface.
                val infoYolo = n.info?.get("yolo")?.jsonPrimitive?.contentOrNull
                uiState = uiState.copy(
                    contextUsed = context.first ?: uiState.contextUsed,
                    contextMax = context.second ?: uiState.contextMax,
                    currentModel = infoModel ?: uiState.currentModel,
                    currentReasoning = infoReasoning?.takeIf { it.isNotBlank() }
                        ?: uiState.currentReasoning,
                    yoloEnabled = infoYolo?.toBooleanStrictOrNull() ?: uiState.yoloEnabled,
                )
            }

            is RpcNotification.SessionChanged -> {
                // Global broadcast — the session list changed server-side. The
                // SessionsViewModel observes this and refetches; here we just
                // note it so the event isn't silently dropped.
                DebugLog.log("RPC", "DashboardChat", "sessions.changed — SessionsVM will reload")
            }

            is RpcNotification.Unknown -> {
                Log.w("Hermex", "DashboardChat: unknown event: ${n.eventType}")
                DebugLog.log("RPC", "DashboardChat", "unknown event: ${n.eventType} — rawParams=${n.rawParams?.toString()?.take(200)}")
                // v0.1.74: the gateway broadcasts cron.changed whenever the cron
                // list changes (any surface: phone, desktop, Telegram). Forward
                // it to the alarm watcher so schedules re-arm without polling.
                if (n.eventType == "cron.changed") {
                    CronWatcher.sync(getApplication())
                }
            }
        }

        // Emit updated state with scrollGeneration bump for auto-scroll.
        //
        // v0.1.137 — self-healing finalization. Normally `isStreaming` follows
        // whether any assistant message is still streaming (a queued turn's
        // first delta creates its placeholder mid-turn). BUT if we've already
        // received a completion signal for this turn (`turnDoneSeen`) and the
        // only thing still marked streaming is a stale/stray placeholder, force
        // it closed here so a dropped/out-of-order completion can never strand
        // a spinner forever — even on a short "status check" turn with no tools.
        val forcedClosed = turnDoneSeen && msgs.any { it.role == "assistant" && it.isStreaming }
        uiState = uiState.copy(
            messages = msgs,
            isStreaming = if (forcedClosed) false else msgs.any { it.role == "assistant" && it.isStreaming },
            scrollGeneration = uiState.scrollGeneration + 1,
        )
        // A forced close just changed the last message's shape — bump scroll so
        // the final bubble settles at the bottom (mirrors the completion paths).
        if (forcedClosed) {
            DebugLog.log("RPC", "DashboardChat", "v0.1.137: turn already completed but a placeholder was still streaming — force-closed to clear spinner")
        }
    }

    override fun toggleTodosExpanded() {
        uiState = uiState.copy(todosExpanded = !uiState.todosExpanded)
    }

    // ── v0.1.170: server→client requests (approval/clarify/sudo/…) ──
    //
    // These arrive with a string id (`srq-<hex>`) + `method` but NO result/error — the
    // gateway is asking a question and waiting for one JSON-RPC response frame on that id.
    // Distinct from notifications (no id). We mirror the notification handlers to populate
    // UI state, then answer via respondRequest(...). Parsing reads params at top level, since
    // server→client requests place fields directly under params (unlike notifications, which
    // nest them under params["payload"]).

    private fun handleServerRequest(req: JsonRpcClient.ServerRequest) {
        val p = req.params
        when (req.method) {
            "approval" -> {
                // Server→client approval params sit at the top level of `params`
                // (request_id, command, description, choices, tool_name, allow_permanent,
                // smart_denied — see ApprovalRequestParams), unlike notifications which
                // nest under params["payload"].
                val commandLine = p["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val serverDescription = p["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val rawToolName = p["tool_name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "command" }

                DebugLog.log("RPC", "DashboardChat",
                    "server→client approval request: id=${req.id} tool=$rawToolName command=${commandLine.ifBlank { "<none>" }}")

                uiState = uiState.copy(
                    pendingApproval = PendingApproval(
                        toolName = rawToolName,
                        toolArgs = "",
                        description = serverDescription.ifBlank { "Runs ${rawToolName}" },
                        requestId = req.id,  // v0.1.170: capture so we can respond via JSON-RPC
                    )
                )

                // When the user isn't watching, also push a notification so the approval is
                // seen before the gateway deadline. The dialog + response come from the approve/deny
                // buttons calling approveCurrentTool() / denyCurrentTool(), which now answer via the
                // captured request id.
                if (!screenVisible || AppState.isBackgrounded) {
                    runCatching {
                        NotificationHelper.postApproval(
                            getApplication(), sessionId, rawToolName,
                            commandLine.ifBlank { "" }.take(200),
                        )
                    }
                }
            }

            "clarify" -> {
                val requestId = p["request_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    ?: p["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val question = p["question"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val choicesArr = p["choices"] as? JsonArray
                val choices = choicesArr?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.filter { it.isNotBlank() }.orEmpty()

                DebugLog.log("RPC", "DashboardChat",
                    "server→client clarify request: id=$requestId question=$question")

                uiState = uiState.copy(
                    pendingClarify = PendingClarify(
                        requestId = requestId,
                        question = question,
                        choices = choices,
                    )
                )
            }

            else -> {
                Log.w("Hermex", "DashboardChat: unhandled server→client request: ${req.method}")
                DebugLog.log("RPC", "DashboardChat",
                    "server→client request: method=${req.method} id=${req.id} — no local handler")
                // Answer with -32601 (method not found) so the gateway fails fast instead of
                // stalling out its deadline. This matches createServerRequestHandler's contract.
                respondJsonRpc(req.id, buildJsonObject {
                    put("error", buildJsonObject {
                        put("code", JsonPrimitive(-32601))
                        put("message", JsonPrimitive("method not found: ${req.method}"))
                    })
                })
            }
        }
    }

    /** Answer a server→client request with one JSON-RPC response frame on the same id. */
    private fun respondJsonRpc(id: String, result: JsonObject) {
        rpcClient.respondRequest(id, result)
    }


    override suspend fun completeSlash(text: String): List<JsonRpcClient.SlashItem> =
        rpcClient.completeSlash(text)

    // ── v0.1.88: model picker ──

    /** Model options for the picker sheet. */
    override suspend fun loadModelOptions(): JsonRpcClient.ModelOptionsResult = rpcClient.modelOptions()

    /**
     * Persist the user's model + thinking/effort pick (applies to NEW sessions) +
     * reflect it. [thinkingOn] flips reasoning "off" when false — persisted as the
     * "off" effort level so new sessions don't reason. [reasoning] is the chosen
     * non-off level (ignored when [thinkingOn] is false).
     */
    override fun saveModelPick(model: String, reasoning: String, thinkingOn: Boolean) {
        val effort = if (thinkingOn) reasoning.ifBlank { DEFAULT_EFFORT } else "off"
        viewModelScope.launch {
            val settingsRepo = SettingsRepository(getApplication())
            settingsRepo.setModelPick(model)
            settingsRepo.setReasoningPick(effort)
            uiState = uiState.copy(currentReasoning = effort.ifBlank { null })
        }
    }

    /**
     * v0.1.89/90/91: switch THIS session's model/effort mid-conversation.
     *
     * v0.1.90 sent `/model` + `/reasoning` via slash.exec — broken: the server's
     * slash-exec mirror (`_mirror_slash_side_effects`) has NO reasoning branch
     * (so /reasoning silently no-ops the live session) and its /model branch
     * requires a live agent (fresh/reaped sessions no-op) and rejects a busy
     * session instead of deferring. v0.1.91 uses the `config.set` RPC — the
     * desktop's path — which defers a busy model switch to the next turn and
     * builds the agent for a fresh session. `config.set` resolves `_sessions`
     * by LIVE SID only, so send the resolved live sid (fall back to sessionId,
     * which IS the live sid for a just-created session).
     */
    override fun applyModelToSession(model: String, reasoning: String, thinkingOn: Boolean) {
        viewModelScope.launch {
            try {
                val sid = liveSid.ifBlank { sessionId }
                if (model.isNotBlank()) {
                    val res = rpcClient.configSet(sid, "model", model)
                    val applied = res["value"]?.jsonPrimitive?.contentOrNull ?: model
                    DebugLog.log("RPC", "DashboardChat",
                        "config.set model=$model → applied=$applied")
                }
                // v0.1.116: thinking on/off drives the effort level. "off" turns
                // reasoning off; any real level turns it on. Empty reasoning while
                // on falls back to the default (non-off) level.
                val effort = if (thinkingOn) reasoning.ifBlank { DEFAULT_EFFORT } else "off"
                rpcClient.configSet(sid, "reasoning", effort)
                DebugLog.log("RPC", "DashboardChat",
                    "config.set reasoning=$effort (thinkingOn=$thinkingOn)")
                uiState = uiState.copy(
                    currentModel = model.ifBlank { uiState.currentModel },
                    currentReasoning = effort.ifBlank { uiState.currentReasoning },
                )
                // The server emits session.info with the new model/reasoning; a
                // lightweight resume also refreshes the gauge + chip immediately.
                refreshUsageAndModel()
            } catch (e: Exception) {
                Log.e("Hermex", "applyModelToSession failed", e)
                DebugLog.log("ERROR", "DashboardChat", "applyModelToSession failed: ${e.message}")
                uiState = uiState.copy(error = e.message ?: "Model switch failed")
            }
        }
    }

    /** Lightweight resume to refresh context gauge + model chip (v0.1.91). */
    private suspend fun refreshUsageAndModel() {
        try {
            val res = rpcClient.sessionResume(sessionId, omitMessages = true)
            val ctx = parseContextUsage(res.info)
            val m = res.info?.get("model")?.jsonPrimitive?.contentOrNull
            val r = res.info?.get("reasoning_effort")?.jsonPrimitive?.contentOrNull
            uiState = uiState.copy(
                contextUsed = ctx.first ?: uiState.contextUsed,
                contextMax = ctx.second ?: uiState.contextMax,
                currentModel = m ?: uiState.currentModel,
                currentReasoning = r?.takeIf { it.isNotBlank() } ?: uiState.currentReasoning,
            )
        } catch (_: Exception) { }
    }

    /** Read the profile's reasoning_effort (config.yaml) once, for the chip. */
    fun loadReasoningFromConfig() {
        viewModelScope.launch {
            try {
                val settingsRepo = SettingsRepository(getApplication())
                val pick = settingsRepo.reasoningPick.first()
                if (pick.isNotBlank()) {
                    uiState = uiState.copy(currentReasoning = pick)
                    return@launch
                }
                when (val r = DashboardApiClient.configRaw()) {
                    is NetworkResult.Success -> {
                        val yaml = r.data.yaml.orEmpty()
                        val effort = Regex("""reasoning_effort:\s*["']?([a-z]+)""")
                            .find(yaml)?.groupValues?.get(1)
                        if (effort != null) {
                            uiState = uiState.copy(currentReasoning = effort)
                        }
                    }
                    else -> {}
                }
            } catch (_: Exception) {}
        }
    }

    // ── YOLO mode (v0.1.4) — session-scoped approval-skip toggle ──
    // Mirrors the WebUI's cmdYolo: session-scoped, not persisted. Loads the
    // current state from the server, and toggles on button press.

    /**
     * Load the current YOLO state for the composer button.
     *
     * The authoritative yolo flag lives in session.info (server-side, updated
     * live by any surface), so the button reads it there — no separate REST
     * call needed. This stub exists to keep the contract; the button's initial
     * and live state is populated in the SessionInfo handler.
     */
    override fun loadYoloStatus(desiredEnabled: Boolean) {
        // no-op — yoloEnabled is populated from session.info (see
        // RpcNotification.SessionInfo handler)
    }

    /**
     * Toggle YOLO mode on/off for this session.
     *
     * Optimistic: the button flips immediately so the user always gets feedback.
     * YOLO is session-scoped and not persisted. The toggle is routed through the
     * existing `/yolo` slash command (gateway slash infra) — no new dashboard
     * route needed for the write. The slash output ("YOLO mode enabled/disabled")
     * renders in chat as confirmation, and the button re-reads state on next open.
     */
    override fun setYolo(enabled: Boolean) {
        if (sessionId.isEmpty()) return
        // Flip immediately — the pill must respond the instant it's tapped.
        uiState = uiState.copy(yoloEnabled = enabled)
        sendMessage("/yolo")
    }

    /** Whether the chat screen is currently visible (background-turn tracking). */
    private var screenVisible = true

    override fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        // If the turn finished while away, the banner shows on re-entry
        if (visible && uiState.completedWhileAway) {
            DebugLog.log("RPC", "DashboardChat", "re-entered chat — completedWhileAway banner shown")
        }
        // Re-fetch live context on every chat open (v0.1.63): the one-time
        // resume snapshot can miss real context after a server-side agent
        // rebuild (e.g. auto-compression), and per-turn session.info events
        // aren't guaranteed to reach this client (single-owner transport).
        // A lightweight resume (messages omitted) gets us usage fresh.
        // v0.1.69: keep retrying (burst then 5s poll) until the gauge has
        // real data — the server always has it for a live session; a null
        // reading is just a transient rebuild/compression window.
        if (visible && sessionId.isNotEmpty()) {
            viewModelScope.launch {
                var attempts = 0
                // v0.1.87: poll CONTINUOUSLY while the chat is open — burst
                // (2s) until first data, then every 30s to keep the gauge live.
                // session.info events aren't guaranteed to reach this client
                // (single-owner transport — the desktop usually owns the live
                // stream), so without this the gauge freezes at the last
                // reading or "—/—" forever. One lightweight resume per 30s is
                // trivial while the screen is open.
                while (screenVisible) {
                    attempts++
                    try {
                        val result = rpcClient.sessionResume(sessionId, omitMessages = true)
                        val ctx = parseContextUsage(result.info)
                        if (ctx.first != null || ctx.second != null) {
                            uiState = uiState.copy(
                                contextUsed = ctx.first ?: uiState.contextUsed,
                                contextMax = ctx.second ?: uiState.contextMax,
                            )
                        }
                        // v0.1.88: keep the model chip fresh on every poll
                        val m = result.info?.get("model")?.jsonPrimitive?.contentOrNull
                        val r = result.info?.get("reasoning_effort")?.jsonPrimitive?.contentOrNull
                        if ((!m.isNullOrBlank() && m != uiState.currentModel) ||
                            (!r.isNullOrBlank() && r != uiState.currentReasoning)) {
                            uiState = uiState.copy(
                                currentModel = m ?: uiState.currentModel,
                                currentReasoning = r?.takeIf { it.isNotBlank() }
                                    ?: uiState.currentReasoning,
                            )
                        }
                    } catch (_: Exception) {
                        // Best-effort — loop back and try again.
                    }
                    delay(if (attempts <= 3) 2_000 else 30_000)
                }
            }
        }
    }

    override fun clearCompletedWhileAway() {
        if (uiState.completedWhileAway) {
            uiState = uiState.copy(completedWhileAway = false)
        }
    }

    /**
     * Called when a turn finishes (message.completed / run.completed). If the
     * chat screen wasn't visible at that moment, flag it for the re-entry banner.
     */
    private fun onTurnFinished() {
        if (!screenVisible && uiState.isStreaming) {
            DebugLog.log("RPC", "DashboardChat", "turn finished while screen away — flagging banner")
            uiState = uiState.copy(completedWhileAway = true)
        }
    }

    companion object {
        /**
         * Extract live context-window occupancy from a session.info JSON payload.
         * The server's session.info carries `usage: {context_used, context_max, ...}`
         * (current window occupancy, NOT cumulative lifetime tokens). Returns
         * (used, max); either side may be null when the server hasn't reported it.
         */
        private fun parseContextUsage(info: JsonObject?): Pair<Long?, Long?> {
            if (info == null) return null to null
            return try {
                val usage = info["usage"]?.jsonObject ?: return null to null
                val used = usage["context_used"]?.jsonPrimitive?.longOrNull
                val max = usage["context_max"]?.jsonPrimitive?.longOrNull
                used to max
            } catch (_: Exception) {
                null to null
            }
        }

        /** Parse `{"todos": [{id, content, status}, ...]}` from a tool.complete result. */
        private fun parseTodos(element: JsonElement?): List<UiTodo>? {
            if (element == null) return null
            return try {
                val arr = element.jsonObject["todos"]?.jsonArray ?: return null
                val todos = arr.mapNotNull { item ->
                    val obj = item.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    UiTodo(
                        id = id,
                        content = obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
                        status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "pending",
                    )
                }
                todos.ifEmpty { null }
            } catch (_: Exception) {
                null
            }
        }

        /** Parse todos from a raw JSON string (tool message `context` in history). */
        private fun parseTodosFromString(raw: String?): List<UiTodo>? {
            if (raw.isNullOrBlank()) return null
            return try {
                parseTodos(Json.parseToJsonElement(raw))
            } catch (_: Exception) {
                null
            }
        }
    }
}
