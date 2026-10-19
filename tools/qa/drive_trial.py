#!/usr/bin/env python3
"""Fully autonomous Hermex drop trials: drives the Pixel 8 UI itself.

Per trial: wake → open a chat by title → type a tool-heavy prompt → tap Send →
arm on the gateway turn_start → kill the phone's socket --kill-at seconds into
the turn → observe --observe seconds, screenshotting at +30/+120 for visual
ground truth → parse journal + gateway trace → classify into a bucket.

Nothing is asked of the user. Results append to trials.jsonl; screenshots land
in shots/.

  python3 drive_trial.py --count 5 --scenario D --chat testing
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trial_harness as th   # noqa: E402

SHOTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "shots")
os.makedirs(SHOTS, exist_ok=True)

PROMPT_DEFAULT = ("List every file under ~/HermexAndroidV3/core with its line count, "
                  "then summarize what each module does in two sentences.")


def adb(args, check=False):
    cmd = ["adb", "-s", th.SERIAL, "shell"] + (args.split() if isinstance(args, str) else list(args))
    p = subprocess.run(cmd, capture_output=True, text=True)
    if check and p.returncode != 0:
        raise RuntimeError(f"{args}: {p.stderr}")
    return p.stdout


def tap(x, y):
    adb(f"input tap {x} {y}")
    time.sleep(1.2)


def type_text(s):
    """`input text` sends %20 literally, so spaces go through KEYCODE_SPACE."""
    for i, word in enumerate(s.split()):
        safe = word.replace("'", "").replace('"', "")
        adb(["input", "text", safe])
        if i < len(s.split()) - 1:
            adb("input keyevent KEYCODE_SPACE")
        time.sleep(0.15)


def nodes():
    adb("uiautomator dump /sdcard/wd.xml")
    subprocess.run(["adb", "-s", th.SERIAL, "pull", "/sdcard/wd.xml",
                    "/tmp/wd.xml"], capture_output=True)
    out = []
    try:
        root = ET.parse("/tmp/wd.xml").getroot()
    except Exception:
        return out
    for n in root.iter():
        a = n.attrib
        b = a.get("bounds", "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({"text": a.get("text", ""), "desc": a.get("content-desc", ""),
                    "cls": a.get("class", ""), "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
                    "bounds": b})
    return out


def find(ns, key, pat):
    pat = pat.lower()
    return [n for n in ns if pat in (n[key] or "").lower()]


def center_of_field(ns, key, pat):
    """Prefer the largest matching node — labels overlap their fields."""
    hits = find(ns, key, pat)
    if not hits:
        return None
    def area(n):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n["bounds"])
        x1, y1, x2, y2 = map(int, m.groups())
        return (x2 - x1) * (y2 - y1)
    return max(hits, key=area)


def ensure_app():
    adb("input keyevent KEYCODE_WAKEUP")
    time.sleep(1.5)
    adb("input swipe 540 2200 540 700 250")
    time.sleep(1.0)
    fg = adb("dumpsys activity activities").lower()
    if th.PKG.lower() not in fg.split("resumedactivity")[0][-400:]:
        subprocess.run(["adb", "-s", th.SERIAL, "shell", "monkey", "-p", th.PKG,
                        "-c", "android.intent.category.LAUNCHER", "1"], capture_output=True)
        time.sleep(4)


def warm_vms(titles):
    """Open other chats first so several ChatVM instances exist at reconnect time.

    Measured: on v0.1.892 every reconnect made THREE DashboardChat VMs re-register three
    different sessions (the probe chat plus two stale ones) — audit findings #12/#13
    running live, and the reason reconcile gates evaluated a session other than the one
    running the turn. After a fresh install with one chat open there is only ONE, so a
    post-fix run measured in that state is not comparable to the 9/10 baseline. Opening
    the same extra chats reproduces the baseline condition; the log line
    "reconnect detected — re-registering session" is counted afterwards to prove it.
    """
    for t in titles:
        if open_chat(t):
            time.sleep(2)
        adb("input keyevent KEYCODE_BACK")
        time.sleep(1.5)


def open_chat(title):
    """Exact title match. Substring matching picked 'Testing Pixel 8 correctness' when asked
    for 'testing', so trials landed in the wrong session."""
    for attempt in range(3):
        ns = nodes()
        exact = [n for n in ns if (n["text"] or "").strip().lower() == title.lower()]
        hit = max(exact, key=lambda n: -n["cy"]) if exact else None
        if hit is None:
            # Not on the sessions list — go home and try again.
            adb("input keyevent KEYCODE_BACK")
            time.sleep(2)
            ensure_app()
            continue
        tap(hit["cx"], hit["cy"])
        time.sleep(3)
        ns = nodes()
        if find(ns, "cls", "edittext"):
            return True
    return False


def send_prompt(text):
    ns = nodes()
    field = center_of_field(ns, "cls", "edittext")
    if field is None:
        return False, "no input field on screen"
    tap(field["cx"], field["cy"])
    type_text(text)
    ns = nodes()
    filled = [n for n in find(ns, "cls", "edittext") if len(n["text"]) > 5]
    send = center_of_field(ns, "desc", "send")
    if not filled:
        return False, "prompt text did not land in the input field"
    if send is None:
        return False, "no Send control found"
    tap(send["cx"], send["cy"])
    return True, "sent"


def journal_since(start_wall_ms):
    """Whole journal across rotated files, filtered by timestamp. Byte-offset slicing breaks on
    rotation (hermex.log → hermex.log.1); reading only the current file silently loses the
    first half of a window — that turned a fully recovered trial into a fake 'no reconnect'."""
    lines = []
    for f in ("hermex.log.1", "hermex.log"):
        out = subprocess.run(["adb", "-s", th.SERIAL, "shell", "run-as", th.PKG, "cat",
                              "files/debuglog/" + f], capture_output=True, text=True).stdout
        for line in out.splitlines():
            head = line.split("|", 1)[0].strip()
            if head.isdigit() and int(head) >= start_wall_ms:
                lines.append((int(head), line))
    lines.sort(key=lambda x: x[0])
    return "\n".join(l for _, l in lines)


def shot(name):
    path = os.path.join(SHOTS, name)
    adb("screencap -p /sdcard/shot.png")
    subprocess.run(["adb", "-s", th.SERIAL, "pull", "/sdcard/shot.png", path], capture_output=True)
    return path


def app_submit_time(start_wall_ms, timeout=45):
    """Find the app's OWN prompt.submit in its journal. Arming on a gateway turn_start is
    ambiguous — this agent's session ids rotate, so its turns look identical to the phone's.
    The app's journal is unambiguous: it logs `[N] prompt.submit — params={"session_id":…}`.
    Returns (wall_seconds, session_key) or (None, None)."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        txt = journal_since(start_wall_ms)
        for line in txt.splitlines():
            if "prompt.submit" in line and "RPC|Request" in line:
                head = line.split("|", 1)[0].strip()
                if not head.isdigit():
                    continue
                m = re.search(r'"session_id"\s*:\s*"([^"]+)"', line)
                return int(head) / 1000.0, (m.group(1) if m else None)
        time.sleep(0.5)
    return None, None


