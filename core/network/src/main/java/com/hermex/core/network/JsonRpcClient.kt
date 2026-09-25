package com.hermex.core.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * JSON-RPC 2.0 client over WebSocket.
 *
 * Request/response correlation: every outgoing request gets a unique id.
 * Incoming frames with a matching "id" complete the pending CompletableDeferred.
 * Frames with "method" but no "id" are server-pushed notifications — routed
 * via [notifications] Flow.
 *
 * Usage:
 *   val client = JsonRpcClient(connectionManager)
 *   client.start()  // begins consuming frames from WS
 *
 *   val sessions: SessionListResult = client.request("session.list")
 *   client.request("prompt.submit", mapOf("session_id" to sid, "text" to "hello"))
 *
 *   client.notifications.collect { event -> ... }
 */
class JsonRpcClient(
    @PublishedApi internal val connection: WsConnectionManager,
    @PublishedApi internal val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    @PublishedApi
    internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Whether the underlying WS connection is currently live. Exposed so callers
     * (e.g. SessionsViewModel) can reuse a healthy observer socket instead of
     * opening a fresh one per request. */
    val isConnected: Boolean get() = connection.isConnected

    @PublishedApi
    internal val requestCounter = AtomicLong(1)
    @PublishedApi
    internal val pendingRequests = ConcurrentHashMap<Long, kotlinx.coroutines.CancellableContinuation<JsonElement>>()

    // Separate channel for notifications to avoid backpressure on the WS message flow
    private val notificationChannel = Channel<RpcNotification>(UNLIMITED)
    /** Server-pushed events (message.delta, tool.started, approval.request, etc.). */
    val notifications: Flow<RpcNotification> = notificationChannel.receiveAsFlow()

    /**
     * Server→client JSON-RPC requests: the backend asks a question and waits for one
     * response frame carrying the same id. Approvals (id `srq-<hex>`), clarify, sudo,
     * secret, vault prompts all arrive this way — NOT as notifications. The client must
     * send back `{jsonrpc:"2.0", id:<same>, result:{...}}`. Without a handler the frame
     * falls through to "Unknown" and the gateway stalls for the full deadline.
     */
    data class ServerRequest(
        val id: String,
        val method: String,
        val params: JsonObject,
    )

    private val serverRequestChannel = Channel<ServerRequest>(UNLIMITED)
    val serverRequests: Flow<ServerRequest> = serverRequestChannel.receiveAsFlow()

    private var consumerJob: Job? = null

    // ── Lifecycle ──

    /** Start consuming WS frames. Must be called after [connection.connect()]. */
    fun start() {
        if (consumerJob?.isActive == true) return
        Log.d("Hermex", "JsonRpcClient.start()")
        DebugLog.log("RPC", "Client", "start — consuming WS frames")
        consumerJob = scope.launch {
            connection.messages.collect { rawFrame ->
                processFrame(rawFrame)
            }
        }
    }

    /** Stop consuming. Pending requests are NOT cancelled — they'll timeout naturally. */
    fun stop() {
        consumerJob?.cancel()
        consumerJob = null
        Log.d("Hermex", "JsonRpcClient.stop()")
    }

    // ── Request/Response ──

    /**
     * Send a JSON-RPC request and await the response.
     *
     * @param method  e.g. "session.list", "prompt.submit"
     * @param params  Named parameters (nulls filtered out)
     * @return  Deserialized result
     * @throws JsonRpcException  on JSON-RPC error response
     * @throws Exception  on timeout, connection loss, or parse failure
     */
    @PublishedApi
    internal suspend inline fun <reified T> request(
        method: String,
        params: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = 30_000,
    ): T {
        val id = requestCounter.getAndIncrement()

        // Build params as JsonObject
        val paramsObj = buildJsonObject {
            params.filterValues { it != null }.forEach { (k, v) ->
                when (v) {
                    is String -> put(k, JsonPrimitive(v))
                    is Number -> put(k, JsonPrimitive(v.toString()))
                    is Boolean -> put(k, JsonPrimitive(v))
                    is JsonElement -> put(k, v)
                    else -> put(k, JsonPrimitive(v.toString()))
                }
            }
        }
        val paramsStr = json.encodeToString(JsonObject.serializer(), paramsObj)

        val requestJson = buildString {
            append("{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\",\"params\":$paramsStr}")
        }

        DebugLog.log("RPC", "Request", "[$id] $method — params=$paramsStr")
        Log.d("Hermex", "JsonRpcClient.request: [$id] $method — params=$paramsStr")

        return suspendCancellableCoroutine { cont ->
            pendingRequests[id] = cont

            // Send the request
            connection.send(requestJson)

            // Timeout handler
            val timeoutJob = scope.launch {
                kotlinx.coroutines.delay(timeoutMs)
                if (pendingRequests.remove(id) != null) {
                    DebugLog.log("RPC", "Timeout", "[$id] $method — timed out after ${timeoutMs}ms")
                    cont.resumeWithException(
                        JsonRpcException(-1, "Request [$id] $method timed out after ${timeoutMs}ms")
                    )
                }
            }

            cont.invokeOnCancellation {
                pendingRequests.remove(id)
                timeoutJob.cancel()
            }
        }.let { element ->
            json.decodeFromJsonElement<T>(element)
        }
    }

    // ── Notification (no response expected) ──

    /**
     * Send a JSON-RPC notification (no "id" field — no response expected).
     * Used for fire-and-forget calls like approval.respond.
     */
    fun notify(method: String, params: Map<String, Any?> = emptyMap()) {
        val paramsFiltered = params.filterValues { it != null }
        val paramsStr = if (paramsFiltered.isNotEmpty()) {
            paramsFiltered.entries.joinToString(",") { (k, v) ->
                val valStr = when (v) {
                    is String -> "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                    is Number -> v.toString()
                    is Boolean -> v.toString()
                    else -> "\"$v\""
                }
                "\"$k\":$valStr"
            }.let { "{$it}" }
        } else {
            "{}"
        }
        val msg = """{"jsonrpc":"2.0","method":"$method","params":$paramsStr}"""
        connection.send(msg)
        DebugLog.log("RPC", "Notify", method)
    }

    // ── Frame processing ──

    private fun processFrame(raw: String) {
        try {
            val frame = json.parseToJsonElement(raw).jsonObject

            val rawId = frame["id"]?.jsonPrimitive?.content
            // Outgoing requests use numeric ids (from requestCounter) so responses complete via
            // handleResponse/handleError. Server→client questions use a string id (`srq-<hex>`);
            // toLongOrNull() returns null for those, so we keep the raw string to route them to
            // serverRequests instead of mistaking them for notifications (which have no id).
            val numericId = rawId?.toLongOrNull()
            // v0.1.137 — envelope-tolerant notification detection. The dashboard
            // emits notifications with the method under `method`, but a few events
            // (notably some completion events) have arrived under `event` or with
            // an empty/absent `params`. Recognize either key so a valid event can
            // never silently fall through to the "unrecognized" branch and get
            // dropped — a dropped message.completed/run.completed is exactly what
            // strands a spinner on a short turn. `result`/`error` still win for
            // request/response correlation.
            val method = frame["method"]?.jsonPrimitive?.content
                ?: frame["event"]?.jsonPrimitive?.content
            val result = frame["result"]
            val error = frame["error"]
            val params = frame["params"]?.jsonObject

            when {
                // Response to one of our requests
                numericId != null && result != null -> {
                    handleResponse(numericId, result)
                }
                // Error response
                numericId != null && error != null -> {
                    handleError(numericId, error.jsonObject)
                }
            // Server→client request (string id like `srq-<hex>`, a method, but no result/error yet):
            // the backend is asking a question and waiting for one response frame on this same id.
            // Route to serverRequests so a handler can answer via respondRequest(...). Without this
            // it falls through to "Unknown" and the gateway stalls out the full deadline (approval/clarify/sudo fail).
                rawId != null && numericId == null && method != null && result == null && error == null -> {
                    serverRequestChannel.trySend(
                        ServerRequest(id = rawId, method = method, params = params ?: JsonObject(emptyMap()))
                    )
                }
            // Server-pushed notification (no id, has method)
                numericId == null && method != null && params != null -> {
                    handleNotification(method, params)
                }
            // Server-pushed notification with method at top level
                numericId == null && method != null -> {
                    handleNotification(method, params ?: JsonObject(emptyMap()))
                }
                else -> {
                    DebugLog.log("RPC", "Unknown", "unrecognized frame shape: ${raw.take(200)}")
                    Log.w("Hermex", "JsonRpcClient: unrecognized frame: ${raw.take(200)}")
                }
            }
        } catch (e: Exception) {
            DebugLog.log("RPC", "ParseError", "failed to parse frame: ${e.message} — raw: ${raw.take(200)}")
            Log.e("Hermex", "JsonRpcClient: parse error", e)
        }
    }

    private fun handleResponse(id: Long, result: JsonElement) {
        val cont = pendingRequests.remove(id)
        if (cont != null) {
            DebugLog.log("RPC", "Response", "[$id] resolved")
            cont.resume(result)
        } else {
            DebugLog.log("RPC", "Response", "[$id] no pending request (stale/timed out)")
        }
    }

    private fun handleError(id: Long, error: JsonObject) {
        val code = error["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        val message = error["message"]?.jsonPrimitive?.content ?: "Unknown error"
        val cont = pendingRequests.remove(id)
        if (cont != null) {
            DebugLog.log("RPC", "Error", "[$id] code=$code message=$message")
            cont.resumeWithException(JsonRpcException(code, message))
        } else {
            DebugLog.log("RPC", "Error", "[$id] no pending request — $message")
        }
    }

    // ── Notification parser ──

    private fun handleNotification(method: String, params: JsonObject) {
        val eventType = params["type"]?.jsonPrimitive?.content ?: method
        val sessionId = params["session_id"]?.jsonPrimitive?.content
            ?: params["sessionId"]?.jsonPrimitive?.content

        val notification = when (eventType) {
            "gateway.ready" -> RpcNotification.GatewayReady(
                agentId = params["agent_id"]?.jsonPrimitive?.content,
                version = params["version"]?.jsonPrimitive?.content,
                sessionId = sessionId,
            )

            "message.delta" -> {
                val payload = params["payload"]?.jsonObject
                val text = payload?.get("text")?.jsonPrimitive?.content
                    ?: payload?.get("rendered")?.jsonPrimitive?.content ?: ""
                val pps = payload?.get("timings")?.jsonObject
                    ?.get("predicted_per_second")?.jsonPrimitive?.content?.toFloatOrNull()
                    ?: payload?.get("predicted_per_second")?.jsonPrimitive?.content?.toFloatOrNull()
                RpcNotification.MessageDelta(sessionId ?: "", text, pps)
            }

            "message.start", "message.started" -> {
                val payload = params["payload"]?.jsonObject
                RpcNotification.MessageStarted(
                    sessionId = sessionId,
                    messageId = payload?.get("id")?.jsonPrimitive?.content
                        ?: params["message_id"]?.jsonPrimitive?.content,
                )
            }

            "reasoning.available" -> RpcNotification.ReasoningAvailable(sessionId = sessionId)

            "thinking.delta" -> {
                val payload = params["payload"]?.jsonObject
                val text = payload?.get("text")?.jsonPrimitive?.content ?: ""
                RpcNotification.ThinkingDelta(sessionId ?: "", text)
            }

            "reasoning.delta" -> {
                val payload = params["payload"]?.jsonObject
                val text = payload?.get("text")?.jsonPrimitive?.content ?: ""
                RpcNotification.ReasoningDelta(sessionId ?: "", text)
            }

            "tool.generating" -> {
                val payload = params["payload"]?.jsonObject
                RpcNotification.ToolGenerating(
                    sessionId = sessionId ?: "",
                    toolName = payload?.get("name")?.jsonPrimitive?.content ?: "unknown",
                )
            }

            "tool.start" -> {
                val payload = params["payload"]?.jsonObject
                RpcNotification.ToolStart(
                    sessionId = sessionId ?: "",
                    toolId = payload?.get("tool_id")?.jsonPrimitive?.content ?: "",
                    toolName = payload?.get("name")?.jsonPrimitive?.content ?: "unknown",
                    context = payload?.get("context")?.jsonPrimitive?.content,
                )
            }

            "tool.complete" -> {
                val payload = params["payload"]?.jsonObject
                RpcNotification.ToolComplete(
                    sessionId = sessionId ?: "",
                    toolId = payload?.get("tool_id")?.jsonPrimitive?.content ?: "",
                    toolName = payload?.get("name")?.jsonPrimitive?.content ?: "unknown",
                    args = payload?.get("args"),
                    result = payload?.get("result"),
                    summary = payload?.get("summary")?.jsonPrimitive?.content,
                    inlineDiff = payload?.get("inline_diff")?.jsonPrimitive?.content,
                )
            }

            "run.started" -> RpcNotification.RunStarted(sessionId ?: "")

            "run.completed" -> RpcNotification.RunCompleted(sessionId ?: "")

            "message.completed", "assistant.completed", "message.complete" -> {
                // Try payload first (Dashboard WS convention), fall back to message (REST SSE convention)
                val msgObj = params["payload"]?.jsonObject ?: params["message"]?.jsonObject
                val toolCalls = msgObj?.get("tool_calls")?.let { tcArray ->
                    json.decodeFromJsonElement<List<RpcNotification.ToolCallInfo>>(tcArray)
                }
                val usage = params["usage"]?.let {
                    json.decodeFromJsonElement<RpcNotification.UsageInfo>(it)
                }
                RpcNotification.MessageCompleted(
                    sessionId = sessionId ?: "",
                    messageId = msgObj?.get("id")?.jsonPrimitive?.content,
                    content = msgObj?.get("content")?.jsonPrimitive?.content,
                    toolCalls = toolCalls,
                    usage = usage,
                )
            }

            "approval.request" -> {
                // Server wraps approval fields under params["payload"] (dashboard WS
                // convention) — NOT at params top level. See references/approval-protocol.md.
                val ap = params["payload"]?.jsonObject
                val choicesArr = ap?.get("choices") as? JsonArray
                RpcNotification.ApprovalRequest(
                    sessionId = sessionId ?: "",
                    sessionKey = params["session_key"]?.jsonPrimitive?.content ?: "",
                    toolName = ap?.get("command")?.jsonPrimitive?.content,
                    args = ap?.get("args"),
                    command = ap?.get("command")?.jsonPrimitive?.content,
                    description = ap?.get("description")?.jsonPrimitive?.content,
                    choices = choicesArr?.mapNotNull { (it as? JsonPrimitive)?.content }
                        ?.filter { it.isNotBlank() },
                )
            }

            "clarify.request" -> {
                // Server wraps clarify fields under params["payload"] (dashboard WS
                // convention) — NOT at params top level. Same as approval.request.
                // Payload shape: {request_id, question, choices?, multi_select?} for
                // a single question, or {questions: [{qid, question, choices,
                // multi_select}], request_id} for a batch. See server.py _clarify_block.
                val cp = params["payload"]?.jsonObject
                val requestId = cp?.get("request_id")?.jsonPrimitive?.content
                    ?: params["request_id"]?.jsonPrimitive?.content
                    ?: ""
                // Single-question path: question + choices at payload top level.
                val q = cp?.get("question")?.jsonPrimitive?.content
                val choicesArr = cp?.get("choices") as? JsonArray
                val choices = choicesArr?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?.filter { it.isNotBlank() }
                // Batch path: a list of questions, each with qid/question/choices.
                val questionsArr = cp?.get("questions") as? JsonArray
                val questions = questionsArr?.mapNotNull { el ->
                    (el as? JsonObject)?.let { qo ->
                        RpcNotification.ClarifyQuestion(
                            qid = qo["qid"]?.jsonPrimitive?.content ?: "",
                            question = qo["question"]?.jsonPrimitive?.content ?: "",
                            choices = (qo["choices"] as? JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                                ?.filter { it.isNotBlank() } ?: emptyList(),
                            multiSelect = qo["multi_select"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                        )
                    }
                }
                RpcNotification.ClarifyRequest(
                    sessionId = sessionId ?: "",
                    requestId = requestId,
                    question = q,
                    choices = choices,
                    questions = questions,
                )
            }

            "session.info" -> RpcNotification.SessionInfo(
                sessionId = sessionId ?: "",
                // Server emits _event_frame("session.info", sid, info) → the
                // info dict (with usage.context_used/context_max) is in
                // params.payload, NOT params itself. Passing `params` here made
                // parseContextUsage always miss → the context gauge never
                // live-updated after an app restart.
                info = params["payload"]?.jsonObject ?: params,
            )

            "sessions.changed" -> RpcNotification.SessionChanged(
                sessionId = sessionId,
            )

            else -> RpcNotification.Unknown(
                eventType = eventType,
                rawParams = params,
                sessionId = sessionId,
            )
        }

        // ClarifyRequest: pass through to ViewModel for user response
        // (auto-deny was removed in Phase 5E — user now gets a dialog)
        if (notification is RpcNotification.ClarifyRequest) {
            DebugLog.log("RPC", "Clarify", "forwarding to ViewModel: requestId=${notification.requestId}")
        }

        notificationChannel.trySend(notification)
    }

    // ── v1 convenience methods ──

    @Serializable
    data class SessionListResult(val sessions: List<SessionInfo>)

    @Serializable
    data class CreateSessionResult(
        val session_id: String? = null,
        val stored_session_id: String? = null,
        val message_count: Int? = null,
        val messages: List<MessageData>? = null,
        val info: JsonObject? = null,
        val session: JsonObject? = null,
    )

    @Serializable
    data class DeleteSessionResult(
        val ok: Boolean = false,
        val session: JsonObject? = null,
    )

    @Serializable
    data class SlashCompletionResult(val items: List<SlashItem> = emptyList())

    /** One slash-command completion from complete.slash. */
    @Serializable
    data class SlashItem(
        val text: String = "",
        val display: String? = null,
        val meta: String? = null,
        val kind: String? = null,  // "command" | "skill"
    )

    @Serializable
    data class SessionInfo(
        val id: String,
        val title: String? = null,
        // Server sends started_at/ended_at as epoch seconds (Double), NOT ISO strings.
        @SerialName("started_at") val started_at: Double? = null,
        @SerialName("ended_at") val ended_at: Double? = null,
        val message_count: Int? = null,
        val model: String? = null,
        val source: String? = null,
        val preview: String? = null,
        @SerialName("input_tokens") val input_tokens: Long? = null,
        @SerialName("output_tokens") val output_tokens: Long? = null,
        val is_active: Boolean? = null,
    )

    @Serializable
    data class SessionResumeResult(
        val session_id: String,
        val resumed: String? = null,       // original DB session key returned by server
        val session_key: String? = null,    // stable lookup key returned by server
        val message_count: Int? = null,
        val running: Boolean? = null,       // true if a turn is actively streaming server-side
        val turn_started_at: Double? = null,
        val messages: List<MessageData>? = null,
        val info: JsonObject? = null,
        val session: JsonObject? = null,
    )

    @Serializable
    data class MessageData(
        val id: String? = null,
        val role: String? = null,
        val content: String? = null,
        val text: String? = null,  // server sends "text" field, not "content"
        val name: String? = null,  // tool-result messages have "name"
        val context: String? = null,  // tool-result messages have "context"
        val reasoning: String? = null,  // assistant messages carry thinking here
        val reasoning_content: String? = null,  // (alias used by some models)
        val created_at: String? = null,
        val tool_calls: List<RpcNotification.ToolCallInfo>? = null,
    ) {
        /** Resolve message text from any known field. */
        val resolvedContent: String?
            get() = content ?: text ?: context

        /** Thinking/reasoning text for assistant messages (v0.1.73). */
        val resolvedThinking: String?
            get() = reasoning_content ?: reasoning
    }

    @Serializable
    data class PromptSubmitResult(
        val session_id: String? = null,
        val turn_id: String? = null,
        val status: String? = null,
    )

    suspend fun sessionList(): List<SessionInfo> {
        val result: SessionListResult = request("session.list")
        return result.sessions
    }

    /** Create a new session. Returns the live session id (usable with session.resume). */
    suspend fun createSession(model: String? = null, reasoningEffort: String? = null): String {
        DebugLog.log("RPC", "JsonRpc", "session.create (model=$model effort=$reasoningEffort)")
        val params = mutableMapOf<String, Any>("source" to "api")
        if (!model.isNullOrBlank()) params["model"] = model
        if (!reasoningEffort.isNullOrBlank()) params["reasoning_effort"] = reasoningEffort
        val result: CreateSessionResult = request(
            "session.create",
            params,
        )
        return result.session_id
            ?: throw JsonRpcException(-1, "session.create returned no session_id")
    }

    @Serializable
    data class ModelProviderRow(
        val slug: String = "",
        val name: String? = null,
        val models: List<String> = emptyList(),
    )

    @Serializable
    data class ModelOptionsResult(
        val model: String? = null,
        val provider: String? = null,
        val providers: List<ModelProviderRow> = emptyList(),
    )

    /** Model picker data (v0.1.88): current model + provider/model list. */
    suspend fun modelOptions(): ModelOptionsResult {
        val result: ModelOptionsResult = request(
            "model.options",
            mapOf("explicit_only" to true),
        )
        return result
    }

    /**
     * Slash-command completion (v0.1.65). Params: {text: "/..."}. Server ranks
     * and filters via SlashCommandCompleter — returns matching commands/skills.
     */
    suspend fun completeSlash(text: String): List<SlashItem> {
        val result: SlashCompletionResult = request("complete.slash", mapOf("text" to text))
        return result.items
    }

    /** Execute a slash command (v0.1.67) — same path the desktop/TUI use.
     *  Long timeout: /compress on a big session can take minutes. */
    suspend fun slashExec(sessionId: String, command: String): JsonObject {
        DebugLog.log("RPC", "JsonRpc", "slash.exec: $command")
        return request(
            "slash.exec",
            mapOf("session_id" to sessionId, "command" to command),
            timeoutMs = 180_000,
        )
    }

    /**
     * Route a command through command.dispatch (v0.1.99) — the server's path
     * for skill/bundle commands that slash.exec rejects with 4018
     * ("use command.dispatch"). Returns the same shapes as slash.exec
     * ({"type":"send"|"skill","message":...}, {"output":...}, …).
     *
     * NOTE: `sessionId` MUST be the LIVE SID (8-hex from session.resume /
     * session.create), NOT the persistent DB key — command.dispatch resolves
     * `_sessions` directly with no DB-key fallback (same rule as config.set).
     */
    suspend fun commandDispatch(sessionId: String, name: String, arg: String = ""): JsonObject {
        DebugLog.log("RPC", "JsonRpc", "command.dispatch $name${if (arg.isNotBlank()) " $arg" else ""}")
        return request(
            "command.dispatch",
            mapOf("session_id" to sessionId, "name" to name, "arg" to arg),
            timeoutMs = 60_000,
        )
    }

    /**
     * Set a per-session config key (v0.1.91) — the desktop's model/reasoning
     * switch path. `sessionId` MUST be the LIVE SID (8-hex from session.resume /
     * session.create), NOT the persistent DB key: `config.set` resolves
     * `_sessions` directly with no DB-key fallback. Unlike slash.exec, this
     * handles busy sessions (model switch is deferred to the next turn) and
     * agent-less fresh sessions (agent is built on demand).
     */
    suspend fun configSet(sessionId: String, key: String, value: String): JsonObject {
        DebugLog.log("RPC", "JsonRpc", "config.set $key=$value")
        return request(
            "config.set",
            mapOf("session_id" to sessionId, "key" to key, "value" to value),
            timeoutMs = 60_000,
        )
    }

    /**
     * Stage an image into the session (base64 bytes → gateway images dir).
     * The NEXT prompt.submit carries the staged image automatically.
     * Returns the server response (contains the image path + placeholder text).
     */
    suspend fun attachImage(sessionId: String, contentBase64: String, filename: String? = null): JsonObject {
        DebugLog.log("RPC", "JsonRpc", "image.attach_bytes (${contentBase64.length} b64 chars)")
        val params = mutableMapOf<String, Any>(
            "session_id" to sessionId,
            "content_base64" to contentBase64,
        )
        if (!filename.isNullOrBlank()) params["filename"] = filename
        return request("image.attach_bytes", params)
    }

    /** Stage a non-image file into the session workspace via `file.attach`. */
    suspend fun attachFile(sessionId: String, dataUrl: String, name: String? = null): JsonObject {
        DebugLog.log("RPC", "JsonRpc", "file.attach (${dataUrl.length} chars)")
        val params = mutableMapOf<String, Any>(
            "session_id" to sessionId,
            "data_url" to dataUrl,
        )
        if (!name.isNullOrBlank()) params["name"] = name
        return request("file.attach", params)
    }

    /** Read a profile's SOUL.md (Agent Soul) content. */
    suspend fun profileDescribe(name: String): JsonObject =
        request("profiles.describe", mapOf("name" to name))

    /** Save a full SOUL.md replacement for a profile via `profiles.configure`. */
    suspend fun profileConfigure(name: String, soul: String): JsonObject =
        request("profiles.configure", mapOf("name" to name, "soul" to soul))

    /** Steer the next tool call of the live turn (non-interrupting). */
    suspend fun sessionSteer(sessionId: String, text: String): JsonObject =
        request("session.steer", mapOf("session_id" to sessionId, "text" to text))

    suspend fun sessionResume(sessionId: String, omitMessages: Boolean = false): SessionResumeResult {
        DebugLog.log("STATE", "SessionID",
            "sessionResume called with sessionId=$sessionId omitMessages=$omitMessages")
        val params = mutableMapOf<String, Any>("session_id" to sessionId)
        if (omitMessages) params["omit_messages"] = true
        val result: SessionResumeResult = request("session.resume", params, timeoutMs = 60_000)
        DebugLog.log("STATE", "SessionID",
            "sessionResume result: session_id=${result.session_id} " +
            "resumed=${result.resumed} session_key=${result.session_key} " +
            "message_count=${result.message_count}")
        return result
    }

    suspend fun promptSubmit(sessionId: String, text: String): PromptSubmitResult =
        request("prompt.submit", mapOf("session_id" to sessionId, "text" to text), timeoutMs = 120_000)

    suspend fun sessionInterrupt(sessionId: String): JsonObject =
        request("session.interrupt", mapOf("session_id" to sessionId))

    /** Delete a session server-side. Returns the deleted session id. */
    suspend fun sessionDelete(sessionId: String): String {
        val result: DeleteSessionResult = request(
            "session.delete",
            mapOf("session_id" to sessionId),
        )
        return result.session?.get("session_id")?.jsonPrimitive?.content ?: sessionId
    }

    /** Answer a server→client request with one JSON-RPC response frame carrying the
     * same id. The gateway's resolve_response() matches on `id` and reads `result`,
     * so this must be `{jsonrpc:"2.0", id:<same>, result:{...}}`. Use for approvals,
     * clarify, sudo, secret, vault prompts — anything the backend asked a question
     * about over the socket (NOT fire-and-forget notifications). [result] is the body
     * of the `result` member (for an error response, pass `{code, message}`); callers
     * that need to emit an error object directly can build it here.
     */
    fun respondRequest(id: String, result: JsonObject) {
        val frame = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            if (result.isNotEmpty()) putJsonObject("result") { result.forEach { (k, v) -> put(k, v) } }
        }
        connection.send(frame.toString())
        DebugLog.log("RPC", "Response", "[$id] answered")
    }

    /** Respond to a tool approval request.
     * Server expects session_id (DB key), choice ("approve"|"deny"), and optional all.
     * Uses respondRequest() so the gateway's server→client request resolves on its id —
     * NOT notify(), which leaves the request open and the gateway stalls for the deadline.
     */
    fun approvalRespond(sessionId: String, choice: String, requestId: String?, all: Boolean = false) {
        val result = buildJsonObject {
            put("choice", JsonPrimitive(choice))
            if (all) put("all", JsonPrimitive(true))
            // session_id is part of the request params on the wire; echo it so the
            // gateway can correlate the answer to the right session queue.
            if (sessionId.isNotBlank()) put("session_id", JsonPrimitive(sessionId))
        }
        val reqId = requestId ?: ""
        connection.send(buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(reqId.ifBlank { "approval" }))
            putJsonObject("result") { result.forEach { (k, v) -> put(k, v) } }
        }.toString())
        DebugLog.log("RPC", "Approval", "responded choice=$choice requestId=$reqId all=$all")
    }

    /** Respond to a clarify request.
     * Server expects request_id and answer.
     * Uses respondRequest() so the gateway's server→client request resolves on its id.
     */
    fun clarifyRespond(requestId: String, answer: String) {
        connection.send(buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(requestId))
            putJsonObject("result") { put("answer", JsonPrimitive(answer)) }
        }.toString())
        DebugLog.log("RPC", "Clarify", "responded requestId=$requestId")
    }
}

// ── Helpers ──

/** JSON-RPC error response. */
class JsonRpcException(val code: Int, message: String) : Exception("JSON-RPC error $code: $message")
