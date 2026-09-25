# Bug: ws_orphan_reap + first-call 4007 session not found

**Reported:** 2026-08-21 (Hermex Android v0.1.118 debug log)
**Area:** Server session lifecycle / reaper (Hermex Dashboard, 100.80.204.66:9119)
**Client fix shipped:** v0.1.119 (interim backoff+retry mitigation; root cause is server-side)

---

## Symptom (from client debug log)

    [RPC] DashboardChat: unknown event: session.reclaimed ... reason=ws_orphan_reap   <- reaped
    [RPC] Request: [6] prompt.submit -> Error 4001 session not found                  <- submit fails
    [STATE] SessionID: prompt.submit 4001 - re-registering via session.resume         <- self-heal starts
    [RPC] Request: [7] session.resume -> Error 4007 session not found                <- resume ALSO fails
    [STATE] SessionID: self-heal resume 4007 - sending prompt.submit directly         <- fallback
    [RPC] Request: [8] prompt.submit -> Error 4001 session not found                 <- fails AGAIN

The client's existing self-heal (v0.1.99 submitWithSelfHeal) recovers a reaped session by
calling session.resume to re-materialize it from the DB, then retrying prompt.submit. That
works only if session.resume succeeds. Here session.resume returned 4007 on its very first
call - meaning the server had already purged the DB row for the just-created session.

## Key observations

1. The reap happens unusually fast. Session c1c3a917 was created via session.create (request [1])
   and immediately got a ws_orphan_reap. Normally a freshly created session lives until its first
   real turn streams. Here it was reaped before the client could resume it.
2. liveSid was empty throughout. The session never materialized a live runtime SID, which is exactly
   the state the reaper targets (orphaned/unattached live sessions).
3. session.reclaimed arrives as a server event but the client logs it as unknown event - it has no
   handler, so it can't react proactively. It only reacts via the reactive 4001 path.
4. This is not a classic phone-slept-then-woke reap (that is covered by the existing self-heal). This
   one fires during an active session lifecycle, seconds after creation.

## Likely server-side causes (investigate on the dashboard)

- Aggressive LRU / idle reap threshold. The reaper may evict live sessions whose idle window is
  measured from session.create rather than last activity, or the threshold is too low. Check the
  reap policy in the session manager.
- DB-row flush race. If the server only materializes the DB row on first run and the reaper runs
  against the pre-flush state, a just-created session can be reaped before its row exists.
- ws_orphan_reap fires on live-SID absence. A session that never got a live SID (no stream, no
  resume completed) may be considered orphaned immediately. Consider exempting sessions in the
  create-to-first-stream window, or extending their grace period.

## Client-side mitigation (v0.1.119)

submitWithSelfHeal now, when session.resume returns 4007 during self-heal, waits ~1.5s and retries
the resume up to 2 more times before falling back to a direct prompt.submit. This recovers
transiently-reaped sessions (where the DB row flushes within a couple seconds) instead of failing
immediately. It does not fix a genuinely-deleted session - those still correctly fall through to
the direct-send path.

## Server-side fix (desired)

Tune the reaper so it does not reap a live session that was created seconds ago and is otherwise
healthy, and/or send a proactive session.reclaimed signal the client can act on before the next
prompt.submit fails. The client already receives session.reclaimed as an event - wiring a client
handler to pre-emptively re-resume would be the cleanest long-term fix.

## How to reproduce from the dashboard side

1. Create a session via session.create (source=api).
2. Immediately (before any turn streams) trigger the reaper's sweep (or wait for an idle/LRU tick).
3. Observe the session get ws_orphan_reap and the next prompt.submit / session.resume return 4001/4007.
