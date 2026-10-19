package com.hermex.v3.feature.chat.replay

import android.app.Application
import android.os.Looper
import com.hermex.core.network.DebugLog
import com.hermex.core.network.JsonRpcClient
import com.hermex.core.network.WsConnectionManager
import com.hermex.v3.di.AppModule
import com.hermex.v3.feature.chat.DashboardChatViewModel
import com.hermex.v3.feature.chat.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import okhttp3.WebSocket
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.time.Duration

/**
 * JVM frame-replay testbed for the D/14 blank-row investigation (P2).
 *
 * What is REAL here:
 *  - DashboardChatViewModel itself, constructed with the app-wide AppModule
 *    singletons (WsConnectionManager + JsonRpcClient), exactly like production;
 *  - its connectWsAndStart() wiring: the ws-state collector, the notification
 *    collector (rpcClient.notifications.collect { handleNotification(it) }), and
 *    loadMessages()/session.resume — all run as written in production;
 *  - JsonRpcClient.processFrame(): the real frame parser, seq watermarking and
 *    SharedFlow fan-out that every registered VM's collector receives;
 *  - the reconnect reconcile gate (DashboardChatViewModel ~line 1339) including
 *    sessionResume(omitMessages=true), lastSeenSeqFor(), sessionEventsSince() and
 *    replayEvents() — driven by feeding response frames back through processFrame.
 *
 * What is SIMULATED at the transport boundary (and only there):
 *  - OkHttp sockets / ticket fetch: none are opened. WsConnectionManager._state is
 *    driven directly (Connected -> Reconnecting -> Connecting -> Connected) which is
 *    exactly what the ViewModel's state collector observes when a socket dies and a
 *    new generation opens. Outgoing request frames are captured via a fake
 *    WebSocket installed on the manager (its send() path is the production one).
 *  - "Socket generation" tags in the write log are the testbed's own gen counter
 *    (incremented per injected reconnect); WsConnectionManager.generation is not
 *    exercised because no real socket is opened.
 *  - Frames are delivered straight into JsonRpcClient.processFrame — the same call
 *    the live-frame consumer makes — instead of through the raw WS receive channel.
 *
 * The gateway side is scripted from [ReplayStream]: session.resume answers carry the
 * server history faithful to the moment (mid-turn: user row only, assistant NOT
 * persisted; after complete: both), session.events.since answers replay exactly the
 * frames with seq > last_seen (ring intact) or return truncated+empty (ring lost).
 */
class ReplayTestbed(private val app: Application, private val outDir: File) {

    enum class Mode { NONE, RECONNECT, SECOND_VM, SECOND_VM_SID_ROTATION }
    enum class RingMode { INTACT, TRUNCATED_EMPTY }

    data class TrialConfig(
        val mode: Mode,
        val ringMode: RingMode,
        val injectAfter: Int,           // inject after frame idx (ignored for Mode.NONE)
        val detailedTags: Boolean = false,
        // Test-only stream overrides for focused (fast) trials:
        /** Use this frame list instead of the full 424-frame stream. */
        val frames: List<ReplayStream.Frame>? = null,
        /** Rewrite params.session_id to [overrideSid] on every frame with idx >= this. */
        val overrideSidFromIdx: Int = -1,
        val overrideSid: String? = null,
    )

    data class RowObservation(
        val present: Boolean,
        val rowId: String,
        val contentLen: Int,
        val thinkingLen: Int,
        val streaming: Boolean,
        val toolCards: Int,
        val totalRows: Int = 0,
        val assistantRows: Int = 0,
    )

    data class TrialResult(
        val cfg: TrialConfig,
        val genAtEnd: Int,
        val blankA: Boolean,
        val obsA: RowObservation,
        val blankB: Boolean?,          // null when no second VM in this trial
        val obsB: RowObservation?,
        val gateLines: List<String>,  // TRACE/gate + replay_decision lines from DebugLog
        val filteredCount: Int,       // notification FILTERED lines seen by any VM
        val servedCompleteNonEmpty: Boolean,
        // P5 instrumentation evidence (A2/A4): the journal lines a fix must produce.
        val dispLines: List<String> = emptyList(),
        val identityLines: List<String> = emptyList(),
        val frameLines: List<String> = emptyList(),
    )

