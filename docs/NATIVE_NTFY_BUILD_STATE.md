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
- [ ] Phase 3b: verify CI: gh run list → gh release view v0.1.87 (isDraft=false, asset size>0,
      aapt2 badging versionCode=16). If CI skipped (guard) → manual: gh release create v0.1.87
      app/build/outputs/apk/release/app-release.apk + gh release edit --latest.
- [ ] Phase 4: Jeff installs via Obtainium → 5-min test cron → confirm Hermex native push
      QA context: test phone = Pixel 8 WITH ChatGPT/Grok/Claude apps installed (Jeff, 17:55).
      Hermex notifications post under its own channels — verify the test notification
      shows as Hermex, not confusable with those apps' notifications.
- [ ] Phase 5: handoff/CHANGELOG docs, skill update

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
