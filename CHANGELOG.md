## v0.1.897 (vc43) — 2026-10-19 — session.title routed by frame id; Connection/Push Delivery rewritten for a second user

- **`session.title` never renamed an in-app chat header (fixed, device-verified).** The full diagnosis,
  the two-id pair from the live run and the re-test are written up under the v0.1.896 section below
  ("Phase 2 confirmed on device…"). Shipping side: `JsonRpcClient` keeps the frame's session id as the
  routing id and carries the server's `session_key` separately as `storedKey`; the ViewModel arm drops
  its key comparison because the sid gate has already routed the frame. Two parser tests pin both ids.
- **Everything a second user needs to type is now labelled as theirs.** Onboarding pre-filled
  `http://100.80.204.66:9119` — one developer's tailnet address — and had no username field: the app
  signed every installer in as `"jeff"` through defaults in `DashboardApiClient`, `KeychainStore`,
  `HermexApplication` and the setup ViewModel. A fresh install therefore failed login against an account
  that does not exist on their gateway, and the error said "check password". There is now an explicit
  Username field, no default anywhere in the credential path, and a blank username is rejected with its
  own message. Placeholders/supporting text state what to enter (gateway web port 9119, Tailscale/VPN or
  https address, the login from that gateway's web UI) and that Hermex has no server or account system
  of its own.
- **Push Delivery stopped pretending the developer's ntfy fleet is shared infrastructure.** The section
  said "Keep the defaults for the Hermes fleet" while the compiled-in defaults are one self-hosted ntfy
  host and four topics nobody else publishes to; a new user got a subscription that never connects. It
  now says push is optional and separate from the chat connection, that you need your own ntfy server
  plus a Hermes side publishing to the same topic names, and that access tokens / password-protected
  topics are NOT supported yet (no auth header anywhere in `NtfySubscriptionService` — confirmed by grep,
  so it is documented as a limit rather than left to fail). When nothing is saved the section prints the
  built-in values in numbers and marks them as the developer's, instead of pre-filling them into the
  fields where they read as the user's own setting.
- **Not verified on device yet:** the settings/onboarding copy changes are compile- and test-verified only
  (Gradle unit tests + release build for this version). A fresh-install walkthrough — clear credentials,
  set up from scratch, check the second-user path — still needs a phone pass. Installs that already
  saved credentials see no behaviour change.

## v0.1.896 (vc42) — 2026-10-19 — audit Phases 2, 4, 5, 6, 8: lifecycle, honest notifications, protocol arms, RFB byte math

- **Phase 5 — held chat ViewModels and the microphone.** `ChatScreen` released its `MediaRecorder` only
  inside `stopRecording()`; navigating away mid-recording left the mic claimed. Now released through a
  `DisposableEffect` on dispose (`runCatching { rec.stop() }` then `release()`). `ChatVmsHolder` kept
  every chat ViewModel it had ever created, unbounded; it now holds at most `MAX_HELD_CHATS = 6` in LRU
  order and `pickToEvict` refuses to evict the current chat or one with a turn still running
  (`ChatVmsHolderEvictionTest`, 7 tests, including the exactly-at-cap no-evict case).
  **Correction to the audit's Phase 5 framing:** the "three ChatVMs re-registering the same session"
  evidence I used for the multi-VM risk (#12/#13) is wrong — those three log lines carry three distinct
  dbKeys, i.e. three different sessions, which is intended behaviour. No refactor was done against it.
- **Phase 6 — foreground services and notification honesty.** `WsKeepaliveService` now handles a null
  intent (a START_STICKY redelivery means no chat ViewModel owns this service) by logging, `stopSelf()`
  and returning `START_NOT_STICKY` instead of showing a "Connected" notification for a connection nobody
  asked for. Both foreground services gained an explicit Stop action (`ACTION_STOP`,
  `STOP_REQUEST_CODE` 8081/8091); stopping ntfy persists `ntfy_enabled=false` so it does not come back
  on next launch. A reply that timed out posted the same "finished" notification as a completed turn:
  there is now a separate `postReplyTimedOut` ("no completion came back", Reply still offered) and
  `postReplyFailed` stays distinct — Sent / TimedOut / Failed are three different claims
  (`WsKeepaliveServiceTest`, 3 Robolectric tests using `shadowOf(service).isStoppedBySelf()`).
  **Audit claim corrected:** Phase 6 assumed a WorkManager `onTimeout` recovery path; there is no
  WorkManager in this app and dataSync foreground services get no `onTimeout` callback. The real recovery
  path is start-on-connect / start-on-launch, which is what the null-intent guard now makes honest.
- **Phase 8 — dead code removed:** an unreachable `if (true) {` branch in `ChatScreen`, the unused
  private composables `LiveThinkingPreviewLine` and `ThinkingToggle`, and the no-op `loadYoloStatus`
  plus its contract declaration and call site (YOLO state arrives with `session.info`).
- **Phase 4 — RFB server-message byte math.** Two payload lengths in the read loop were wrong in the
  direction that never raises: Bell (type 2) has NO payload but the client consumed 7 bytes — six of them
  belonging to the next message — and SetColourMapEntries' n x 3 channels x U16 payload was never
  consumed at all. A short read there does not fail; every later rectangle header lands bytes off-centre
  and the picture goes blank while the socket stays up. Bell is not theoretical here: the client sends no
  SetPixelFormat and `printf '\a'` on the remote host emits one. ServerCutText (type 3) was unhandled and
  fell through to "unknown", ending the connection; it is now parsed and ignored with one log line. An
  unknown type now throws `RFB protocol error: unknown server message type N` instead of logging and
  returning, because a silent stop is how a blank screen hides. The lengths moved into two pure functions
  (`headerLength` / `payloadLength`) so they can be tested from RFC 6143 fixtures without a socket —
  `RfbProtocolLengthTest`, 7 tests: Bell zero, ColourMap 5+6n for n=0/3/255, CutText length parsing,
  ServerFbUpdate size at offset 13, unknown types raise, plus a deterministic and a 200-case randomized
  concatenation that must walk to exactly the end of the stream. Writing the fixtures caught an error in
  my own test first (SetColourMapEntries has ONE padding byte, not three), which is the value of
  fixtures over prose.
- **Phase 2 — nine dropped notifications, not five.** The audit listed five names as being dropped;
  counting the trial journals' own `unknown_event` lines gives nine: `platforms.changed` (1633 lines),
  `agent.terminal.output` (256), `cron.changed` (81), `status.update` (53), `subagent.tool` (15),
  `subagent.thinking` (13), `review.summary` (6), `subagent.progress` (2), `session.title` (1). All are
  real server-side events. Six are now typed with the emission site and payload contract cited in each
  KDoc, and parsing goes through non-throwing field reads (`as? JsonObject` + `strField`) so a
  `"payload":null` or string payload falls back to `Unknown` instead of taking down the WS reader
  coroutine — the pre-existing arms used `?.jsonObject`/`?.jsonPrimitive`, which throw on exactly those
  frames. A malformed payload deliberately routes to the Phase 0 unknown counter rather than fabricating
  an empty typed event, because the counter exists to catch protocol drift.
  - `session.title` is the one that had a real surface, so it is rendered: auto-titling renames a session
    after the first turn, and until now the phone kept the placeholder title it invented for itself
    (`sessionId.take(16)`) until reopened. The handler matches on the STORED KEY from the payload
    (`SessionTitlePayload.session_id` is the stored key, while the frame's own `session_id` is the live
    sid — the two differ, and the parser test pins that the parser keeps the right one).
  - The other five (`status.update`, `agent.terminal.output`, `review.summary`, `cron.changed`,
    `platforms.changed`) are parsed but **not rendered**, and that is stated in code rather than papered
    over with invented UI: there is no transient-status slot, no process surface anywhere in the app
    (`process_id` appears in no client model), no notice/inbox surface, the cron list is fetched
    imperatively inside `CronScreen`'s `remember` with no state holder to invalidate, and platform status
    has no screen at all. Each instead emits one bounded `TRACE|proto` line with ids and counts only
    (`kind=`, `textLen=`, `proc=`, `chunkLen=`) — never payload text, because these payloads carry raw
    background-process output and review text and the debug journal is shareable. A per-name volume gate
    logs the first occurrence then every 25th while keeping the running count in the line, so a
    suppressed burst is still counted (`ProtocolTrace` + `ProtoTraceGate`; `platforms.changed` fires on a
    5 s floor and would otherwise evict DebugLog's ring).
  - Still open, named rather than guessed: the three `subagent.*` events. The gateway already mirrors
    child activity into ordinary `tool.*` / `message.*` frames for a live watch window
    (`tui_gateway/agent_callbacks.py`, `tool_progress._progress_subagent`), so typing them client-side
    without deciding how a delegate's tool activity should render risks double-rendering, not missing UI.
  - Tests: `UnknownEventParsingTest` (17 — payloads copied verbatim from the journals or from the
    emitter where the client's own `take(200)` truncated the log line, malformed/partial/null/wrong-type
    payloads fall back without throwing, `chunk:""` stays typed, change signals with no `payload` key are
    still valid) and `ProtocolTraceTest` (9 — ids/counts only, payload text never leaks, gate sequence
    1/25/50, per-name independence).
- **Found while installing this build on the phone — a startup ANR, caused by an earlier change of mine.**
  Three launches in a row died with `Process … failed to complete startup`; the trace names it exactly:
  `HermexApplication.onCreate → DebugLog.init → reloadFromDisk → readTail`, where `readTail` was
  `f.readLines()` with the byte budget applied *after* the whole file was read and split — on the main
  thread, from `Application.onCreate`. That was harmless while the journal rotated at 2 MB; it stopped being
  harmless when v0.1.894 raised the rotation ceiling for debug/harness builds, because the previous
  session's file is now routinely tens of megabytes (the Pixel had 64 MB + 6.3 MB on disk at the time).
  `readTail` now seeks to the tail (`skip(start)`, drop the partial first line, parse at most
  `MAX_ENTRIES`), so the work is bounded by the budget instead of the file size. Measured on the same
  device with the same 70 MB journal pair still present: cold start `TotalTime: 2269 ms`, process alive,
  zero ANRs — versus >15 s and killed, three for three, before the fix.
  `DebugLogJournalReloadTest` (6 tests) guards it with a **measured** counter, not an asserted one:
  `lastTailBytesTouched` counts bytes actually transferred through a counting stream, and the test requires
  it to stay inside the budget. Mutation-checked: making the implementation read from byte 0 again fails
  exactly the two budget assertions.
- **Verification of this release.** Full unit suite — `:app`, `:core:network`, `:core:data`
  `testDebugUnitTest` plus `:app:assembleDebug`: **BUILD SUCCESSFUL in 14m30s, 22 suites / 141 tests /
  0 failures** (was 21 suites / 135 before this round; +49 tests across six new files listed above). The
  built APK's manifest was read back before installing (`aapt2 dump badging` → `versionCode=42
  versionName=0.1.896`) rather than trusting the build output path. On the Pixel 8 (`39151FDJH00HX4`):
  installs over v0.1.894, cold start `TotalTime: 2269 ms` with the 70 MB journal pair present, process
  alive, no ANR — that is the startup fix measured against the fixture that caused it.
- **Not verified at the time of writing, then verified — with one of my own diagnoses corrected.** I first
  wrote that the phone's Tailscale had been down since its reboot; the `ENETUNREACH` was real, but my check
  was wrong: Android's interface is `tun0`, not `tailscale0`, and `ip addr show tailscale0` finds nothing on
  a healthy device. Opening the Tailscale app brought the VPN up (`tun0 100.101.185.85/32`, route
  `100.64.0.0/10`, ping to BigRed 5.6 ms) and the app reconnected immediately. With that fixed, the
  connected checks were run on the Pixel 8 and one of them found a real bug — see the next bullet.
- **Phase 2 confirmed on device, and `session.title` needed a second fix that only the device could show.**
  After the v0.1.896 process start (`app process start (v0.1.896)` at ts 1792433853) the journal contains
  **zero** `unknown event:` lines, where the same file holds 230 of them from earlier builds; the typed arm
  instead logs `TRACE|proto|name=platforms.changed count=1 … signal`, and the volume gate suppressed the
  rest of the burst while keeping the count.
  `session.title` arrived but was dropped: `disp=… type=session.title disp=FILTERED nSid=20261019_191332_38e061
  dbKey=18c053ac`. Cause, from the gateway source (`prompt_turn.py`: `_title_key = session.get("session_key")
  or sid`, emitted against `sid`): a title frame is addressed to the **session id** while its payload names
  the **session key**, and for a chat created inside the app those two differ — `session.create` returned
  `18c053ac`, the key is `20261019_191332_38e061`. My parser had put the key into `sessionId`, which is the
  field the ViewModel's sid gate routes on, so the gate filtered it out — in exactly the case where a title
  matters. Fixed by keeping the frame id as the routing id and carrying the key separately (`storedKey`),
  with no id guard in the arm because the gate has already routed the frame. Re-tested on the same device:
  `type=session.title disp=APPLIED` on the owning VM, `session.title applied — header renamed (titleLen=30
  sid=45e66970 key=20261019_193355_2d9a1c …)`, a second open ViewModel correctly logging `disp=FILTERED`
  (so cross-chat titles still can't leak), and the screenshot showing the header changed from "New session"
  to the new title. Two parser tests now pin the id pair from that live run; total for that file 18 tests.

## v0.1.895 (vc41) — 2026-10-19 — R8 keep rules retargeted at the real package; QA harness checked in
- **What was wrong (audit finding #21, Phase 7):** every package-scoped rule in `app/proguard-rules.pro` named `com.hermex.android.*` — the frozen v2 package. This app is `com.hermex.v3`, with DTOs in `com.hermex.core.network` and `com.hermex.core.data`. So `-keep class com.hermex.android.HermexApplication`, the Retrofit interface keeps for `.data.remote.**`, the `**$$serializer` / `Companion` / `serializer()` keeps, and the blanket `.data.** .model.** .domain.** .di.** .ui.**` keeps all matched nothing. Release builds are minified (`isMinifyEnabled = true`, `isShrinkResources = true`), so this was live config, not dead text.
- **Why it appeared to work:** the package-INDEPENDENT rules covered the same ground by accident — `-keep @kotlinx.serialization.Serializable class * { *; }`, `-keep class * extends androidx.lifecycle.ViewModel`, Room's annotation-driven keeps. Correcting the package names without removing any effective rule means this change can only add coverage, never reduce it.
- **Library blocks removed:** Hilt/Dagger, Retrofit, Moshi. Verified absent from the release runtime graph with `./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath` (0 matches); Gson 2.8.9 IS present transitively, so its keeps stay, as do OkHttp/okio, Room, Coil, androidx.security-crypto (Tink) and kotlinx rules.
- **Verification (static, on this build):** `assembleRelease` → BUILD SUCCESSFUL in 2m12s. `app/build/outputs/mapping/release/mapping.txt` now shows the rules matching real code: `com.hermex.v3.HermexApplication -> com.hermex.v3.HermexApplication:` (kept, unobfuscated) and 51 `$$serializer` entries under `com.hermex.core.network.*` kept by name.
- **Behaviour-neutral evidence:** the comparison APK was built from the **v0.1.894 tree with only these rules changed** (same versionCode, so the rules are the only variable), and it came out byte-for-byte the same size as the published CI `v0.1.894` asset — 30,930,616 bytes — which is what you expect when the kept set does not actually change. Supporting evidence, not proof; and see the next line for what was not done at all.
- **Not verified on device.** The phone currently runs a debug-signed build, and a release-signed APK cannot overlay it (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), which would mean an uninstall and re-login. So this release was checked through the mapping file and the CI artifact path, not driven on the phone. Say so if you install it: it needs an uninstall.
- **Signing checked while we were here:** the published `v0.1.894` asset and this local build share one signer — `CN=Hermex Android`, SHA-256 `fde07f5cec794ed95f3f8a09c634cad7d2057b15919247a90c1ea1d319b4cb2c`. No keystore drift at the moment, so Obtainium can update in place from CI artifacts. (This was a real past failure mode; worth re-checking after any CI secret change.)
- **`tools/qa/`:** the on-device fault-trial harness used for the v0.1.892–v0.1.894 work is now in the repo (`drive_trial.py`, `trial_harness.py`, `delivery.py`, `mark.py`, `p5_report.py`, `h_check.py`, `rebucket.py`, `recapture.py`, `run_batch.py`, `panel_gate.py`) with a README covering scenarios A/C/D/E/F/G/H, why scenario B is unscriptable, and the measurement rules that came out of being wrong (screenshots arbitrate; arm on the app's own `prompt.submit`; quote n and configuration; per-ViewModel dispositions; journal span coverage). Every count quoted in this changelog should now be reproducible without a private working directory.

## v0.1.894 (vc40) — 2026-10-19 — P5 instrumentation + four render/recovery fixes, then a three-model review round of this diff

- **Instrumentation first, because every earlier long-run investigation was blind without it.**
  - A1 — every socket open mints a short client connection id (`ClientConnId`, 8 hex chars), logs
    `WS|conn cid=… gen=… reason=…` once, and stamps `cid=` (plus `seq=` and `type=`) on the existing
    `TRACE|frame` line and on every `ws_cb`. The gateway only ever sees its own transport id, so
    "frames routed to transport X" and "frames parsed by this app" could not be joined before; that is
    exactly the open question in the corrected D/14 facts.
  - A2 — one disposition line per notification per displaying ViewModel:
    `TRACE|disp cid= sid= seq= type= disp=APPLIED|FILTERED|NO_ROW vm=<identityHash>` (FILTERED carries
    `nSid=/dbKey=/liveSid=`). This separates "the frames never reached the phone" from "they reached the
    parser and this ViewModel dropped them", which no existing log line could distinguish. Volume is
    deliberate (~500 lines/turn); `RpcNotification` gains parser-stamped diagnostic fields
    (`frameSeq`, `wireType`) that are not constructor values, so parsed-value equality is unchanged.
  - A3 — `DebugLog` rotation is now an explicit knob: release default stays 2 MB, a debug/harness switch
    (`setJournalRotationDisabled`) raises it to a 64 MB ceiling so a trial's journal is ONE file the
    harness can pull whole, and **any** rotation writes a `TRACE|rotate file=… bytes=… threshold=…`
    marker. The D/14 turn window covered 3 seconds of its own 30 because rotation was silent.
    Release behaviour is otherwise unchanged (`HermexApplication` calls the switch only under
    `BuildConfig.DEBUG`).
  - A4 — every liveSid change (`event=live_sid old= new= reason=`), every sid binding the gateway hands
    out (`event=sid_bound`), and ViewModel register/unregister (`event=register|unregister dbKey= vm=`)
    are logged. All liveSid writes go through one `setLiveSid()`.
- **FIX 1 — Reconcile rule C deleted.** It compared the gateway's persisted `message_count` with the
  app's rendered row count: different quantities, so it was a permanent false positive (235 of 315 gate
  evaluations across 37 journals; offsets stable or inverted). It also masked rules A/B/D by
  short-circuiting behind a useless reload. No count-based replacement; A/B/D unchanged
  (`ReconcileNoCountRuleTest`, old rule-C test deleted).
- **FIX 2 — the foreground poll loop no longer dies at its first reconcile.** The `break` after
  `loadMessages()` ended `while (screenVisible)` permanently: 274 of 315 loops logged exactly one tick.
  Polling now continues while the screen is visible, with the full-history reload rate-limited to one
  per 30 s per session and every suppression logged
  (`PollLoopReconcileTest`: ticks=4, reloads=1, suppressed=1).
- **FIX 3 — `message.complete` is an id-keyed upsert, never a silent no-op.** A terminal event for a
  session with no assistant row used to do nothing at all (P2 testbed SECOND_VM@422 → `NO_ROW`). It now
  matches by server message id, then the live streaming row, then the last assistant row as before, and
  otherwise creates the row with the delivered text; a terminal event carrying neither text nor tools logs
  `disp=NO_ROW` instead of inventing an empty row.
- **FIX 4 — a rotated live sid that belongs to THIS session is adopted, not dropped.** Live sids are
  per-attach, so any second attach of the same chat rotates the sid and the first ViewModel's copy goes
  stale — every later frame was filtered (reproduced: 423 of 424 frames parsed and dropped, row blank).
  Each `session.resume(dbKey)` response records the dbKey→liveSid pair in `SessionSidRegistry`; a frame
  carrying a mapped-but-unfamiliar sid is now accepted, `liveSid` is rotated, and it is logged
  (`TRACE|identity liveSid rotated old=… new=…`). Frames for sessions we never bound stay filtered
  (asserted separately).
- **Re-run evidence.** The P2 sweep config `SECOND_VM_SID_ROTATION` at all 424 insertion indices: 0 blank
  rows for either ViewModel, full answer (1290 ch) everywhere, `filteredLines=0`
  (`artifacts/p2fix/matrix_sid_rotation_after_fix.csv`, `summary_sid_rotation_after_fix.txt`) — previously
  vmA was blank at idx 0 and held truncated text with `streaming=true` elsewhere. `P2FrameReplayTest`'s
  positive-control assertion flipped to "no blanks anywhere", which the full sweep now upholds.
  Mutation-checked per fix: restoring rule C fails only the two count-gap assertions; restoring the loop's
  `break` fails only the poll-loop test; disabling the terminal upsert fails only the two FIX-3 tests;
  disabling sid-rotation adoption fails only the FIX-4 test while cross-session filtering still passes.

### Review round on this exact diff (ChatGPT / Grok / Claude, one shared brief: `hermex-qa/longrun/P6_brief.md`, 7 questions, all answered by all three)
All three returned **SHIP-WITH-CHANGES**. Their required changes are in this release except where explicitly declined below; the panel's own thread URLs are in `hermex-qa/longrun/P6_panel_run.log`.
- **The terminal upsert can no longer overwrite an earlier turn's answer** — flagged independently by all three. The target-row choice moved out of the ViewModel into `Reconcile.terminalUpsertTarget`: exact server message id → the row actually streaming → a last-assistant fallback ONLY when it belongs to this turn (it comes after the last user message and is either our own `asst_` placeholder or carries the same server id) → otherwise create a new row. A refusal logs why (`TRACE|upsert … REFUSED a fallback row: <reason>`), because "we chose to create a row" has to be distinguishable from "we lost the answer". 7 tests in `ReconcileTerminalUpsertTest`; mutation-checked — disabling the two guard conditions fails exactly those 3 guard tests and nothing else.
- **The reload cooldown never holds back the blank-row recovery** — all three flagged the 30 s constant as turning a recoverable bug into a visible failure. `Reconcile.ruleFor()` now returns *which* rule fired ("A"/"B"/"D") and `needsReload` is its boolean wrapper; the poll gate rate-limits A/B but exempts D, and `gate_poll` logs `rule=`. Tests: 5 in `ReconcileBlankTurnTest`, including a matrix over 6 message states × 3 `running` values × 2 histories × 2 completion flags × 2 submission flags asserting the boolean and the identity can never disagree.
- **"Reloaded and still blank" now ends somewhere** (open item from the first review round). Rule D is exempt from the cooldown, so it is bounded instead: at most 3 blank-row reloads per turn, each logged with its attempt number, then `rule=D reload STOPPED — … the answer is not in the server history we can read`. Without this bound a genuinely missing server-side answer would have reloaded every 2–3 s forever.
- **FIX 5 — rule D also accepts "this screen asked the question"** as turn knowledge. Claude found the hole FIX 1 left: rule C's permanent false positive meant almost every chat-open did an accidental full reload, which sometimes rescued a completely lost turn; with C gone, a turn that starts *and* finishes inside a disconnect gap is invisible to A (the stale-stream watchdog has cleared the placeholder's spinner), B (we never saw `running=true`) and D's old gate (no completion event ever arrived). The app does know one thing nobody else in that sequence knows: that it submitted the prompt. `localTurnSubmitted` is set on prompt/slash submit, counts only while the final row is still blank, and is cleared the moment text lands — so it cannot drive a reload loop. Tests: `blankRowFromOurOwnPromptWithNoServerSignals_reloads` (asserts the old gate was blind) and `ourOwnPromptFlagCannotFireWhenTheAnswerIsThereOrStillComing`.
- **One live sid, one chat.** `SessionSidRegistry` gained a reverse index (`sid → dbKey`): a claim by a different session takes the sid away from the older key and logs `event=sid_conflict`; `forget(dbKey)` runs on `session.delete`, so a deleted session's sids stop being accepted anywhere. 7 tests in `SessionSidRegistryTest`: rotation history stays accepted, conflict transfers ownership, forget clears both directions, per-session history is bounded at 32, degenerate inputs ignored.
- **Disposition lines name the chat and carry the frame's own connection id.** `RpcNotification.frameCid` is stamped at parse time in `JsonRpcClient.handleNotification`, so a `disp` line reports the socket the frame actually arrived on rather than whichever connection is current when the ViewModel gets around to handling it — after a reconnect those differ, which would have mis-attributed exactly the client/gateway join this instrumentation exists to make. Lines now also carry `dbKey=`; `JournalLineInstrumentationTest`'s line-contract regex updated to require it.
- **Declined: the redaction pre-filter optimisation is out of this change** (all three: keep a security-sensitive change separate from behaviour changes; it needs differential-equivalence and canary tests of its own). `DebugLog.redactSecrets` is byte-for-byte the previous implementation again. The rotation knob and the `TRACE|rotate` marker stay — they are measurement infrastructure, not behaviour.
- **Declined for now, recorded:** replacing rule C's lost coverage with a seq-gap detector (the app now has a per-frame `seq`, so that is the right replacement signal; not built, no evidence yet that it is needed — FIX 5 covers the concrete sequence a reviewer produced); sampling or gating `disp` lines (the journal is debug-only: `HermexApplication` initialises `DebugLog` under `BuildConfig.DEBUG`, so release builds never write them); pruning `cidByGen` in `ClientConnId`.
- **Verification.** Full unit suites for `:app`, `:core:network` and `:core:data`: 13 suites / 70 tests / **0 failures** (includes the 424-index frame-replay sweep), `BUILD SUCCESSFUL in 14m38s`; targeted subset over every touched surface re-run green after FIX 5; each fix mutation-checked.
- **Device (Pixel 8 `39151FDJH00HX4`, 0.1.894/vc40, matched three-ChatVM config, varied prompts) — note `HermexAndroid/p5-device-verification-v0.1.894-2026-10-19.md`.** Scenario D N=10 (mid-turn socket kill at +20 s): 9 valid trials, **9 rendered answers, 0 blank rows**, every trial `DELIVERED-AND-ACCEPTED` (e.g. gateway routed 522 frames → client ingress 524 → one ViewModel applied 524 while the other two filtered their copies). Control N=8 with no fault: **7 PASS / 1 INCONCLUSIVE** (a provider stall at 0.0 tok/s, not an app fault). Across the scenario-D journals: **9 of 9 poll loops reached ≥2 ticks** (previously 274 of 315 stopped at one), and `gate_poll rule=` shows **zero count-based reloads** with only the 9 legitimate rule-B cases produced by the kills themselves. Journals span 94 %+ of each window with zero rotation markers.
- **Still unproven on device:** rule D actually healing a blank row (no blank rows occurred in these 18 trials — its reachability is now real, the recovery itself is JVM-tested only), and D/14's original mechanism (H1 vs H2), which predates this instrumentation.

## v0.1.893 (vc39) — 2026-10-19
- **A finished turn could render nothing.** Found while measuring mid-turn socket drops on the Pixel 8
  (trial D/14, note `HermexAndroid/drop-trial-harness-v0.1.892-2026-10-18.md`): the prompt was
  accepted, the gateway routed the whole answer — `message.start`, 258 `message.delta`, and
  `message.complete` — into the phone's live transport (`route=session_transport`) about five seconds
  *before* the socket was killed, and the app ended up holding an assistant row with no text in it.
  The user saw their own message and no reply; the tool card sat incomplete until the watchdog
  force-closed it.
  - The first hypothesis — that the actively-rendered session had no replay coverage — was
    **disproved by the frame trace**: delivery had already happened. The failure is local state.
  - Why nothing recovered it: at the next resume the reconcile gate logged
    `running=false localInFlight=false turnDoneSeen=true reconcile=false`. Nothing was streaming, no
    tool card was open, and the server's `message_count` EQUALLED the local row count — because the
    blank row counts as a row. All three existing rules (streaming/open card, running-flag
    transition, count gap) are blind to that state.
  - **Fix:** `Reconcile.needsReload` gains rule D — if the server says the turn is over and we know a
    turn ran in this chat, a blank final assistant row means our copy is wrong, so re-read the
    history. The turn-knowledge condition (`serverTurnWasRunning || turnDoneSeen`) is what stops an
    old blank row from an aborted turn reloading on every poll.
  - `gate_poll` now logs `turnDoneSeen=` and `blankFinalRow=`. Without those two inputs the D/14 log
    read as "nothing to do", which is why the failure looked unexplained for a day.
- **Known limitations, from an external review round of this change (GPT/Grok/Claude, same brief).**
  - The drop was NOT causal in D/14: that turn's frames end 01:08:11 and the socket was killed at
    01:08:16, so the row was already blank on a live socket. The trigger is not network-specific, and
    the earlier description "the turn completes during the disconnect window" was wrong.
  - Rule D covers only the foreground-poll gate. The reconnect gate (`DashboardChatViewModel.kt:1339`)
    still requires something streaming or an open tool card, so a blank non-streaming row is not
    reconciled there — recovery waits for the next poll instead of happening on rebind.
  - **Delivery to the phone was never proven, and an earlier claim about it was wrong (corrected
    2026-10-19).** This entry originally said the frame trace proved the answer had been delivered to the
    phone and only the local state failed. The gateway trace proves frames were *routed* to a
    `session_transport` — for D/14 that transport is the phone's socket (`transport=715e85ef3350`, which
    detached with `reason=ws_disconnect` at 1792372096, exactly our kill moment) — but the app-side journal
    for that window is too incomplete to show whether any of those frames reached the parser: in the two
    surviving capture files, the 30-second turn window contains journal lines for only three seconds
    (…076, …096, …098). The supporting count I used before ("424 gateway frames vs 445 inbound client frame
    lines") is invalid: those client lines carry `sid=955c00b6` — a different session — not the probe chat's.
    So D/14 sits between two live hypotheses: the reducer lost applied frames (reproducible in JVM, see
    `longrun/P2_replay_results.md`), or the frames never reached the app because they went to a different
    live connection. On the second: an earlier note here said the gateway rebound the session "mid-turn" —
    that was wrong. Re-checked against the trace, `message.complete` was routed at 1792372091.47, our kill
    landed at 1792372096, and the rebind at 1792372098 is the ordinary reconnect (D/44 shows the same
    shape). What the trace does show is that `turn_start reason=prompt_submit` arrived on transport
    `715e285fbed0` while the session had been attached 63 µs earlier to `715e85ef3350` — two live
    connections for the same phone at prompt time. Settling it needs per-frame send confirmation on the
    gateway plus an un-rotated client journal, both keyed by a client-chosen connection id.
  - Whether the server's own final message had content in D/14 was never captured; if it was blank too,
    no client-side reload can surface an answer. No test covers "reloaded and still blank".
  - **Reachability, measured after shipping (2026-10-19, note
    `HermexAndroid/poll-loop-dead-ruleC-falsepositive-2026-10-19.md`):** `Reconcile.needsReload` is called
    from one place — the foreground poll loop — and that loop does not survive its first tick. Across 37
    captured journals there were 315 `gate_poll` evaluations, 239 of them requesting a reload, **235
    attributable to rule C alone** (`serverMessageCount > messages.size`), and every reload ends with
    `loadMessages(); break` (`DashboardChatViewModel.kt:2337`), which exits the `while (screenVisible)`
    loop permanently: **274 loops logged exactly one tick**, 19 reached a second. Rule C compares the
    gateway's persisted transcript counter (`sessions.message_count`) against the app's rendered row count —
    different quantities, offsets stable (`count=46 local=38`, `count=100 local=90`) and sometimes inverted
    (`count=230 local=375`) — so it fires on essentially every evaluation, reloads, and kills the loop.
    Practical consequence: rule D is only evaluated when a chat is opened, so a blank row that appears while
    the chat is already open is never re-checked, and the device tallies below show that the common recovery
    path works — they are **not** evidence that rule D did anything.
- **Verification.** Unit: `ReconcileBlankTurnTest` (6 cases, incl. a blank row while the server is
  still running, which must NOT reload) — 25 tests green. Mutation-checked: disabling rule D fails
  exactly the two D/14 assertions and nothing else. Device, scenario D (socket kill at submit +20 s):
  N=10 in a single-Chat-ViewModel configuration recovered 10/10, and a matched N=5 run
  (trials D/41-D/45, three live Chat ViewModels verified from each journal's three
  `reconnect detected - re-registering session` lines — the same configuration the 9/10 baseline was
  measured in) recovered 5/5. No spurious full-reload fired in any trial and no duplicated message
  appeared in any screenshot. Honest limit: the lost-message race needs the turn to finish inside the
  ~2.5 s dead window, so it appeared once in ten baseline trials and has not been reproduced since;
  rule D is pinned by unit tests against that exact state, not by a fresh device observation of it.

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
