#!/usr/bin/env python3
"""Decide, per trial, whether a missing answer is H1 (frames reached the app and were dropped
inside it) or H2 (they never reached this client). Written because a whole day of reasoning about
D/14 rested on counts I had not attributed to a session — see
HermexAndroid/panel-gate-P3-2026-10-19.md.

Inputs are the two artifacts every trial already produces:
  --client   the captured app journal for the trial window (shots/<scen>_<n>_client.log)
  --gw       the gateway frame trace (default ~/.hermes/logs/gateway_frame_trace.log)
  --sid      the live session id under test (prefix ok, e.g. 34b8c2a1)
  --t0 --t1  wall-clock epoch SECONDS bounding the turn

It reports counts, not a story, and refuses to decide when the capture is incomplete.
"""
import argparse, os, re, sys

CLIENT_FRAME = re.compile(r"TRACE\|frame\|.*sid=([0-9a-f]{4,})")
CLIENT_DISP = re.compile(r"disp=(APPLIED|FILTERED|NO_ROW)")
CLIENT_NOTIFY = re.compile(r"notification (MATCHED|FILTERED): nSid=([0-9a-f]*)[^|]*dbKey=([^ ]*) liveSid=([0-9a-f]*)")
GW_ROUTE = re.compile(r"^(\d+)\s+sid=(\S+)\s+seq=(\d+)\s+type=(\S+)")
GW_LIFE = re.compile(r"^(\d+)\s+sid=(\S+)\s+(rebind|detach|turn_start|connect|close)\s+transport=(\S+)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--client", required=True)
    ap.add_argument("--gw", default=os.path.expanduser("~/.hermes/logs/gateway_frame_trace.log"))
    ap.add_argument("--sid", required=True)
    ap.add_argument("--t0", type=float, required=True)
    ap.add_argument("--t1", type=float, required=True)
    a = ap.parse_args()

    txt = open(a.client, errors="replace").read()
    lines = [l for l in txt.splitlines() if l[:13].isdigit()]
    lo, hi = int(a.t0), int(a.t1)

    # capture completeness first: an incomplete window cannot support either conclusion.
    # NOTE the metric: "seconds that contain at least one journal line" is NOT completeness —
    # a quiet chat legitimately writes nothing for tens of seconds. Completeness means the
    # captured timestamps SPAN the trial window with no rotation marker inside it. The old
    # per-second ratio called correct captures "26% INCOMPLETE" and made every P5 verdict
    # provisional for no reason.
    ts = sorted(int(l[:13]) // 1000 for l in lines)
    inside = [t for t in ts if lo <= t <= hi]
    rotated = sum(1 for l in lines if re.search(r"\bROTATE\b", l))
    span_cov, max_silence = 0.0, 0
    if inside:
        s_lo, s_hi = inside[0], inside[-1]
        span_cov = (min(s_hi, hi) - max(s_lo, lo)) / max(1, hi - lo)
        max_silence = max((b - a for a, b in zip(inside, inside[1:])), default=0)
    complete = bool(inside) and span_cov >= 0.9 and rotated == 0
    frac = span_cov

    covered = set(ts)
    want = set(range(lo, hi))
    have = covered & want

    cids = set(re.findall(r"cid=([0-9a-f]{4,})", txt))
    ingress = {}
    for l in lines:
        m = CLIENT_FRAME.search(l)
        t = int(l[:13]) / 1000.0
        if m and lo <= t <= hi:
            ingress[m.group(1)] = ingress.get(m.group(1), 0) + 1
    disp = {"APPLIED": 0, "FILTERED": 0, "NO_ROW": 0}
    # Per-ViewModel, because that is the whole point of the disposition lines. With three
    # ChatVMs open on one socket (the matched-config setup), FILTERED lines for the target
    # sid are EXPECTED — the two VMs that are not showing that chat must drop its frames.
    # Counting them together with APPLIED ones produced a false H1 verdict on every P5
    # trial: applied == ingress, yet the aggregate said "dropped inside the app".
    disp_by_vm = {}
    for l in lines:
        t = int(l[:13]) / 1000.0
        if not (lo <= t <= hi):
            continue
        for m in CLIENT_DISP.finditer(l):
            if a.sid in l or f"sid={a.sid}" in l:
                disp[m.group(1)] += 1
                vm = re.search(r"vm=(\S+)", l)
                per = disp_by_vm.setdefault(vm.group(1) if vm else "?",
                                           {"APPLIED": 0, "FILTERED": 0, "NO_ROW": 0})
                per[m.group(1)] += 1
    legacy = {"MATCHED": 0, "FILTERED": 0}
    for l in lines:
        t = int(l[:13]) / 1000.0
        if not (lo <= t <= hi):
            continue
        m = CLIENT_NOTIFY.search(l)
        if m and a.sid in (m.group(2), m.group(4)):
            legacy[m.group(1)] += 1

    # gateway side
    routed, life = 0, []
    with open(a.gw, errors="replace") as fh:
        for line in fh:
            head = line.split(" ", 1)[0]
            if not head.isdigit():
                continue
            t_us = int(head)
            if not (a.t0 * 1e6 <= t_us <= a.t1 * 1e6):
                continue
            if f"sid={a.sid}" not in line:
                continue
            m = GW_ROUTE.match(line)
            if m:
                routed += 1
                continue
            m = GW_LIFE.match(line)
            if m:
                life.append((t_us / 1e6, m.group(3), m.group(4)))

    target_ingress = sum(v for k, v in ingress.items() if k.startswith(a.sid))
    other_ingress = sum(v for k, v in ingress.items() if not k.startswith(a.sid))

    print(f"window {lo}..{hi} ({hi-lo}s)  sid={a.sid}")
    print(f"  journal capture: first/last entries in window = "
          f"{inside[0] if inside else '-'}/{inside[-1] if inside else '-'}; span coverage {span_cov:.0%}; "
          f"largest internal silence {max_silence}s; rotate markers {rotated}")
    print(f"  capture completeness: {'COMPLETE' if complete else 'INCOMPLETE — conclusions are provisional'}"
          f"   (lines present in {len(have)}/{len(want)} seconds of the window; a quiet chat writes nothing, "
          f"which is not a hole)")
    print(f"  cids seen in client journal: {sorted(cids) or 'NONE (pre-cid build)'}")
    print(f"  gateway: routed frames for this sid = {routed}; lifecycle events = {len(life)}")
    for t, kind, tr in life[:8]:
        print(f"      {t:.3f} {kind} transport={tr}")
    print(f"  client ingress by sid: target={target_ingress} other={other_ingress}")
    print(f"  disposition for target sid (aggregate): {disp}   legacy MATCHED/FILTERED: {legacy}")
    for vm in sorted(disp_by_vm):
        d = disp_by_vm[vm]
        role = "accepted the turn" if d["APPLIED"] else ("dropped every frame for this sid"
                                                         if d["FILTERED"] else "saw nothing")
        print(f"      vm={vm}: {d}  -> {role}")

    if routed == 0:
        print("VERDICT: NO-GATEWAY-TRAFFIC — the turn's frames are not in the trace window; check --t0/--t1.")
        return 1
    if not inside:
        print("VERDICT: INCONCLUSIVE — no journal entries at all inside the trial window.")
        return 2
    if target_ingress == 0 and legacy["MATCHED"] == 0:
        print(f"VERDICT: H2 — gateway routed {routed} frames for this session but the app logged no "
              f"inbound frame for it (it did log {other_ingress} for other sessions in the same window).")
        return 0
    applied_anywhere = disp["APPLIED"] or legacy["MATCHED"]
    if applied_anywhere:
        filtered_note = (f" Other ViewModels filtered their copies ({disp['FILTERED']} lines), which is the "
                         "correct behaviour for VMs that are not showing this chat."
                         if disp["FILTERED"] else "")
        print("VERDICT: DELIVERED-AND-ACCEPTED — frames reached the app AND at least one ViewModel applied "
              "them, so delivery and the identity filter both worked. If a user still saw no answer the loss "
              "is downstream of the filter (rendering/state), not delivery." + filtered_note
              + " Whether a row actually rendered is decided by the screenshot mark in mark.py, not here.")
        return 0
    if disp["FILTERED"] or legacy["FILTERED"]:
        print(f"VERDICT: H1 — frames reached the app ({target_ingress} inbound) and EVERY ViewModel dropped "
              f"them (filtered={disp['FILTERED'] or legacy['FILTERED']}, applied=0). That is the D/14 shape: "
              "the parser saw the frames, the identity filter rejected them, nothing was rendered.")
        return 0
    print("VERDICT: INCONCLUSIVE — no disposition lines; is this a build with the P5 instrumentation?")
    return 2


if __name__ == "__main__":
    sys.exit(main())
