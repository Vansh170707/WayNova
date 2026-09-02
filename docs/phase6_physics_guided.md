# Phase 6 — Physics-Guided Losses and Adaptive Process Noise (B6)

Phase 2 left this line in its own conclusions: *"Physics-guided losses and adaptive process
noise (B6) are not yet implemented."* This phase implements them, and the honest summary is
that **the diagnostics were worth more than the model changes**. Two of the four things
built are negative results, the two that survive are small, and the largest single outcome
is a correction to how this project has been reading its own numbers.

Everything below is reproducible:

| Script | Produces |
|---|---|
| `scripts/probe_physics_observables.py` | which physical constraints are measurable at all |
| `scripts/ablate_error_sources.py` | where the drift on top of B5 actually comes from |
| `scripts/run_baselines.py --tag T` | drift for one checkpoint, tagged so runs can be compared |
| `scripts/compare_methods.py --a T:M --b T:M` | paired per-window comparison with intervals |

---

## 0. A naming collision, resolved

Phase 3 used "B6" for TCN + map aiding. The blueprint's ladder uses B6 for the
physics-guided variant, and map aiding failed Gate 4 and is off by default. The map
variant is now `B4b_tcn_map_es_ekf`; `B6_phys_es_ekf` is this phase.

---

## 1. The measurement that came first

Two physics-guided mechanisms were candidates. Rather than build both, each was measured
first, which took under an hour and killed one of them outright.

### 1.1 The centripetal speed observable is dead on this data

For a planar vehicle, `a_lat = v * omega` exactly. If the phone obeyed it, `v = a_lat/omega`
would be an **IMU-only speed observable available during every turn** — independent of the
learned model, free during a blackout, and strongest exactly where drift is worst.

The physics is fine. The vehicle's own channels obey it at correlation 0.91–0.97 with a
gain of 1.01, which also confirms the sign conventions. The phone does not:

| route | vehicle corr | phone corr | turning duty | implied speed RMSE |
|---|---|---|---|---|
| S1_seg00 | 0.922 | −0.342 | 25.1% | 17.5 m/s |
| S2_seg01 | 0.940 | −0.247 | 26.7% | 25.5 m/s |
| S3a_seg00 | 0.930 | −0.421 | 18.0% | 15.3 m/s |
| S4_seg00 | 0.913 | −0.234 | 21.8% | 25.0 m/s |
| M_seg00 | 0.973 | −0.363 | 28.0% | 25.2 m/s |
| M_seg01 | 0.974 | −0.813 | 17.6% | 6.8 m/s |
| Vta2_seg00 | 0.917 | −0.050 | 47.5% | 47.3 m/s |

The reason is already in the Phase 0 audit: IO-VNBD's `accel - gravity` has its sustained
component filtered out, and a steady turn is precisely a sustained lateral acceleration.
The per-segment gain varies from 0.06 to 0.80 with no stable value to calibrate against.

**Not built.** Worth re-testing the moment there is data from an unfiltered phone — the
shakedown log from the project's own Android logger has `accel_p99 = 2.19 m/s²` against
IO-VNBD's 1.05–1.48, which is the first hardware where this could work.

### 1.2 Heading error splits into two terms, and the filter could only represent one

Gyro **bias** accumulates with elapsed time. Gyro **scale-factor error** accumulates with
total turning — invisible on a straight road, dominant on a twisty one. The ES-EKF
estimated a bias and had no state for a scale error, so it could not absorb one.

Regressing the heading error accumulated over 120 s on both terms:

| route | turning in 120 s | heading err | from bias | from scale | scale err |
|---|---|---|---|---|---|
| S1_seg00 | 450° | 38.7° | 48.5° | 8.0° | +1.8% |
| S2_seg01 | 459° | 12.1° | 12.5° | 3.6° | +0.8% |
| S3a_seg00 | 261° | 27.8° | 27.4° | 0.6° | +0.2% |
| S4_seg00 | 331° | 18.4° | 33.9° | 11.8° | +3.5% |
| **M_seg00** | 457° | **49.9°** | 4.4° | **44.1°** | **−9.7%** |
| M_seg01 | 275° | 22.6° | 12.3° | 7.5° | −2.7% |
| Vta2_seg00 | 210° | 31.6° | 40.2° | 41.7° | −19.9% |

Driver A's segments are bias-dominated; driver B's `M_seg00` is scale-dominated, with the
scale term contributing nine times the bias term. **Built** — see §3.

Two things to notice. Heading error over 120 s is 12–50° median, which is on its own
enough to explain long-outage drift. And the split is a property of the *segment*, so a
scale state should help driver B far more than driver A — a prediction §4 can check.

---

## 2. Re-running the oracle killed the plan this phase started with

