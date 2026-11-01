## v0.5.2 (vc51) — 2026-11-01 — the debug log kept eating the incident before I could read it

- **Reported by Jeff:** a new gpt session wouldn't start (he had to fall back to SSH CLI), and the debug export
  he sent contained nothing about it. True: all 1,914 exported entries covered **5.1 seconds** — a
  `notification FILTERED` storm (1,821 lines) had flushed the entire 4,000-entry ring. Every held chat ViewModel
  was re-logging every foreign-session delta.
- **FILTERED now follows the v0.1.82 delta rule on the foreign side too:** the first foreign delta per sid logs
  once with a running `foreignEventsSeenForSid=` tally, later deltas only bump the counter; non-delta foreign
  events still log per-event. TRACE `dispLine` capture untouched — the ring should hold history, not spam.
- **The real surprise: 20 chat VMs were alive against `MAX_HELD_CHATS = 6`.** The stale-stream guard deliberately
  leaves `isStreaming=true` on dead-with-content turns, and a zombie VM never receives another MATCHED event to
  clear it — so `pickToEvict` protected every orphan and the cap silently disabled itself (chats from 2026-10-23
  were still holding). New `HARD_HELD_CHATS = 12`: past the hard ceiling the LRU victim is evicted regardless of
  streaming. Eviction only drops the local collector — the WebSocket is app-wide and shared, reopening reloads,
  and no server turn is ever interrupted.
- **Pinned by JVM tests:** `streamingProtectionCannotDefeatTheHardCeiling` (12 held → keep everything, 13 →
  oldest goes); eviction suite 8/8 and all replay suites green, RC=0.
- **The start failure itself was server-side, not the app:** engine SIGTERM + gateway restart + the :9119
  dashboard restarting under Hermex + an exhausted openai-codex credential pool, all inside the XeStrata
  promotion window (11:01–11:11 PDT). The app behaved; it just could no longer prove what it had seen.
  Full write-up: vault "Hermex v0.5.1 — debug capture 2026-11-01 findings".

## v0.5.1 (vc50) — 2026-10-23 — Insights was showing 3% of the prompt volume

- **Reported by Jeff: "it seems weird that there's insane input vs output?"** It was not a bad ratio; it was a missing
  source. The panel totals `session.list` rows, and those rows carry the gateway's `sessions` counters, which hold
  **main-agent-loop calls only** and **uncached prompt tokens only** (`agent/usage_pricing.py` normalizes providers that
  way on purpose). Measured over 30 days on a real store: it showed **58.9M in / 14.7M out** while the model was actually
  sent **2.14B prompt tokens** (2.08B of it cached) across **15,890 calls**, plus **1,572 more calls / 9.8M input (+13%)**
  of auxiliary work — memory review, context compaction, titling, vision — that never appears in a session row at all. True
  prompt:output is ~146:1, which is normal for replaying a ~107k-token context every turn; the panel was understating input
  about 35×, not inflating it.
- **Fix is on the server, and this app half reads it.** `insights.get` used to return only `{days, sessions, messages}`;
  it now also returns a `usage` rollup for one window (totals, per-model, per-task), computed from
  `session_model_usage` — the same accounting `/insights` uses — so a total can never disagree with its own breakdowns. The
  phone asks for DAY=1 / WEEK=7 / MONTH=30 instead of pulling up to 2,000 rows and summing them on-device.
- **What the panel shows now:** prompt volume including cached tokens, with uncached and cached split out underneath; output
  with thinking tokens named instead of added (they are already inside `completion_tokens`, so totalling them twice would
  invent spend); API calls and sessions; estimated cost when there is one; and a **BEHIND THE SCENES** section listing the
  auxiliary work by plain name ("memory review", "context compaction", "titling"). Token formatting gained a billions branch
  because "2137.3M" reads like a bug.
- **An unlabelled number is a wrong number.** Against a gateway that predates the rollup the panel keeps its old
  client-side totals but says so in amber: "Approximate — this gateway reports no usage rollup. These are main-agent calls
  and uncached prompt tokens only." A missing capability must never render as a zero.
- **Pinned by JVM tests** (`InsightsUsageRenderTest`, 8 new, on top of the existing 9): prompt counts cache, input does not;
  auxiliary tasks are listed heaviest-first with human names; reasoning is annotated not added; unknown task ids fall
  through unchanged; cost appears only when non-zero; window→period mapping is 1/7/30.

## v0.5.0 (vc49) — 2026-10-23 — milestone: what Hermex does on your phone today

**No new code in this release.** v0.5.0 renumbers v0.1.902 — the build that has been the daily driver since Oct 22 — so installing it changes nothing except the number in Settings. The `0.1.NNN` counter is retired: fixes from here go out as **0.5.1, 0.5.2, …**. Below is what the app actually does, rather than a list of fixes.

**Chat, one-to-one with the Hermes CLI/WebUI.** Sessions stream token-by-token with the model's thinking shown as it arrives and tool calls rendered as live cards; a card that can never receive its completion closes itself instead of hanging (v0.1.889). Copy takes the code block you pressed, not the whole message (v0.1.900). Images attach from the picker (v0.1.899). Slash commands are checked before they leave the phone: `/queue` queues behind the running turn, `/steer` redirects mid-turn (v0.1.901).

**Every session, honestly reported.** The session drawer sees the whole history, not the first 200 rows (v0.1.901). Insights totals DAY/WEEK/MONTH from its own snapshot with real states — loading, "no conversations loaded" with a retry, "no usage in this window", an amber note when the snapshot stops short of history — and the aggregation is pure code pinned by nine timezone-aware JVM tests (v0.1.902).

**Replies from the lock screen.** A finished turn posts a notification carrying a Reply action, so you answer without opening the app; duplicate notifications across delivery paths are guarded in persistent storage, not memory (v0.1.901). Push arrives over the native path or ntfy, and both Connection and Push Delivery screens say which account's values you're looking at for a second user (v0.1.897).

**It comes back on its own.** Reconnect checks are generation-scoped, with a 15 s liveness watchdog, so the app never reports Connected on a dead socket (v0.1.890); a reaped session recovers its history through capped exponential backoff instead of showing an empty chat or a red panel; opening a session after a gateway restart self-heals. Secrets are fail-closed encrypted and opted out of cloud backup (v0.1.891/892). The ANR storm caused by our own instrumentation is gone (v0.1.897).

## v0.1.902 (vc48) — 2026-10-22 — Insights was totalling the drawer's page, so MONTH saw two days

