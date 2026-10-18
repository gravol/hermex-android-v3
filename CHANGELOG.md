## v0.1.892 (vc38) — 2026-10-18
- **Login stopped persisting after the Phase 3 cleanup — found on the Pixel 8 within minutes of
  shipping v0.1.891.** `SecureStore.open` deleted `shared_prefs/<name>.xml` on the *success* path
  too, meant as cleanup for the old plaintext fallback. That file is the encrypted store itself
  (`EncryptedSharedPreferences.create` writes there), so every launch deleted the credentials it had
  just read. Measured on device: sign in → force-stop → `restored dashboard credentials` →
  force-stop → `no dashboard credentials stored → isConfigured=false`. Login survived exactly one
  restart, i.e. the app looked unconfigured again on the next cold start.
  - Deletion now happens only where it belongs: the failure path. A file `create()` cannot open is
    by definition not a usable encrypted store (the old plaintext leftover, or a half-written file),
    and deleting it there is what makes the retry-with-fresh-key succeed. The success path never
    touches the store file.
  - New pure predicate `SecureStore.isPlaintextLeftover(text)` distinguishes the two shapes by the
    Tink keyset marker — an encrypted store's keys are SIV-encrypted, so credential key names never
    appear literally; the old plaintext fallback names them directly.
  - Tests: `SecretStorageAndBackupRulesTest` +2 (6 total) — asserts the success block contains no
    delete call, that the failure path still deletes, and that the encrypted-store text is never
    classified as a leftover.

## v0.1.891 (vc37) — 2026-10-18
- **Audit Phase 3: secret storage fails closed, and the app opts out of cloud backup.**
  - `KeychainStore` and `CookiePersistor` each had their own silent plaintext fallback: on
    `EncryptedSharedPreferences.create` failure they wrote dashboard credentials and live session
    cookies into ordinary SharedPreferences while the Settings screen kept stating "Saved
    credentials are encrypted on this device". A Keystore failure is exactly what happens after a
    device transfer/restore — the common case. Both now delegate to `SecureStore`: create
    encrypted, delete the broken prefs file, retry once with a fresh key, and if it still fails
    keep secrets in an in-memory store only, reporting `SecretStorageMode.SessionOnly`. Nothing
    sensitive reaches disk unencrypted, and Settings shows which mode it is actually in
    (red warning + sign-in required each launch when SessionOnly).
  - `allowBackup="false"`, and both rule files are excludes-only. They included `sharedpref` +
    `database`, which shipped the plaintext Room DB — every chat message, tool output and thinking
    card — to Google cloud backup and to device-transfer. The encrypted blobs were useless in a
    backup anyway (the Tink master key stays in the local Keystore), so a restored phone read
    nothing and reported `isConfigured=false`; re-login is now the honest, stated behaviour.
  - Regression test `SecretStorageAndBackupRulesTest` (4 tests) asserts: no `allowBackup=true`,
    no `<include domain="sharedpref"|"database">`, no `getSharedPreferences(` in either secret
    store, and that the Settings claim is derived from the storage mode.

## v0.1.890 (vc36) — 2026-10-17
- **The app sat "Connected" on a dead socket for 18 minutes while the gateway was healthy.**
  Testing on 2026-10-17 11:10 (a `ss -K "sport = :9119"` socket kill plus a dashboard restart)
  severed every client socket. The debug log shows the reconnect loop opening gen=4, its
  `onOpen` setting state=Connected, the server closing gen=4 800 ms later (code 1012), and the
  loop's success check reading that stale edge: it logged "reconnected (attempt 1)" with
  `webSocket == null` and no loop running. Nothing after that reconnected — the UI believed it
  was connected, every send was dropped, and the gateway was fine the whole time.
  Three fixes in `WsConnectionManager`:
  - **The success test is about the socket this attempt opened.** `openWebSocket` returns its
    generation; the loop now requires `state == Connected && generation == myGen && webSocket != null`.
  - **A death during the loop's 2 s stabilization window cancels the in-flight loop** before
    starting a new one. The old `reconnectJob?.isActive` guard swallowed the `onClosed` that
    arrived mid-window, so the loop kept driving a dead socket and reported success on it.
  - **Liveness watchdog:** every 15 s, if state says Connected and there is no socket under it,
    reconnect. OkHttp fires `onClosed`/`onFailure` only when it learns the socket is gone; with
    `readTimeout(0)` and a 300 s ping, a black-holed route (Tailscale path change, network
    switch) leaves the reader blocked for minutes with state still Connected.

