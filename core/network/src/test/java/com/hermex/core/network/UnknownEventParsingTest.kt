package com.hermex.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Typed parsing for the five dashboard-WS events that used to land in the Unknown arm
 * (`status.update`, `agent.terminal.output`, `review.summary`, `cron.changed`,
 * `platforms.changed`).
 *
 * Ground truth, in order of authority:
 *  - payload shapes are copied VERBATIM from live client journals
 *    (`~/hermex-qa/shots/D_*_client.log`, the `unknown event: … — rawParams={…}` lines), or
 *    taken from the server emission site where the journal line was cut by the client's own
 *    `rawParams.take(200)` truncation (marked per test).
 *  - required-vs-optional fields follow `tui_gateway/contracts/events.py`:
 *    StatusUpdatePayload {kind, text}, TerminalOutputPayload {process_id, chunk},
 *    ReviewSummaryPayload {text} — all required; ChangeSignalPayload is `{}` (extra=allow),
 *    nothing required.
 *  - names verified against the emitters: server.py:975 + session_notifications.py:178,254 +
 *    prompt_turn.py:353,374,394 (status.update); session_notifications.py:838
 *    (agent.terminal.output); server.py:1170 (review.summary); change_watcher.py:287,292
 *    (cron.changed, platforms.changed).
 *
 * Frames go through the production parse path — `replayEvents` → `handleNotification` — which
 * is the same single path the live socket uses, so these arms are exercised as on device.
 */
class UnknownEventParsingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Feed one event frame's `params` object through the production parser, return what came out. */
    private fun parseFrame(paramsJson: String): RpcNotification = runBlocking {
        val rpc = JsonRpcClient(WsConnectionManager(this))
        val received = Collections.synchronizedList(mutableListOf<RpcNotification>())
        // UNDISPATCHED so the collector subscribes before the frame is emitted: `notifications`
        // is a replay-0 SharedFlow, and an unsubscribed tryEmit is dropped.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            rpc.notifications.collect { received.add(it) }
        }
        rpc.replayEvents(listOf(json.parseToJsonElement(paramsJson) as JsonObject))
        // tryEmit only QUEUES the resumption of the collector on this single-threaded event
        // loop; cancelling straight away would drop the value. Let it land, then stop. The
        // timeout is only a guard so a genuinely-missing emission fails the assertion below
        // instead of spinning forever.
        withTimeoutOrNull(2_000) { while (received.isEmpty()) yield() }
        collector.cancelAndJoin()
        assertEquals("expected exactly one parsed notification", 1, received.size)
        received[0]
    }

    // ── status.update — payload {kind, text}, both required ──────────────────────────

    @Test fun statusUpdateFromJournalParsesTyped() {
        // Verbatim from D_*_client.log (seq 52062, kind "compacted").
        val n = parseFrame("""{"type":"status.update","session_id":"955c00b6","payload":{"kind":"compacted","text":"✓ Context compaction complete — continuing turn..."},"seq":52062}""")
        assertTrue("got ${n::class.simpleName}", n is RpcNotification.StatusUpdate)
        n as RpcNotification.StatusUpdate
        assertEquals("955c00b6", n.sessionId)
        assertEquals("compacted", n.kind)
        assertEquals("✓ Context compaction complete — continuing turn...", n.text)
        // The new arms must still go through the same frame-identity stamping path.
        assertEquals(52062L, n.frameSeq)
        assertEquals("status.update", n.wireType)
    }

    @Test fun statusUpdateObservedKindsAllParse() {
        // The kinds actually seen in the journals: process(26) lifecycle(22) compacting(4) compacted(1).
        listOf("process", "lifecycle", "compacting", "compacted").forEach { kind ->
            val n = parseFrame("""{"type":"status.update","session_id":"db3cf13e","payload":{"kind":"$kind","text":"x"},"seq":14}""")
            assertTrue("$kind -> ${n::class.simpleName}", n is RpcNotification.StatusUpdate)
            assertEquals(kind, (n as RpcNotification.StatusUpdate).kind)
        }
    }

    @Test fun statusUpdateLifecycleTextFromJournalParses() {
        // Verbatim from D_*_client.log (sid db3cf13e, seq 14).
        val n = parseFrame("""{"type":"status.update","session_id":"db3cf13e","payload":{"kind":"lifecycle","text":"⏳ Provider temporarily unavailable — retrying automatically in 18s (cycle 1/5); press Esc to stop"},"seq":14}""")
        assertTrue(n is RpcNotification.StatusUpdate)
        assertEquals("lifecycle", (n as RpcNotification.StatusUpdate).kind)
        assertEquals(14L, n.frameSeq)
    }

    @Test fun statusUpdateHeartbeatFromEmitterParses() {
        // Shape taken from the emitter itself (session_notifications.py:254):
        //   {"kind": "heartbeat", "text": f"♥ heartbeat #{fire_count} firing…"}
        // (Not in the journals: no heartbeat fired during those trials.)
        val n = parseFrame("""{"type":"status.update","session_id":"955c00b6","payload":{"kind":"heartbeat","text":"♥ heartbeat #4 firing…"},"seq":7}""")
        assertTrue(n is RpcNotification.StatusUpdate)
        assertEquals("heartbeat", (n as RpcNotification.StatusUpdate).kind)
        assertEquals("♥ heartbeat #4 firing…", n.text)
    }

    // ── agent.terminal.output — payload {process_id, chunk}, both required ───────────

    @Test fun terminalOutputFromJournalParsesTyped() {
        // Verbatim from D_11_client.log (seq 23383).
        val n = parseFrame("""{"type":"agent.terminal.output","session_id":"955c00b6","payload":{"process_id":"proc_487b3b9829de","chunk":"  prompt send: sent\n"},"seq":23383}""")
        assertTrue("got ${n::class.simpleName}", n is RpcNotification.TerminalOutput)
        n as RpcNotification.TerminalOutput
        assertEquals("955c00b6", n.sessionId)
        assertEquals("proc_487b3b9829de", n.processId)
        assertEquals("  prompt send: sent\n", n.chunk)
        assertEquals(23383L, n.frameSeq)
    }

    @Test fun terminalOutputKeepsRawChunkBytes() {
        // Verbatim from D_*_client.log (seq 153569): a chunk is raw process output.
        val n = parseFrame("""{"type":"agent.terminal.output","session_id":"955c00b6","payload":{"process_id":"proc_9ad9983ecdc7","chunk":"  screenshot /home/jeff/hermex-qa/shots/G_68_plus120.png\n"},"seq":153569}""")
        assertTrue(n is RpcNotification.TerminalOutput)
        n as RpcNotification.TerminalOutput
        assertEquals("proc_9ad9983ecdc7", n.processId)
        assertEquals("  screenshot /home/jeff/hermex-qa/shots/G_68_plus120.png\n", n.chunk)
        assertEquals(153569L, n.frameSeq)
    }

    // ── review.summary — payload {text}, required ────────────────────────────────────

    @Test fun reviewSummaryFromJournalParsesTyped() {
        // Verbatim from D_11_client.log (seq 23387).
        val n = parseFrame("""{"type":"review.summary","session_id":"955c00b6","payload":{"text":"💾 Self-improvement review: Skill 'hermex-drop-trial-harness' patched"},"seq":23387}""")
        assertTrue("got ${n::class.simpleName}", n is RpcNotification.ReviewSummary)
        n as RpcNotification.ReviewSummary
        assertEquals("955c00b6", n.sessionId)
        assertEquals("💾 Self-improvement review: Skill 'hermex-drop-trial-harness' patched", n.text)
        assertEquals(23387L, n.frameSeq)
    }

    // ── cron.changed / platforms.changed — ChangeSignalPayload, `{}` by contract ──────

    @Test fun changeSignalsFromJournalParseAsSignals() {
        // Verbatim: {"type":"platforms.changed","session_id":"","payload":{}}. These global
        // broadcasts carry an EMPTY session_id (change_watcher emits them session-less), so
        // they must not be treated as session-scoped or filtered away downstream.
        listOf("cron.changed", "platforms.changed").forEach { name ->
            val n = parseFrame("""{"type":"$name","session_id":"","payload":{}}""")
            when (name) {
                "cron.changed" -> assertTrue("$name -> ${n::class.simpleName}", n is RpcNotification.CronChanged)
                else -> assertTrue("$name -> ${n::class.simpleName}", n is RpcNotification.PlatformsChanged)
            }
            assertEquals("", n.sessionId)
            assertEquals(name, n.wireType)
        }
    }

    @Test fun changeSignalWithNoPayloadKeyStillParses() {
        // ChangeSignalPayload has NO required fields, so a frame that omits `payload`
        // entirely is still a valid signal — the signal IS the fact that something moved.
        val n = parseFrame("""{"type":"cron.changed"}""")
        assertTrue("got ${n::class.simpleName}", n is RpcNotification.CronChanged)
        assertEquals(null, n.sessionId)
    }

    // ── malformed / partial payloads: no throw, and NOT a fabricated typed event ──────

    @Test fun statusUpdateMissingFieldsFallBackToUnknown() {
        // `kind` and `text` are required by StatusUpdatePayload. A payload carrying neither
        // must not become an empty StatusUpdate: it goes to Unknown so the drop counter
        // still reports the protocol drift.
        listOf(
            """{"type":"status.update","session_id":"955c00b6","payload":{},"seq":1}""",
            """{"type":"status.update","session_id":"955c00b6","seq":2}""",
            """{"type":"status.update","session_id":"955c00b6","payload":{"kind":"process"},"seq":3}""",
            """{"type":"status.update","session_id":"955c00b6","payload":"nope","seq":4}""",
            """{"type":"status.update","session_id":"955c00b6","payload":null,"seq":5}""",
        ).forEach { raw ->
            val n = parseFrame(raw)
            assertTrue("$raw -> ${n::class.simpleName}", n is RpcNotification.Unknown)
            assertEquals("status.update", (n as RpcNotification.Unknown).eventType)
        }
    }

    @Test fun terminalOutputPartialPayloadFallsBackToUnknown() {
        // chunk without process_id, process_id without chunk, and a non-object payload.
        listOf(
            """{"type":"agent.terminal.output","session_id":"955c00b6","payload":{"chunk":"x\n"},"seq":9}""",
            """{"type":"agent.terminal.output","session_id":"955c00b6","payload":{"process_id":"proc_1"},"seq":9}""",
            """{"type":"agent.terminal.output","session_id":"955c00b6","payload":null,"seq":9}""",
        ).forEach { raw ->
            val n = parseFrame(raw)
            assertTrue("$raw -> ${n::class.simpleName}", n is RpcNotification.Unknown)
            assertEquals("agent.terminal.output", (n as RpcNotification.Unknown).eventType)
            assertEquals("routing id must survive the fallback", "955c00b6", n.sessionId)
        }
    }

    @Test fun emptyStringChunkStaysTyped() {
        // PRESENCE, not emptiness, is what the contract requires: `chunk: ""` is a legal
        // (if pointless) emission and must stay typed rather than read as absent.
        val n = parseFrame("""{"type":"agent.terminal.output","session_id":"s","payload":{"process_id":"proc_1","chunk":""},"seq":1}""")
        assertTrue("got ${n::class.simpleName}", n is RpcNotification.TerminalOutput)
        assertEquals("", (n as RpcNotification.TerminalOutput).chunk)
    }

    @Test fun reviewSummaryMissingTextFallsBackToUnknown() {
        listOf(
            """{"type":"review.summary","session_id":"34b8c2a1","payload":{},"seq":11881}""",
            """{"type":"review.summary","session_id":"34b8c2a1","payload":{"text":null},"seq":11881}""",
        ).forEach { raw ->
            val n = parseFrame(raw)
            assertTrue("$raw -> ${n::class.simpleName}", n is RpcNotification.Unknown)
            assertEquals("review.summary", (n as RpcNotification.Unknown).eventType)
        }
    }

    @Test fun genuinelyUnknownNameStillLandsInUnknown() {
        // The fallback arm is shared, so an un-parsed name keeps its raw params for the audit.
        // (subagent.tool: real server-side event, observed in journals, deliberately not in scope here.)
        val n = parseFrame("""{"type":"subagent.tool","session_id":"955c00b6","payload":{"name":"x"},"seq":1}""")
        assertTrue(n is RpcNotification.Unknown)
        n as RpcNotification.Unknown
        assertEquals("subagent.tool", n.eventType)
        assertTrue(n.rawParams != null)
    }

    // ── session.title — payload {session_id, title}, both required ───────────────────

    @Test fun sessionTitleFromJournalKeepsBothIds() {
        // Verbatim from a trial journal (`unknown event: session.title — rawParams={…}`, seq 3532).
        // The frame is addressed to the session ID; the payload names the server's session KEY. Both are
        // kept: `sessionId` is what the ViewModel's sid gate routes on, `storedKey` is for audit only.
        val n = parseFrame("""{"type":"session.title","session_id":"34b8c2a1","payload":{"session_id":"20261018_212611_2a120c","title":"List OpenEQ RE files with descriptions"},"seq":3532}""")
        assertTrue("got ${n::class.simpleName}", n is RpcNotification.SessionTitle)
        n as RpcNotification.SessionTitle
        assertEquals("34b8c2a1", n.sessionId)
        assertEquals("20261018_212611_2a120c", n.storedKey)
        assertEquals("List OpenEQ RE files with descriptions", n.title)
    }

    /**
     * The case that broke on device (Pixel 8, v0.1.896, chat created in-app): `session.create` returned
     * id `18c053ac`, and the title event arrived addressed to that id while naming the session key
     * `20261019_191332_38e061`. Putting the KEY into `sessionId` made the sid gate drop it —
     * `disp=FILTERED … nSid=20261019_191332_38e061 dbKey=18c053ac` — so the header never renamed.
     * Ids are from that live run; the title text is illustrative.
     */
    @Test fun anInAppCreatedChatHasADifferentFrameIdAndSessionKey() {
        val n = parseFrame("""{"type":"session.title","session_id":"18c053ac","payload":{"session_id":"20261019_191332_38e061","title":"What is the RFB Bell message used for"},"seq":3}""")
        assertTrue("got ${n::class.simpleName}", n is RpcNotification.SessionTitle)
        n as RpcNotification.SessionTitle
        assertEquals("routing id must be the frame's, not the key", "18c053ac", n.sessionId)
        assertEquals("20261019_191332_38e061", n.storedKey)
    }

    @Test fun sessionTitleBlankOrMissingFallsBackToUnknown() {
        // An empty title would blank a real header, and `title` is required by SessionTitlePayload —
        // both go to Unknown so the drop counter reports the drift instead of a fabricated rename.
        listOf(
            """{"type":"session.title","session_id":"34b8c2a1","payload":{"session_id":"k"},"seq":1}""",
            """{"type":"session.title","session_id":"34b8c2a1","payload":{"title":""},"seq":2}""",
            """{"type":"session.title","session_id":"34b8c2a1","payload":{"title":null},"seq":3}""",
            """{"type":"session.title","session_id":"34b8c2a1","payload":{},"seq":4}""",
            """{"type":"session.title","session_id":"34b8c2a1","seq":5}""",
        ).forEach { raw ->
            val n = parseFrame(raw)
            assertTrue("$raw -> ${n::class.simpleName}", n is RpcNotification.Unknown)
            assertEquals("session.title", (n as RpcNotification.Unknown).eventType)
        }
    }

    @Test fun whitespaceOnlyTitleIsTreatedAsMissing() {
        val n = parseFrame("""{"type":"session.title","session_id":"34b8c2a1","payload":{"session_id":"k","title":"   "},"seq":6}""")
        assertTrue("got ${n::class.simpleName}", n is RpcNotification.Unknown)
    }
}
