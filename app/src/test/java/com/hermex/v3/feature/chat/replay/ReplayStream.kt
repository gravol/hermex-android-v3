package com.hermex.v3.feature.chat.replay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Structurally faithful reproduction of the D/14 long-run turn (measured facts,
 * drop-trial-harness-v0.1.892-2026-10-18 trial matrix):
 *
 *   424 gateway event frames for one session — message.start x1, message.delta x258,
 *   reasoning.delta x161, review.summary x1, reasoning.available x1, thinking.delta x1,
 *   message.complete x1 — all route=session_transport, monotonic per-session seq.
 *
 * The gateway frame trace records only seq/type/id/route (payloads and real message ids
 * are NOT recorded), so a byte-faithful replay from stored data is impossible. This
 * stream is STRUCTURALLY faithful instead: same frame types, same counts, same ordering
 * as given in the measured listing (start → deltas → reasoning → review.summary →
 * reasoning.available → thinking.delta → complete), monotonic seq, and SYNTHETIC text
 * segments. `message.complete` carries the full concatenated answer, i.e. the served
 * completion text is guaranteed non-empty.
 *
 * Wire shape mirrors what JsonRpcClient.processFrame expects on a live socket:
 * {"jsonrpc":"2.0","method":"event","params":{"type":...,"session_id":<live sid>,
 *  "seq":N,"route":"session_transport","payload":{...}}}
 */
object ReplayStream {

    /** Persistent DB session key the ViewModels hold (never used on the wire). */
    const val DB_KEY = "20261019_p2replay_deadbeef"

    /** Live RPC sid carried by every event frame (what the gateway emits). */
    const val LIVE_SID = "p2live01"

    /** A rotated live sid, used by the SID_ROTATION variant (resume re-attach hands
     *  out a new transient live sid; the first VM's liveSid then goes stale). */
    const val LIVE_SID_B = "p2liver02"

    /** Server message id for the assistant row (message.start / message.complete payload.id). */
    const val ASSISTANT_MSG_ID = "msg-p2-replay-assistant-0001"

    const val N_MESSAGE_DELTA = 258
    const val N_REASONING_DELTA = 161
    const val TOTAL_FRAMES = 424 // 1 + 258 + 161 + 1 + 1 + 1 + 1

    const val PROMPT_TEXT = "P2 replay control prompt: produce the long answer."

    data class Frame(
        val idx: Int,                 // 0-based position in the stream
        val seq: Long,                // monotonic per-session gateway seq (1-based)
        val type: String,             // params.type as parsed by JsonRpcClient
        val raw: String,              // the exact WS text frame fed to processFrame
        val params: JsonObject,       // params object served to session.events.since replay
        val messageId: String?,       // assistant message id the frame belongs to (tagging)
        val textLen: Int,             // payload text length (0 for non-text frames)
    )

    data class Spec(
        val frames: List<Frame>,
        val idxStart: Int,
        val idxFirstContentDelta: Int,
        val idxLastContentDelta: Int,
        val idxReasoningDeltaStart: Int,
        val idxReviewSummary: Int,
        val idxReasoningAvailable: Int,
        val idxThinkingDelta: Int,
        val idxComplete: Int,
    )

    private fun seg(kind: Char, i: Int): String = "$kind${String.format("%03d", i)}|"

    val contentSegments: List<String> by lazy { (1..N_MESSAGE_DELTA).map { seg('c', it) } }
    val reasoningSegments: List<String> by lazy { (1..N_REASONING_DELTA).map { seg('r', it) } }
    val thinkingSegment: String by lazy { "t001|" }
    val FULL_ANSWER: String by lazy { contentSegments.joinToString("") }
    val FULL_REASONING: String by lazy { reasoningSegments.joinToString("") }

    private fun event(seq: Long, type: String, payload: JsonObject?, messageId: String?, textLen: Int): Frame {
        val params = buildJsonObject {
            put("type", JsonPrimitive(type))
            put("session_id", JsonPrimitive(LIVE_SID))
            put("seq", JsonPrimitive(seq))
            put("route", JsonPrimitive("session_transport"))
            if (payload != null) put("payload", payload)
        }
        val raw = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("method", JsonPrimitive("event"))
            put("params", params)
        }.toString()
        return Frame(0, seq, type, raw, params, messageId, textLen)
    }

    @Volatile
    private var cached: Spec? = null

    /** Build the 424-frame stream (idempotent; deterministic). */
    fun spec(): Spec = cached ?: synchronized(this) {
        cached ?: run {
            val out = ArrayList<Frame>(TOTAL_FRAMES)

            fun add(type: String, payload: JsonObject?, messageId: String? = null, textLen: Int = 0) {
                out.add(event(out.size + 1L, type, payload, messageId, textLen))
            }

            // 1. message.start — carries the server message id.
            add(
                "message.start",
                buildJsonObject { put("id", JsonPrimitive(ASSISTANT_MSG_ID)) },
                messageId = ASSISTANT_MSG_ID,
            )

            // 2. 258 message.delta — append-only answer segments.
            for (s in contentSegments) {
                add("message.delta", buildJsonObject { put("text", JsonPrimitive(s)) }, ASSISTANT_MSG_ID, s.length)
            }

            // 3. 161 reasoning.delta — thinking segments.
            for (s in reasoningSegments) {
                add("reasoning.delta", buildJsonObject { put("text", JsonPrimitive(s)) }, ASSISTANT_MSG_ID, s.length)
            }

            // 4. review.summary — a frame type with NO parser arm in JsonRpcClient
            //    (lands in the Unknown arm; included because the gateway really emitted it).
            add(
                "review.summary",
                buildJsonObject { put("summary", JsonPrimitive("P2 synthetic review summary")) },
                ASSISTANT_MSG_ID,
            )

            // 5. reasoning.available — flips the thinking-collapse flag.
            add("reasoning.available", null, ASSISTANT_MSG_ID)

            // 6. thinking.delta — appends to thinkingText via ThinkingDelta arm.
            add("thinking.delta", buildJsonObject { put("text", JsonPrimitive(thinkingSegment)) }, ASSISTANT_MSG_ID, thinkingSegment.length)

            // 7. message.complete — full final answer, non-empty by construction.
            add(
                "message.complete",
                buildJsonObject {
                    put("id", JsonPrimitive(ASSISTANT_MSG_ID))
                    put("content", JsonPrimitive(FULL_ANSWER))
                },
                ASSISTANT_MSG_ID,
                FULL_ANSWER.length,
            )

            check(out.size == TOTAL_FRAMES) { "expected $TOTAL_FRAMES frames, built ${out.size}" }
            for ((i, f) in out.withIndex()) {
                out[i] = f.copy(idx = i)
            }
            val spec = Spec(
                frames = out,
                idxStart = 0,
                idxFirstContentDelta = 1,
                idxLastContentDelta = TOTAL_FRAMES - (N_REASONING_DELTA + 5), // last message.delta
                idxReasoningDeltaStart = 1 + N_MESSAGE_DELTA,
                idxReviewSummary = 1 + N_MESSAGE_DELTA + N_REASONING_DELTA,
                idxReasoningAvailable = 2 + N_MESSAGE_DELTA + N_REASONING_DELTA,
                idxThinkingDelta = 3 + N_MESSAGE_DELTA + N_REASONING_DELTA,
                idxComplete = TOTAL_FRAMES - 1,
            )
            cached = spec
            spec
        }
    }
}