## v0.1.886 (vc32) — 2026-10-17
- **Frozen tool card, missing final answer (the "close and reopen shows it" bug).** The gateway
  routes each session's event frames to the **one transport stored on the session**. When the
  phone's socket drops mid-turn the server sets a detached sentinel and every remaining frame is
  **dropped, not queued** — the turn is still persisted, so a hard close + reopen (full
  `loadMessages()`) shows the answer while the live UI sits on a spinning tool card forever.
  Reproduced on Pixel 8 with a 12-tool-call turn: the server had 26 messages ending `DONE`, the
  app log showed one `session.resume` and **zero** delta/tool/completion/reconcile lines.
  Four fixes:
  - **Widened the reconcile gate** (`localInFlight()`): the old test looked only at
    `isStreaming` on an assistant message, and the v0.1.123 stale-stream watchdog clears that
    flag — when it fires first the gate goes false and the full reload never runs. A tool call
    with `completed == false` is the same stuck state and now counts.
  - **Foreground reconcile:** the existing 30s `session.resume(omitMessages=true)` gauge poll
    already carries the server's `running`. If it says the turn is over and the local UI still
    has a streaming message or an unfinished tool card, do the full history reload — the
    "close and reopen" path, taken automatically while the chat is open.
  - **Persisted in-flight marker** (`inflight_<sid>` in DataStore): set on send, cleared on every
    completion signal and when the server reports `running == false`. Android kills backgrounded
    processes and the chat ViewModel dies with them, so no reconcile code can run after a process
    death; the marker survives, and the next chat open reconciles and fires the turn-finished ping.
  - **Killed the sessions-list reload storm:** field logs show `session.list` in identical pairs
    every 2-10s (the churn drives reconnects, which is where the transport detaches). One reload
    in flight at a time, plus a 1.5s debounce on `sessions.changed`.
- **Parser gaps found in the same log:** `session.usage`, `platforms.changed` and `session.title`
  arrive as `DashboardChat: unknown event` and are dropped.

