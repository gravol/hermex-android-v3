#!/usr/bin/env python3
"""Record the human/agent adjudication of a trial next to the auto-bucket.

The classifier only sees logs. The screenshot at +120 s is the user-visible ground truth, and
several buckets (B7 "UI row count never changed") are detector artifacts when the answer in fact
rendered by streaming into an existing message. Adjudications are stored in trials.jsonl as
`adjudicated_bucket` / `adjudication_note` so the final tally reflects evidence, not heuristics.

  python3 mark.py --trial 15 --bucket PASS --note "+120s screenshot shows the full answer rendered"
  python3 mark.py --trial 15 --show
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trial_harness as th  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("--scenario", default="D")
ap.add_argument("--trial", type=int)
ap.add_argument("--bucket")
ap.add_argument("--note")
ap.add_argument("--show", action="store_true")
ap.add_argument("--tally", action="store_true",
                help="count every trial by auto-bucket vs adjudicated bucket, per scenario")
a = ap.parse_args()

if a.tally:
    import collections
    per = collections.defaultdict(lambda: [collections.Counter(), collections.Counter()])
    for line in open(th.RESULTS).read().splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        sc = r.get("scenario")
        per[sc][0][r.get("bucket")] += 1
        per[sc][1][r.get("adjudicated_bucket") or "UNADJUDICATED"] += 1
    for sc in sorted(per):
        auto, adj = per[sc]
        print(f"== scenario {sc} ==")
        print("  auto:       ", dict(auto))
        print("  adjudicated:", dict(adj))
    sys.exit(0)

if a.trial is None:
    print("--trial N (or --tally)")
    sys.exit(1)

lines = [l for l in open(th.RESULTS).read().splitlines()]
out, hit = lines[:], None
for i, line in enumerate(lines):
    if not line.strip():
        continue
    r = json.loads(line)
    if r.get("scenario") == a.scenario and r.get("trial") == a.trial:
        hit = i
if hit is None:
    print(f"no record for {a.scenario}/{a.trial}")
    sys.exit(1)

r = json.loads(out[hit])
if a.show:
    print(json.dumps({k: r.get(k) for k in ("scenario", "trial", "sid", "bucket", "why",
                                            "adjudicated_bucket", "adjudication_note")}, indent=2))
    sys.exit(0)
if not a.bucket:
    print("--bucket required with --note")
    sys.exit(1)

r["adjudicated_bucket"] = a.bucket
r["adjudication_note"] = a.note or ""
r["adjudicated_at"] = json.dumps({"epoch": round(__import__("time").time(), 3)})
out[hit] = json.dumps(r)
open(th.RESULTS, "w").write("\n".join(out) + "\n")
print(f"{a.scenario}/{a.trial}: auto-bucket {r.get('bucket')} → adjudicated {a.bucket}")
