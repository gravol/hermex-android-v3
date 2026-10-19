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
 *  C. REMOVED in v0.1.894 (FIX 1). It compared `sessions.message_count` (the
 *     gateway's PERSISTED transcript counter) with `messages.size` (this app's
 *     rendered row count for one chat screen). Those are different quantities with
 *     different zero points, so the rule was a permanent false positive: across 37
 *     captured journals it fired on 235 of 315 gate evaluations, with stable offsets
 *     (`count=46 local=38`, `count=100 local=90`) and sometimes inverted readings
 *     (`count=230 local=375`). Worse than useless: it short-circuited A/B/D behind a
 *     reload that could not help, and (with the loop's old `break`) killed the poll
 *     loop at its first tick. No count-based replacement — the signal is not there.
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
     * Which rule fired — "A", "B" or "D" — or null when the local copy is current.
     *
     * The caller needs the *identity*, not just a boolean. Panel review of this change
     * (three independent reviewers, all asking the same thing): a cooldown on full
     * history reloads is fine for the stuck-UI rules, but must never suppress the
     * blank-row recovery, because that is the one case where the user is staring at a
     * prompt with no answer and "wait 30 s" is a visible failure.
     *
     * @param messages the local UI list
     * @param serverRunning the `running` field from `session.resume` (null = unknown)
     * @param serverMessageCount accepted for call-site symmetry and gate logging only.
     *   NO rule reads it since FIX 1 — see the class doc for why it is not a signal.
     * @param serverTurnWasRunning we saw `running == true` on an earlier poll, i.e.
     *   the app attached while a turn was in flight
     * @param localTurnSubmitted THIS ViewModel submitted the prompt that is unanswered.
     *   Reviewer-found gap: a turn can start and finish inside a disconnect gap, so the
     *   app never saw `running=true` and never got a completion event; the stale-stream
     *   watchdog then clears the placeholder's spinner, which blinds rule A as well. The
     *   app still knows it asked the question — that is turn knowledge, and the strongest
     *   kind there is.
     */
    @JvmOverloads
    fun ruleFor(
        messages: List<UiMessage>,
        serverRunning: Boolean?,
        serverMessageCount: Int?,
        serverTurnWasRunning: Boolean,
        turnDoneSeen: Boolean = false,
        localTurnSubmitted: Boolean = false,
    ): String? {
        if (serverRunning == false && localInFlight(messages)) return "A"
        if (serverTurnWasRunning && serverRunning == false) return "B"
        // FIX 1: rule C (serverMessageCount > messages.size) deleted — it compared a
        // persisted gateway counter with a rendered-row count and fired on almost every
        // evaluation. Deliberately NOT replaced by another count heuristic.
        // D. blank final assistant row after a turn we know ran (see the class doc).
        // Requires the turn knowledge so an old blank row from an aborted turn cannot
        // start reloading every poll.
        if (serverRunning == false && (serverTurnWasRunning || turnDoneSeen || localTurnSubmitted) &&
            blankFinalAssistantRow(messages)) return "D"
        return null
    }

    /** The poll gate only needs yes/no; [ruleFor] carries the identity. */
    @JvmOverloads
    fun needsReload(
        messages: List<UiMessage>,
        serverRunning: Boolean?,
        serverMessageCount: Int?,
        serverTurnWasRunning: Boolean,
        turnDoneSeen: Boolean = false,
        localTurnSubmitted: Boolean = false,
    ): Boolean = ruleFor(
        messages, serverRunning, serverMessageCount, serverTurnWasRunning, turnDoneSeen,
        localTurnSubmitted,
    ) != null

    /**
     * Which row a terminal `message.complete` should update, and why a candidate was
     * refused. Extracted from the ViewModel for the same reason as the reconcile rules:
     * this decision destroyed a user's answer once (the silent no-op) and can destroy a
     * different one if it guesses wrong, so it needs tests without a phone.
     *
     * Order: exact server message id → the row actually streaming right now → the last
     * assistant row, but only when that row belongs to THIS turn (it comes after the last
     * user message) and is not carrying a different server id. Anything else returns -1,
     * meaning "create a new row" — creating a row is always better than overwriting an
     * earlier turn's finished answer with this turn's text.
     *
     * @return index to update (-1 = create), plus a journal-ready refusal reason or null.
     */
    fun terminalUpsertTarget(
        messages: List<UiMessage>,
        serverMsgId: String?,
    ): Pair<Int, String?> {
        val id = serverMsgId?.takeIf { it.isNotBlank() }
        if (id != null) {
            val exact = messages.indexOfFirst { m -> m.id == id }
            // An exact id match is decisive; a NON-match is not — the frame may be one we
            // have never seen (late attach), and the streaming / same-turn rules below are
            // then the right thing to consult. Returning -1 here would skip them.
            if (exact >= 0) return exact to null
        }
        val streaming = messages.indexOfLast { it.role == "assistant" && it.isStreaming }
        if (streaming >= 0) return streaming to null
        val lastUserIdx = messages.indexOfLast { it.role == "user" }
        val cand = messages.indexOfLast { it.role == "assistant" }
        if (cand < 0) return -1 to null
        val candId = messages[cand].id
        if (cand <= lastUserIdx) {
            return -1 to "candidate index=$cand precedes the last user message at index=" +
                "$lastUserIdx, so it belongs to an earlier turn"
        }
        if (!candId.startsWith("asst_")) {
            return -1 to "candidate id=$candId is a finished server row, not a placeholder for this turn"
        }
        return cand to null
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