## v0.1.885 (vc31) — 2026-10-16
- **One WebSocket for the whole app.** `WsConnectionManager` was constructed per screen:
  4 sites in `SessionsViewModel`, one per chat session in `ChatVmsHolder` ("one WS per
  session"), plus `SoulScreen`, `BotScreenEngine` and `NotificationReplyService`. The
  gateway delivers every event to every socket, so the same delta lands N times —
  doubled words and sentences in chat. Worse, `session.resume` on a second socket
  re-attaches the session to that socket, so a running turn's events go to a connection
  where no chat ViewModel is listening: the turn runs and never shows up.
  Fix: `AppModule.provideWsConnection()` / `provideRpcClient()` are app-wide singletons
  on a `SupervisorJob` scope (a ViewModel scope dies when the ViewModel clears). No
  screen disconnects the shared socket any more — one VM clearing must not drop
  everyone's connection, and the server's orphan-reaper tears a running session down
  20 s after a disconnect.
- **Notifications are a broadcast.** `JsonRpcClient.notifications` was
  `Channel.receiveAsFlow()` — a single-consumer flow, so with the shared client the chat
  VM, the sessions observer and the reply service split events between them and the chat
  bubble silently missed deltas. Now a `MutableSharedFlow` (buffer 1024, drop-oldest):
  every subscriber gets every event. `serverRequests` stays a single-consumer channel —
  a server→client request must be answered exactly once.
- **`connect()` is single-flight.** Every screen's `LaunchedEffect` calls `connect()` on
  entry, so app launch fired several at the same millisecond. The state check was
  check-then-act and every caller passed it: logcat 2026-10-15 20:29:29.658 shows two
  `connect()` lines, two tickets, two `onOpen` from one fresh process. A `Mutex` now
  serializes `connect()`/`forceConnect()`; a caller arriving mid-connect waits for the
  in-flight socket instead of opening its own.

## v0.1.884 (vc30) — 2026-10-16
- **Doubled words and sentences in chat: duplicate WebSocket sockets.** One app instance
  was holding 6 live sockets to the gateway at once (`ss -tnp` on BigRed, 2026-10-16).
  Every socket receives every server event, and the delta handler appends each delta
  text into the streaming bubble with no dedupe — so the same words land N times.
  Root cause is a check-then-act race in `startReconnectLoop()`: the guard
  `if (reconnectJob?.isActive == true) return` is read on OkHttp's callback threads, so
  when several sockets die in the same millisecond (the gateway's 20s keepalive ping
  kills them all at once) every `onClosed` passes the guard before any assignment
  lands. Gateway log 2026-10-16 03:21:35: three sockets accepted in 3ms.
  Fix: `@Synchronized` on the reconnect guard, a per-socket generation number, and the
  superseded socket is closed BEFORE a new one opens. Stale listeners can no longer
  touch shared state, fire the capabilities advert, replay `session.resume`, or spawn
  a reconnect loop.
- **`client.capabilities` ack logged as a failure.** The advert is fire-and-forget, so
  its reply hit `no pending request (stale/timed out)` on every connection. Five such
  lines read as a broken connection. Ids are now registered as fire-and-forget and the
  ack logs as `fire-and-forget ack`.

## v0.1.883 (vc29) — 2026-10-15
- **Hextile subencoding bits corrected to RFC 6143.** `decodeHextile` read Background on
  bit `0x01` and Foreground on `0x02`. The spec is `0x01` Raw, `0x02` Background, `0x04`
  Foreground, `0x08` AnySubrects, `0x10` SubrectsColoured. A Raw tile made the decoder
  consume 3 bytes where the server sends `tileW*tileH*4` — instant stream desync. Latent
  because Xvnc only advertises Raw/CopyRect/Tight/TightPng, so Hextile never arrives in
  practice. Raw tiles are 32bpp BGRA, not 3-byte RGB triples.
- **Rectangle bounds guard.** Server rectangles are now validated against the framebuffer.
  The handshake bounds the allocation, not each rect, so a desynced stream can produce a
  rect that writes past the framebuffer. Out-of-bounds is treated as fatal — continuing
  prints garbage for the rest of the session.
- **Published frame snapshot.** The decoder writes `pixels`, then copies into a `published`
  array under a lock after the WHOLE update. `blitTo` reads the snapshot. Previously the UI
  read the decoder's live buffer, so a render could copy pixels from different stages of an
  update (tearing / partial frame), and `frameSeq` being volatile gave visibility of the
  counter, not atomicity of the array.
- **Bot Screen text box caption.** The box sends keystrokes to the focused field on the
  desktop; it is not a chat message. Label and caption changed after that was misread.
- Found by having a frontier model read the source from the public GitHub repo. 14 unit
  tests, 0 failures.

## v0.1.882 (vc28) — 2026-10-15
- **Bot Screen renders the desktop on the phone for the first time.** Four bugs,
  found by reading Xvnc's own log (`~/.hermes/bot-desktop/launcher.log`) and the
  app's `RfbClient` logcat, then capturing 5.18 MB of the live `rfb.sock` stream
  and decoding it offline before rebuilding.
- **The desync: a zero-size Tight/TightPng rect carries EXACTLY ONE byte** — the
  flush code (0x21 flush the zlib stream, 0x22 flush + drop its palette, anything
  else a no-op). The decoder read zero bytes, leaving one byte per opcode, so every
  following rect header landed one byte off: `unhandled encoding -350941171`,
  `unknown server msg 235`, black screen.
- **Tight control-byte layout corrected:** bits 7-5 = compression type (000 Fill,
  001 Basic, 010 JPEG, 011 PNG, 100 CopyFromHistory), bits 4-3 = filter id, bits 2-0
  = compression level. The old decoder read bits 7-6 as the type and bit 5 as
  "explicit palette" — bit 5 belongs to the type field, so every basic rect made it
  consume palette bytes that were never sent. Fill is the top three bits zero (3 RGB
  bytes, no palette, no zlib). Level 0 is uncompressed TPI; levels 1-4 are zlib with
  `2^level` colours. Gradient filter added.
- Verified on the Pixel: desktop renders, `Take over` → `Hand back` +
  `type for the bot…` + `Send`, pointer moves to the tapped point.

## v0.1.881 (vc27) — 2026-10-15
- **Dropped the `SetPixelFormat` packet.** The client sent it as message type 0 and
  Xvnc closed the link: `VNCSConnST: closing rfb.sock: invalid pixel format`. The
  server default is already 32bpp depth-24 little-endian rgb888 @ 16/8/0 — the
  format we want — so the packet is unnecessary. Handshake then completed:
  `ServerInit 1440x900 'hermes:default'`.
- **Tight Fill special case:** control byte `0x00` is a solid-colour rect (3 RGB
  bytes), not a palette request. A desktop opening on a blank background sends
  exactly that, and the basic path threw `tight: no palette for stream 0`.

## v0.1.880 (vc26) — 2026-10-15
- Speak TigerVNC's protocol numbering (client messages are NOT RFC 6143:
  `SetEncodings=2`, `FramebufferUpdateRequest=3` — proved by the server log reading
  our packet as `FBUR 192x65535 at 4,0`) and decode Tight zlib.

