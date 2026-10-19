#!/usr/bin/env python3
"""Regenerate stored client-log slices from the device journal across ROTATED files.

Trials recorded before the capture fix have slices that stop at a rotation boundary, so
rebucketing them re-derives buckets from partial evidence. This re-reads hermex.log.1 +
hermex.log, filters each trial's window by timestamp, and overwrites shots/*_client.log.
Windows whose start predates the oldest retained line are reported as PARTIAL.

  python3 recapture.py            # rewrite all windows that are still on-device
"""
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trial_harness as th  # noqa: E402


def cat(f):
    return subprocess.run(["adb", "-s", th.SERIAL, "shell", "run-as", th.PKG, "cat",
                           "files/debuglog/" + f], capture_output=True, text=True).stdout


def stamp(line):
    head = line.split("|", 1)[0].strip()
    return int(head) if head.isdigit() else 0


lines = []
for f in ("hermex.log.1", "hermex.log"):
    lines += [l for l in cat(f).splitlines() if stamp(l)]
lines.sort(key=stamp)
if not lines:
    print("no journal lines readable on device")
    sys.exit(1)
print(f"journal available on device: {len(lines)} lines, "
      f"{lines[0][:13]} → {lines[-1][:13]} (epoch ms)")
oldest = stamp(lines[0])

records = [json.loads(l) for l in open(th.RESULTS)]
seen = {}
for r in records:
    sc, n = r.get("scenario", "D"), r.get("trial")
    if (sc, n) in seen:
        continue
    seen[(sc, n)] = True
    fault = r.get("fault_at")
    if not fault:
        continue
    lo = int((fault - 30) * 1000)
    hi = int((fault + (r.get("observed_s") or 150) + 20) * 1000)
    window = [l for l in lines if lo <= stamp(l) <= hi]
    path = os.path.join(th.SHOTS_DIR if hasattr(th, "SHOTS_DIR") else os.path.join(os.path.dirname(th.RESULTS), "shots"),
                        f"{sc}_{n}_client.log")
    if not window:
        print(f"{sc}/{n}: NO DATA on device (window starts {lo}, oldest retained {oldest})")
        continue
    note = ""
    if lo < oldest:
        note = f"  PARTIAL: window starts before oldest retained line ({oldest})"
    with open(path, "w") as fh:
        fh.write("\n".join(window))
    print(f"{sc}/{n}: wrote {len(window)} lines → {path}{note}")
