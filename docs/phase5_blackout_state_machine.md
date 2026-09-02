# Phase 5 — Blackout State Machine and the Signature Demo

Covers blueprint section 9.2 (the five-state blackout machine), the section 13.1 metric
*blackout recovery jump*, and section 16 (the finale demonstration and its replay fallback).

## The problem nobody had looked at

Every evaluation up to this point stopped at the end of the outage. Measuring what happens
when GNSS **returns** exposed a serious defect:

| Blackout | Error at return | Jump on re-acquisition | p90 |
|---|---|---|---|
| 30 s | 65.9 m | 68 m | 229 m |
| 60 s | 101.9 m | 95 m | 416 m |
| 120 s | 267.7 m | **245 m** | 649 m |

The filter recovered *correctly* and fast — back within 2.4 m after ten seconds — but it did
so as a single teleport completed in half a second. Section 9.2 step 5 requires resuming
"without discontinuity in the map display", and the risk register rates "GNSS return causes
jump" High. For a live demonstration a marker leaping 245 m across the map is the worst
possible moment, and it lands exactly when the audience is watching for the recovery.

## Why the jump is not a bug in the filter

It is the correct Bayesian move. After two minutes dark the estimate is ~270 m out with a
large covariance, and the returning fix is accurate to ~5 m; snapping to it is optimal. The
estimate is right. What is wrong is the *rendering*.

So the estimate is left untouched and only the displayed track is slew-limited. The
alternative — inflating measurement noise on re-entry so the correction arrives gradually —
would buy the same visual smoothness by making the estimate itself worse for longer, which
is the wrong trade for a navigation system.

## What was built

`src/neuronav/fusion/blackout.py`:

* **`BlackoutManager`** — the five states as `AIDED / BLACKOUT / REACQUIRING`. GNSS loss is
  *detected*, not announced: fixes stopping, accuracy exceeding a threshold, or fixes
  mutually inconsistent all count. Evaluation can hand the estimator a mask, but a deployed
  system gets no such signal, so the same code has to run live and in replay.
* **Validated re-acquisition** — several consecutive mutually consistent fixes are required
  before aiding resumes. After a long outage the first returning fix may be multipath, and
  accepting it could drag the solution somewhere worse than dead reckoning.
* **`TrackSmoother`** — slew-limits the rendered position only.

### One design detail that needed measuring

A fixed 40 m/s slew removed the jump but took ~12 s to close a 290 m gap, leaving the display
43 m out ten seconds after recovery — trading a teleport for a display that is simply wrong
for a long time. The rate is now proportional to the remaining gap (floored so small
corrections stay gentle, capped so a large one still cannot become a single-frame jump).

The parameter is an exponential **time constant**, not a deadline. A unit test asserting it
as a deadline failed, which is how the mismatch between the name and the behaviour was
caught; the name was wrong, not the behaviour.

## Result, held-out driver B

| Blackout | Jump before | Jump after | p90 | **Reduction** | Error @10 s before → after |
|---|---|---|---|---|---|
| 30 s | 43.2 m | 4.0 m | 4.7 m | **91%** | 2.82 → 3.01 m |
| 60 s | 85.8 m | 4.7 m | 11.5 m | **94%** | 2.59 → 2.89 m |
| 120 s | 173.7 m | 8.8 m | 20.1 m | **95%** | 2.80 → 3.02 m |

The right-hand column is the control. Removing a jump is only worth anything if the estimate
is no less accurate afterwards, and it is unchanged to within 0.3 m. The state machine
detected the outage and validated re-acquisition in **100%** of windows without being told
one was happening.

Reproduce with `scripts/eval_recovery.py --drivers B`.

## The signature demonstration

`scripts/demo_replay.py` renders the section 16.1 sequence from a recorded drive: aided and
stable, blackout triggered, three overlaid trajectories, raw INS visibly diverging while
NeuroNav-X holds, a live status panel (blackout duration, distance, drift ratio, heading
error, confidence, update rate, latency), and a smooth re-fusion.

This exists because of the blueprint's demo rule — *never depend on one fragile live
scenario*. A venue with poor GNSS, no vehicle access or no connectivity still has to see the
whole pipeline. The replay drives **real logged sensor data through the real estimator**:
the same `run_es_ekf`, the same state machine, the same speed model the live app would use.
The only difference from the live path is where the samples come from.

Example (driver B, held out of training, 120 s outage over 1236 m):

| | |
|---|---|
| Drift, NeuroNav-X | **24.6%** |
| Drift, raw INS | 48.8% |
| Re-acquisition jump | 15.5 m |
| Heading error (median) | 16.6° |

## Still open

* Drift remains above the <10% target for outages beyond ~30 s.
* Heading error of ~17° during a long outage is the dominant remaining term, consistent with
  the oracle ablation in `docs/phase2_learned_speed.md`.
* The displayed-vs-estimated distinction should carry into the Android UI when the live app
  is built, or the phone will reintroduce exactly the jump removed here.