## v0.1.879 (vc25) — 2026-10-14
- **Fixed the Bot Screen OOM crash.** `ServerInit` is 24 bytes (width, height,
  16-byte pixel format, name length), not 32. Reading 32 swallowed the name length
  and the first name bytes, so the "name length" landed on ASCII and asked for a
  1.7 GB allocation. Geometry/name-length are now sanity-checked and a desync is
  reported by name instead of crashing.

## v0.1.878 (vc24) — 2026-10-14
- Bot Screen: fix RFB parser EOF-on-empty-poll, `connect()` re-entrance, WS ping
  timeout.

## v0.1.875 (vc21) — 2026-10-14
- **Copy a URL out of a message without copying the whole message.** The
  long-press Reply/Copy dialog now detects every `http(s)://` URL in the message
  and lists each one with its own **Copy link** button. The existing **Copy**
  button still copies the full message. Fixes the "I just wanted the APK link"
  annoyance.

## v0.1.874 (vc20) — 2026-10-13
- **Turn notifications no longer ping the chat you're already looking at.** A
  finished turn in the visible session now posts to a new silent channel
  (`turns_quiet`, IMPORTANCE_LOW, `setSound(null)`, no vibration, no badge) with
  `flags=SILENT` — the card lands in the shade with its Reply action intact, and
  the phone makes no sound, no vibration, no heads-up. Same notification id, so
  the loud push that arrives when you background the app replaces the quiet one.
- Suppression was the wrong answer and was reverted in the same commit: the
  turn-finished notification is the inline-reply affordance, so killing it kills
  lock-screen/shade reply. Silence, not absence.
- **Fixed: `AppState.visibleSessionId` was always empty.** `ChatScreen`'s
  `DisposableEffect(Unit)` ran `setScreenVisible(true)` once at first composition
  — before `LaunchedEffect(sessionId)` had populated the VM's `sessionId` (starts
  `""`) — and with key `Unit` never re-ran on session change. `init()` now
  re-publishes the key when the screen is visible, and the effect is keyed on
  `sessionId`.
- Server-side (`hermes-ops/app/turnwatch.py`): lease freshness now measured
  against `expires_at`, not `acquired_at`. A turn lease is acquired at turn START
  and refreshed while held, so `acquired_at` ages by the whole turn (real turns
  here run 350-880s) and a viewer of a long turn looked "stale, nobody watching".
## v0.1.873 (vc19) — 2026-10-12
- Version bump only, to ship the warm-tap deep-link fix. v0.1.872's tag landed on
  `d479f92`; the warm-tap commit arrived with the tag already present, so CI's
  "tag already exists" guard skipped the build and the released APK never got it
  (the phone had it via adb — Obtainium would not have). New version → new tag →
  CI builds from HEAD. Released APK verified: CI checked out master at `f59e3b5`,
  warm-tap commit `a2fe458` is its ancestor.

## v0.1.872 (vc18) — 2026-10-12
- **Cron/alert pushes are now clickable.** Turn-finished pushes opened the session
  because they carry `X-Click: hermex://session/<key>`; cron output and infra alerts
  carry no click header, and `postNtfyMessage` set **no content intent** on that path —
  tapping opened the app and stopped at whatever screen was already up.
- Link-less pushes now get a content intent: `hermes-cron` topic → Cron screen,
  everything else → session list. A server-published `hermex://route/<name>` click
  overrides that. Routes are whitelisted (home/cron/skills/config/settings/soul) so an
  unknown route cannot throw in `navigate()`. The app owns its routes — the server
  never needs to learn them.
- **Warm taps fixed**: the deep-link effect was keyed on `activity?.intent`, and
  `setIntent()` triggers no recomposition, so cold starts navigated while taps made
  while the app was already foreground did nothing. Keyed on a counter bumped in
  `onNewIntent` instead.
