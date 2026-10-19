# Hermex QA harness — on-device fault trials

Scripts here drive a real phone over ADB and decide what the app actually did during a network
fault. They exist because reading code was not enough: three separate defects in this area were
found only by forcing a fault mid-turn on hardware and reading both sides of the wire afterwards.

This is a copy of the working set used for the v0.1.892–v0.1.894 investigation (`~/hermex-qa/`),
checked in so the results quoted in `CHANGELOG.md` and the vault notes can be reproduced without
that directory. Paths inside the scripts default to `~/hermex-qa/` for their output
(`trials.jsonl`, `shots/`); run them from wherever you want that data to land, or edit `OUT_DIR`.

## Hard prerequisites

- Pixel 8 `39151FDJH00HX4` (shiba), `com.hermex.v3` installed as a **debug** build — release builds
  are not debuggable, so `adb shell run-as com.hermex.v3 cat files/debuglog/hermex.log` fails there
  and the journal, which is the primary evidence source, is unreadable.
- The app signed in and connected to the gateway on `:9119`; the gateway host must be reachable with
  `sudo ss -K ...` for the socket-kill scenarios.
- The gateway frame trace at `~/.hermes/logs/gateway_frame_trace.log` (written by `tui_gateway`,
  Phase 0/1 trace). Without it there is no server-side half of every comparison.
- A chat selected by **exact title** (`--chat probe`), never a substring: substring matching picked
  the wrong conversation once and silently invalidated a batch.

## How to run one batch

```
python3 drive_trial.py --count 10 --scenario D --chat probe \
    --extra-chat "Testing Pixel 8 correctness" --extra-chat "testing" \
    --kill-at 20 --observe 150 --prompt "..." --prompt "..."
```

`drive_trial.py` does everything: warms the extra ChatViewModels, opens the chat, types and submits
the prompt through the UI, arms the fault at `--kill-at` seconds after submit, screenshots at +30 s
and +120 s, pulls the app journal slice and the gateway trace slice, pairs the turn against the app's
own `prompt.submit`, and classifies. Results append to `trials.jsonl`; journals and screenshots go to
`shots/<scenario>_<n>_*`.

Do not touch the phone while a batch runs. One batch at a time.

## Scenarios

| Scenario | Fault injected | What it tests |
|---|---|---|
| `A` | airplane mode on for ~5 s | transport loss and reconnect |
| `C` | Doze + screen off | deep-sleep socket death (the case OkHttp keepalive cannot see) |
| `D` | `sudo ss -K "sport = :9119 and dst <phone>"` mid-turn | socket killed under a running turn — the main scenario |
| `E` | socket kill during final-answer streaming | whether a nearly-finished answer survives |
| `F` | seven kills 3 s apart (~21 s) | sustained outage, repeated reconnect |
| `G` | none (`--kill-at 0`) | control; must be judged from screenshots only |
| `H` | client-side: the app drops its own answer stream (`DebugFaults`, v0.1.895) | forces the blank-final-row state that no network fault can produce, to reach `Reconcile` rule D. Reverted after v0.1.894's device pass; the code is archived, so H currently reports `fault_injected=False` |

Scenario **B** (Wi-Fi → LTE while the tailnet interface toggles) is not scriptable: there is no
`tailscale` CLI on the phone. It stays a manual test.

## Reading results — the rules that came out of being wrong

- **Screenshots arbitrate.** Every automatic bucket has produced false positives (B1/B6/B7/B9? were
  all reported on trials whose screenshots showed a complete answer). `classify()` is a triage aid;
  the scrolled-to-last-message +120 s screenshot decides. `mark.py --trial D/47 --bucket PASS --note
  "..."` records the adjudication, `mark.py --tally` prints counts.
- **Arm on the app's own `prompt.submit`, never on a gateway `turn_start`.** The driving agent's
  session id rotates mid-run; arming on `turn_start` paired the harness with its own conversation and
  voided an entire batch.
- **Quote `n` and the configuration with every count.** 5/5 and 10/10 are not proof at a ~1-in-10
  defect rate, and a control batch cannot discriminate at that rate either.
- **Matched configuration for any A/B claim.** Use `--extra-chat` so both sides of the comparison run
  with the same number of ChatViewModels, and verify by counting `reconnect detected — re-registering
  session` lines in the journal. An uncontrolled A/B once attributed a behavioural difference to a
  code change when the real difference was 3 ViewModels vs 1.
- **Per-ViewModel dispositions, never aggregated.** With several chat ViewModels alive, the ones not
  displaying the chat *should* log `disp=FILTERED` for that session id. Aggregating them made every
  healthy trial look like frames were dropped.
- **Journal completeness = span coverage + rotation markers**, not "seconds containing ≥1 line". A
  quiet chat writes nothing by design, and the journal rotates at ~2 MB / ~20 min, which is the root
  cause of every historical capture hole.

## What each analysis script answers

| Script | Question |
|---|---|
| `trial_harness.py` | one trial: inject the fault, slice both logs, classify into buckets B1–B9/CONTROL/PASS/INVALID |
| `drive_trial.py` | batch driver: UI automation + pairing + capture + classification |
| `delivery.py` | per trial: did the gateway route the frames and did the app accept them? Reports capture completeness, routed-vs-ingress counts, per-VM `APPLIED/FILTERED`, verdict `DELIVERED-AND-ACCEPTED` / H1 / H2 / unmeasurable |
| `p5_report.py` | cross-tabulates `delivery.py` verdicts against screenshot adjudication into a markdown table |
| `h_check.py` | scenario H only: was the injected fault armed, did events get dropped, did `blankFinalRow=true` appear, did the gate name `rule=D`, did the bounded reload heal it |
| `mark.py` | record/correct a trial's bucket after looking at screenshots; `--tally` for counts |
| `rebucket.py`, `recapture.py` | re-derive buckets from journals after harness changes, without re-running trials (older trials are marked INVALID when they predate a required marker) |
| `run_batch.py`, `panel_gate.py` | thin batch wrapper; three-model review gate used before shipping a risky diff |

## Known limits of this harness

- It measures one phone on one tailnet path. Cellular behaviour, other devices, and other network
  paths are untested.
- A defect at ~1-in-10 needs N≈30 to be counted reliably; batches here are N=8–10 and are used to
  find mechanisms, not to estimate rates.
- The app journal rotates, so long turns lose their beginning. The 1.2 s daemon pumper plus the
  `JOURNAL COVERAGE: n/m seconds` line exist because two earlier conclusions were drawn from
  journals that covered 3 of 30 seconds.
- Frame identity (`cid=`, `frameCid=`, per-frame `disp=`) only exists from v0.1.894 onward. Trials
  before that cannot be re-analysed for delivery questions — which is exactly why the D/14 mechanism
  is still unresolved.