The Phase 2 oracle was measured against **B3** and concluded that speed dominates at short
outages while heading catches up by 120 s. B5 has since taken a large bite out of the speed
term, so that conclusion was stale. `scripts/ablate_error_sources.py` re-runs it on top of
**B5**, held-out driver B, 30 windows per duration:

| Blackout | neither | true speed | true heading | both |
|---|---|---|---|---|
| 30 s | 16.3% | **5.6%** | 11.9% | 0.8% |
| 60 s | 14.0% | **8.5%** | 12.7% | 0.5% |
| 120 s | 14.7% | **10.8%** | 11.9% | 0.2% |

**Speed still dominates at every duration, including 120 s.** The working assumption going
in — that heading was now the dominant term at long outages — was wrong, and the phase was
re-aimed at speed because of this table.

With both known the drift is 0.2–0.8%, matching an independent check that integrating the
reference's own speed and heading reproduces its own track to 0.2% over 120 s. The
mechanization is sound; everything left is estimation.

### An instrumentation bug worth remembering

The first version of this table showed a **7% floor with both quantities given as truth**,
which is impossible if the mechanization is sound. The cause was the instrument, not the
filter: with an oracle overwriting speed, the learned-speed update was still firing, so its
innovation was being measured against injected truth and pushed into **position** through
the state cross-covariance. `run_es_ekf` now skips the learned update whenever an oracle
supplies speed. A diagnostic that quietly measures its own interference is worse than no
diagnostic, because it looks like a finding.

---

## 3. What was built

### 3.1 Yaw-gyro scale factor as a filter state

The nominal state grows from `[pE, pN, psi, v, b_w]` to `[pE, pN, psi, v, b_w, s_w]`, with
`omega = (1 + s_w) * (omega_measured - b_w)`. The scale error reaches heading only in
proportion to turn rate — `F[psi, s_w] = (omega - b_w) * dt` — which is exactly what makes
it separable from the bias, and it is observable from GNSS bearing whenever the vehicle
turns under aiding. The filter can therefore enter an outage with it converged.

`ESEKFConfig.estimate_gyro_scale` defaults to **False**, so `ESEKFConfig()` reproduces the
Phase 1–5 filter exactly and B3/B5 stay comparable with their published numbers. With the
flag off the state is pinned: no process noise, no transition coupling, no initial
variance, so its Kalman gain is identically zero. `tests/test_gyro_scale.py` asserts that
pinning, recovers a synthetic 12% gain error to within 0.03, and checks that straight-line
driving — where the scale is unobservable — leaves it alone instead of inventing a value.

A small rate-proportional heading process noise accompanies it, for what a single scale
factor cannot capture (gyro nonlinearity, bandwidth, cradle flex). It is deliberately
smaller than the scale prior: the state absorbs the linear part, and counting the same
error twice would only make the filter deaf.

### 3.2 A horizon loss on mean speed

Ablation §2 says speed dominates, so the next question is *which part* of the speed error.
It is not the size — the terminal speed error at the end of a 120 s outage is only ~1.3 m/s
median. It is the **shape**. On held-out driver B the original head predicts

    v_hat = 0.71 * v + 2.13     (M_seg00)
    v_hat = 0.81 * v + 2.07     (M_seg01)

Textbook regression toward the training mean, and the resulting bias is speed-dependent:
+0.5 to +1.2 m/s below 10 m/s, **−4 to −8 m/s above 20 m/s**. A bias integrates straight
into along-track position while zero-mean noise averages out, and over a 120 s horizon this
one costs 107–139 m of median distance error on ~1000 m travelled — which is essentially
the whole gap the oracle attributes to speed.

Shrinkage is the RMSE-optimal answer under an imbalanced speed distribution, so the loss
had to change rather than the architecture:

* `--phys-weight` adds a loss on **mean predicted speed over a ~10 s horizon**, which *is*
  the along-track drift rate: an outage of length T accumulates `T * (mean predicted −
  mean true)` metres. Pointwise NLL is indifferent to a consistent offset; this term is not.
* `--balance` adds inverse-frequency sample weights over true speed, capped, so the rare
  fast driving that dominates blackout distance is not drowned out by stopped time.

**One implementation detail that cost a full training run.** The first version built every
batch from contiguous chunks so the horizon loss would have consecutive windows. Four
chunks of sixty windows is four independent places in the data, not 240; batch diversity
collapsed and held-out RMSE went from 3.23 to 4.34 m/s. The two terms are now sampled
separately — the shuffled loader is untouched, and the horizon term draws its own small set
of chunks.

### 3.3 Affine de-shrinking: built, measured, and left off

The obvious complement is to invert the fitted shrinkage at prediction time. It is
implemented (`fit_speed_affine`, applied in `SpeedPredictor`) and it is **off by default**,
because it was measured to backfire.