- **Reported by Jeff: "insights are broken again, nothing is populated when I click the section."** Ruled the server out first: 
  running the gateway's own `session.list` projection in-process against the live `state.db` returned every field the panel
  needs (`input_tokens`, `output_tokens`, `model`, `message_count`, `last_activity_at`) for all 25 newest rows. Reproduced on
  the Pixel 8 at v0.1.901 against that same live gateway: the panel rendered — `665.6k in / 245.8k out`, `Total 911.3k`,
  two model rows — so it was not dead. It was **wrong**, and could be empty, for the same reason.
- **Root cause is self-inflicted, from v0.1.901 shipped ~4 h earlier.** That release cut the drawer's default
  `session.list` to an explicit 25 rows (a good change: 72.6 KB → 8.7 KB per `sessions.changed` refetch). But
  `WebuiMenuDrawer` fed that page straight into `InsightsPanel(sessions = state.sessions)`, so a DAY/WEEK/MONTH total became
  "whatever the drawer happened to have loaded". Measured against the same db: true **DAY 12 sessions / 2.13M in / 756k out,
  WEEK 75 / 32.8M / 4.58M, MONTH 147 / 58.4M / 14.3M** — while a 25-row page spans only Oct 20→22, so MONTH was off by an
  order of magnitude and said nothing about it. When the drawer's own fetch had not finished (or failed), the list was empty
  and the panel rendered blank with no explanation — three different failures (not fetched / empty list / genuinely quiet
  window) sharing one blank screen. That is exactly what "nothing is populated" cannot distinguish from the phone.
