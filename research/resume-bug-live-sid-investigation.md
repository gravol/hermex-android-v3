# Resume / turn-notification bug: live-SID used where DB-key is required

**Status:** INVESTIGATED — root cause narrowed, not yet fixed. Left for later (2026-09-17).
**Symptom:** Hermex Android v0.1.165 on Pixel 8 — "resume doesn't work" (past-session history won't
load), and turn-completion notifications for finished turns stop firing.
**Debug log:** `.hermes/attachments/hermex_debug_20260917_114307.txt` (~11:41–11:43 PT).

---

## The smoking gun (from the debug log)

After login + `session.list` (200 sessions OK), a burst of REST calls at 11:43:01, all **404**:

```
GET /api/sessions/45f6686f/messages?limit=3 -> 404 {"detail":"Session not found"}
GET /api/sessions/6b3e0eec/messages?limit=3 -> 404
GET /api/sessions/278e6e11/messages?limit=3 -> 404
GET /api/sessions/27446b64/messages?limit=3 -> 404
GET /api/sessions/3f701521/messages?limit=3 -> 404
```

A re-login (`POST /auth/password-login` → 200) precedes the burst. Every id is a **bare 8-hex
token** (live-SID shape `uuid.hex[:8]`), NOT a DB key (`YYYYMMDD_HHMMSS_8hex`).

## Who makes those calls

- `/api/sessions/{id}/messages?limit=3` is made **only** by `DashboardApiClient.sessionMessages(id, limit)`
  (`core/network/DashboardApiClient.kt:363`).
- Its only two callers: `CronWatcher.kt` (limit=12) and `TurnWatcher.kt:104` (limit=3 — matches log).
- **TurnWatcher** = turn-completion notification watcher. On `prompt.submit` it arms an `AlarmManager`
  with a `sessionId`; wakes ~90s later; calls `sessionMessages(sessionId, 3)` to check if the last
  assistant message has content, then posts a notification. (Lock/app-switch resilient — mirrors CronWatcher.)

## Server behavior (BigRed, hermes-agent HEAD)

- Route `get_session_messages` (`web_routers/sessions.py:528`) → `_resolve_session_id(db, id)`; if not found → 404.
- `resolve_session_id` (`hermes_state_sessions.py:783`) = exact db-key match, else single unambiguous
  `LIKE 'prefix%'`. DB keys start with `YYYYMMDD_`, so a bare 8-hex sid matches nothing → None → 404.
- **Conclusion:** `/messages` with a live SID deterministically 404s even though the row exists.

## Decisive local evidence (read from `~/.hermes/state.db`, no HTTP)

1. **All recent sessions use real DB keys** — e.g. `20260917_184224_a85de8`, `20260917_144752_b83e01`.
   The server's `session.list` returns these db-keys as `id`. So hypothesis "server returns sids as id"
   is **ruled out**.
2. **The five logged 8-hex sids exist NOWHERE in the DB** — not as `id`, `session_key`, `chat_id`,
   `thread_id`, or in `session_turn_leases`. They are purely **transient live SIDs** (in-memory only,
   reaped after WS disconnect). Confirmed via substring scan across all 5 logged tails.

## Client id-flow (traced; every seam preserves the db-key)

```
session.list → SessionSummary.id (= server "id" = DB key)
   → MainActivity nav "chat/{id}/{title}" (MainActivity.kt:187-188)
   → DashboardChatViewModel.init(id) stores sessionId = DB key (comments: "ONLY value for RPC calls",
     "NEVER overwrite with live sid")  [DashboardChatViewModel.kt:57,120-139]
   → TurnWatcher.arm(getApplication(), sessionId)  [:508]  (String extra)
   → TurnFinishedAlarmReceiver reads intent.getStringExtra("session_id")  clean string→string round-trip  [:29]
   → DashboardApiClient.sessionMessages(sessionId, 3)
```

Three seams **eliminated** as the db-key→live-sid transformation site:
- `SessionSummary.kt` has **no** `resolved_id` field → no DTO swap.
- `TurnFinishedAlarmReceiver` string round-trip → AlarmManager does not mangle the id.
- `JsonRpcClient.SessionInfo.kt:473` serializes `id` directly from server `"id"` (no override).

## Current source == installed version

- `git log -1` = `1141dbe "v0.1.165: build from HEAD …"` — current HEAD **is** the v0.1.165 build commit.
- No recent changes to the id-flow path (stable since v0.1.141 turn-finished notifications).

