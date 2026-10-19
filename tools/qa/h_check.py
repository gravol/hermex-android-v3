#!/usr/bin/env python3
"""Did the injected fault actually produce the blank-row state, and did the app recover from it?

The H scenario is not judged by the recovery-chain buckets in trial_harness (there is no network
fault, so those chains never run) and not by delivery.py (delivery is fine by construction — the app
itself throws the frames away). What decides this experiment is the sequence inside the journal:

  1. the fault was armed                -> TRACE|fault action=arm kind=drop_answer_stream
  2. events were really dropped         -> disp=... reason=injected_fault_drop_answer_stream
  3. a blank final row existed          -> gate_poll ... blankFinalRow=true
  4. the gate named the right rule      -> gate_poll ... rule=D
  5. recovery was attempted and bounded -> "blank-row reload attempt N/3" / "reload STOPPED"
  6. the answer then appeared           -> after the reload, blankFinalRow=false and rows grew

Any missing step is a finding on its own; step 4 missing while 1-3 happened means the shipped
safety net does not catch the very state it was written for.
"""
import glob, json, os, re, sys

SHOTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "shots")


def check(scenario, lo, hi):
    out = []
    for n in range(lo, hi + 1):
        path = os.path.join(SHOTS, f"{scenario}_{n}_client.log")
        if not os.path.exists(path):
            continue
        txt = open(path, errors="replace").read()
        armed = len(re.findall(r"action=arm kind=drop_answer_stream", txt))
        dropped = len(re.findall(r"reason=injected_fault_drop_answer_stream", txt))
        blank_ticks = len(re.findall(r"blankFinalRow=true", txt))
        ruleD = len(re.findall(r"rule=D\b", txt))
        attempts = re.findall(r"blank-row reload attempt (\d+)/(\d+)", txt)
        stopped = len(re.findall(r"rule=D reload STOPPED", txt))
        suppressed = len(re.findall(r"reload SUPPRESSED", txt))
        full_reload = len(re.findall(r"— full reload", txt))
        # did a row end up non-blank after the last blank observation? compare last states
        seq = re.findall(r"gate_poll\|[^|]*blankFinalRow=(true|false)", txt)
        healed = any(seq[i] == "true" and "false" in seq[i + 1:] for i in range(len(seq)))
        out.append({
            "trial": f"{scenario}/{n}", "fault_armed": armed, "events_dropped": dropped,
            "blank_row_gate_ticks": blank_ticks, "rule_D_hits": ruleD,
            "blank_reloads": [f"{a}/{b}" for a, b in attempts], "stopped": stopped,
            "suppressed": suppressed, "full_reloads": full_reload,
            "gate_blank_sequence": "".join("B" if v == "true" else "." for v in seq),
            "blank_then_nonblank": healed,
        })
    return out


def main():
    scenario = sys.argv[1] if len(sys.argv) > 1 else "H"
    lo = int(sys.argv[2]) if len(sys.argv) > 2 else 71
    hi = int(sys.argv[3]) if len(sys.argv) > 3 else 80
    rows = check(scenario, lo, hi)
    if not rows:
        print(f"no {scenario} journals found in {SHOTS}")
        return 1
    for r in rows:
        print(json.dumps(r))
    n = len(rows)
    print("\n== tally ==")
    print(f"trials with journals: {n}")
    for k in ("fault_armed", "events_dropped", "blank_row_gate_ticks", "rule_D_hits",
              "full_reloads"):
        vals = [r[k] for r in rows]
        print(f"  {k}: min={min(vals)} max={max(vals)} any={any(vals)}")
    print(f"  blank-then-nonblank (healed): {sum(1 for r in rows if r['blank_then_nonblank'])}/{n}")
    print(f"  blank row seen but NEVER named rule=D: "
          f"{sum(1 for r in rows if r['blank_row_gate_ticks'] and not r['rule_D_hits'])}/{n}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