- Verified on Pixel 10a: cron-channel pushes carry a `contentIntent`
  (`dumpsys notification`), cold start with `open_route=cron` lands on the Cron
  screen, warm tap with `open_route=skills` lands on Skills & Tools.

## v0.1.871 (vc17) — 2026-10-12
- **Native ntfy delivery actually works now.** v0.1.87's subscription invented a protocol
  ntfy does not implement, so it never delivered a single notification: it dialed `/ws`
  (ntfy serves its web app's HTML there → HTTP 200 → the WS upgrade fails → silent
  reconnect loop), sent `subscribe`/`catch-up` client frames (ntfy accepts no client
  frames), read frames by `type` (they carry `event`), de-duped with `id <= lastId`
  (ntfy ids are base64 snowflakes with a random component — NOT lexicographically
  ordered, so that filter drops messages), and read the deep link from `link` (ntfy's
  ATTACHMENT field; the tap target is `click`).
- Correct protocol: WebSocket is `<topic>/ws`, topics share one connection as
  `/a,b/ws?since=<unix-seconds>`; cursor is the message `time` per topic (persisted),
  same-second ids de-dupe replays; deep link from `click`.
- Default topics now cover every topic BigRed publishes to
  (`hermes-out,hermes-sessions,hermes-cron,hermes-alerts`) — hermes-cron/hermes-alerts
  were a silent delivery hole.
- Server side (hermes-ops/app/ntfy.py): deep links publish as `X-Click`, not `Link`.
- Verified on Pixel 10a: all four topics, a real `deliver=ntfy` cron run, and an
  ops-published `hermex://session/...` deep link all arrive as Hermex notifications.

## v0.1.87 (vc16) — 2026-10-12
- **Native ntfy push delivery**: Hermex speaks the ntfy WebSocket protocol itself.
  Cron output, infra alerts, and finished background sessions arrive as plain Hermex
  notifications (own channels, own tap behavior, inline reply) — no ntfy app needed.
  Foreground subscription service with per-topic catch-up (Doze-safe, idempotent by
  ntfy message id). `hermex://session/<key>` links open the chat (shares the live
  turn-notification id, so a late server push replaces, never doubles). Settings →
  Push Delivery: enable, server, topics. Plan + full revert map:
  docs/NATIVE_NTFY_DELIVERY_PLAN.md.
- **Notification watchers gutted** (phase A, 259b796, −595 lines): CronWatcher,
  TurnWatcher 90s alarm, both alarm receivers, manifest entries, alarm/boot permissions.
  Revert: `git revert d89ef84 259b796` (tag pre-ntfy-native = full rollback).
- Kept on purpose: the app's live WS turn/approval notifications (they know screen
  visibility; the server suppresses pushes for watched sessions via leases).

## v0.1.86 (vc15) — 2026-10-08
- Build fix: DebugLog.kt had a duplicate `isLevelEnabled` (added in the v0.1.85 level work) —
  Kotlin `Conflicting overloads` broke `:core:network:compileReleaseKotlin`, so v0.1.85 never
  built on CI and never shipped. Removed the duplicate; v0.1.85's features (attach-4001
  session.create heal, STATE/RPC/WS in exports, Settings level toggles) ship in this release.
- Lesson: run `assembleRelease` LOCALLY before pushing a release tag — v0.1.85 shipped a
  compile error straight to CI.

# Changelog

All notable changes to Hermex Android are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),  
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.1.85] — Hermex 3 (com.hermex.v3) — attach-4001 real fix + debug exports carry the session story
- **`file.attach` 4001 on fresh chats fixed for real**: the 0.1.83 self-heal's fallback assumed
  attaching materializes a session server-side — it never does (`file.attach` is a pure runtime
  lookup; only `prompt.submit`/`session.create` make a row). On a brand-new chat resume 4007s
  (no stored row) and the bare retry 4001'd forever — your log's `file.attach retry failed`.
  Now the heal materializes the session itself: `session.create` → adopt the new DB key → retry.
- **Debug exports now include STATE / RPC / WS lines** ("Session & heal", "RPC calls", "Socket"
  checkboxes, on by default): the entire session-lifecycle + self-heal trail lives in STATE, and
  the default export filter hid it — diagnosing the 4001 trail was blind. Levels are a strict
  filter; empty = everything.

---

## [0.1.84] — Hermex 3 (com.hermex.v3) — log secret redaction + self-healing attach + sessions-list 30s timeout fix

