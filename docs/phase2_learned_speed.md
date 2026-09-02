# Phase 2 — Learned Speed Model (B5)

Gates 2 and 3 of the blueprint: a compact TCN must improve a held-out motion metric, and
TCN + ES-EKF must improve blackout drift over classical fusion.

## Why the network predicts speed

Before building anything, an oracle ablation measured where blackout drift actually comes
from, by feeding the propagation true speed / true heading during the outage:

| Blackout | neither | true speed | true heading | both |
|---|---|---|---|---|
| 30 s | 32.2% | **11.7%** | 19.7% | **2.2%** |
| 60 s | 37.7% | **16.3%** | 26.0% | **1.2%** |
| 120 s | 42.6% | 23.4% | 22.8% | **0.9%** |

Two conclusions. With both quantities known the drift is ~1%, so the mechanization, the
filter and the geometry are sound — all remaining error is speed and heading *estimation*.
And speed dominates at short and medium outages, which is why the learned component
targets speed rather than position or a full pose correction.

This also matches the audit: the accelerometer cannot simply be integrated (its sustained
component is filtered out), so speed has to come from somewhere else.

## Model

`src/neuronav/models/tcn.py` — causal dilated TCN, **24,194 parameters**, receptive field
61 samples (6.1 s at 10 Hz).

* **Inputs** (`models/dataset.py`): calibrated vehicle-frame quantities, not raw device
  axes — longitudinal / lateral / vertical specific force, yaw rate, horizontal magnitude,
  and a jerk-based vibration proxy. Feeding raw axes would force the network to memorise
  per-session sensor conventions, which the audit shows differ between sessions.
* **Output**: mean speed plus a log-variance, trained with Gaussian NLL. The variance is
  functional, not decorative — the ES-EKF consumes it as the measurement noise of the
  learned pseudo-measurement (blueprint 9.1).
* **Export-friendly by construction**: no custom operators, no dynamic control flow, fixed
  window. The model that trains is the model that ships.

Windows are labelled at their **final** sample so the model is strictly causal.

## Splits

Held out by **driver**, not by time — that withholds an unseen phone, vehicle and driving
style simultaneously. A within-drive time split mostly measures route memorisation: the
same features gave within-route skill of +0.20 to +0.45 while transferring at only
+0.06 to +0.36.

Training pool: 7 segments, 8.9 h, drivers A / B / E.

## Speed accuracy, leave-one-driver-out

| Held out | RMSE | predict-train-mean | skill | corr |
|---|---|---|---|---|
| Driver B | 3.23 m/s | 5.92 m/s | **+0.455** | +0.831 |
| Driver A | 3.05 m/s | 5.75 m/s | **+0.470** | +0.832 |

The two agree closely, which matters because holding out A leaves only ~2.2 h of training
data — the result is not an artefact of one convenient split.

For comparison, ridge regression on hand-crafted features reached +0.06/+0.26 skill on
driver B, so the TCN contributes well beyond the feature engineering.

The uncertainty head is over-confident raw (z-std 1.49 on B); a scale factor fitted **on
validation, never on the held-out driver** brings B to ~1.06. It remains under-dispersed
on A (z-std ~1.8 after scaling), so the learned sigma is usable for weighting but is not
yet a calibrated confidence the UI should display verbatim.

## Blackout drift, held-out driver B

| Blackout | B1 raw INS | B3 ES-EKF | **B5 TCN + ES-EKF** | B3 p90 | **B5 p90** |
|---|---|---|---|---|---|
| 10 s | 14.8% | **9.7%** | 10.5% | 55.7% | **34.7%** |
| 30 s | 10.0% | **10.8%** | 16.3% | 56.1% | **32.0%** |
| 60 s | 17.9% | 15.0% | **14.0%** | 61.1% | **34.6%** |
| 120 s | 28.9% | 26.0% | **14.7%** | 91.3% | **51.2%** |

The learned speed helps where it was designed to: **long outages and the tail**. At 120 s
it nearly halves median drift, and it cuts p90 drift by 35-45% at every duration — the
worst-case behaviour that actually determines whether a navigation system is trustworthy.
At 10-30 s the last GNSS speed is still an excellent estimate and the network mostly adds
noise, so it is roughly neutral there.

### One implementation detail that mattered

Fusing the prediction every sample made B5 *lose* to B3 on short outages. The TCN sees a
6 s window, so consecutive outputs are strongly correlated; updating at 10 Hz treats one
piece of evidence as ten independent measurements per second and lets the network
overwhelm a still-accurate held speed. Fusing at ~1 Hz (`learned_speed_update_hz`) fixed
the short-outage regression while keeping the long-outage gain.

The network also never writes position or heading directly — it enters only as a speed
pseudo-measurement through the filter's gating, so a bad prediction degrades the solution
gracefully instead of teleporting it.

## Honest limitations

* Drift is still above the <10% SIH target for outages beyond ~30 s. Map matching (B4) is
  the next lever and is expected to help most with the cross-track error the oracle
  attributes to heading. *(Phase 3: it does not — Gate 4 fails.)*
* Only 3 drivers and 8.9 h survive the data contract, so "unseen driver" rests on a small
  sample. Own-drive collection (blueprint 5.3) remains the highest-value data work.
* Physics-guided losses and adaptive process noise (B6) are not yet implemented.
  *(Phase 6: implemented — see `docs/phase6_physics_guided.md`.)*

## Two corrections from Phase 6

The oracle table above was measured against **B3**. Re-run on top of **B5**, speed still
dominates at *every* outage length, including 120 s — so "heading catches up by 120 s" does
not describe the current stack, and the drift comparisons in this document use the marginal
median, which Phase 6 §4.1 shows is a noisy statistic. The B3 → B5 gap survives a paired
re-analysis (−15.4 points at 120 s, 95% interval [−19.4, −12.0]); the smaller gaps here
should be treated as unverified until re-run through `scripts/compare_methods.py`.