The correction is only as good as the set it is fitted on, and the validation route is
2,185 windows from a third vehicle. It reports a slope near 0.60 where the held-out
driver's is 0.86–0.99. Applying it over-corrects:

| checkpoint | slope before | slope after | RMSE before | RMSE after |
|---|---|---|---|---|
| held-out B, standard loss | 0.860 | 1.165 | 3.43 | **5.42** |
| held-out B, horizon + balance | 0.987 | 1.355 | not separately run | **7.31** |

(The first row is an exact pair: `p6plain` and `p6base` differ only in whether the
correction is applied. The second row's uncorrected twin was not trained, so only the
after-value is measured there — but note the slope it started from, 0.987, is a model that
needed no correction at all.)

Fixing shrinkage during training turned out to be both cheaper and safer than correcting it
afterwards. The fit is still computed and reported in every checkpoint as the cleanest
available measure of how much shrinkage is left; `--affine` applies it for anyone whose
validation set is representative enough to deserve it.

---

## 4. Results

### 4.1 How these numbers are computed, and why that changed

Every earlier phase compared methods by the **median of each method's own drift
distribution**. On ~50 windows that statistic is far noisier than it looks. The first pass
of this phase produced a table reading `B5 17.4% → B6 14.3%` at a 60 s outage, which is a
large win. The same 50 windows, **paired**, gave a median per-window difference of −0.02
points with a 95% interval of [−0.09, +0.17], and B6 better in 27 of 50 windows. The
marginal median only has to step past a few windows to jump several points.

All numbers below therefore come from `scripts/compare_methods.py`: identical windows for
both methods, paired on `route_id@start_s@blackout_s`, with a paired bootstrap over
windows. **n = 200 per cell per driver** (driver B: 2 routes × 100 windows; driver A:
4 routes × 50). An interval that excludes zero is the only thing called an improvement.

Re-checked under this stricter statistic, the headline claim of Phase 2 survives
comfortably — B3 → B5 at 120 s is −15.4 points of median drift, interval [−19.4, −12.0],
and −42 points of p90. The small gaps reported in earlier phases would not.

### 4.2 The yaw-scale state, tested against its own prediction

§1.2 predicted from the reference data alone that this state should help driver **B**
(scale-dominated, 44° of 50°) and do nothing for driver **A** (bias-dominated). Held-out
drift, same checkpoint on both sides, so only the filter differs:

**Driver B** — median drift, then p90, with paired 95% intervals:

| Outage | B5 med | B6 med | Δ median | B5 p90 | B6 p90 | Δ p90 |
|---|---|---|---|---|---|---|
| 10 s | 13.1% | 11.5% | **−1.63 [−2.73, −0.48]** | 33.4% | 32.1% | −1.26 [−3.74, +1.86] |
| 30 s | 15.0% | 14.3% | −0.73 [−2.19, +0.20] | 34.5% | 31.8% | −2.62 [−4.95, +0.40] |
| 60 s | 16.8% | 15.9% | −0.90 [−2.51, +0.73] | 35.8% | 31.5% | **−4.24 [−9.63, −1.16]** |
| 120 s | 14.3% | 14.3% | −0.01 [−1.43, +1.26] | 38.6% | 33.0% | −5.56 [−12.06, +0.20] |

**Driver A** — every interval spans zero, at both the median and the tail:

| Outage | Δ median | Δ p90 |
|---|---|---|
| 10 s | −0.10 [−0.96, +0.23] | +0.08 [−0.95, +0.14] |
| 30 s | +0.32 [−0.48, +1.05] | −1.18 [−1.79, +0.38] |
| 60 s | −0.15 [−1.00, +0.55] | +0.22 [−0.65, +2.04] |
| 120 s | −0.87 [−1.77, +0.72] | −0.61 [−2.08, +1.31] |

The contrast is the result. A state motivated by a measurement taken on one signal
(reference yaw rate) helps exactly the driver that measurement said it would, on a
different signal (held-out position drift), and is inert on the driver it said it would not
help. That is much stronger evidence than either table alone, and it is why the state is
recommended despite a small average effect: it is not a free parameter that happened to
fit, it is a term that fires when its physical cause is present.

### 4.3 The horizon loss: not established

Same filter on both sides, only the checkpoint differs (`p6plain` → `p6hb`):

| Outage | B Δ median | B Δ p90 | A Δ median | A Δ p90 |
|---|---|---|---|---|
| 10 s | **+1.76 [+0.01, +2.91]** | +2.37 [−2.03, +7.91] | −0.19 [−1.33, +1.35] | −1.38 [−3.79, +3.76] |
| 30 s | −0.50 [−2.64, +1.53] | −1.84 [−5.90, +1.43] | −1.03 [−2.38, +0.63] | +1.45 [−1.24, +4.95] |
| 60 s | +0.83 [−1.42, +1.63] | −1.34 [−4.13, +1.82] | +0.34 [−1.06, +1.37] | −1.88 [−4.13, +1.98] |
| 120 s | −0.14 [−1.75, +1.43] | −1.45 [−3.73, +1.43] | +0.89 [−1.12, +1.87] | **−2.48 [−4.90, −0.20]** |

