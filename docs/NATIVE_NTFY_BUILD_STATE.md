# NATIVE NTIFY — BUILD STATE (live handoff, update every milestone)

Repo: ~/HermexAndroidV3 · origin gravol/hermex-android-v3 · start HEAD 59edda2
(v0.1.86 / versionCode 15). Spec: docs/NATIVE_NTFY_DELIVERY_PLAN.md (authoritative).
If context compacts: read this file + the spec, `git log --oneline -8`, resume here.

## Progress ledger
- [x] 2026-10-12 17:20 baseline verified: clean tree @59edda2, remotes ok
- [x] 2026-10-12 17:20 server-side prerequisites (already live, NOT app work):
      ntfy server :8080, plugin deliver=ntfy, turnwatch, cron 4778dfbd3e46 (07:00 PDT)
- [x] 2026-10-12 17:25 Phase 0 DONE: tag pre-ntfy-native @59edda2 pushed to origin
- [x] 2026-10-12 17:40 Phase 1 DONE: commit A 26c3272 (amended) = -595 lines:
      4 watcher files, manifest receivers+alarm perms, 9 call sites, zero residue (grep-verified).
      KEEP-DECISION: app's live WS turn/approval notifications (DashboardChatViewModel+
      NotificationHelper) STAY — they know screen visibility; server lease-suppression
      prevents duplicates. Server INTERACTIVE set needs "dashboard" platform (verify Phase 2).
- [x] 2026-10-12 18:10 Phase 2 DONE: commit B = d89ef84 (final, after 2 compile fixes amended in):
      NtfySubscriptionService.kt (WS+catch-up+backoff), NotificationHelper.postNtfyMessage
      routing (link→chat-opens w/ shared id = no doubles; priority≥4→alerts; else cron),
      reply→hermes-in via NtfyClient, Settings "Push Delivery" section, manifest service,
      version bump 0.1.87/16 in-commit. Server: turnwatch INTERACTIVE += "desktop".
      COMPILE FIXES (amended into B, lesson-worthy):
        1. `this` inside serviceScope.launch = CoroutineScope, not the Service →
           postReplyFailed(this@NotificationReplyService, ...).
        2. OkHttp has no dispatcher.shutdown() — share one OkHttpClient via `by lazy`.
        3. Nullable link chain: link?.takeIf{…}?.removePrefix(…) (smart-cast rules).
- [x] 2026-10-12 18:25 Phase 3a: BUILD SUCCESSFUL (2m9s). Pushed master 59edda2..d89ef84.
      Order on origin: 259b796 (A) → d89ef84 (B). Revert = git revert d89ef84 259b796.
      CI flow: version bump IN B → CI builds from pushed HEAD, creates tag v0.1.87 + release.
- [x] 2026-10-12 18:30 Phase 3b DONE: CI run 38288336624 success → v0.1.87 release live
      (isDraft=false, app-release.apk 30.8MB uploaded, aapt2: versionCode=16 vc confirmed,
      dex contains NtfySubscriptionService x13 — the real built code). Docs-only CI run
      failing = guard path, harmless. CHANGELOG + handoff pushed (e457b8d).
- [x] Phase 4 DONE 2026-10-12 21:15 PDT: v0.1.87 (vc16) installed via Obtainium, ntfy
      phone app uninstalled. TEST FAILED — zero notifications. Root cause: v0.1.87's
      subscription invented a protocol ntfy does not have (see below). Fixed in
      v0.1.871 (vc17, commit 51713ce), installed via adb, ALL RAILS VERIFIED:
      hermes-out / hermes-sessions / hermes-cron / hermes-alerts publishes, a real
      `deliver=ntfy` cron run (job 9e58c70c83c5), and an ops-published
      `hermex://session/...` deep link → all arrive as Hermex notifications
      (alerts channel for priority>=4 + session links, cron channel for the rest).
- [ ] Phase 5: handoff/CHANGELOG docs, skill update

## Protocol ground truth (ntfy 2.24, verified 2026-10-12 — the spec's assumptions were wrong)
- WebSocket endpoint: `<topic>/ws` (101 upgrade). `/ws` = SPA HTML (HTTP 200) → OkHttp
  handshake fails. Multi-topic: `/a,b/ws` = ONE connection. `/stream` does not exist;
  SSE is `<topic>/sse`.
- Catch-up = `?since=<id|unix-seconds|duration>` on the upgrade URL. ntfy accepts NO
  client frames — `{"type":"subscribe"...}` / `catch-up` frames are ignored server-side.
- Frames carry `event` (open/message/keepalive/poll_request), NOT `type`.
- Message ids are base64 snowflakes with a random component: NOT lexicographically
  ordered, NOT numerically monotonic (measured: 10 publishes in order decode out of
  order). `id <= lastId` is NOT a duplicate test. Order by `time` (unix seconds);
  de-dupe ids within the same second.
- Deep link = `click` field (publish header `X-Click` / `Click`). `Link` header =
  ATTACHMENT url, never reaches the message JSON → hermex:// links published as Link
  were silently dropped (hermes-ops/app/ntfy.py fixed to X-Click).
- Topics BigRed publishes to: hermes-out (gateway replies + cron deliver=ntfy),
  hermes-sessions (turnwatch), hermes-cron + hermes-alerts (ops notifier). The app's
  old default listened to only the first two.

## Revised deletion inventory (verified against code, supersedes spec's broad set)
DELETE (commit A):
  app/.../v3/notify/CronWatcher.kt            281  alarm polling
  app/.../v3/notify/TurnWatcher.kt            178  90s alarm
  app/.../v3/service/TurnFinishedAlarmReceiver.kt 37
  app/.../v3/service/CronAlarmReceiver.kt        37
KEEP (rewire in commit B):
  app/.../v3/notify/NotificationHelper.kt     233  channels + tap nav + post primitives — app-native UI
  app/.../v3/notify/NotificationReplyService.kt 136  rewire: reply → publish hermes-in
  app/.../v3/notify/NotificationReplyReceiver.kt 35  keep, point at rewired service
  core/network/RpcNotification.kt             198  CHAT STREAMING PROTOCOL — not a notification file, keep
CALL SITES to strip (commit A): MainActivity (CronWatcher.sync, ensureChannels stays if channels kept),
  SystemScreens (3× CronWatcher.sync), DashboardChatViewModel (TurnWatcher arm/cancel, postTurnFinished)
MANIFEST: remove 2 receivers (+BOOT intents); add NtfySubscriptionService in commit B.
Cleartext: network_security_config.xml ALREADY scopes 100.80.204.66 → ntfy :8080 covered, no change.

## Revert path (by construction)
git revert <B> <A>  → watchers back. Tag `pre-ntfy-native` = full rollback point.
Server never rolls back (subscriber-less topics are harmless).

## Release facts (v3)
versionCode=15 versionName=0.1.86 → target 16 / 0.1.87. Build:
`./gradlew assembleRelease --no-configuration-cache`; verify sign with apksigner;
check v3 CI/release flow BEFORE pushing (see Phase 3 notes when reached).

## Test cron (Phase 4 recipe)
hermes cron create "5m" --name "ntfy-native test" --no-agent --script ntfy-rail-test.sh --deliver ntfy --repeat 1
(script exists pattern: ~/.hermes/scripts/) → expect: Hermex notification (NOT ntfy app).