(0.1.83 shipped redaction + attach self-heal; this release adds the sessions fix below.)
- **Secret redaction in the debug journal** (found by the v0.1.82 log itself: the dashboard password,
  ws-tickets, cookies and Authorization headers were logged verbatim and the journal is SHAREABLE).
  Redaction at the DebugLog choke point: `"password"/"token"/"api_key"/"secret"` JSON fields, `"ticket"`
  fields, `Cookie:`/`Set-Cookie:`/`Authorization` header lines, `?ticket=` URLs → `***`. Applies to every
  entry at write time — memory, disk journal, exports, clipboards.
- **Self-healing file/image attach**: sending a file to a reaped session (4001 "session not found")
  used to die with a raw error (seen live in the v0.1.82 log). Attach now runs the SAME recovery
  prompt.submit uses — force a live socket if needed, `session.resume` with 4007 backoff — and retries
  the attach once. Non-4001 errors surface unchanged.
- **Sessions list 30s timeouts fixed**: on a fresh observer socket the fallback built a SECOND
  JsonRpcClient over it and never `start()`ed it — no frame-consumer loop, so `session.list` sent,
  no reply was ever dispatched, and it timed out at 30s (every `observer reconnected` line in the
  field log was followed by exactly that). Now reuses the started `observerClient`.

## [0.1.82] — Hermex 3 (com.hermex.v3) — debug logging rework: logs survive kills, stop self-eviction, measure jank
- **Disk journal**: every entry appends to `filesDir/debuglog/hermex.log` via a write-behind thread (2s
  flush), reloaded on app start. The log now survives crashes AND swipe-up force-kill — the moment you
  need it. HARD CAPS: journal rotates at 2 MB into one `.1` file (oldest dropped, ~4 MB total forever);
  last 10 exports kept; buffer raised 1000→4000 entries.
- **No more self-eviction**: the per-delta "notification MATCHED" lines (~30/sec of streaming) evicted the
  ring buffer in ~30s — an export after a long run contained only the crawl's tail. Deltas are counted;
  each flush batch logs ONE aggregate line (`flush c=32/1450ch t=5/200ch tok/s=41.2`, ~9/sec).
- **Crash handler** records the uncaught exception + flushes the journal synchronously before hand-off.
- **Lifecycle breadcrumbs**: foreground/background transitions logged (+ flush at pause).
- **Perf probe**: while streaming, a Choreographer frame monitor logs per 5s window `stream fps=… worst=…ms
  over100ms=n/frames` — proves main-thread saturation (the "phone slows down" symptom) from the log.
- **Fixed**: Level checkboxes snap-back (same non-snapshot bug v0.1.157 fixed for sections); Search filter
  now has a UI (was dead code — export honored it, nothing set it).
- **Exports persist**: share writes to `filesDir/debugexports/` (survives kill, last 10 kept) instead of
  cacheDir (evictable); FileProvider paths updated; Settings shows disk usage.

## [0.1.81] — Hermex 3 (com.hermex.v3) — streaming UI batching: no more phone slowdown on long runs
- **Long agent runs (heavy thinking/streaming) crawled the chat and lagged the whole phone** until the
  app was force-killed. Cause: every token/thinking delta committed `uiState` individually (~30/sec) —
  full message-list copy + full Markdown re-parse of the ever-growing streaming bubble + scroll pass
  per delta (quadratic in message length). Fix: buffer deltas, commit in ~110ms batches (cap 4096 chars);
  non-delta events flush the buffer first so wire order is preserved; stale-stream watchdog now re-arms
  per flush (~9/sec) instead of per delta. Visually identical stream, ~3x fewer full-tree commits.

## [0.1.8] — Hermex 3 (com.hermex.v3) — Insights DAY bucket: real last-activity clock
- **Insights Daily view was always empty** (Week/Month showed tokens). Cause: `session.list` rows carried
  only `started_at` — `SessionSummary.lastActivityAt` existed and the panel's bucket chain
  (`lastActivityAt ?: lastActive ?: endedAt ?: startedAt`) was ready for it, but `SessionInfo` never parsed
  the server's `last_activity_at` and `toSessionSummary()` never mapped it, so every session bucketed by
  start time. `started_at` anchors near midnight UTC and the panel compares local dates → an evening
  schedule reads "today = 0 sessions" permanently. Fix: parse `@SerialName("last_activity_at")` in
  `SessionInfo` and map `lastActivityAt = last_activity_at`. Pre-feature rows return null → same
  started_at fallback as before, no regression. Requires the gateway to emit the field (companion
  hermes-agent patch `fix(session.list): surface last_activity_at` + gateway restart).
  (`JsonRpcClient.kt`, `SessionsViewModel.kt`.)

