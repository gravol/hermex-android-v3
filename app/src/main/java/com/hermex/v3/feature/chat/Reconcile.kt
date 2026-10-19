package com.hermex.v3.feature.chat

/**
 * The reconcile decision, extracted from DashboardChatViewModel so it can be tested
 * without a phone.
 *
 * Background: the gateway routes each session's event frames to the ONE transport
 * stored on the session. When the phone's socket drops mid-turn the server sets a
 * detached sentinel and every remaining frame is DROPPED, not queued. The turn is
 * still persisted, so the client is left with a frozen tool card and no way to
 * learn the turn ended — the user's only recovery was close + reopen.
 *
 * Three independent ways the local UI can be behind the server, all discovered in
 * field logs on v0.1.885/v0.1.886:
 *  A. the turn finished while disconnected, and the local UI still shows a
 *     streaming message or an unfinished tool card;
 *  B. the app ATTACHED MID-TURN — a full `session.resume` returns complete server
 *     rows, so nothing is marked streaming and no tool card is unfinished. The
 *     v0.1.886 gate (`isStreaming` only) never fires in this case; verified on
 *     device. Only the running-flag transition catches it.
 *  C. the server has more rows than the local list — the tail of the turn is
 *     missing. The message count is the robust signal: it does not depend on
 *     catching the turn while `running` is true.
 * D. the local list has the RIGHT number of rows, but the final assistant row is
 *     blank. Measured in trial D/14 (v0.1.892): the gateway routed `message.start`,
 *     258 deltas and `message.complete` to the phone's live transport before the
 *     socket was killed, and the app kept an empty assistant row. Nothing streams,
 *     no card is open, and the count matches — because the blank row counts as a
 *     row — so A, B and C are all blind and the user sees their prompt with no
 *     answer. A turn the server says is finished always ends with text, so a blank
 *     final row means our copy is wrong: re-read the history.
 */
object Reconcile {

    /** A stuck UI: a streaming assistant message, or a tool call that never got its
     *  completion event. The stale-stream watchdog clears `isStreaming`, so the tool
     *  card is what is left to detect — that was the v0.1.886 gate bug. */
    fun localInFlight(messages: List<UiMessage>): Boolean =
        messages.any {
            it.role == "assistant" && (it.isStreaming || it.toolCalls.any { t -> !t.completed })
        }

    /**
     * @param messages the local UI list
     * @param serverRunning the `running` field from `session.resume` (null = unknown)
     * @param serverMessageCount the `message_count` field from `session.resume`
     * @param serverTurnWasRunning we saw `running == true` on an earlier poll, i.e.
     *   the app attached while a turn was in flight
     */
    @JvmOverloads
    fun needsReload(
        messages: List<UiMessage>,
        serverRunning: Boolean?,
        serverMessageCount: Int?,
        serverTurnWasRunning: Boolean,
        turnDoneSeen: Boolean = false,
    ): Boolean {
        if (serverRunning == false && localInFlight(messages)) return true
        if (serverTurnWasRunning && serverRunning == false) return true
        if (serverMessageCount != null && serverMessageCount > messages.size) return true
        // D. blank final assistant row after a turn we know ran (see the class doc).
        // Requires the turn knowledge so an old blank row from an aborted turn cannot
        // start reloading every poll.
        if (serverRunning == false && (serverTurnWasRunning || turnDoneSeen) &&
            blankFinalAssistantRow(messages)) return true
        return false
    }

    /** The last assistant row carries no text and is not still streaming. Tool cards on
     *  it are irrelevant: a finished turn's answer is a text row, so a tool-call-only
     *  tail with no text is just as missing. */
    fun blankFinalAssistantRow(messages: List<UiMessage>): Boolean {
        val last = messages.lastOrNull { it.role == "assistant" } ?: return false
        if (last.isStreaming) return false
        return last.content.isBlank()
    }

    /**
     * Close tool cards that can never receive their completion event.
     *
     * `UiToolCall.completed` drives the card's spinner glyph (`◌` vs `✓`) and the
     * ticking elapsed timer, and ONLY `tool.complete` flips it. The stale-stream
     * watchdog cleared assistant `isStreaming` and never touched tool cards, so a
     * card whose `tool.complete` is lost — ring truncated, frame never in the ring,
     * replay unavailable — spins forever.
     *
     * Authority is the server's turn state: if the turn is not running, no
     * `tool.complete` is coming, so every open card is stale by definition. A
     * genuinely long-running tool keeps `serverRunning=true` and is left alone —
     * that is the false-positive guard.
     *
     * @return the closed messages plus the number of cards closed, or null when
     *   nothing should change.
     */
    fun closeStaleCards(
        messages: List<UiMessage>,
        serverRunning: Boolean?,
        turnDoneSeen: Boolean,
    ): Pair<List<UiMessage>, Int>? {
        val turnNotRunning = turnDoneSeen || serverRunning == false
        if (!turnNotRunning) return null
        val open = messages.sumOf { it.toolCalls.count { c -> !c.completed } }
        if (open == 0) return null
        val closed = messages.map { m ->
            if (m.toolCalls.any { !it.completed })
                m.copy(toolCalls = m.toolCalls.map { if (it.completed) it else it.copy(completed = true) })
            else m
        }
        return closed to open
    }
}
