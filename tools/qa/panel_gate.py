#!/usr/bin/env python3
"""Panel gate: send a brief to GPT, Grok and Claude, save raw answers, report coverage.

Used by the long run at P3 and P6 so the gate needs no interactive step:
    ./panel_gate.py longrun/P3_brief.md P3
Writes longrun/<tag>/{chatgpt,grok,claude}.json + .md and prints per-site status plus which of the
brief's numbered questions each model answered (a model silently skipping one is a known failure mode).
"""
import json
import os
import re
import subprocess
import sys

PY = "/home/jeff/scrapling-venv/bin/python3"
DRV = os.path.expanduser("~/.hermes/scripts/chatgpt-agent/bot_chat.py")
SITES = ("chatgpt", "grok", "claude")


def qnumbers(text):
    return sorted({int(m.group(1)) for m in re.finditer(r"(?m)^\s*(?:#{1,4}\s*)?([1-9])[.)]\s+", text)})


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    brief_path, tag = sys.argv[1], sys.argv[2]
    outdir = os.path.join(os.path.dirname(brief_path) or ".", f"{tag}_panel")
    os.makedirs(outdir, exist_ok=True)
    prompt = open(brief_path).read()
    want = qnumbers(prompt)
    env = dict(os.environ, DISPLAY=":21")
    rows = []
    for site in SITES:
        r = subprocess.run([PY, DRV, site, prompt, "--timeout", "480"],
                           capture_output=True, text=True, env=env)
        path = os.path.join(outdir, f"{site}.json")
        open(path, "w").write(r.stdout)
        try:
            d = json.loads(r.stdout)
        except Exception as e:
            rows.append((site, f"unparsable:{e}", 0, [], ""))
            continue
        ans = d.get("answer") or ""
        open(os.path.join(outdir, f"{site}.md"), "w").write(ans)
        got = qnumbers(ans)
        rows.append((site, d.get("status"), len(ans), got, d.get("url") or ""))

    print(f"brief {brief_path} ({len(prompt)} chars) — questions asked: {want}")
    missing_any = False
    for site, status, n, got, url in rows:
        missing = [q for q in want if q not in got]
        missing_any = missing_any or bool(missing)
        print(f"  {site:8} status={status} chars={n} answered={got} MISSING={missing}")
        if url:
            print(f"           thread: {url}")
    with open(os.path.join(outdir, "coverage.json"), "w") as f:
        json.dump({"asked": want, "rows": [
            {"site": s, "status": st, "chars": n, "answered": g, "missing": [q for q in want if q not in g],
             "url": u} for s, st, n, g, u in rows]}, f, indent=2)
    if missing_any:
        print("NOTE: at least one model skipped a numbered question — follow up in the SAME thread.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