## [0.1.7] — Hermex 3 (com.hermex.v3) — Context token readout left of ring; compress button always visible
- **Context token readout:** compact `used / max` token gauge (e.g. `130k / 262k`) now renders immediately left of the ctx ring, using `formatTokens()` for human-readable values. Ring still clickable → ctx details panel. (`ChatScreen.kt` composer Row.)
- **Compress button always reachable:** the ctx details panel now always shows the Compress button (previously gated behind `fraction >= 0.50f || hasUsageData`), so `/compress` is reachable even at low usage.
- **Release fix (CI):** the auto-release workflow was failing at the signing step (`packageRelease` → `KeytoolException`). Root cause: CI only had `KEYSTORE_BASE64` set to a keystore the Android signing tool couldn't parse, and the password/alias secrets were entirely absent — so every release build died before producing an APK. Fix: set all four keystoress secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`) to match `keystore.properties`. Build is JDK-version-independent (same error on JDK 17 and 21), so the earlier "pin JDK 21" change was a red herring and was reverted. (`.github/workflows/release.yml`, GitHub Actions secrets.)

## [0.1.6] — Hermex 3 (com.hermex.v3) — YOLO toggle via /yolo slash command + composer gap
- **YOLO toggle now goes through the existing `/yolo` slash command.** v0.1.5's REST approach (`DashboardApiClient.yoloToggle()` → `POST /api/session/yolo`) was a second copy of approval state that the 9119 dashboard never implemented (it has no `/api/session/yolo` route — only the 8787 webui does), so the button's 405 was "endpoint not found", not a rejection. The gateway already has a real toggle: `gateway/slash_commands.py::_handle_yolo_command` (`/yolo`), which flips the per-session yolo flag. `DashboardChatViewModel.setYolo()` now calls `sendMessage("/yolo")` — no new dashboard route needed.
- **Yolo button state now reads `session.info` instead of a REST GET.** The dashboard's `_session_info()` (tui_gateway/server.py) already computes an authoritative `yolo` flag (ORs the process `--yolo` env, `approvals.mode=off`, and the per-session flag) and broadcasts it via `session.info` RPC + notifications. `DashboardChatViewModel` now mirrors `info["yolo"]` into `ChatUiState.yoloEnabled` in the `SessionInfo` handler — the button's initial and live state comes from the same source the model chip uses, and it updates whenever yolo is toggled from any surface (desktop, Telegram, CLI). Removed the now-unused `DashboardApiClient.yoloStatus()`/`yoloToggle()`.
- **Composer gap:** reduced the vertical gap between the text field and the controls row (attach, mic, ctx ring, send) from 6.dp to 2.dp. (`DashboardChatViewModel.kt`, `DashboardApiClient.kt`, `ChatScreen.kt`, `RpcNotification.kt`.)

## [0.1.5] — Hermex 3 (com.hermex.v3) — YOLO button now responds optimistically
- **YOLO button "does nothing" fixed.** The ⚡ YOLO pill was wired correctly (button → `viewModel.setYolo()` → `DashboardApiClient.yoloToggle()` → `POST /api/session/yolo`), but it never gave feedback: `handleResult()` turns any non-2xx server response into `HttpError`, and the ViewModel only updated `yoloEnabled` on `NetworkResult.Success`. The server's enable path can return non-2xx (e.g. 409 while a gateway approval relay is in flight), so a tap that the server rejected silently did nothing — the pill stayed grey and the user got no signal. **Fix:** the toggle is now optimistic — the pill flips the instant it's tapped, then syncs with the server and reverts on any failure, with a Toast explaining the outcome (`YOLO rejected (HTTP …)` / `Couldn't toggle YOLO`). Same applies to the initial status fetch. (`DashboardChatViewModel.kt`, `ChatViewModelContract.kt`.)

## [0.1.4] — Hermex 3 (com.hermex.v3) — YOLO mode button in composer
- **YOLO button:** amber pill (⚡ "YOLO") beside the ctx ring in the composer. Toggles session-scoped approval-skip — when on, the app auto-approves tool calls for the current session. Mirrors the WebUI's `.yolo-pill` (amber when active, outlined when off). REST via new `DashboardApiClient.yoloStatus()`/`yoloToggle()` (`GET`/`POST /api/session/yolo`), state in `ChatUiState.yoloEnabled`, wired through `ChatViewModelContract` open methods so the legacy SSE ViewModel keeps compiling. (`DashboardApiClient.kt`, `DashboardChatViewModel.kt`, `ChatScreen.kt`, `UiModels.kt`, `ChatViewModelContract.kt`.)

