#!/usr/bin/env python3
"""Per-trial delivery verdicts for a batch, cross-tabulated with the screenshot adjudication.

Reads trials.jsonl (written by drive_trial.py), runs delivery.py for each trial with its recorded
app-submit time, paired session id and observation window, then prints and saves a table:

    scenario/trial | capture % | gateway routed | client ingress(target) | disp counts | verdict | adjudicated

The point of the cross-tab is honesty: a PASS with an H2 verdict means the answer came back through some
path we did not observe (e.g. a later reload), which is a different claim than "recovery worked".
"""
import argparse, collections, json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", required=True)
    ap.add_argument("--from", dest="lo", type=int, required=True)
    ap.add_argument("--to", dest="hi", type=int, required=True)
    ap.add_argument("--out", default=os.path.join(HERE, "longrun", "P5_delivery_table.md"))
    a = ap.parse_args()

    rows = [json.loads(l) for l in open(os.path.join(HERE, "trials.jsonl")) if l.strip()]
    sel = [r for r in rows if r.get("scenario") == a.scenario and a.lo <= int(r.get("trial", 0)) <= a.hi]
    if not sel:
        print(f"no trials for scenario {a.scenario} in [{a.lo},{a.hi}]")
        return 1

    lines = [f"# Delivery verdicts — scenario {a.scenario} trials {a.lo}..{a.hi} (n={len(sel)})", ""]
    tally_v, tally_a, cross = collections.Counter(), collections.Counter(), collections.Counter()
    for r in sorted(sel, key=lambda x: int(x["trial"])):
        n = int(r["trial"])
        client = os.path.join(HERE, "shots", f"{a.scenario}_{n}_client.log")
        sid = (r.get("sid") or "").strip()
        t0 = float(r.get("app_submit") or r.get("fault_at") or 0)
        t1 = t0 + float(r.get("observed_s") or 150)
        if not sid or not os.path.exists(client):
            print(f"  {a.scenario}/{n}: SKIP (no sid or no captured journal)")
            tally_v["NO_DATA"] += 1
            continue
        p = subprocess.run([sys.executable, os.path.join(HERE, "delivery.py"), "--client", client,
                            "--sid", sid, "--t0", str(t0), "--t1", str(t1)],
                           capture_output=True, text=True)
        out = p.stdout
        verdict = next((l for l in out.splitlines() if l.startswith("VERDICT:")), "VERDICT: ERROR").split("VERDICT: ")[1]
        cap = next((l for l in out.splitlines() if "journal capture:" in l), "")
        routed = next((l for l in out.splitlines() if "gateway: routed" in l), "")
        ing = next((l for l in out.splitlines() if "client ingress by sid" in l), "")
        disp = next((l for l in out.splitlines() if "disposition for target sid" in l), "")
        adj = r.get("adjudicated_bucket") or r.get("bucket")
        tally_v[verdict.split(" —")[0]] += 1
        tally_a[adj] += 1
        cross[(verdict.split(" —")[0], adj)] += 1
        lines.append(f"## {a.scenario}/{n}  sid={sid}  window={int(t0)}..{int(t1)}")
        for l in (cap.strip(), routed.strip(), ing.strip(), disp.strip()):
            if l:
                lines.append(f"- {l}")
        lines.append(f"- **verdict:** {verdict}")
        lines.append(f"- adjudicated outcome (screenshot): {adj}"
                     + (f" — {r.get('adjudication_note')}" if r.get("adjudication_note") else ""))
        lines.append("")

    lines.append("## Tally")
    lines.append(f"- delivery verdicts: {dict(tally_v)}")
    lines.append(f"- adjudicated outcomes: {dict(tally_a)}")
    lines.append("- cross-tab (verdict × adjudicated):")
    for (v, adj), c in sorted(cross.items()):
        lines.append(f"  - {v} × {adj}: {c}")
    txt = "\n".join(lines) + "\n"
    open(a.out, "w").write(txt)
    print(txt)
    print(f"[written] {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
