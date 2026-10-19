#!/usr/bin/env python3
"""Re-bucket recorded trials with the CURRENT classifier, from the stored raw logs.

The classifier evolves as trials teach it what correct behaviour looks like, so every
recorded trial is re-derived from its evidence instead of trusting the bucket written at
the time. Reads trials.jsonl + shots/<scenario>_<n>_client.log/_gateway.log.

  python3 rebucket.py            # table of old → new
  python3 rebucket.py --json     # machine-readable
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trial_harness as th  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("--json", action="store_true")
a = ap.parse_args()

rows = []
for line in open(th.RESULTS):
    r = json.loads(line)
    sc, n = r.get("scenario", "?"), r.get("trial")
    cl = os.path.join(th.SHOTS_DIR, f"{sc}_{n}_client.log") if hasattr(th, "SHOTS_DIR") else \
        os.path.join(os.path.dirname(th.RESULTS), "shots", f"{sc}_{n}_client.log")
    gl = cl.replace("_client.log", "_gateway.log")
    if not (os.path.exists(cl) and os.path.exists(gl)):
        rows.append({"scenario": sc, "trial": n, "sid": r.get("sid"),
                     "old_bucket": r.get("bucket"), "old_why": (r.get("why") or "")[:70],
                     "new_bucket": "NO_LOGS", "new_why": "raw logs not stored",
                     "counts": r.get("counts"), "shots": list((r.get("shots") or {}).values())})
        continue
    client, sids = th.parse_client(open(cl).read())
    trace = th.parse_trace(open(gl).read())
    chat_live = bool(client["vm_state_rx"] or client["gate"] or client["poll_loop"])
    kill_ok = "ESTAB" in r.get("kill", "") or "FIN" in r.get("kill", "")
    try:
        b, w = th.classify(client, trace, r["fault_at"], kill_ok, sid=r.get("sid"))
    except Exception as e:
        b, w = "HARNESS_ERROR", f"{type(e).__name__}: {e}"
    if not chat_live and b not in ("B1", "B2"):
        b, w = "INVALID", "no chat ViewModel active during the drop"
    # Pairing: the phone's transport must have been bound to the session that ran the turn.
    lc = [x for x in trace if x.get("kind") == "lifecycle"]
    sid = r.get("sid")
    attached = bool(sid) and any(x["sid"] == sid and x["action"] in ("detach", "rebind") for x in lc)
    # Trials armed on a gateway turn_start are void: this agent's own session ids rotate, so
    # its turns look identical to the phone's and the fault landed on the agent's turn.
    # Arming moved to the app's own prompt.submit at trial 11; records from that run onward
    # carry app_submit. The numeric boundary covers runs started before the field was recorded.
    SUBMIT_ARMED_FROM = 11
    if ("app_submit" not in r and (r.get("trial") or 0) < SUBMIT_ARMED_FROM) and b not in ("HARNESS_GAP", "NO_LOGS"):
        b, w = "INVALID", "armed on a gateway turn_start, not the app's submit — fault hit the agent's turn"
    elif not attached and b not in ("HARNESS_GAP", "NO_LOGS"):
        b, w = "INVALID", f"phone transport never bound to the turn's session {sid}"
    rows.append({"scenario": sc, "trial": n, "sid": r.get("sid"),
                 "old_bucket": r.get("bucket"), "old_why": (r.get("why") or "")[:70],
                 "new_bucket": b, "new_why": w,
                 "counts": r.get("counts"), "shots": list((r.get("shots") or {}).values())})

if a.json:
    print(json.dumps(rows, indent=2))
else:
    print(f"{len(rows)} trials re-bucketed\n")
    for x in rows:
        key = f"{x['scenario']}/{x['trial']}"
        print(f"{key:>6}  {str(x['old_bucket']):>12} → {x['new_bucket']}")
        print(f"          {x['new_why'][:150]}")
        if x.get("counts"):
            print(f"          counts: {x['counts']}")
    tally = {}
    for x in rows:
        tally[x["new_bucket"]] = tally.get(x["new_bucket"], 0) + 1
    print("\ntally:", json.dumps(tally))