## [0.1.3] — Hermex 3 (com.hermex.v3) — Composer wrapped in single capsule
- **Composer layout:** text field and controls (attach, mic, ctx ring, send) now live inside ONE capsule instead of two separate rows. Controls sit below the text field, all inside the same rounded surface. (`ChatScreen.kt`.)

## [0.1.2] — Hermex 3 (com.hermex.v3) — Removed ctx gauge + token numbers from top bar
- **Top bar:** removed the context-window progress bar and token-count text (`used/max`) that appeared next to the model chip. The model chip stays; the gauge row is gone. (`ChatScreen.kt`.)

## [0.1.1] — Hermex 3 (com.hermex.v3) — WebUI-style composer layout
- **Composer:** text field on top (full width, grows left-to-right then wraps down), controls below (attach, mic, ctx ring, send) — matching the WebUI composer structure. (`ChatScreen.kt`.)

---

## [0.1.170] — 2026-09-21 — Approvals/clarify now work (client never advertised server→client requests)

### Fixed
- **Approvals never reached the user; every tool call was auto-denyed/hung.** When a tool call needs approval the gateway sends a server→client JSON-RPC *request* (`approval`, id `srq-<hex>`) and waits for one response frame on that id. Hermex's WebSocket client did two things wrong, so no approval could ever be answered:
  1. **It never advertised `client.capabilities {server_requests:true}`.** The gateway gates every server→client request (approval, clarify, sudo, secret) behind this capability flag; a client that never sends it is treated as predating the feature and the request is withdrawn *before* it's even sent — producing the exact error "the attached client cannot answer approval requests (update the Hermes app)". **Fix:** `WsConnectionManager.onOpen()` now calls the new `advertiseCapabilities()`, which sends `{jsonrpc:"2.0",id:1,method:"client.capabilities",params:{server_requests:true}}` once per connection generation (mirrors the desktop/TUI). The gateway ignores a `-32601` from an older backend, so this is safe against backends that never adopted it.
  2. **Incoming request frames were dropped as "Unknown".** `JsonRpcClient.processFrame()` keyed on `id.toLongOrNull()`, but server→client requests use string ids (`srq-…`), which return null and fell through to the unrecognized branch — silently, with no response, so the gateway stalled out its full deadline. **Fix:** a new branch routes frames that have a string id + method but no result/error into a `serverRequests` Flow; `DashboardChatViewModel.handleServerRequest()` mirrors the existing approval/clarify notification handlers (parsing params at top level, since server→client requests place fields directly under `params`) and now answers via a new `respondRequest(id, JsonObject)` that sends back `{jsonrpc:"2.0", id:<same>, result:{…}}` on the matching id. Unhandled methods answer `-32601` so the gateway fails fast instead of stalling. The approve/deny/clarify buttons now respond through the captured `PendingApproval.requestId`.

### Notes
- This is the same transport half that makes live turn-finished, cron, and approval notifications work server→client — the root cause gated all of them behind the missing capability flag.

### Fixed
- **Persistent `4001 session not found` after a silent socket drop.** The client already had `submitWithSelfHeal()`: on a 4001 it calls `session.resume(sessionId)` and retries. But that resume runs over whatever socket exists at the moment of failure — and if the WebSocket is dead or mid-reconnect (a silent Tailscale drop leaves the WS unusable while `_state == Connected`/`Reconnecting`), `session.resume` cannot complete on it either, so self-heal loops forever and never recovers. Evidence: a debug log showed repeated "observer unavailable — opening fresh WS connection" + multiple `ws-ticket` fetches, i.e. the observer kept dropping and forcing fresh connections without recovery. **Fix:** before resuming on a 4001, ensure a live socket — if `wsConnection.isConnected` is false, call the new `WsConnectionManager.forceConnect()` (cancels any pending reconnect loop so there's exactly one active socket, fetches a fresh ticket, opens the WS, waits for handshake) and only then run resume + submit. Files: `WsConnectionManager.kt` (`forceConnect()`), `DashboardChatViewModel.kt` (`submitWithSelfHeal`).

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
