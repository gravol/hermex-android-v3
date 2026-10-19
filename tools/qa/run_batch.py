#!/usr/bin/env python3
"""Run N drop trials back-to-back against the Pixel 8.

Each trial arms on the NEXT phone-initiated turn (the agent's own session id is
excluded), kills the phone's gateway socket --kill-at seconds into it, observes
--observe seconds, then prints READY for the next prompt. Results append to
trials.jsonl; run `trial_harness.py --list` to summarise.

  python3 run_batch.py --scenario D --count 10 --exclude-sid 74e51dbb
"""
import argparse
import subprocess
import sys
import time

ap = argparse.ArgumentParser()
ap.add_argument("--scenario", default="D")
ap.add_argument("--count", type=int, default=10)
ap.add_argument("--start-trial", type=int, default=1)
ap.add_argument("--kill-at", type=float, default=20.0)
ap.add_argument("--observe", type=float, default=150.0)
ap.add_argument("--arm-timeout", type=int, default=900, help="max seconds to wait for a prompt")
ap.add_argument("--exclude-sid", action="append", default=[])
a = ap.parse_args()

for i in range(a.count):
    n = a.start_trial + i
    print(f"\n===== TRIAL {a.scenario}/{n}: READY — send a prompt on the phone "
          f"(waiting up to {a.arm_timeout}s) =====", flush=True)
    cmd = [sys.executable, "trial_harness.py", "--scenario", a.scenario, "--trial", str(n),
           "--arm-on-turn", str(a.arm_timeout), "--kill-at", str(a.kill_at),
           "--observe", str(a.observe), "--label", f"{a.scenario} batch trial {n}"]
    for sid in a.exclude_sid:
        cmd += ["--exclude-sid", sid]
    t0 = time.time()
    # Stream the child's output straight through so progress is visible live (capture_output
    # buffers everything until the child exits, which hides mid-trial state).
    rc = subprocess.call(cmd)
    print(f"----- trial {a.scenario}/{n} done in {round(time.time()-t0)}s (rc={rc}) -----", flush=True)
    time.sleep(3)

print("\nBATCH COMPLETE", flush=True)