    private val spec = ReplayStream.spec()

    // Production singletons the ViewModels use (same objects, same process).
    private val ws = AppModule.provideWsConnection()
    private val rpc = AppModule.provideRpcClient()

    // ── reflection seams into private production members (read/execute only) ──
    private val wsStateField: Field =
        WsConnectionManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
    private val wsWebSocketField: Field =
        WsConnectionManager::class.java.getDeclaredField("webSocket").apply { isAccessible = true }
    private val rpcProcessFrame: Method =
        JsonRpcClient::class.java.getDeclaredMethod("processFrame", String::class.java)
            .apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private val rpcLastSeenSeq: MutableMap<String, Long> =
        JsonRpcClient::class.java.getDeclaredField("lastSeenSeq").apply { isAccessible = true }
            .get(rpc) as MutableMap<String, Long>
    @Suppress("UNCHECKED_CAST")
    private val rpcPendingRequests: MutableMap<Long, *> =
        JsonRpcClient::class.java.getDeclaredField("pendingRequests").apply { isAccessible = true }
            .get(rpc) as MutableMap<Long, *>
    @Suppress("UNCHECKED_CAST")
    private val debugBuffer: Collection<DebugLog.Entry> =
        DebugLog::class.java.getDeclaredField("buffer").apply { isAccessible = true }
            .get(DebugLog) as Collection<DebugLog.Entry>
    private val vmSessionIdField: Field =
        DashboardChatViewModel::class.java.getDeclaredField("sessionId").apply { isAccessible = true }
    private val vmLiveSidField: Field =
        DashboardChatViewModel::class.java.getDeclaredField("liveSid").apply { isAccessible = true }
    private val vmConnectWsAndStart: Method =
        DashboardChatViewModel::class.java.getDeclaredMethod("connectWsAndStart").apply { isAccessible = true }
    private val vmOnCleared: Method =
        DashboardChatViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }

    // ── fake transport: captures every frame the production send() path emits ──
    private inner class CapturingSocket : WebSocket {
        val sent = ArrayDeque<kotlinx.serialization.json.JsonObject>()
        override fun send(text: String): Boolean {
            sent.addLast(kotlinx.serialization.json.Json.parseToJsonElement(text)
                as kotlinx.serialization.json.JsonObject)
            return true
        }
        override fun send(bytes: okio.ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
        override fun queueSize(): Long = 0L
        override fun request(): Request = Request.Builder().url("http://p2.test/ws").build()
    }

    private var socket = CapturingSocket()

    private fun setState(s: WsConnectionManager.State) {
        @Suppress("UNCHECKED_CAST")
        (wsStateField.get(ws) as MutableStateFlow<WsConnectionManager.State>).value = s
    }

    private fun idleAll(maxPasses: Int = 30) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(maxPasses) {
            looper.idle()
            if (looper.isIdle) return
        }
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    // ── write-tag observer: tags every row write with VM instance, row id, source
    //    message id and (simulated) socket generation — external to production code,
    //    derived by diffing each VM's uiState.messages after every frame step. ──
    private inner class Tagger {
        val prev = HashMap<String, List<UiMessage>>()
        val lines = StringBuilder()
        var gen = 1

        fun snapshotAll(vms: Map<String, DashboardChatViewModel>, phase: String, frame: ReplayStream.Frame?) {
            for ((label, vm) in vms) {
                val cur = vm.uiState.messages
                val old = prev[label] ?: emptyList()
                diffInto(label, phase, frame, old, cur)
                prev[label] = cur
            }
        }

        private fun diffInto(label: String, phase: String, frame: ReplayStream.Frame?,
                             old: List<UiMessage>, cur: List<UiMessage>) {
            if (cur.size > old.size) {
                for (i in old.size until cur.size) {
                    tag(label, phase, frame, "ROW_ADDED", i, cur[i].id,
                        "role=${cur[i].role} contentLen=${cur[i].content.length} streaming=${cur[i].isStreaming}")
                }
            } else if (cur.size < old.size) {
                tag(label, phase, frame, "ROWS_REPLACED", -1, "-",
                    "rowCount ${old.size}->${cur.size} idsBefore=${old.map { it.id }}")
            }
            for (i in 0 until minOf(old.size, cur.size)) {
                val a = old[i]; val b = cur[i]
                val changes = ArrayList<String>()
                if (a.id != b.id) changes += "rowId:${a.id}->${b.id}"
                if (a.content != b.content) changes += "content:${a.content.length}ch->${b.content.length}ch"
                if ((a.thinkingText?.length ?: 0) != (b.thinkingText?.length ?: 0))
                    changes += "thinking:${a.thinkingText?.length ?: 0}ch->${b.thinkingText?.length ?: 0}ch"
                if (a.isStreaming != b.isStreaming) changes += "streaming:${a.isStreaming}->${b.isStreaming}"
                if (a.toolCalls.size != b.toolCalls.size) changes += "toolCards:${a.toolCalls.size}->${b.toolCalls.size}"
                if (changes.isNotEmpty()) {
                    tag(label, phase, frame, "ROW_WRITE", i, b.id, changes.joinToString(" "))
                }
            }
        }

        private fun tag(label: String, phase: String, frame: ReplayStream.Frame?, kind: String,
                        rowIdx: Int, rowId: String, detail: String) {
            lines.appendLine(
                "TAG gen=$gen phase=$phase " +
                    "frame=${frame?.idx ?: -1}@seq=${frame?.seq ?: -1}/${frame?.type ?: "-"} " +
                    "vm=$label $kind rowIdx=$rowIdx rowId=$rowId | $detail | " +
                    "srcMsgId=${frame?.messageId ?: "-"}"
            )
        }
    }

    // ── scripted gateway answers ──
    private fun resumeResult(running: Boolean, includeAssistantRow: Boolean): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("session_id", kotlinx.serialization.json.JsonPrimitive(ReplayStream.LIVE_SID))
            put("resumed", kotlinx.serialization.json.JsonPrimitive(ReplayStream.DB_KEY))
            put("session_key", kotlinx.serialization.json.JsonPrimitive(ReplayStream.DB_KEY))
            put("running", kotlinx.serialization.json.JsonPrimitive(running))
            val msgs = ArrayList<kotlinx.serialization.json.JsonObject>()
            msgs += kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive("hist-user-p2"))
                put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                put("content", kotlinx.serialization.json.JsonPrimitive(ReplayStream.PROMPT_TEXT))
            }
            if (includeAssistantRow) {
                msgs += kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(ReplayStream.ASSISTANT_MSG_ID))
                    put("role", kotlinx.serialization.json.JsonPrimitive("assistant"))
                    put("content", kotlinx.serialization.json.JsonPrimitive(ReplayStream.FULL_ANSWER))
                }
            }
            put("messages", kotlinx.serialization.json.JsonArray(msgs))
            put("message_count", kotlinx.serialization.json.JsonPrimitive(msgs.size))
        }

    private fun eventsResult(lastSeen: Long, ring: RingMode): kotlinx.serialization.json.JsonObject {
        val events = if (ring == RingMode.INTACT) {
            spec.frames.filter { it.seq > lastSeen }.map { it.params }
        } else emptyList()
        return kotlinx.serialization.json.buildJsonObject {
            put("events", kotlinx.serialization.json.JsonArray(events))
            put("latest_seq", kotlinx.serialization.json.JsonPrimitive(spec.frames.last().seq))
            put("count", kotlinx.serialization.json.JsonPrimitive(events.size))
            put("truncated", kotlinx.serialization.json.JsonPrimitive(ring == RingMode.TRUNCATED_EMPTY))
            put("epoch", kotlinx.serialization.json.JsonPrimitive("p2-replay-epoch"))
            put("open_requests", kotlinx.serialization.json.JsonArray(emptyList()))
        }
    }

    /** Pump the main looper and answer every request frame the app sent, until quiet. */
    private class DrainOutcome(var sawEventsSince: Boolean = false, var eventsSinceLastSeen: Long = -1)

    private fun drainRequests(drain: DrainOutcome): Int {
        var answered = 0
        repeat(10) {
            idleAll()
            var progressed = false
            while (socket.sent.isNotEmpty()) {
                val req = socket.sent.removeFirst()
                val id = (req["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: continue
                val method = (req["method"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "unknown"
                val params = req["params"] as? kotlinx.serialization.json.JsonObject
                val result: kotlinx.serialization.json.JsonObject = when (method) {
                    "session.resume" -> pendingResumeAnswer
                    "session.events.since" -> {
                        drain.sawEventsSince = true
                        val ls = params?.get("last_seen")
                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() } ?: 0L
                        drain.eventsSinceLastSeen = ls
                        eventsResult(ls, pendingRingMode)
                    }
                    else -> kotlinx.serialization.json.JsonObject(emptyMap())
                }
                val frame = kotlinx.serialization.json.buildJsonObject {
                    put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
                    put("id", kotlinx.serialization.json.JsonPrimitive(id))
                    put("result", result)
                }.toString()
                rpcProcessFrame.invoke(rpc, frame)
                answered++
                progressed = true
            }
            if (!progressed) return@repeat
        }
        // One last pump so continuations' follow-up work (gate, replay) runs.
        idleAll()
        return answered
    }

    // Answers used by drainRequests for the current phase (set before each drain).
    private var pendingResumeAnswer: kotlinx.serialization.json.JsonObject =
        resumeResult(false, false)
    private var pendingRingMode: RingMode = RingMode.INTACT
    // When non-null, the NEXT session.resume answer (a second VM attaching) uses this
    // payload instead — used to hand a second ViewModel a NEW live sid.
    private var pendingResumeOverride: kotlinx.serialization.json.JsonObject? = null
    // Frames at idx >= rotatedFromIdx carry the rotated live sid (SID_ROTATION variant).
    private var rotatedFromIdx: Int = -1

    private fun resumeResultWithSid(sid: String, runningAtAttach: Boolean, includeAssistantRow: Boolean):
            kotlinx.serialization.json.JsonObject {
        val base = resumeResult(runningAtAttach, includeAssistantRow)
        val m = base.toMutableMap()
        m["session_id"] = kotlinx.serialization.json.JsonPrimitive(sid)
        return kotlinx.serialization.json.JsonObject(m)
    }

    // Stream overrides from the current TrialConfig (foreign-sid experiments).
    private var cfgOverrideFromIdx: Int = -1
    private var cfgOverrideSid: String? = null

    /** The wire text for a frame, with any configured session_id rewrite applied. */
    private fun wireFor(f: ReplayStream.Frame): String {
        val sid = when {
            rotatedFromIdx >= 0 && f.idx >= rotatedFromIdx -> ReplayStream.LIVE_SID_B
            cfgOverrideFromIdx >= 0 && cfgOverrideSid != null && f.idx >= cfgOverrideFromIdx -> cfgOverrideSid!!
            else -> null
        }
        return if (sid == null) f.raw else rewriteSid(f, sid)
    }

    /** Rebuild a frame's wire text with a different session_id (sid rotation). */
    private fun rewriteSid(f: ReplayStream.Frame, sid: String): String {
        val p = f.params.toMutableMap()
        p["session_id"] = kotlinx.serialization.json.JsonPrimitive(sid)
        val raw = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("method", kotlinx.serialization.json.JsonPrimitive("event"))
            put("params", kotlinx.serialization.json.JsonObject(p))
        }.toString()
        return raw
    }

    private fun newVm(label: String, vms: MutableMap<String, DashboardChatViewModel>,
                     tagger: Tagger, runningAtAttach: Boolean, historyHasAssistant: Boolean): DashboardChatViewModel {
        val vm = DashboardChatViewModel(app)
        vmSessionIdField.set(vm, ReplayStream.DB_KEY)
        socket = CapturingSocket().also { wsWebSocketField.set(ws, it) }
        setState(WsConnectionManager.State.Connected)
        pendingResumeAnswer = pendingResumeOverride ?: resumeResult(runningAtAttach, historyHasAssistant)
        pendingRingMode = RingMode.INTACT
        vmConnectWsAndStart.invoke(vm)   // real production wiring: state collector + notification collector + loadMessages
        drainRequests(DrainOutcome())
        // wait until loadMessages finished
        repeat(10) { idleAll(); if (!vm.uiState.isLoading) return@repeat }
        vms[label] = vm
        tagger.snapshotAll(vms, "attach", null)
        return vm
    }

    private fun feed(frames: List<ReplayStream.Frame>, phase: String,
                     vms: Map<String, DashboardChatViewModel>, tagger: Tagger, detailed: Boolean) {
        for ((k, f) in frames.withIndex()) {
            rpcProcessFrame.invoke(rpc, wireFor(f))
            if (detailed) {
                // Advance the virtual clock like real time would: the ~110 ms delta
                // flush heartbeat lands between frames exactly as on device.
                idleAll(); idleFor(120); idleAll()
                tagger.snapshotAll(vms, phase, f)
            } else if (k % 16 == 15) {
                idleAll(); idleFor(120); idleAll()
            }
        }
        idleAll()
        tagger.snapshotAll(vms, "$phase:end", null)
    }

    private fun observe(vm: DashboardChatViewModel): RowObservation {
        val all = vm.uiState.messages
        val assistants = all.count { it.role == "assistant" }
        val row = all.lastOrNull { it.role == "assistant" }
        return if (row == null) {
            RowObservation(false, "NO_ROW", 0, 0, false, 0, all.size, assistants)
        } else {
            RowObservation(true, row.id, row.content.length, row.thinkingText?.length ?: 0,
                row.isStreaming, row.toolCalls.size, all.size, assistants)
        }
    }

    private fun debugLinesSince(startMs: Long): List<DebugLog.Entry> =
        debugBuffer.filter { it.timestamp >= startMs }

    /** Run one trial of the sweep. */
    fun runTrial(cfg: TrialConfig): TrialResult {
        val trialStartMs = System.currentTimeMillis()
        // Reset shared client state so trials don't leak watermarks into each other.
        rpcLastSeenSeq.clear()
        rpcPendingRequests.clear()
        rotatedFromIdx = -1
        pendingResumeOverride = null
        cfgOverrideFromIdx = cfg.overrideSidFromIdx
        cfgOverrideSid = cfg.overrideSid
        val frames = cfg.frames ?: spec.frames
        val vms = LinkedHashMap<String, DashboardChatViewModel>()
        val tagger = Tagger()

        // Attach VM A over an established socket (state=Connected + capturing sink).
        newVm("A", vms, tagger, runningAtAttach = true, historyHasAssistant = false)

        feed(frames.take(cfg.injectAfter + 1), "live", vms, tagger, cfg.detailedTags)

        var gen = 1
        var liveStartIdx = cfg.injectAfter + 1
        when (cfg.mode) {
            Mode.NONE -> Unit
            Mode.SECOND_VM, Mode.SECOND_VM_SID_ROTATION -> {
                // A second chat ViewModel registers for the SAME session mid-turn.
                // In the SID_ROTATION flavor its session.resume returns a NEW live sid
                // (the gateway re-attaches the turn to this new resume — live sids are
                // per-attach, see the "transient live RPC SID" contract in
                // DashboardChatViewModel), and every remaining frame carries it. VM A,
                // which does not resume again, keeps the stale liveSid and its
                // handleNotification filter then drops everything that follows.
                val rotate = cfg.mode == Mode.SECOND_VM_SID_ROTATION
                if (rotate) {
                    pendingResumeOverride = resumeResultWithSid(
                        ReplayStream.LIVE_SID_B, runningAtAttach = true,
                        includeAssistantRow = cfg.injectAfter >= spec.idxComplete,
                    )
                }
                newVm("B", vms, tagger, runningAtAttach = true,
                    historyHasAssistant = cfg.injectAfter >= spec.idxComplete)
                pendingResumeOverride = null
                if (rotate) {
                    rotatedFromIdx = cfg.injectAfter + 1
                    tagger.snapshotAll(vms, "sid-rotated", null)
                }
            }
            Mode.RECONNECT -> {
                // Transport detach -> new generation -> CONNECTED edge. This is exactly
                // what WsConnectionManager emits via its listeners; we drive its state
                // flow directly because no real socket is opened in the testbed.
                gen++
                tagger.gen = gen
                wsWebSocketField.set(ws, null)
                setState(WsConnectionManager.State.Disconnected)
                idleAll()
                setState(WsConnectionManager.State.Reconnecting)
                idleAll()
                setState(WsConnectionManager.State.Connecting)
                idleAll()
                socket = CapturingSocket().also { wsWebSocketField.set(ws, it) }
                setState(WsConnectionManager.State.Connected)
                // The VM's reconnect handler now runs: session.resume(omitMessages=true),
                // then (gate open) lastSeenSeqFor + sessionEventsSince + replay | loadMessages.
                pendingResumeAnswer = resumeResult(
                    running = cfg.injectAfter < spec.idxComplete,
                    includeAssistantRow = cfg.injectAfter >= spec.idxComplete,
                )
                pendingRingMode = cfg.ringMode
                val drain = DrainOutcome()
                drainRequests(drain)
                if (drain.sawEventsSince && cfg.ringMode == RingMode.INTACT) {
                    // The gateway ring replayed every frame after the watermark; those
                    // already reached the collectors — do NOT deliver them a second time.
                    liveStartIdx = maxOf(liveStartIdx, drain.eventsSinceLastSeen.toInt())
                }
                tagger.snapshotAll(vms, "post-reconnect", null)
            }
        }

        feed(frames.drop(liveStartIdx), "live2", vms, tagger, cfg.detailedTags)
        idleFor(200) // let any armed delta flush land (completion flushes inline anyway)

        val obsA = observe(vms.getValue("A"))
        val obsB = vms["B"]?.let { observe(it) }

        val gateLines = debugLinesSince(trialStartMs)
            .filter { it.tag == "gate" || it.tag == "replay_decision" }
            .map { "${it.level}|${it.tag}|${it.message}" }
        val filteredCount = debugLinesSince(trialStartMs)
            .count { it.message.contains("notification FILTERED") }
        // Teardown: cancel collectors + scopes so no coroutine leaks into the next trial.
        for (vm in vms.values) vmOnCleared.invoke(vm)
        // Collected AFTER teardown on purpose: the A4 register/unregister pair is only
        // complete once dispose() has run.
        fun tagged(tag: String): List<String> = debugLinesSince(trialStartMs)
            .filter { it.tag == tag }.map { it.level + "|" + it.tag + "|" + it.message }
        val dispLines = tagged("disp")
        val identityLines = tagged("identity")
        val frameLines = tagged("frame")

        if (cfg.detailedTags) {
            val f = File(outDir, "tags_${cfg.mode}_${cfg.ringMode}_after${cfg.injectAfter}.log")
            f.writeText(tagger.lines.toString())
        }

        return TrialResult(
            cfg = cfg,
            genAtEnd = gen,
            blankA = !obsA.present || obsA.contentLen == 0,
            obsA = obsA,
            blankB = obsB?.let { !it.present || it.contentLen == 0 },
            obsB = obsB,
            gateLines = gateLines,
            filteredCount = filteredCount,
            servedCompleteNonEmpty = ReplayStream.FULL_ANSWER.isNotBlank(),
            dispLines = dispLines,
            identityLines = identityLines,
            frameLines = frameLines,
        )
    }
}