## Root-conclusion

The **running client passes live SIDs to `sessionMessages()` where DB keys are required.** Because this
source preserves db-keys through every seam and `HEAD == v0.1.165`, the db-key→live-sid swap is happening
in the *executing* artifact via a path not identical to this source tree — i.e. the installed APK most
likely differs from `~/HermexAndroid` HEAD (GPT's #1 ranking), **or** a runtime path I can't see statically
(e.g. resume/reconnect/4007-self-heal that swaps `sessionId`→`liveSid`) injects the live sid.

### GPT second opinions (chatgpt.com via Clerk, `chatgpt-anonymous-agent` skill)
- Round 1 (`--critique`, evidence v1): **VERDICT "Mostly correct"** — identifier mismatch + deterministic
  404 = high confidence; client-vs-server root cause = medium (missing evidence: where db-key→8-hex).
  Flagged stale-APK, and that a successful `session.resume` by db key doesn't prove the stored id is right
  everywhere.
- Round 2 (evidence v2, seams eliminated): **VERDICT "Has issues"** — my seam-elimination proves the
  *source* doesn't transform but not that the *running artifact* followed those paths. Ranked hypotheses:
  **(a) stale/different APK, (b) server mixed identifiers, (d) different caller, (c) truncation.** Named the
  single decisive check: **raw `session.list` JSON for one affected session** (if `id` already bare-sid →
  server-side; if `2026…_xxxx` → downstream client).

## What's NOT yet confirmed / open questions

- Do the five `/messages` 404s correspond to sessions the user actually resumed, or just background
  watcher activity? (They're TurnWatcher — a *symptom*; may be separate from the resume failure.)
- Where exactly does db-key → live-sid happen in the running app? (Needs runtime evidence.)
- Is "resume/history won't load" the same root cause, or a separate path? The log doesn't clearly capture a
  failing `session.resume`.

## Next steps to finish (pick one)

1. **Instrument the running client + rebuild** (most decisive, tests the actual artifact). Log sessionId at
   each boundary: `init()` → before `TurnWatcher.arm()` → intent payload → on-wake → before
   `sessionMessages()` → inside `sessionMessages()`. Capture a fresh debug log opening one of these sessions;
   pinpoint the first hop where `YYYYMMDD_HHMMSS_8hex` becomes bare 8-hex. Files: `DashboardChatViewModel.kt`,
   `TurnWatcher.kt`, `DashboardApiClient.kt`.
2. **Decompile the installed APK** (`jadx`/`apktool`) of `TurnWatcher` / nav / `DashboardApiClient` and diff
   against this source. Check versionCode/versionName + build commit too — two artifacts can share a label.
3. **Live `session.list` JSON** for one affected session (highest-information single observation) — but the
   dashboard HTTP endpoint stalls from BigRed (tried curl_cffi/urllib/curl; all hung). Try from Clerk or the phone.

## Repro / verification commands (local, no network)

```bash
# session id formats + whether logged sids exist anywhere in the live store
python3 - <<'PY'
import sqlite3, re
db=sqlite3.connect("file:~/.hermes/state.db?mode=ro", uri=True)
c=db.cursor()
c.execute("SELECT id FROM sessions"); ids=[str(r[0]) for r in c.fetchall()]
def kind(i):
    if re.fullmatch(r"\d{8}_\d{6}_[0-9a-f]{8}", i): return "dbkey"
    if re.fullmatch(r"[0-9a-f]{8}", i): return "bare8hex"
    if i.startswith("cron_"): return "cronrun"
    return f"other({len(i)})"
from collections import Counter
print(Counter(kind(i) for i in ids).most_common())
for tail in ["45f6686f","6b3e0eec","278e6e11","27446b64","3f701521"]:
    print(tail, "present:", [i for i in ids if tail in i][:3])
PY
```

## Files referenced

- Client: `~/HermexAndroid/` — `DashboardApiClient.kt`, `TurnWatcher.kt`, `TurnFinishedAlarmReceiver.kt`,
  `DashboardChatViewModel.kt`, `SessionSummary.kt`, `JsonRpcClient.kt`, `MainActivity.kt`, `SessionsScreen.kt`.
- Server: `~/.hermes/hermes-agent/` — `web_routers/sessions.py`, `hermes_state_sessions.py`,
  `tui_gateway/methods_session.py`, `state.db` (live session store).
- Log: `.hermes/attachments/hermex_debug_20260917_114307.txt`.