- **Fix: the panel owns its data.** New `InsightsSnapshot` in `SessionsUiState` + `SessionsViewModel.loadInsights()` —
  fetched on open, tiers 500 → 2000 (measured: 500 rows ≈ 184 KB over the tailnet; this db's whole month is 147 sessions),
  120 s TTL so re-opening does not refetch hundreds of KB, in-flight guard. A page that comes back FULL sets
  `complete = false` instead of silently under-reporting, and the panel says so.
- **Honest states instead of blanks:** `Loading…`, `No conversations loaded` + *Try again* (connection problem, not a quiet
  period), `No usage in this window` with counts ("N sessions available; none active in the selected window" vs "N loaded but
  none reported token usage"), and an amber note when the snapshot stops short of history.
- **Math extracted to `InsightsAggregate.kt`** (pure: window bucketing, per-model rollups, formatting) and pinned by
  `InsightsAggregateTest` — 9 JVM tests, fixed clocks, `America/Vancouver` zone: DAY is today only, WEEK is seven local days
  inclusive of the sixth day back, MONTH is the calendar month and NOT a rolling 30 days (the pre-existing code was already
  right there; now it is asserted), zero/missing timestamps never count, sessions with unknown usage (`-1`) are excluded from
  totals but counted, empty-list vs empty-window are distinguishable, and the exact `formatTokens` strings the UI prints.
  This is the second time this panel has been wrong by construction rather than by coding mistake (first: the gateway omitting
  `model`, Aug 2026 — see skill `hermexandroid-qa/references/insights-model-filter-missing-server-field.md`), which is why the
  logic moved out of the composable.
- **Bonus defect the new test caught:** `modelLabel`'s comment claimed provider prefixes were stripped; the code only handled
  `/`, so `custom:clerk-flashnext-uncensored` displayed whole. Now strips both `provider/` and `scheme:`. Deliberate
  consequence: one model reached through two providers shows as one row — the panel answers "what did this model cost", not
  "which route served it".

## v0.1.901 (vc47) — 2026-10-22 — the drawer could not see 1,027 of 1,227 sessions; `/queue` interrupted instead of queueing

- **The session list was silently truncated at 200 and cost 73 KB per refetch.** `JsonRpcClient.sessionList()` sent
  no `limit`, so the gateway default applied (`methods_session.py`: `int(params.get("limit", 200))`, after fetching
  `max(limit*2, 200)` rows). Measured against the live server on a 1,227-session db: **no limit → 200 rows / 72.6 KB**,
  `limit=25` → 8.7 KB, `limit=500` → 184 KB. Two consequences: every `sessions.changed` refetch (our own v0.1.886 note
  logs those arriving in pairs every 2–10 s) moved ~73 KB, and because the drawer's filter matches only *loaded* rows,
  **1,027 older sessions were invisible and unsearchable** with nothing on screen saying so.
- **Fix (port of Jeff's brother Mike's blackie5 + blackie10 escape hatch, commits `565cf3c`/`d8f1a06`).** Default
  `session.list` limit is now an explicit **25**; the drawer footer "Load older conversations" raises it one tier per tap
  (25 → 200 → 2000) and the raised limit sticks for the ViewModel's life so an auto-reload never shrinks the list back
  out from under the user. The footer hides itself on **proof**, not a guess: a page shorter than the requested limit
  means there is nothing older (`SessionsUiState.fullListLoaded`, set from `rpcSessions.size < asked`). Mike's version
  jumps straight to 500, which for this db still misses the oldest 727 — hence tiers. (`JsonRpcClient.kt`,
  `SessionsViewModel.kt`, `WebuiMenuDrawer.kt`.)
- **A freshly created session was addressed by an id that dies in ~20 s.** `session.create` returns both a live sid
  (in-memory routing key, reaped ~20 s after the creating socket closes — `ws_orphan_reap`) and `stored_session_id`,
  the durable state.db key. We parsed `stored_session_id` and threw it away, then opened the new chat with the live sid
  — so its notification, `hermex://session/<id>` deep link and Reply action all keyed off an unresolvable id, and the
  self-heal path at `DashboardChatViewModel:554` literally logged "adopt the new DB key" while adopting a live sid.
  Every other identity path in this app already keeps the DB key (liveSid is debug-only), which is why this hid.
  **Fix (Mike's blackie9, commit `ae40872`):** `createSessionWithKey()` returns `(liveSid, durableKey)`; the drawer,
  `/new`, and the self-heal adopt the durable key, falling back to the live sid only if a server omits it.
  (`JsonRpcClient.kt`, `SessionsViewModel.kt`, `DashboardChatViewModel.kt`.)
- **`/queue <text>` did not queue.** It called `prompt.submit` without the gateway's queue flag, and this server runs
  `display.busy_input_mode: interrupt` (config.yaml:252) — `_handle_busy_submit` then **redirects or interrupts the live
  turn** rather than waiting. The server has an explicit flag for exactly this case ("queued=True … a 'run after' message
  must NEVER become a live correction"), and we never set it. The ack was also dishonest before it knew: it printed
  "⏳ Queuing for next turn…" unconditionally. Now `promptSubmit(…, queued = true)` and the wording follows what is
  actually true at that instant ("Queuing for after this turn…" vs "Nothing running — sending now…"). Idle sessions
  behave as before: the busy handler no-ops and the prompt runs immediately. (`JsonRpcClient.kt`,
  `DashboardChatViewModel.kt`.)
- **`/steer` dropped your text whenever nothing was running.** `session.steer` answers `{"status":"rejected"}` for an
  idle session **by design** — `_correction_method` documents it: *"An idle agent accepts steer() but only the next turn
  drains it… 'rejected' makes the client queue it as a normal next prompt."* We printed "⚠️ Steer rejected" and discarded
  the words. The 4010 branch lied twice: 4010 means *this agent has no steer capability*, while our ack said "Steer
  queued — no active turn; it'll apply on your next message" (nothing had been queued). And the pre-flight ack promised
  steering before anything was known. **Fix:** idle → take the documented fallback (`submitPrompt`); mid-turn → keep the
  RPC, word the ack as what actually happens ("lands at the next tool boundary"), handle the `rejected` race with the same
  fallback, and state 4010 honestly with `/queue` as the alternative. Server-side behaviour confirmed by reading
  `agent_runtime_helpers`: an undelivered steer is requeued and delivered later as a normal user row, so nothing is lost
  server-side — the loss was ours. (`DashboardChatViewModel.kt`.)
- **Two notifications for one finished turn.** Jeff's shade showed *"Turn finished | Check Strata Engine Cache Graph
  Setup"* and *"Check Strata Engine Cache Graph Setup"* — same answer, two cards. Cause: a completed turn reaches
  `NotificationHelper.postTurnFinished` from **two paths** — the app's own completion handlers, and the ntfy rail
  (`postNtfyMessage` delegates any `hermex://session/<key>` click straight to `postTurnFinished`). They addressed the
  session by **different ids** (live sid vs durable key), so they landed in different notification ids instead of
  replacing each other; the differing titles came from the server's `"Turn finished | <chat title>"` being used as a
  *session* title. Fix is three-part: the durable-key work above makes both paths address one session; the ntfy path now
  strips that prefix and titles the card with the chat; and `isDuplicateTurnNotice()` suppresses the same preview for the
  same session inside a 20 s window, recorded in **SharedPreferences** because ntfy delivery can run in a freshly started
  process. Suppressions are logged, so if the window ever hides a real second turn we will see it rather than guess.
  (`NotificationHelper.kt`.)
- **Slash commands paid for a guaranteed failure.** `slash.exec` / `command.dispatch` resolve sessions by live SID only,
  but when `liveSid` was blank the call sent the DB key — a deterministic 4001 — and only then did our own 4001 handler
  resume and retry. Now `execSlashWithFallbacks` resumes first when there is no live sid and logs the preflight; the
  4001 self-heal stays as the recovery path for genuine reaping. (`DashboardChatViewModel.kt`.)
- **Not taken from the blackie batch:** the TurnWatcher changes (no polling watcher exists in v3 — pings come from the
  completion paths + ntfy push), and deleting `WsKeepaliveService` (still started on WS connect here; a separate call,
  made harder by `NtfySubscriptionService` already being an ongoing-notification FGS). Audit with measurements:
  vault `HermexAndroid/blackie-handoff-audit-vs-v3-2026-10-21.md`.

## v0.1.900 (vc46) — 2026-10-21 — code-panel copy button copied the whole message, not the block

- **Copy button on a code panel handed you the entire message markdown.** `CopyableCodeBlock`/`CopyableCodeFence`
  fed `model.content` into `CodeBlockShell` as the clipboard string — but the mikepenz library passes every custom
  component override the **full raw markdown of the message** as `MarkdownComponentModel.content` (MarkdownExtension.kt:
  `MarkdownComponentModel(content = content, ...)`). The visible panel looked right only because the built-in renderers
  re-slice to the AST node's own source offsets internally. So on any multi-block message, copy grabbed everything.
- **Fix (ported from Jeff's brother's blackie build, commit `0542d2f`, v0.1.176-blackie7).** Route both wrappers
  through the library's own splitters — `MarkdownCodeBlock(model.content, model.node)` / `MarkdownCodeFence(...)` —
  and use the lambda's `code`/`language` for `CodeBlockShell` + rendering, so clipboard == panel contents exactly.
  Our internals keep **SafeCodeContent** (off-composition highlighting, v0.1.897 ANR fix) instead of his
  `highlightedCode*` — the port is wrapper-only. Verified against the AAR: `MarkdownCodeFence`/`MarkdownCodeBlock`
  are public in the pinned 0.34.0 with the (String, ASTNode, …) { code, language, style } shape.

## v0.1.899 (vc45) — 2026-10-21 — image attach actually worked nowhere: the bounds probe was gating the decode

- **"Couldn't read image" / "Couldn't read photo" on every pick (fixed, device-verified).** Reported by
  Jeff as "when I try to send an image from my phone it says image could not be read". `downscaleAndDecode`
  opened the picked URI, ran a bounds-only probe (`BitmapFactory.Options.inJustDecodeBounds = true`) and then
  did `?: return null` on its **return value** — but with `inJustDecodeBounds` the platform decoder returns
  `null` by design and only fills `outWidth`/`outHeight`. So the function bailed out before the real decode
  ever ran: gallery pick AND camera capture failed for **every** input format, on every version since the
  feature existed (v0.1.56 in the frozen v2 tree, v0.1.0 in v3). Not a regression and not format-related.
  The earlier working theory (HEIF that `BitmapFactory` cannot decode) is **wrong as the cause** — kept in the
  QA reference as one failure mode among others, no longer as the headline one.
- **Fix shape.** The probe's result is ignored; decodability is judged on the dimensions it filled in
  (`outWidth/outHeight <= 0` → not an image / not decodable → same toast). Sampling math moved to pure
  `decodeSampleSize(width, height, maxDim)` + `MAX_ATTACH_DIM`, covered by `ImageAttachSamplingTest`
  (4 tests: passthrough under the limit, power-of-two ladder for 3200/4032/9000 px, one oversized side,
  and a minimality sweep). `OutOfMemoryError` on a huge decode now returns null (toast) instead of
  crashing the process.
- **Device proof, Pixel 8 (`shiba`, 39151FDJH00HX4), release-signed build.** Positive control first on the
  installed v0.1.898: pushed a known plain JPEG into `/sdcard/Pictures`, MediaStore-scanned it, picked it in
  the photo picker → toast "Couldn't read image" (so a plain JPEG fails too — rules out HEIF/permissions).
  After installing the fixed build over it (same signing cert, login preserved): the same pick produced the
  composer thumbnail + "Image attached", and sending staged a real file on the gateway —
  `~/.hermes/images/upload_20261021_155458_1.jpg`, valid JPEG 1200x900, average pixel (40,90,159) against
  the generated source RGB(40,90,160). Decode → re-encode → base64 → `image.attach_bytes` verified end to end.
- **Not covered by this change:** the camera path shares the same helper, so it is fixed by the same edit but
  was not separately driven on device (no camera capture trial). HEIF input remains untested on a device that
  holds a real `.heic` — if one shows up failing after this version, that is the *next* hypothesis, with its
  own test, not an assumption carried over from the old note.

## v0.1.897 (vc43) — 2026-10-19 — session.title routed by frame id; Connection/Push Delivery rewritten for a second user

- **`session.title` never renamed an in-app chat header (fixed, device-verified).** The full diagnosis,
  the two-id pair from the live run and the re-test are written up under the v0.1.896 section below
  ("Phase 2 confirmed on device…"). Shipping side: `JsonRpcClient` keeps the frame's session id as the
  routing id and carries the server's `session_key` separately as `storedKey`; the ViewModel arm drops
  its key comparison because the sid gate has already routed the frame. Two parser tests pin both ids.
- **Everything a second user needs to type is now labelled as theirs.** Onboarding pre-filled
  `http://100.80.204.66:9119` 

... [OUTPUT TRUNCATED - 70,254 chars omitted out of 120,180 total] ...

e on it either, so self-heal loops forever and never recovers. Evidence: a debug log showed repeated "observer unavailable — opening fresh WS connection" + multiple `ws-ticket` fetches, i.e. the observer kept dropping and forcing fresh connections without recovery. **Fix:** before resuming on a 4001, ensure a live socket — if `wsConnection.isConnected` is false, call the new `WsConnectionManager.forceConnect()` (cancels any pending reconnect loop so there's exactly one active socket, fetches a fresh ticket, opens the WS, waits for handshake) and only then run resume + submit. Files: `WsConnectionManager.kt` (`forceConnect()`), `DashboardChatViewModel.kt` (`submitWithSelfHeal`).

## [0.1.167] — 2026-09-20 — Image-thumbnail Coil crash fix (ephemeral content URI)

### Fixed
- **Hard process crash on the Pixel 8 when attaching an image.** Crash log: `java.util.NoSuchElementException: List is empty` at `coil.decode.a.invoke` (= `coil.decode.BitmapFactoryDecoder`, R8-mapped) inside `androidx.compose.runtime.SnapshotFlowKt.snapshotFlow`, main thread, ~30s after launch. The composer's attached-image thumbnail (`AsyncImage`) was fed the picker-provided **content URI**. Coil re-reads that URI on every recompose inside its internal snapshotFlow coroutine; the backing file behind a `PickVisualMedia` / `TakePicture` URI is ephemeral and can be replaced or have its read permission revoked between pickup and a recompose (GrapheneOS/Android tightened this). When Coil re-read the now-dead URI, `BitmapFactoryDecoder.first()` threw `NoSuchElementException: List is empty`, escaping uncaught in Compose's snapshotFlow coroutine → process crash. The base64 payload sent to the server was fine (`downscaleAndEncode` decoded on pickup); only the thumbnail's *second* read failed — which is why it "suddenly" started with no app change (thumbnail code + Coil 2.7.0 untouched since v0.1.127). Fix: decode once into a `Bitmap`, store it in `pendingImageBmp`, and render the in-memory Bitmap instead of re-reading the URI; derive the base64 payload from that same Bitmap (`bitmapToDataUrl`). Filename generated inline. (`ChatScreen.kt`.)

## [0.1.163] — 2026-09-14 — Self-healing pure-thinking panel scroll + dead-code cleanup

### Fixed
- **Pure-thinking live panel could lose the bottom edge and never re-pin it.** During a turn with substantial thinking but no content/tools yet, the docked `LiveActivityPanel` has a single tall THINKING item. The old code scrolled to top (`scrollToItem(0)`) then compensated with `scrollBy(thinkingHeight - viewportHeight)`, where `thinkingHeight` comes from `BoxWithConstraints.minHeight`. That manual math was the *only* path — there was no self-correction loop, so if `thinkingHeight` lagged a frame (or drifted), the newest thinking streamed off-screen and stayed off until some unrelated state change flipped the panel to its tools branch. The tools branch (`scrollToItem(count - 1)`) is robust because LazyColumn computes its own offset from live layout state every frame — so scrolling "worked" once a tool appeared but silently drifted in pure-thinking mode. Fix: after `scrollToItem(0)` (which forces a layout pass), read the actual item bottom from live `layoutInfo` and scrollBy the overflow — the same proven pattern `autoScrollToBottom()` uses for the main list — then cross-check against `thinkingHeight` so any residual frame drift self-corrects instead of piling up. Built on the v0.1.161 BoxWithConstraints lesson (authoritative height source) but adds the missing self-healing correction path.

### Cleaned Up
- **Removed provably-dead code.** A `showLiveThinking` local fed a `LiveThinkingTicker` branch gated on `msg.isStreaming && !state.isStreaming`. Every Message's `isStreaming` is assigned `= state.isStreaming` (line 1144), so the global never lags the per-message flag and those two states can never diverge — the branch was impossible by construction. Removed rather than leaving dead code that looks like a real post-stream path.

## [0.1.162] — 2026-09-12 — Single scroll surface for thinking during streaming

### Fixed
- **Thinking text scrolled in two places at once during a turn.** While the model reasoned, the main transcript rendered a truncated inline `LiveThinkingPreviewLine` *and* the docked LiveActivityPanel ran its own nested LazyColumn, so the same thinking text scrolled independently in both. Fix: removed the preview line from the main list; the main-list auto-scroll now keys on content + tool calls only (the StreamLoop snapshot no longer includes `thinkingText.length`). Thinking scrolls exclusively inside the docked panel during streaming; post-turn `ThinkingScrollBox` behavior is unchanged.

## [0.1.161] — 2026-09-11 — Live thinking panel auto-scroll on first message

### Fixed
- **Live activity (thinking) panel did not live-scroll on the first assistant turn** — new streaming text appeared below the THINKING box and only began tracking once a tool card fired. Root cause: when no tool calls have arrived yet, the panel's LazyColumn has a single tall THINKING item at index 0, and `scrollToItem(count - 1)` = `scrollToItem(0)` aligns that item's **top** to the viewport top, so text streaming past the 200.dp viewport scrolled off-screen. Fix: in the pure-thinking case (thinking is the only item), scroll by the measured content height minus the viewport (`BoxWithConstraints` + `scrollBy`) so its bottom edge stays visible as it grows; the tools path keeps the original `scrollToItem(count - 1)`.

## [0.1.156] — 2026-08-26 — Debug-log filter checkboxes + cold-start session list

### Fixed
- **Debug-log section/level toggles do nothing when tapped (all stay "solid")** — the `Checkbox` components read their `checked` value from plain mutable fields on the `DebugLog` object (`isSectionEnabled` / `isLevelEnabled`), which are NOT Compose snapshot state. Toggling a checkbox mutated those fields but never triggered a recomposition, so the fully-controlled M3 `Checkbox` animated to the new state then snapped back to its last real value — it always looked stuck on. Fix: mirror the filter state into local `remember { mutableStateOf(...) }` vars in `DebugLogFilters()` so every toggle re-renders the panel; the setters still write `DebugLog` (what the export path reads). (`SettingsScreen.kt`, `DebugLog.kt`.)
- **Session list stuck on loading spinner for minutes after first launch** — on a cold start the persistent observer WebSocket isn't connected yet, so `SessionsViewModel.loadDashboardSessions()` took the fresh-connection fallback path, which opened a throwaway WS connection and returned WITHOUT ever calling `session.list()`. The list stayed on the loading spinner until an unrelated `sessions.changed` broadcast happened to fire minutes later (once the observer finally connected). Fix: the fresh-connection path now actually calls `session.list()` before disconnecting; both paths share one `applySessionList()` helper so neither can silently skip the fetch. (`SessionsViewModel.kt`.)

## [Unreleased] — 2026-08-24 — Self-healing stream finalization (frozen-response fix)
- **"In-app the response looks frozen and I have to restart Hermex"** — after a turn finishes (even a short "status check" turn with no tools), the client sometimes fails to clear `isStreaming` on the last assistant message, so the UI shows a perpetual spinner even though the server completed the turn correctly. Root cause: a dropped / late / out-of-order `message.completed`/`run.completed` event left the placeholder stranded; the v0.1.123 "no-live-stream" guard only fires when there's *no* live stream, and the v0.1.135 reconnect reconciliation only fires on a disconnect/reconnect cycle — neither covered a turn that finishes normally while connected. Fix (`v0.1.137`): `DashboardChatViewModel` now tracks `turnDoneSeen` (set on any completion event) and **force-closes** `isStreaming` immediately when a turn is known done but a stray placeholder is still streaming — no 45s watchdog wait, no reconnect needed. Watchdog lowered to ~10s as belt-and-suspenders. `JsonRpcClient.processFrame()` now recognizes completion events under either the `method` or `event` key so they can't silently drop into the "unrecognized frame shape" branch. (`DashboardChatViewModel.kt`, `JsonRpcClient.kt`.)

## [0.1.66] — 2026-08-13 — Slash-command crash fix (NPE in deferred LazyColumn DSL)

### Fixed
- **Crash on using slash commands (v0.1.65)** — `NullPointerException` at `ChatScreenKt$LiveActivityPanel$1$$ExternalSyntheticLambda0.invoke` under `LazyListIntervalContent.<init>`. The slash-completions list did `itemsIndexed(slashItems!!)` inside the `LazyColumn` content block. `LazyColumn` **defers** its content DSL lambda (runs later in `LazyListItemProviderLambda.intervalContentState`, a `derivedStateOf`), so the `if (slashItems != null)` guard at composition time did NOT protect the `!!` — when `slashItems` was re-nulled (text edit, focus loss, streaming start, or tapping a suggestion, which sets `slashItems = null`), the deferred `Intrinsics.d(null)` threw inside `LazyListIntervalContent.<init>`. Fix: capture the nullable to a local `val slashItemsNow = slashItems` before the guard and use the local inside the LazyColumn, so the deferred lambda closes over a stable non-null value.


## [0.1.48] — 2026-08-12 — Accent visibility fix (chat chrome)

### Fixed
- **Accent barely visible in the chat (v0.1.47)** — the accent scheme's `primaryContainer` was a faint 22% tint and `surfaceVariant` (used by assistant bubbles, top bars, composer) was left untouched, so sliders/cursor (full-strength `primary`) recolored but the chat itself barely moved. `primaryContainer` → 40% accent (user bubbles clearly tinted), `surfaceVariant` → 16% accent tint (assistant bubbles + top bars + composer pick up the hue), `secondaryContainer` → 25%.

## [0.1.47] — 2026-08-12 — Theme accent colors

### Added
- **Theme accent picker (Settings → Theme)** — swatch row: System (follow wallpaper / dynamic color), Cyan (brand default), Blue, Purple, Green, Red, Orange. Tap applies instantly to the whole app: top bar, buttons, links, assistant bubbles and highlights — Material 3 derives all container tones from the picked accent (`accentColorScheme()` blends the accent over the dark surfaces, alpha-over).
- Persisted as `#RRGGBB` in the settings DataStore; a picked accent overrides Android's dynamic/wallpaper colors (`HermexTheme(accentColor=...)` takes precedence over `dynamicColor`). Reuses the existing `hexToColor`/`isDarkForeground` helpers.

## [0.1.46] — 2026-08-12 — Display settings: UI zoom + text size

### Added
- **Display settings (Settings → Display)** — two sliders applied live to every screen via a `LocalDensity` override at the app root (`MainActivity`):
  - **UI zoom** 80–130% — scales the whole UI (dp: spacing, icons, bubbles)
  - **Text size** 80–150% — scales text (sp) on top of zoom
  - Persisted per-app in the existing "hermex_settings" DataStore (`SettingsRepository`, int percentages — no core-module changes). Sliders save on every change, so the UI previews the scale live as you drag.

## [0.1.45] — 2026-08-12 — StreamLoop auto-scroll fix (v0.1.44 regression)

### Fixed
- **Auto-scroll dead during streaming (v0.1.44 regression)** — the `snapshotFlow` in `ChatScreen.kt` read message state off the captured `ChatUiState` instance: plain field reads, **zero snapshot-state reads**, so `snapshotFlow` emitted exactly once at stream start (one scroll to the empty placeholder) and never re-fired as deltas grew the bubble — the response scrolled off-screen. Fixed by reading `viewModel.uiState` (the `MutableState` getter — a real snapshot read) inside both the block and the `collect`. The key now also covers `thinkingText` growth (thinking scrolls too), and the effect is gated on message presence instead of `isStreaming`, so a session resumed while the assistant is mid-response still tracks the stream.

## [0.1.44] — 2026-08-12 — StreamLoop optimization + 4001 session self-heal

### Fixed
- **JSON-RPC 4001 "session not found" after phone sleep** — the dashboard reclaims live sessions whose WebSocket went orphaned (`ws_orphan_reap` / `idle_timeout` / `lru_evict`) with no signal to the client. New `submitWithSelfHeal()` in `DashboardChatViewModel` catches 4001, re-registers via `session.resume`, and retries the submit once. Wired into both send and retry paths.
- **Streaming scroll gap** — auto-scroll only fired on message-count change; a single streaming bubble growing taller was scrolled once and never tracked again. The 100ms polling loop is replaced by a `snapshotFlow` + `distinctUntilChanged` collector keyed on (message count, last content length, toolCalls size) — scrolls on actual content change only, killing 10×/sec no-op wake-ups during thinking.

### Server-side (not in this APK)
- Restored DB-key → live-SID fallback in `_sess_nowait` (`tui_gateway/server.py`, local uncommitted patch — re-verify after any `hermes update`). Same-day docs realignment: repo public, handoff/AGENTS/CHANGELOG corrected, v0.1.22–v0.1.43 changelog backfilled.

## [Unreleased] — 2026-08-12 — Docs realignment

### Changed
- **Repo made public** — Obtainium can now see GitHub Releases APKs without a PAT (`gh repo edit gravol/hermex-android --visibility public`).
- **PROJECT_HANDOFF_CURRENT.md realigned with repo reality** — production signing marked DONE (keystore + CI secrets since 2026-07-17), toolchain table corrected (Kotlin 2.1.20 / BOM 2025.05.00 / AGP 8.6.1 / KSP / compileSdk 35), legacy two-stack architecture claims removed (stack fully deleted in v0.1.42), StreamLoop interval corrected 50ms → 100ms, HEAD updated to `bd41033`.
- **AGENTS.md** — repo visibility PUBLIC, StreamLoop interval 100ms.

> **Backfilled 2026-08-12** from git history + PROJECT_HANDOFF_CURRENT.md phase records.

## [0.1.43] — 2026-08-02 — Dependency alignment (crash fix)

### Fixed
- **Crash opening any session** (`NoSuchMethodError: BasicText-CL7eQgs`) — `multiplatform-markdown-renderer-m3:0.34.0` was compiled against Compose foundation 1.8.0, but the app shipped BOM 2024.06.00 (foundation 1.6.x). Full version cascade: Compose BOM 2024.06.00 → **2025.05.00**, Kotlin 2.0.20 → **2.1.20** (Compose compiler ships with Kotlin), KSP → **2.1.20-1.0.32**, AGP 8.5.0 → **8.6.1** (Gradle 8.7 caps AGP at 8.6.x), compileSdk 34 → **35**. All interdependent — bump together.

## [0.1.42] — 2026-07-19 — Phase 7C: Syntax highlighting & legacy stack cleanup

### Added
- **Syntax highlighting** for code blocks (Kotlin, Java, Python, Bash, JSON, XML, Markdown) via `multiplatform-markdown-renderer-code:0.34.0` + Highlights library.

### Removed
- **Legacy REST+SSE stack fully deleted** — `ApiClient.kt`, `DTOs.kt`, `SseParser.kt`, `SetupScreen`/`SetupViewModel`, legacy `ChatViewModel`, `HermesForegroundService`, orphaned `feature/` modules (11 subdirs, 32 files). App is dashboard JSON-RPC/WebSocket only.

## [0.1.41] — 2026-07-18 — Phase 7B: Background WebSocket keepalive

### Added
- `WsKeepaliveService` — `START_STICKY` foreground service (`dataSync` type, LOW-importance silent notification) that keeps the process alive while the chat WebSocket is active. Started on WS connect, stopped on ViewModel clear. Does NOT own the WS — only prevents the OS from killing the process when the phone locks.

> Note: v0.1.41 tag exists but no GitHub Release was created (superseded by v0.1.42).

## [0.1.40] — 2026-07-18 — Phase 7A: Markdown rendering

### Added
- **Markdown message rendering** via `multiplatform-markdown-renderer-m3:0.33.0` — headings, bold/italic, inline code, fenced code blocks, tables, blockquotes, lists, task lists. Streaming cursor preserved outside the markdown block; `rememberMarkdownState` re-parses per delta.

## [0.1.39] — 2026-07-18 — Phase 6E: Tool card improvements

### Added
- Tool cards capture `result`, `summary`, `startedAt` from `ToolComplete` notifications; context-aware emoji icons per tool; elapsed-time display; expand/collapse with full Arguments + Result sections (result preview up to 500 chars).

## [0.1.38] — 2026-07-18 — Phase 6B + 6C: Reliable interrupt & regenerate

### Fixed
- **Stop button left stale UI** — `stopStreaming()` now clears `isStreaming` on the last assistant message (blinking cursor, thinking ticker, typing dots disappear) and dismisses pending approval/clarify dialogs.

### Added
- **Retry/regenerate button** — Refresh icon in composer bottom bar re-sends the last user prompt after removing the last assistant message.

## [0.1.37] — 2026-07-18 — Phase 5E: Clarify dialog UI

### Added
- `PendingClarify` state model + Compose dialog with free-text answer input. `ClarifyRequest` notifications flow through to the ViewModel (auto-deny removed in `JsonRpcClient`); Cancel sends empty string to unblock the turn.

## [0.1.36] — 2026-07-18 — Phase 5D.4: Tool card display fix

### Fixed
- `MessageCompleted` **merges** server IDs into live-accumulated tool calls instead of replacing the list — tool cards keep preview/args/context through finalization and session reload. `loadMessages()` populates `UiToolCall.args` from `function.arguments` so replayed cards show context.

## [0.1.35] — 2026-07-18 — Phase 5D.3: Tool event name fix

### Fixed
- Tool events parsed as `Unknown` — dashboard server emits `tool.generating` / `tool.start` / `tool.complete` (payload nested under `params["payload"]`), not the assumed `tool.started`/`tool.progress`/`tool.completed`. New `ToolGenerating`/`ToolStart`/`ToolComplete` classes; `tool_id` correlates start→complete.

## [0.1.34] — 2026-07-18 — Phase 5D.2: Remove trust-all SSL, scope cleartext

### Changed
- Removed `hostnameVerifier { _, _ -> true }`, trust-all `X509TrustManager`, and `sslSocketFactory()` override (dead code on plain-HTTP dashboard).
- Replaced app-wide `android:usesCleartextTraffic="true"` with `network_security_config.xml` scoping cleartext to `100.80.204.66` only.

## [0.1.33] — 2026-07-18 — Phase 5D.2: Port 8443 cleanup

### Fixed
- Dashboard serves REST + WebSocket on **9119** plain HTTP — code assumed 8443 HTTPS. `setDashboardUrl()` now derives WS URL by scheme-only swap (port preserved); default URL updated to `http://100.80.204.66:9119`.

## [0.1.32] — 2026-07-18 — Phase 5D fix: Stuck scroll loop

### Fixed
- `loadMessages()` did not reset `isStreaming` — ViewModel surviving navigation kept the StreamLoop scrolling at 20fps forever. Explicit `isStreaming = false` added to the state copy.

## [0.1.31] — 2026-07-18 — Phase 5D: Remove JsonRpcClient auto-deny

### Fixed
- `JsonRpcClient.parseNotification()` auto-denied `ApprovalRequest` before the ViewModel saw it. Removed the auto-deny block — approvals now flow server → WS → client → ViewModel → dialog.

## [0.1.30] — 2026-07-18 — Phase 5B+5C: Approval dialog UI

### Added
- `PendingApproval` state model (`toolName`, `toolArgs`, `requestId`) + Compose `Dialog` with Approve/Deny buttons. `approveCurrentTool(approveAll)` / `denyCurrentTool(denyAll)` wired to `approval.respond` RPC.

## [0.1.29] — 2026-07-18 — Phase 5A: approval.respond params fix

### Fixed
- `JsonRpcClient.approvalRespond()` sent wrong params (`session_key`, boolean `approved`) — corrected to server contract: `session_id`, `choice: "approve"|"deny"`, `all: Boolean`.

## [0.1.28] — 2026-07-18 — Phase 4L.4: Reconnect session re-registration

### Fixed
- **4001 after WebSocket reconnect** — on every `Connected` transition the ViewModel calls `session.resume` to re-register the live session with the server's new runtime (does not reload history).
- Keyboard bottom-lock regression — `imePadding()` + `navigationBarsPadding()` in Scaffold; replaced with `WindowInsets.ime`-only calculation.

## [0.1.27] — 2026-07-18 — Keyboard bottom-lock fix

### Fixed
- Composer sat below the keyboard after IME open — scroll compensates on every `imeBottom` change (frame-based settle via `withFrameNanos` × 3).

## [0.1.26] — 2026-07-18 — Phase 4L.3: Log cleanup

### Fixed
- WS 101 false error — OkHttp `normalClose()` logged a fake `onFailure` when the server sent close frame 1000; early-return after close frame processed.
- Keyboard log spam — scroll logging gated on `LOG_SCROLL` env var + >50px viewport delta.

## [0.1.25] — 2026-07-17 — Phase 4L.1: Session lifecycle fix

### Fixed
- **Session ID mismatch root cause** — `prompt.submit` was sending the live SID after `session.resume`; server `_sess_nowait` returns a 3-tuple resolving DB key → live SID. `sessionId` (DB key, immutable) split from `liveSid` (transient, routing); `prompt.submit` always sends the DB key. Comprehensive session-ID logging added (`dbKey=… liveSid=…`).

## [0.1.24] — 2026-07-17 — 4001 session-not-found fix

### Fixed
- **4001 on resume** — client normalized `sessionId` to the resolved live SID after `session.resume`; notification filter matches against BOTH DB key and live SID (two-phase matching, Phase 4L.2).

## [0.1.23] — 2026-07-17 — Phase 4L: Chat viewport & keyboard anchoring

### Added
- `autoScrollToBottom()` two-step helper — `scrollToItem(targetIndex)` then `scrollBy(remaining)` when the streaming bubble grows taller than the viewport; unified across SessionOpen / AutoScroll / StreamEnd / Keyboard.
- `scrollGeneration` counter + 50ms polling loop while streaming (later raised to 100ms).
- `session_key` field on `SessionResumeResult`; debug logging in `sessionResume`.
- **Production signing introduced** — `hermex-release.keystore` + `keystore.properties` + 4 CI signing secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

> Note: v0.1.22 had no tag/release — interim version folded into v0.1.23.

## [0.1.21] — 2026-07-18 — Phase 4K: Chat Viewport Stabilization

### Fixed
- **Auto-scroll during streaming didn't compensate for item height growth** — `scrollToItem(index)` only made the target visible; when a streaming bubble grew taller than the viewport, new content remained below the visible area. New `autoScrollToBottom()` helper performs a two-step scroll: `scrollToItem` then `scrollBy(remaining)` computed from `visibleItemsInfo` offset+size vs viewport height.
- **Keyboard open left newest content behind IME** — after keyboard opens, the scroll now compensates for viewport height change, ensuring the last message's bottom is above the IME.
- **All scroll sites now use the same compensated approach** — SessionOpen, AutoScroll (per-delta), StreamEnd, and Keyboard all call the shared `autoScrollToBottom()` helper.

### Added
- Comprehensive debug logging around every scroll event: `firstVisibleItemIndex`, `lastVisibleItemIndex` (from `visibleItemsInfo.lastOrNull()?.index`), `canScrollForward`, `viewportSize.height` (before/after), and actual item bottom offset when compensation is applied.
- IME open/close events log viewport height before and after the change.

### Changed
- Bumped version from 0.1.20 → 0.1.21 (versionCode 20 → 21)

---

## [0.1.20] — 2026-07-17 — Phase 4J: Streaming Verification + Hardening

### Fixed
- **`isWaitingForFirstEvent` never cleared by `thinking.delta`/`reasoning.delta`** — if the server's first event was a thinking delta, `TypingDots` displayed indefinitely. All delta handlers now clear the flag.
- **`MessageStarted` didn't clear `isWaitingForFirstEvent` when `messageId` was null** — if `message.start` arrived without a server message ID, the placeholder never transitioned from TypingDots. Always clears flag regardless of messageId.
- **All delta handlers hardened with `isStreaming` filter** — `indexOfLast { it.role == "assistant" && it.isStreaming }` prevents content being applied to the wrong (completed non-streaming) assistant message.

### Changed
- Bumped version from 0.1.19 → 0.1.20 (versionCode 19 → 20)

---

## [0.1.19] — 2026-07-17 — Phase 4G: Session ID Normalization

### Added
- Auto-release CI workflow (`.github/workflows/release.yml`) — builds APK, creates tag and GitHub Release on push to master
- Server-side session key → live sid resolution in `_sess_nowait`, `_sess`, `prompt.submit`
- 5 regression tests for DB key → live sid resolution

### Fixed
- **4001 "session not found" bug** — Root cause: DB session key ≠ runtime live session ID. `DashboardChatViewModel` now normalizes `sessionId` to the resolved live sid returned by `session.resume` before using it for `prompt.submit` and all downstream operations

### Changed
- Bumped version from 0.1.18 → 0.1.19 (versionCode 18 → 19)

---

## [0.1.18] — 2026-07-17 — Phase 4E: Session ID Trace Logging

### Added
- Comprehensive session ID trace logging through the `session.resume` → `prompt.submit` flow
- All session ID logs tagged with "STATE" for easy filtering

### Changed
- Bumped version from 0.1.17 → 0.1.18 (versionCode 17 → 18)

---

## [0.1.17] — 2026-07-17 — Phase 4D: Debug Instrumentation

### Added
- Debug instrumentation for keyboard, scroll, and WebSocket state transitions
- Enhanced DebugLog output for ScrollState and NestedScrollConnection behavior
- WebSocket state monitoring via `state.collect` in ChatViewModel

### Changed
- Bumped version from 0.1.16 → 0.1.17 (versionCode 16 → 17)

---

## [0.1.16] — 2026-07-17 — Dashboard URL Separation

### Fixed
- REST vs WebSocket URL separation: login and ws-ticket calls use port 8443 HTTPS, WebSocket upgrade uses port 9119 WS
- `DashboardApiClient.setDashboardUrl()` now automatically derives the WebSocket URL from the REST URL

### Changed
- Bumped version from 0.1.15 → 0.1.16 (versionCode 15 → 16)

---

## [0.1.15] — 2026-07-17 — Session Resume Type Fix

### Fixed
- `session.resume` `resumed` field is a `String` not `Boolean` — corrected deserialization
- Messages use `text` key not `content` key in `session.resume` response — added fallback in `MessageData.resolvedContent`

### Changed
- Bumped version from (Phase 4C) → 0.1.15 (versionCode 14 → 15)

---

## [Phase 4B] — 2026-07-17 — Migration Hardening

### Fixed
- `WsConnectionManager.connect()` now waits for the WebSocket handshake to complete before returning (`waitForConnection()`)
- Explicit `provider='basic'` in login requests + `encodeDefaults = true` in kotlinx serializer
- Default dashboard URL changed from `https://100.80.204.66:8443` → `http://100.80.204.66:9119` (then back to 8443 with proper URL separation in v0.1.16)

### Added
- Diagnostic DebugLog entries for backend path audit (Phase 4A)

---

## [Phase 3] — 2026-07-17 — Dashboard WebSocket + JSON-RPC Chat

### Added
- **Full WebSocket chat pipeline**:
  - `WsConnectionManager` — WebSocket lifecycle, fresh ticket every connect, exponential backoff reconnect, 30s ping keepalive
  - `JsonRpcClient` — JSON-RPC 2.0 request/response correlation, `CompletableDeferred` pending map, notification routing via `Flow<RpcNotification>`
  - `RpcNotification` — sealed class for 17 server-pushed event types (message.delta, thinking.delta, reasoning.delta, tool.started/progress/completed, approval.request, clarify.request, gateway.ready, run.started/completed, message.completed, session.info)
  - Auto-deny for approval/clarify requests (v1 — real UI deferred)
- **Dashboard ChatViewModel** (`DashboardChatViewModel`):
  - WS connect → RPC start → `session.resume` → notification collection → UI state
  - Full streaming: gateway.ready → run.started → deltas → tool events → message.completed → run.completed
  - Reuses existing `ChatScreen`, `ChatUiState`, `UiMessage` — no UI changes needed
- Convenience methods: `sessionList()`, `sessionResume()`, `promptSubmit()`, `sessionInterrupt()`, `approvalRespond()`, `clarifyRespond()`

---

## [Phase 2] — 2026-07-17 — Dashboard Authentication Setup Flow

### Added
- `DashboardSetupScreen` — Compose screen for dashboard URL + password entry with password visibility toggle
- `DashboardSetupViewModel` — `status()` → `login()` flow with NetworkResult handling, credential persistence via `KeychainStore`
- `MainActivity` route for `dashboard-setup`, start destination priority: dashboard first, fallback to legacy setup
- `DashboardApiClient.status()`, `login()`, `fetchWsTicket()` — REST endpoints for auth
- `DashboardAuthenticator` — 401 auto-relogin with stored password
- Trust-all SSL for development (self-signed cert on Tailscale IP)

### Changed
- `HermexApplication` — `DashboardApiClient.init(this)` called alongside legacy `ApiClient.init()`
- `KeychainStore` — added `saveDashboardCredentials()`, `getDashboardUrl()`, `getDashboardPassword()`

---

## [Phase 1] — 2026-07-16 — Dashboard Initialization

### Added
- `DashboardApiClient` — cookie-based OkHttpClient with `CookiePersistor`, 401 auto-relogin authenticator, trust-all SSL
- Dashboard network layer skeleton: `WsConnectionManager`, `JsonRpcClient`, `RpcNotification` — initial versions
- Dashboard credentials stored in `KeychainStore` (same EncryptedSharedPreferences as legacy)

### Changed
- `HermexApplication.onCreate()` — adds `DashboardApiClient.init(this)` in separate try/catch block
- Dashboard failure does not block legacy API server access

---

## [0.1.0–2.3.3] — Pre-Dashboard Era

These versions established the core app infrastructure: Compose UI (Telegram-style bubbles, typing dots, auto-scroll), legacy REST+SSE networking (port 8650), EncryptedSharedPreferences, debug logging, and the full chat experience with the Hermes API Server. See the git history for details — the project was reset to v0.1.0 from the earlier v2.x.x versioning scheme at commit `c21675a`.

Key milestones:
- **v2.3.3** — Keyboard scroll fix: read IME before Scaffold consumes it
- **v2.3.0** — `mutableStateOf` recomposition fix + Telegram pill composer + keyboard scroll
- **v2.1.0** — First end-to-end chat with SSE streaming
- **v2.0.0** — Bearer auth migration (replaced cookie-based login with API key setup)
- **v1.0.0** — Cleartext fix, network_security_config, correct endpoint paths
- **v0.1.0** — Initial Kotlin Compose scaffold with 4-tab navigation