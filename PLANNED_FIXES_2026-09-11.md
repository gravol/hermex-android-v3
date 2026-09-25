# Planned Fixes — 2026-09-11 (implemented in v0.1.159)

**Status:** Fix #2 and #3 implemented, built (APK v0.1.159 / versionCode 160),
committed. Nothing pushed to GitHub/Obtainium yet — see "Release steps" below.
Fix #1 was already shipped as v0.1.158.

**Important correction:** The plan written while Jeff slept had a STALE root
cause for #2 (it claimed the streaming placeholder's `thinkingText` stayed
blank during thinking). It does NOT stay blank — line 1196 appends to it on
every ThinkingDelta. The real problem was that the main list renders only a
frozen spinner placeholder during streaming, so there is no growing content in
the main list for the StreamLoop's auto-scroll to follow; thinking lives inside
the docked (200dp, clipped) LiveActivityPanel. Fix #3's liveSid theory was also
wrong — the real error Jeff saw was server-side ("slash worker exited" / 5030).

---

## Fix #2 — Thinking does not auto-scroll until the first tool call ✅ IMPLEMENTED (v0.1.159)

### Symptom
While the model is thinking, the chat does not scroll to follow; the screen
goes stale. Once the first tool call appears, scrolling "works" again.

### Real root cause (verified against current code v0.1.158)
During streaming the main `LazyColumn` renders only a frozen spinner placeholder
(the in-stream thinking block at ChatScreen.kt:1246 is gated behind
`!state.isStreaming`). Thinking lives inside the docked `LiveActivityPanel`,
which is capped at `heightIn(max = 200.dp)` (ChatScreen.kt:2359) and clips. The
StreamLoop snapshot (ChatScreen.kt:516) keys on the last message's content
length, but there is no growing content in the main list → nothing scrolls to
follow until a tool call grows the placeholder.

### Fix applied (Option A — inline preview line)
Rendered a truncated (`take(120)`) inline live-thinking preview line INSIDE the
main list during streaming:
- ChatScreen.kt ~line 1246: `if (msg.role == "assistant" && msg.isStreaming && showThinking)` → `LiveThinkingPreviewLine(...)`. This gives the StreamLoop a growing item to follow, so auto-scroll works during pure thinking. Disappears when the turn ends (then ThinkingScrollBox renders).
- Added composable `LiveThinkingPreviewLine` at ChatScreen.kt ~line 1625: dimmed monospace italic line with a small "●" dot — reads as thinking, not an answer; maxLines=3, ellipsis.

### Verification
Built clean (JDK 17): `BUILD SUCCESSFUL in 1m 32s`. APK = v0.1.159 (versionCode
160), 30.8 MB. No new compiler warnings from the changed lines. To QA on device:
send a message, lock briefly / switch apps, watch the chat follow while thinking;
the preview line should appear and scroll away once real content/tools arrive.

---

## Fix #3 — `/yolo` (and other slash commands) fail to execute ✅ IMPLEMENTED (v0.1.159)

### Symptom (actual error Jeff saw)
`/yolo` returns: `⚠️ Command failed: JSON-RPC error 5030: slash worker re-spawn failed: slash worker exited`

### Real root cause (verified against server source)
The plan's liveSid theory was WRONG. The real fault is **server-side**:
`slash.exec` routes through a server-side slash-worker subprocess that can crash
("slash worker exited" / 5030). No client Kotlin patch fixes a dead worker.
Verified in `hermes-agent/gateway/slash_commands.py`: `/yolo` is handled by
`_handle_yolo_command` (line 3982), and busy-policy commands are dispatched via
the in-process `_dispatch_busy_slash_command` table (`slash_commands.py:3982`,
`run.py:16172`). `command.dispatch` runs these **in-process** — it never touches
the crashed slash worker.

### Fix applied (client fallback to command.dispatch on slash-worker error)
In `DashboardChatViewModel.kt::execSlashWithFallbacks` (~line 548), added a new
fallback branch BEFORE the existing 4018/4001 branches:
- On `e.code == 5030 || e.message?.contains("slash worker")`, route through
  `command.dispatch(liveSid.ifBlank { sessionId }, base, arg)` — same shape as
  the existing 4018 handler. This runs `/yolo` in-process and sidesteps the dead
  worker entirely.

This also fixes any other slash command that currently dies with a 5030 /
"slash worker exited" error, not just `/yolo`.

### Verification
Built clean (see #2). To QA on device: type `/yolo` — it should now toggle
approval bypass and return "enabled"/"disabled" instead of the 5030 error.
⚠️ `/yolo` toggles approval bypass; test only when someone is present to reset
with `/stop`.

---

## Release steps (per repo protocol in AGENTS.md)

1. APK already built: versionCode 160, versionName "0.1.159"
   (`app/build/outputs/apk/release/output-metadata.json`).
2. Commit ALONE (done below).
3. Push commit alone → wait for CI to fully finish (watch `gh run list --limit 1`
   until `completed`; a sub-60s run = skip, not success).
4. Then push tag v0.1.159 SEPARATELY (never concatenated with the commit).
5. Verify: `gh release view v0.1.159 --json name,isDraft,assets` — APK attached,
   non-draft. If "release not found" → guard skipped; create manually from the
   built APK.

**Not done:** nothing pushed to GitHub/Obtainium yet. `/yolo` not fired live.
