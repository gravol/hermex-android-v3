# Native ntfy delivery for Hermex (app-side feature spec)

Repo: this project (com.hermex.v3). Planned PR — NOT part of the Hermes Ops app;
the ops app only supplies the server-side publisher (turnwatch), which needs
zero changes for this feature. Status: PLANNED (server side live 2026-10-12).
Server-side deep review: Obsidian vault "Hermes Ops/2026-10-12 ntfy delivery
integration — deep review.md".

## Goal (user-stated)

The notification arrives **from Hermex**, not from the ntfy app. Same rail
(self-hosted ntfy on BigRed), new consumer: Hermex speaks the ntfy WebSocket
protocol directly and posts notifications on its own channels. Native feel:
Hermex icon, Hermex tap behavior, inline reply, one app.

Why it's fully possible: ntfy is a public protocol (`GET /ws`, subscribe frames,
publish via HTTP POST), not a proprietary service. The official app is just a
client. Speaking the protocol from our own app carries no licensing catch.

## Server side — already live, zero changes needed

- ntfy server: BigRed :8080 · topics: `hermes-out` (crons/alerts),
  `hermes-sessions` (turn notices from ops-app turnwatch), `hermes-in` (chat door).
- Hermes ntfy platform plugin: enabled (cron `deliver=ntfy` works, phone→agent chat works).
- Cron jobs (verified in `~/.hermes/cron/jobs.json`):
  - `4778dfbd3e46` Weather 7am Vancouver — `0 14 * * *` UTC = **07:00 PDT**,
    no-agent, script `~/.hermes/scripts/weather-vancouver.py` (tested live).
  - `92c3be2191f7` BigRed Health Watchdog — every 15m, no-agent
  - `ed28fcf112a3` vault-graph-indexer — Mon 06:00 PDT, agent
  - `2e3c3bef684f` Neo4j monthly audit — 1st monthly 08:00 PDT, no-agent
- Turn watcher: `~/hermes-ops/app/turnwatch.py` (finish_reason='stop',
  interactive-lease suppression, `Link: hermex://session/<db-key>`).

**Weather flow exactly as asked:** 07:00 → script output published to
`hermes-out` → notification on phone. TODAY it appears in the ntfy app. After
this PR it appears as a Hermex notification. Note the honest nuance: script
jobs have NO session (by design) — the message IS the content, so the tap
opens Hermex to the notification view (expanded message), not a session.
Agent jobs push a headline whose tap opens the session chat (existing
mechanism, below).

## The PR — "native ntfy delivery"

New (small):
1. `service/NtfySubscriptionService.kt` — foreground service, OkHttp
   WebSocket to `<server>/ws`, subscribe frames for `hermes-out` +
   `hermes-sessions` (topic list in settings; server URL configurable).
   Catch-up on reconnect: HTTP `GET /<topic>/json?poll=1&since=<last-seen-id>`
   persisted per topic. Exponential backoff.
2. One persistent low-importance notification ("Notifications connected") —
   Android foreground-service requirement; tap → status/settings screen.
3. Message handling reuses EXISTING plumbing (key finding):
   - `MainActivity.kt:153` already handles `EXTRA_OPEN_SESSION` →
     `navController.navigate(chatRoute(...))`. Tap-to-session is DONE —
     build the PendingIntent with the db-key from `Link: hermex://session/…`
     (or the raw link header) and existing code opens the chat.
   - Notification channels already exist (`NotificationHelper.ensureChannels`).
4. Inline reply: publish POST to `hermes-in` (server-side chat door —
   agent receives it as a message, real session, visible in recents).
5. Settings toggle + server/topic fields (plain SharedPreferences pattern used
   elsewhere in app).

Deleted (the gutting — verified inventory, 8 files / 1,135 lines):

| File | Lines | Role |
|---|---|---|
| `app/.../v3/notify/CronWatcher.kt` | 281 | cron alarm polling |
| `app/.../v3/notify/NotificationHelper.kt` | 233 | notification construction (KEEP channels, trim) |
| `app/.../v3/notify/TurnWatcher.kt` | 178 | 90s turn alarm |
| `app/.../v3/notify/NotificationReplyService.kt` | 136 | inline reply → REST |
| `core/network/.../RpcNotification.kt` | 198 | RPC notification parsing |
| `app/.../v3/notify/NotificationReplyReceiver.kt` | 35 | reply receiver |
| `app/.../v3/service/TurnFinishedAlarmReceiver.kt` | 37 | alarm receiver |
| `app/.../v3/service/CronAlarmReceiver.kt` | 37 | alarm receiver |

Manifest removals: `<receiver>` CronAlarmReceiver + TurnFinishedAlarmReceiver,
their BOOT_COMPLETED intents (lines ~62–90). Keep `WsKeepaliveService`
(chat) and `POST_NOTIFICATIONS` permission.

Call-site edits (small, mechanical):
- `MainActivity.kt`: drop `CronWatcher.sync`, `ensureChannels` calls (keep
  EXTRA_OPEN_SESSION handling — it's REUSED)
- `feature/system/SystemScreens.kt`: remove 3× `CronWatcher.sync`
- `feature/chat/DashboardChatViewModel.kt`: remove `TurnWatcher.arm` +
  2× `TurnWatcher.cancel` + `NotificationHelper.postTurnFinished` call

Net: ~1,135 lines out, ~350 lines in. App gets simpler.

## Revert path (if it doesn't work out)

Design as ONE commit pair on `main`:
1. commit A = "delete notification watchers" (pure removal, nothing added)
2. commit B = "native ntfy subscription" (only the new service + wiring)

Revert = `git revert B A` (reverse order). Two commands, clean tree, no
surgery. Keep tag `pre-ntfy-native` at the pre-PR commit. Server side needs
NO rollback ever: pushes to a topic with no subscriber are harmless — the
ntfy-app path keeps working the whole time, so "it doesn't work out" is
never a broken state, just a return to the ntfy app.

Documented revert notes (existing, for commit A): PROJECT_HANDOFF_CURRENT.md:324-331.

## Test plan (acceptance = the weather flow)

1. Unit-less by design; prove live on tailnet with phone.
2. `hermes cron run 4778dfbd3e46` → Hermex notification appears ≤2s,
   priority + title correct.
3. Phone idle 20 min → kill app → trigger again → notification arrives
   (service survives; check Doze with `adb shell dumpsys deviceidle`).
4. Reconnect catch-up: stop ntfy server 5 min with 3 messages queued →
   restart → all 3 delivered, no duplicates (per-topic last-seen id).
5. Inline reply to a hermes-sessions push → lands on hermes-in → agent
   replies → reply notification arrives.
6. Tap agent-job push → app opens at that session (existing nav path).
7. Tap script-job push → expanded notification view (weather = content).
8. Release = 07:00 weather the next morning = acceptance test.

## Open questions (decide at PR time, not blocking)

- Topic list: hardcode 2 topics vs settings-editable (recommend editable).
- Notification grouping when many arrive (recommend group + summary).
- Whether hermes-in replies from the app ALSO notify (recommend: notify,
  it's the agent's answer).
- Suppression when app is foreground+viewing that session (server already
  suppresses watched sessions via leases — app-side, likely unnecessary).