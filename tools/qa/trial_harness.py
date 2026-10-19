#!/usr/bin/env python3
"""Hermex v3 mid-turn drop trial harness (audit Phase 0/1 trial matrix).

Runs ONE trial: marks log offsets, induces a network fault at t+kill_at,
observes for --observe seconds, then pulls the app journal slice + gateway
frame-trace slice for that window and classifies the outcome into a bucket.

Log timestamps are wall-clock epoch, verified against time.time():
  app journal prefix  = milliseconds since epoch
  gateway trace prefix = microseconds since epoch (frame lines and _trace_bind lines)
  → t_client = ms/1000, t_gateway = us/1e6, both comparable to time.time() directly.

Buckets (from the audit's Phase 0 protocol):
  B1 no reconnect
  B2 CONNECTED suppressed
  B3 handler ran, gate false
  B4 gate true, resume threw
  B5 resume ok, server did not rebind
  B6 unknown event dropped
  B7 reconcile ran, UI did not update
  B8 duplicate loops
  PASS  full recovery chain with no user action

Usage:
  python3 trial_harness.py --scenario D --trial 1 [--kill-at 20] [--observe 150]
  python3 trial_harness.py --list        # show scenarios + last results
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime

SERIAL = "39151FDJH00HX4"          # Pixel 8 (shiba), tailnet 100.101.185.85
PHONE_TS = "100.101.185.85"
PKG = "com.hermex.v3"
GATEWAY_TRACE = os.path.expanduser("~/.hermes/logs/gateway_frame_trace.log")
OUT_DIR = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(OUT_DIR, "trials.jsonl")


def sh(cmd, check=False):
    p = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True)
    if check and p.returncode != 0:
        raise RuntimeError(f"{cmd}: {p.stderr.strip()}")
    return p.stdout


def adb(cmd):
    return sh(["adb", "-s", SERIAL, "shell"] + (cmd.split() if isinstance(cmd, str) else list(cmd)))


def journal_size():
    out = adb(["run-as", PKG, "stat", "-c", "%s", "files/debuglog/hermex.log"]).strip()
    return int(out) if out.isdigit() else 0


def journal_slice(from_bytes):
    """Full journal from a byte offset (1-indexed tail -c +N)."""
    return adb(["run-as", PKG, "tail", "-c", f"+{from_bytes + 1}",
                "files/debuglog/hermex.log"])


def trace_slice(from_bytes):
    with open(GATEWAY_TRACE, "rb") as f:
        f.seek(from_bytes)
        return f.read().decode("utf-8", "replace")


# ── fault injection ──────────────────────────────────────────────────────────
def inject(scenario, note):
    """Return a short description of what was done."""
    if scenario == "G":          # control: no fault at all
        # P1 of the long run: does a blank final assistant row ever occur with NO network
        # event? The D/14 evidence says the drop was incidental, so the control batch is the
        # cheapest test of that claim. Nothing is touched here — that is the point.
        return "no fault injected (control)"
    if scenario == "D":          # server-side socket kill, agent keeps running
        out = sh(f'sudo ss -K "sport = :9119 and dst {PHONE_TS}" 2>&1')
        return f"ss -K phone socket: {out.strip()[:120]}"
    if scenario == "A":          # airplane mode 5 s
        adb("cmd -w connectivity airplane-mode enable")
        time.sleep(5)
        adb("cmd -w connectivity airplane-mode disable")
        return "airplane mode on 5s / off"
    if scenario == "C":          # Doze + screen off
        adb("screen-off")
        adb("dumpsys deviceidle force-idle")
        time.sleep(note.get("doze_seconds", 180))
        adb("dumpsys deviceidle unforce")
        adb("input keyevent KEYCODE_WAKEUP")
        return "screen off + deviceidle force-idle"
    if scenario == "E":          # kill during final-answer streaming
        out = sh(f'sudo ss -K "sport = :9119 and dst {PHONE_TS}" 2>&1')
        return f"ss -K during final stream: {out.strip()[:120]}"
    if scenario == "F":          # sustained outage: re-kill every 3 s for 20 s
        # The app reconnects ~2.5 s after it notices, so a single kill leaves only a ~2.5 s
        # dead window. Re-killing keeps it disconnected long enough that any short turn
        # FINISHES inside the outage — the exact condition trial D/14 hit by luck.
        outs = []
        for i in range(7):
            outs.append(sh(f'sudo ss -K "sport = :9119 and dst {PHONE_TS}" 2>&1').strip()[:60])
            time.sleep(3)
        # Report whether any kill actually matched a socket, so the harness can tell a real
        # sustained outage from a no-op (the header-only output used to read as INCONCLUSIVE).
        killed = any("tcp" in o.lower() or "estab" in o.lower() for o in outs)
        return f"sustained outage (7 kills over 21s) killed={killed}: {'; '.join(outs)}"[:600]
    if scenario == "H":          # client-side fault: drop the answer stream, no network event
        # The point of this scenario is reachability, not recovery from a network problem. A socket
        # kill never leaves a blank final row standing: rule A fires while something still looks in
        # flight, rule B fires on the running true->false transition. Both are correct, so rule D is
        # never reached that way. Here the app itself drops message.delta/thinking/reasoning AND
        # message.complete/run.completed for a bounded window while message.start and the tool events
        # still land -- exactly the state trial D/14 left behind. See DebugFaults.kt (v0.1.895).
        ms = int(note.get("drop_ms", 25000))
        out = adb(f"am broadcast -n com.hermex.v3/.debug.DebugFaultReceiver "
                  f"-a com.hermex.v3.action.DEBUG_FAULT -e kind drop_answer_stream --el ms {ms}")
        armed = "Result:" in out and "Error" not in out
        return (f"client-side fault: drop_answer_stream {ms}ms fault_injected={armed}: "
                f"{out.strip()[:160]}")
    raise SystemExit(f"unknown scenario {scenario}")


# ── parsing ──────────────────────────────────────────────────────────────────
CLIENT_MARKERS = {
    "gen_open": r"gen_open\|",
    "ws_cb": r"ws_cb\|",
    "state_emit": r"state_emit\|",
    "suppress": r"suppressState|state_suppress|onClosed on superseded",
    "net_lost": r"net_lost",
    "net_available": r"net_available",
    "vm_state_rx": r"vm_state_rx",
    "gate": r"\bgate\||gate_poll\|",
    "replay": r"replay\||replay_decision\|",
    "replayed": r"replayed\|",
    "resume": r"sessionResume|session\.resume",
    "resume_err": r"resume.*(exception|Exception|failed|error)|Reconcile.*[Ee]xception",
    "watchdog": r"[Ww]atchdog",
    "ui_count": r"UI\|MsgCount",
    "unknown_event": r"unknown_event|unknown event",
    # Notification routing per ViewModel: MATCHED = accepted by an owning chat VM,
    # FILTERED = rejected by a non-owning one. The counts say whether the session that ran
    # the turn ever received its own frames — the question D/14 left open.
    "notify_matched": r"notification MATCHED",
    "notify_filtered": r"notification FILTERED",
    "poll_loop": r"poll_loop|poll_tick|poll_resume",
    "stale_card": r"closeStaleCards|staleCard|stale_card",
    "turn_done": r"turnDoneSeen",
    # v0.1.893 gate fields: `blankFinalRow=true` is rule D seeing the D/14 state;
    # "full reload" is the action it takes. Together they show on-device whether the
    # rule fires when it should and stays quiet when it shouldn't.
    "rule_d_seen": r"blankFinalRow=true",
    "full_reload": r"full reload",
    "tool_card": r"tool\.complete|toolComplete",
    # P5 join keys (app-side instrumentation added after the P3 panel gate). These exist so a
    # single failing trial can settle H1 vs H2 instead of leaving it to inference:
    #   cid=            the app's own connection id, so gateway transport ids can be joined
    #   disp=APPLIED/FILTERED/NO_ROW  what the displaying ViewModel did with a frame it received
    #   TRACE|identity  every liveSid change and VM register/unregister
    #   ROTATE          the journal rotated, so any gap in this capture is self-describing
    "conn_cid": r"WS\|conn cid=",
    "disp_applied": r"disp=APPLIED",
    "disp_filtered": r"disp=FILTERED",
    "disp_no_row": r"disp=NO_ROW",
    "identity_change": r"TRACE\|identity|liveSid rotated",
    "rotate_marker": r"\bROTATE\b",
}


def parse_client(text):
    """Journal prefix is SystemClock.elapsedRealtime-style epoch MILLISECONDS (verified against
    wall clock on device), so t = ms/1000 lines up directly with the gateway trace."""
    hits = {k: [] for k in CLIENT_MARKERS}
    sids = set()
    for line in text.splitlines():
        parts = line.split("|", 2)
        if not parts or not parts[0].strip().isdigit():
            continue
        t_wall = int(parts[0]) / 1000.0
        for k, pat in CLIENT_MARKERS.items():
            if re.search(pat, line):
                hits[k].append({"t": round(t_wall, 3), "line": line[:400]})
        for m in re.finditer(r"sid=([0-9a-f]{8})", line):
            sids.add(m.group(1))
    return hits, sids


TRACE_FRAME = re.compile(r"^(\d+)\s+sid=([0-9a-f]+)\s+seq=(\d+)\s+type=(\S+)\s+id=(\S+)\s+route=(\S+)")
# tui_gateway/server.py:_trace_bind → "<us> sid=<8hex> <action> transport=<hex> reason=<word>"
TRACE_LIFECYCLE = re.compile(r"^(\d+)\s+sid=([0-9a-f]+)\s+(turn_start|turn_end|detach|rebind|rebind_skipped)"
                            r"\s+transport=(\S+)\s+reason=(\S+)")


def parse_trace(text):
    """Normalise both trace line shapes onto one record type. Prefixes are microseconds since
    the epoch (verified against wall clock), so t is directly comparable to time.time().

    frame:     {"kind":"frame","t","sid","seq","type","route"}
    lifecycle: {"kind":"lifecycle","t","sid","action","transport","reason"}
    """
    rows = []
    for line in text.splitlines():
        line = line.strip()
        m = TRACE_FRAME.match(line)
        if m:
            ns, sid, seq, typ, _rid, route = m.groups()
            rows.append({"kind": "frame", "t": round(int(ns) / 1e6, 3), "sid": sid,
                         "seq": int(seq), "type": typ, "route": route})
            continue
        m = TRACE_LIFECYCLE.match(line)
        if m:
            ns, sid, action, transport, reason = m.groups()
            rows.append({"kind": "lifecycle", "t": round(int(ns) / 1e6, 3), "sid": sid,
                         "action": action, "type": action, "transport": transport, "reason": reason})
    return rows


def classify(client, trace_rows, fault_wall, kill_ok, sid=None, control=False):
    """First-pass bucket call. The human (agent) adjudicates from the evidence."""
    def after(rows, t):
        return [r for r in rows if r["t"] >= t]

    opens = after(client["gen_open"], fault_wall)
    connected = [r for r in after(client["state_emit"], fault_wall)
                 if "→Connected" in r["line"]]
    vms = after(client["vm_state_rx"], fault_wall)
    def gate_flag(line, key="reconcile"):
        m = re.search(rf"{key}=(true|false)", line)
        return m.group(1) if m else None

    def gate_running(line):
        m = re.search(r"running=(true|false)", line)
        return m.group(1) if m else None

    gates = after(client["gate"], fault_wall)
    def gate_local_inflight(line):
        m = re.search(r"localInFlight=(true|false)", line)
        return m.group(1) if m else None

    # A gate returning false while the server says the turn is RUNNING is CORRECT behaviour —
    # there is nothing to reconcile until the turn ends. Only a false verdict on a finished turn
    # WITH local work in flight (running=false and localInFlight=true) is the failure mode B3
    # describes. Measured in trial D/4: running=true → false, streaming resumed after rebind,
    # final row arrived; its other gate had running=false with localInFlight=false, i.e. nothing
    # to reconcile, so false was right there too.
    turn_over_gates = [g for g in gates if gate_running(g["line"]) == "false"
                       and gate_local_inflight(g["line"]) == "true"]
    gate_true = [g for g in gates if gate_flag(g["line"]) == "true"]
    expected_false = [g for g in gates if gate_running(g["line"]) == "true"
                      and gate_flag(g["line"]) == "false"]
    ui_final = [u for u in after(client["ui_count"], fault_wall) if "isStreaming=false" in u["line"]]
    resume_err = after(client["resume_err"], fault_wall)
    resumed = after(client["resume"], fault_wall)
    replayed = after(client["replayed"], fault_wall)
    ui = after(client["ui_count"], fault_wall)
    unknown = after(client["unknown_event"], fault_wall)
    watchdog = after(client["watchdog"], fault_wall)
    polls = {r["line"].split("id=")[1][:8] for r in after(client["poll_loop"], fault_wall)
             if "poll_loop" in r["line"] and "id=" in r["line"]}
    drops = [r for r in trace_rows if r.get("route") == "no_session_transport" and r["t"] >= fault_wall]
    rebinds = [r for r in trace_rows if r["kind"] == "lifecycle" and r["action"] == "rebind"
               and r["t"] >= fault_wall]
    turn_ends = [r for r in trace_rows if r["kind"] == "lifecycle" and r["action"] == "turn_end"
                 and r["t"] >= fault_wall]

    if not kill_ok:
        return "INCONCLUSIVE", "fault injection did not report a killed socket"
    if not any(client[k] for k in client):
        # No client-side evidence at all: the journal slice came back empty (log rotation,
        # app not running, offset past EOF). The app cannot be judged from that.
        return "HARNESS_GAP", "no client journal lines captured for this window"
    if control:
        # A control batch injects no fault, so every recovery-chain bucket (B1 no reconnect,
        # B2 no CONNECTED, B3 gate never evaluated) is meaningless by construction: there is
        # nothing to recover from. Decline the call and let the +120 s screenshot decide
        # whether the turn rendered — which is the only thing a control measures.
        return "CONTROL", "no fault injected; judge from the scrolled +120 s screenshot"
    if not opens:
        return "B1", "no reconnect after the drop"
    if opens and not connected:
        return "B2", "socket opened, CONNECTED never emitted"
    if connected and not vms:
        return "B3", "CONNECTED emitted but no vm_state_rx (handler not driven)"
    if vms and not gates:
        return "B3", "handler ran, reconcile gate never evaluated"
    if gates and not gate_true and turn_over_gates:
        return "B3", "turn reported finished with local work in flight, gate returned false"
    if gate_true and resume_err:
        return "B4", "gate true, session.resume threw"
    if gate_true and not resumed:
        return "B4", "gate true, no session.resume issued"
    if resumed and drops and not rebinds:
        return "B5", "resumed, gateway kept routing with no owning transport (no rebind)"
    if sid and connected and resumed and client["watchdog"] and not any(
            r.get("kind") == "frame" and r.get("sid") == sid and r["t"] >= fault_wall
            for r in trace_rows):
        # Connection recovery is clean and the session was resumed, but the gateway produced
        # nothing more for it: the turn's content is gone, a tool card is left incomplete, and
        # the app's watchdog force-closes it. This is the frozen-card path, measured live (D/14).
        return "B9?", ("turn content lost after the drop: reconnect and resume clean, 0 frames "
                       f"delivered to {sid}, no turn_end, tool-card watchdog fired "
                       f"{len(client['watchdog'])}× to close an incomplete card — "
                       "AUTO-SUGGESTED ONLY: frame delivery is a heuristic and the +120 s screenshot "
                       "must be scrolled to the last message before this counts as a failure")
    if (gate_true or replayed) and not ui:
        return "B7", "reconcile/replay fired, UI row count never changed"
    if len(polls) > 1:
        return "B8", f"{len(polls)} concurrent poll loops: {sorted(polls)}"
    # Recovery chain first: a drop that recovers fully is a PASS even if unrelated events are
    # being dropped. Dropped-event names are reported as a side finding, never as the bucket —
    # v0.1.892 trial D/2 recovered end to end and the old ordering mislabelled it B6.
    def names(rows):
        # Only real event names. Falling back to a line tail produced junk entries made of
        # JSON payload fragments, which made the side finding unreadable.
        out = set()
        for r in rows:
            m = re.search(r"name=([a-zA-Z.]+)", r["line"])
            if m:
                out.add(m.group(1))
        return sorted(out)
    dropped = names(unknown)
    side = f"; SIDE FINDING dropped events: {dropped}" if dropped else ""
    if connected and resumed and ui_final and (gate_true or replayed or expected_false):
        return "PASS", ("full chain: reconnect → Connected → resume/replay → final row rendered "
                        "(isStreaming=false)" + side)
    if connected and resumed and ui and expected_false:
        return "PASS", ("turn was still running server-side at the drop; gate correctly stayed "
                        "false, stream continued after rebind and the UI kept updating" + side)
    if connected and gate_true and (resumed or replayed) and ui:
        return "PASS", "full chain: reconnect → Connected → gate → resume/replay → UI update" + side
    # Stream continuation: a mid-turn drop where the app keeps streaming into the SAME message
    # produces no row-count change and no replay, so the checks above see nothing. Proof is
    # frames delivered to that session over the reconnected socket, plus the turn finishing.
    if sid and connected:
        delivered = [r for r in trace_rows if r.get("kind") == "frame"
                     and r.get("sid") == sid and r["t"] >= fault_wall]
        if not delivered and resumed and client["watchdog"]:
            # Connection recovery is clean and the session was resumed, but the gateway produced
            # nothing more for it: the turn's content is gone and a tool card is left incomplete.
            # The app's watchdog force-closes it — this is the frozen-card path, measured live.
            return "B9", ("turn content lost after the drop: reconnect and resume clean, 0 frames "
                          "delivered to " + sid + ", no turn_end, tool-card watchdog fired "
                          f"{len(client['watchdog'])}× to close an incomplete card")
        ended = [r for r in trace_rows if r.get("kind") == "lifecycle"
                 and r.get("action") == "turn_end" and r.get("sid") == sid and r["t"] >= fault_wall]
        if len(delivered) >= 5:
            tail = " and the turn ended" if ended else " (turn end not seen in window)"
            return "PASS", (f"stream continued over the reconnected socket: {len(delivered)} frames "
                            f"delivered to {sid} after the drop{tail}" + side)
    if dropped:
        return "B6", f"no recovery chain observed and events dropped: {dropped}"
    return "INCONCLUSIVE", "chain partially observed; adjudicate from evidence"


def arm_on_turn(off, timeout, expect_sid=None, exclude_sids=()):
    """Watch the gateway frame trace for a new turn_start. Returns (wall, sid)."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        rows = parse_trace(trace_slice(off))
        starts = [r for r in rows if r.get("type") == "turn_start"
                  and (expect_sid is None or r["sid"] == expect_sid)
                  and r["sid"] not in exclude_sids]
        if starts:
            return starts[0]["t"], starts[0]["sid"]
        time.sleep(0.4)
    return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", required=True, help="A | C | D | E")
    ap.add_argument("--trial", type=int, default=1)
    ap.add_argument("--kill-at", type=float, default=20.0, help="seconds after start to inject the fault")
    ap.add_argument("--observe", type=float, default=150.0, help="seconds to watch after the fault")
    ap.add_argument("--doze-seconds", type=int, default=180)
    ap.add_argument("--label", default="", help="what the turn under test is doing")
    ap.add_argument("--arm-on-turn", type=int, default=0, metavar="TIMEOUT_S",
                    help="wait for a gateway turn_start, then fault kill_at seconds into THAT turn")
    ap.add_argument("--expect-sid", default=None, help="only arm on this live session id")
    ap.add_argument("--exclude-sid", action="append", default=[],
                    help="ignore turn_start for this sid (e.g. the agent running the harness)")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()

    if a.list:
        if not os.path.exists(RESULTS):
            print("no trials recorded yet")
            return
        rows = [json.loads(l) for l in open(RESULTS)]
        print(f"{len(rows)} trials")
        for r in rows:
            print(f"  {r['scenario']}/{r['trial']}: {r['bucket']} — {r['why']} "
                  f"(ui_rows={r['counts'].get('ui_count')}, replayed={r['counts'].get('replayed')})")
        return

    j_off = journal_size()
    t_off = os.path.getsize(GATEWAY_TRACE) if os.path.exists(GATEWAY_TRACE) else 0
    start = time.time()
    turn_wall, turn_sid = None, None
    if a.arm_on_turn:
        print(f"[trial {a.scenario}/{a.trial}] armed: waiting up to {a.arm_on_turn}s "
              f"for a gateway turn_start, will fault at +{a.kill_at}s into it", flush=True)
        turn_wall, turn_sid = arm_on_turn(t_off, a.arm_on_turn,
                                         expect_sid=a.expect_sid,
                                         exclude_sids=set(a.exclude_sid))
        if turn_wall is None:
            print("no turn observed within the arm window — nothing to fault, trial not recorded")
            return
        print(f"  TURN_START observed wall={datetime.fromtimestamp(turn_wall).isoformat()} "
              f"sid={turn_sid}; faulting at +{a.kill_at}s", flush=True)
        fault_wall = turn_wall + a.kill_at
        now = time.time()
        if fault_wall > now:
            time.sleep(fault_wall - now)
    else:
        print(f"[trial {a.scenario}/{a.trial}] marker at wall {datetime.fromtimestamp(start).isoformat()} "
              f"journal@{j_off} trace@{t_off}")
        print(f"  sleeping {a.kill_at}s to fault injection…", flush=True)
        time.sleep(a.kill_at)
        fault_wall = time.time()
    fault_desc = inject(a.scenario, {"doze_seconds": a.doze_seconds})
    kill_ok = "estab" in fault_desc.lower() or "killed" in fault_desc.lower() \
        or "airplane" in fault_desc.lower() or "doze" in fault_desc.lower() \
        or a.scenario not in ("D", "E")
    print(f"  FAULT @ {datetime.fromtimestamp(fault_wall).isoformat()}: {fault_desc}", flush=True)
    time.sleep(a.observe)
    end = time.time()

    client_txt = journal_slice(j_off)
    trace_txt = trace_slice(t_off)
    open(os.path.join(OUT_DIR, f"last_{a.scenario}_{a.trial}_client.log"), "w").write(client_txt)
    open(os.path.join(OUT_DIR, f"last_{a.scenario}_{a.trial}_gateway.log"), "w").write(trace_txt)

    client, sids = parse_client(client_txt)
    trace_rows = parse_trace(trace_txt)
    bucket, why = classify(client, trace_rows, fault_wall, kill_ok)

    # Precondition (protocol): the chat screen must be open with a live chat ViewModel while the
    # drop happens. On the sessions list there is no VM, so the absence of gate/replay lines is
    # an artifact of the setup, not a finding. Measured 2026-10-18: a trial fired while the app
    # sat on the session list produced reconnect + fast rebind and zero reconcile activity.
    chat_live = bool(client["vm_state_rx"] or client["gate"] or client["poll_loop"])
    if not chat_live and bucket not in ("B1", "B2"):
        bucket, why = "INVALID", ("no chat ViewModel active during the drop (app not on the chat "
                                  "screen) — reconnect path observed, reconcile untestable")

    counts = {k: len(v) for k, v in client.items()}
    rec = {
        "scenario": a.scenario, "trial": a.trial, "label": a.label,
        "started": start, "fault_at": fault_wall, "observed_s": round(end - fault_wall, 1),
        "turn_start": turn_wall, "turn_sid": turn_sid,
        "bucket": bucket, "why": why, "counts": counts,
        "sids": sorted(sids),
        "kill": fault_desc,
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
            "gateway_route_drops": [r for r in trace_rows if r.get("route") == "no_session_transport"][:6],
            "gateway_turn": [r for r in trace_rows if r.get("kind") == "lifecycle"][:12],
        },
    }
    with open(RESULTS, "a") as f:
        f.write(json.dumps(rec) + "\n")
    print(json.dumps({"bucket": bucket, "why": why, "counts": counts,
                      "sids": sorted(sids)}, indent=2))
    print(f"raw logs: {OUT_DIR}/last_{a.scenario}_{a.trial}_client.log, ..._gateway.log")


if __name__ == "__main__":
    main()