One marginal regression at 10 s on one driver, one marginal gain at 120 s on the other,
nothing else. **The horizon loss does not measurably improve drift**, and it costs ~0.25 m/s
of held-out RMSE. It is off by default.

It did do one thing well, consistently on both drivers — it made the predicted uncertainty
honest:

| Model | held-out z-std (1.0 = calibrated) | RMSE |
|---|---|---|
| driver B, standard loss | 1.42 | 3.43 m/s |
| driver B, horizon + balance | **1.03** | 3.56 m/s |
| driver A, standard loss | 1.81 | 3.05 m/s |
| driver A, horizon + balance | **0.98** | 3.30 m/s |

That matters because the ES-EKF consumes this sigma as measurement noise: a model reporting
z-std 1.81 is nearly twice as confident as it has earned, and the filter believes it. The
existing post-hoc `sigma_scale` calibration is what currently rescues that, and it is fitted
on a 2,185-window route from a third vehicle. Making the model itself calibrated is worth
more than the table above suggests, but it is not a drift result and is not claimed as one.

### 4.4 Both changes together, versus the published B5

| Outage | driver | B5 p90 | B6 p90 | Δ p90 | Δ median |
|---|---|---|---|---|---|
| 30 s | B | 34.5% | 28.5% | **−5.92 [−8.40, −0.76]** | −1.75 [−3.51, +0.04] |
| 60 s | B | 35.8% | 29.7% | **−6.04 [−11.59, −2.14]** | −1.61 [−3.68, +0.35] |
| 120 s | B | 38.6% | 31.6% | **−6.98 [−13.97, −0.49]** | −0.69 [−2.63, +0.57] |
| 30–120 s | A | — | — | all spanning zero | all spanning zero |

**The measured outcome of Phase 6: worst-case blackout drift falls by 6–7 points at 30–120 s
on the driver whose heading error is scale-dominated, and the median does not move.** No
effect on the other held-out driver. The SIH <10% target is still not met beyond ~10 s.

Tail behaviour is not a consolation prize here. Section 16's live demonstration is judged on
the run that happens, not on the median of runs that did not, and the p90 is the number that
decides whether a marker ends up on the wrong street.

---

## 5. What this phase changed in the repository

| Change | Status |
|---|---|
| `ESEKFConfig.estimate_gyro_scale` + 6th state, with pinning when off | default **off**; `deployment_config()` turns it on |
| `deployment_config()`, used by `demo_replay.py` and `eval_recovery.py` | recommended stack |
| `--phys-weight` / `--horizon-s` / `--balance` in `train_tcn.py` | default **off** |
| `--affine` de-shrinking in `train_tcn.py` + `SpeedPredictor` | default **off**, measured harmful here |
| `scripts/ablate_error_sources.py` | new |
| `scripts/compare_methods.py` | new — required for any future comparison |
| `scripts/probe_physics_observables.py` | new |
| `B6_tcn_map_es_ekf` renamed `B4b_tcn_map_es_ekf` | naming collision resolved |
| `tests/test_gyro_scale.py` | 4 tests |

## 6. What the next phase should know

1. **Speed, not heading, is still the dominant error term** at every outage length on top of
   B5. Anything that reduces speed error is worth more than anything that reduces heading
   error, up to 120 s at least.
2. **The shrinkage is still there** (held-out slope 0.72–0.86). Neither the loss change nor
   the affine correction fixed it properly. The most likely reason is data: five training
   routes across three vehicles, with fast driving rare. This is the strongest argument for
   the outstanding 30-minute own-vehicle drive.
3. **The validation route is a liability.** `sorted(set(train.route))[-1]` selects
   `Vta2_seg00` for both held-out drivers — 2,185 windows, a third vehicle, yaw correlation
   0.28. Both the sigma calibration and the affine fit depend on it, and the affine fit was
   wrong enough to do damage. Worth fixing before any further calibration work.
   **Follow-up:** Phase 8 replaced name ordering with a deterministic representative-route
   policy. Phase 10 retrained with it; the result failed to generalize across held-out
   drivers and was not deployed (`docs/phase10_representative_validation.md`).
4. **Re-test the centripetal observable** on unfiltered phone data. It is the only route to a
   GNSS-free, model-free speed measurement identified so far, and it fails for a reason
   specific to IO-VNBD rather than to the physics.