def run_trial(n, scenario, chat, prompt, kill_at, observe, arm_timeout, exclude, extra_chats=()):
    print(f"\n===== TRIAL {scenario}/{n} =====", flush=True)
    ensure_app()
    if extra_chats:
        warm_vms(extra_chats)
    if not open_chat(chat):
        print("SETUP FAIL: could not open the chat screen", flush=True)
        return {"trial": n, "bucket": "SETUP_FAIL", "why": "chat screen not opened"}

    start_wall_ms = int(time.time() * 1000)
    t_off = os.path.getsize(th.GATEWAY_TRACE)
    ok, msg = send_prompt(prompt)
    print(f"  prompt send: {msg}", flush=True)
    if not ok:
        return {"trial": n, "bucket": "SETUP_FAIL", "why": msg}

    # Arm on the APP's own submit, not on a gateway turn_start: this agent's session ids
    # rotate, so its turns look identical to the phone's and the driver kept arming on mine.
    t_submit, sess_key = app_submit_time(start_wall_ms)
    if t_submit is None:
        return {"trial": n, "bucket": "SETUP_FAIL", "why": "app never sent prompt.submit"}
    turn_wall = t_submit
    print(f"  APP_SUBMIT wall={t_submit:.3f} session={sess_key}; fault at +{kill_at}s", flush=True)

    fault_wall = turn_wall + kill_at
    time.sleep(max(0.0, fault_wall - time.time()))
    fault_desc = th.inject(scenario, {})
    print(f"  FAULT @ {fault_wall}: {fault_desc}", flush=True)

    shots = {}
    # Pump the journal DURING the observation window. The device journal rotates at ~2 MB and
    # keeps only one rotated file, so capturing once at the end can lose exactly the seconds
    # around the fault — that is how D/14's critical 20 s vanished.
    cap_path = os.path.join(SHOTS, f"{scenario}_{n}_client.log")
    seen_lines = set()
    cap = open(cap_path, "w")

    def pump():
        for line in journal_since(start_wall_ms).splitlines():
            if line not in seen_lines:
                seen_lines.add(line)
                cap.write(line + "\n")
        cap.flush()

    # Pump continuously, not just at screenshot times. D/14 taught the hard lesson: the device
    # journal rotates at ~2 MB and keeps one rotated file, so a once-at-the-end capture lost the
    # exact 20 s that mattered, and later analysis silently drew conclusions from a window that
    # contained lines for only 3 of its 30 seconds. 1.2 s cadence keeps us ahead of rotation.
    import threading
    stop_pump = threading.Event()

    def _pump_loop():
        while not stop_pump.is_set():
            try:
                pump()
            except Exception as e:                       # never let the pumper kill the trial
                print(f"  journal pump error: {e}", flush=True)
            time.sleep(1.2)

    pumper = threading.Thread(target=_pump_loop, daemon=True)
    pumper.start()

    for mark in (30, 120):
        target = fault_wall + mark
        time.sleep(max(0.0, target - time.time()))
        shots[f"+{mark}s"] = shot(f"{scenario}_{n}_plus{mark}.png")
        print(f"  screenshot {shots[f'+{mark}s']}", flush=True)
        pump()
    while time.time() < fault_wall + observe:
        time.sleep(min(5.0, max(0.5, fault_wall + observe - time.time())))
        pump()
    pump()
    stop_pump.set()
    pumper.join(timeout=5)
    cap.close()
    # Report completeness of the capture IN THE TRIAL OUTPUT. An incomplete window must be visible
    # at the moment it is produced, not discovered an hour later when it has already rotated away.
    covered = set()
    for line in seen_lines:
        head = line.split("|", 1)[0]
        if head.isdigit():
            covered.add(int(head) // 1000)
    want = set(range(int(fault_wall) - 10, int(fault_wall + observe)))
    have = covered & want
    print(f"  JOURNAL COVERAGE: {len(have)}/{len(want)} seconds of the trial window "
          f"({'complete' if len(have) >= len(want) - 2 else 'INCOMPLETE - do not draw delivery conclusions from this trial'})",
          flush=True)
    time.sleep(max(0.0, fault_wall + observe - time.time()))
    end = time.time()

    client_txt = journal_since(start_wall_ms)
    trace_txt = th.trace_slice(t_off)
    # Merge the final capture with what was pumped live, de-duplicated, so a rotation mid-window
    # can neither lose lines nor double-count them.
    with open(cap_path, "a") as fh:
        fh.write(client_txt + "\n")
    merged, seen2 = [], set()
    for ln in open(cap_path).read().splitlines():
        if ln.strip() and ln not in seen2:
            seen2.add(ln)
            merged.append(ln)
    open(cap_path, "w").write("\n".join(merged) + "\n")
    client_txt = "\n".join(merged)
    # The device journal rotates at ~2 MB and keeps only one rotated file, so a window becomes
    # unreadable minutes later. Archive the whole readable journal with each trial.
    open(os.path.join(SHOTS, f"{scenario}_{n}_journal_full.log"), "w").write(journal_since(0))
    open(os.path.join(SHOTS, f"{scenario}_{n}_gateway.log"), "w").write(trace_txt)
    client, sids = th.parse_client(client_txt)
    rows = th.parse_trace(trace_txt)
    # Pairing proof: a gateway turn_start must coincide with the app's submit.
    starts = [r for r in rows if r.get("kind") == "lifecycle" and r["action"] == "turn_start"
              and abs(r["t"] - t_submit) < 5.0]
    sid = starts[0]["sid"] if starts else None
    print(f"  paired sid: {sid}", flush=True)
    # A control batch injects no fault, so "a socket was killed" is not a validity condition.
    # Scenario H arms a CLIENT-side fault (the app drops its own answer stream) — also no socket
    # event, and the same reasoning applies: requiring a kill would mark every H trial INVALID.
    kill_ok = bool(re.search(r"ESTAB|FIN|killed=True|fault_injected=True", fault_desc)) \
        or "control" in fault_desc
    bucket, why = th.classify(client, rows, fault_wall, kill_ok, sid=sid,
                              control="control" in fault_desc or scenario == "H")
    chat_live = bool(client["vm_state_rx"] or client["gate"] or client["poll_loop"])
    if not chat_live and bucket not in ("B1", "B2"):
        bucket, why = "INVALID", "no chat ViewModel active during the drop"
    lc = [r for r in rows if r.get("kind") == "lifecycle"]
    attached = bool(sid) and any(r["sid"] == sid and r["action"] in ("detach", "rebind") for r in lc)
    if not attached and scenario != "H" and bucket != "HARNESS_GAP":
        # H arms no network fault, so there is no transport churn to require.
        bucket, why = "INVALID", f"phone transport never bound to the turn's session {sid}"

    rec = {"scenario": scenario, "trial": n, "chat": chat, "sid": sid,
           "turn_start": turn_wall, "app_submit": t_submit, "app_session_key": sess_key,
           "fault_at": fault_wall,
           "observed_s": round(end - fault_wall, 1), "bucket": bucket, "why": why,
           "attached": attached, "chat_live": chat_live,
           "counts": {k: len(v) for k, v in client.items() if v},
           "sids": sorted(sids), "kill": fault_desc[:200], "shots": shots,
           "key_evidence": {
               "gen_open": [r["line"] for r in client["gen_open"]][:6],
               "state_emit": [r["line"] for r in client["state_emit"]][:8],
               "gate": [r["line"] for r in client["gate"]][:6],
               "replay": [r["line"] for r in client["replay"]][:4],
               "replayed": [r["line"] for r in client["replayed"]][:8],
               "ui_count": [r["line"] for r in client["ui_count"]][-4:],
               "resume_err": [r["line"] for r in client["resume_err"]][:4],
               "watchdog": [r["line"] for r in client["watchdog"]][:4],
               "unknown_event": [r["line"] for r in client["unknown_event"]][:6],
               "gateway": [r for r in rows if r.get("kind") == "lifecycle"][:12],
               "gateway_drops": [r for r in rows if r.get("route") == "no_session_transport"][:6],
           }}
    with open(th.RESULTS, "a") as f:
        f.write(json.dumps(rec) + "\n")
    print(f"  BUCKET {bucket}: {why}", flush=True)
    print(f"  counts {rec['counts']}", flush=True)
    return rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=5)
    ap.add_argument("--start-trial", type=int, default=1)
    ap.add_argument("--scenario", default="D")
    ap.add_argument("--chat", default="testing", help="session title to open in the app")
    ap.add_argument("--extra-chat", action="append", default=[],
                    help="open these chats first, to reproduce the multi-ChatVM reconnect storm")
    ap.add_argument("--prompt", action="append", default=[],
                    help="repeatable; trials cycle through the given prompts. Repeating ONE prompt "
                         "makes the agent answer with a one-line pointer to its previous answer, "
                         "which degrades what the trial measures - vary the prompts instead.")
    ap.add_argument("--kill-at", type=float, default=20.0)
    ap.add_argument("--observe", type=float, default=150.0)
    ap.add_argument("--arm-timeout", type=int, default=90)
    ap.add_argument("--exclude-sid", action="append", default=["74e51dbb"])
    a = ap.parse_args()

    prompts = a.prompt or [PROMPT_DEFAULT]
    results = []
    for i in range(a.count):
        n = a.start_trial + i
        try:
            results.append(run_trial(n, a.scenario, a.chat, prompts[i % len(prompts)], a.kill_at, a.observe,
                                      a.arm_timeout, a.exclude_sid, a.extra_chat))
        except Exception as e:
            print(f"  TRIAL {n} crashed: {type(e).__name__}: {e}", flush=True)
            results.append({"trial": n, "bucket": "HARNESS_ERROR", "why": str(e)[:200]})
        time.sleep(5)
    print("\nALL TRIALS DONE", flush=True)
    for r in results:
        key = "{}/{}".format(r.get("scenario", "?"), r.get("trial"))
        print("  {}: {} — {}".format(key, r.get("bucket"), r["why"][:90]))


if __name__ == "__main__":
    main()
