# Phase 18 — Degenerate speed-bias guard

## The failure this fixes

The 7 September 18:09 controlled outage drifted 64.8%. The cause was not heading: the
estimator's speed collapsed. `LiveSpeedAdapter` had learned an additive correction of
9.46 m/s from cruising pairs, then applied it unchanged after the vehicle slowed.

Measured on the saved diagnostics for that outage:

| Quantity | Value |
|---|---|
| Learned additive bias | 9.46 m/s, frozen for the whole outage |
| Raw model output, mean | 8.23 m/s |
| Rows where raw output fell **below** the bias | **339 / 599 (57%)** |
| Adapted speed on those rows | clipped to 0.00 m/s |
| Adapted speed, mean | 1.26 m/s |
| Hidden GNSS reference, mean | 3.42 m/s |

For more than half the outage the estimator asserted a stopped vehicle while the car was
moving at about 3.4 m/s. An additive offset only describes the speed regime it was learned
in, and `(prediction - bias).coerceIn(0.0, ...)` turns that model error into a confident
claim of zero motion.

## The correction

When the learned offset exceeds the model's own output *and the vehicle was still moving*,
the additive correction is treated as inapplicable rather than as evidence of a stop. The
estimator falls back to tracking the model's **relative** change from the last aided anchor,
and the fused uncertainty drops back to the uncalibrated floor so the filter weights it
accordingly instead of inheriting the tight residual scatter of a bias just shown not to
hold.

A genuine stop must still be able to reach zero, so the guard requires the last trusted GNSS
speed to be at least 1 m/s before it engages.

## Measured effect — Android field replay, three saved sessions

| Session | Before | After | Change |
|---|---:|---:|---:|
| Sep 7, 18:09 | 64.83% | **57.91%** | −6.92 |
| Sep 7, 17:57 | 26.50% | 26.50% | 0.00 |
| Sep 5, 12:04 | 25.01% | **22.93%** | −2.08 |

No session regressed. The 17:57 session is unchanged because its bias was only 1.37 m/s and
never clipped; its error is heading-dominated (13.6° mean, 36.0° peak) and untouched here.

## A rejected alternative, recorded so it is not retried blindly

Offline arithmetic suggested that simply holding the entry speed through the outage would
beat the model on all three sessions (5.5% vs 18.1%, 33.5% vs 54.0%, 60.6% vs 70.8% on
along-track distance alone). Implemented and replayed, it was **worse**: Sep 5 12:04 went
from 25.01% to 49.41%. Distance-only reasoning ignores how speed error interacts with
heading error in two dimensions. Do not re-derive this policy from distance arguments.

## Status

This narrows a real defect; it does not close the gate. The completed-outage target is below
10% and the best current replay is 22.9%. The dominant remaining terms are heading error on
17:57 and acceleration-from-rest on 18:09, where the vehicle left a near stop (1.37 m/s at
entry) and reached a 3.47 m/s mean — no constant-anchor policy can follow that, and the
unfiltered phone accelerometer is the obvious unused observable.

Verification: 58 Python tests pass (one skipped); 36 Android instrumentation tests pass,
including the three IO-VNBD desktop-parity routes below 0.1 m, confirming the change is
confined to the live path and leaves the replay contract intact.

---

## Addendum — per-device affine calibration tested and rejected

The obvious next step after the guard was to fit a per-device correction on the phone's own
data rather than learning it online in a 15-second window: a scale term is exactly what an
additive offset lacks when the speed regime changes.

Pairing every new 1 Hz model prediction with the filter's GPS-anchored speed while aided,
across all V2422 sessions that carry Phase 15 diagnostics:

| Fit | RMSE vs GPS |
|---|---:|
| Raw model | 4.80 m/s |
| Additive offset only | 3.85 m/s |
| **Affine (scale + offset)** | **1.31 m/s** |
| Predicting the mean speed and ignoring the model entirely | **1.84 m/s** |

The affine fit looks like a large win until it is compared against the right baseline. The
fitted relation is `true ≈ 0.268 × model + 4.75`, which maps a model output range of
2–17 m/s onto 5.3–9.3 m/s. It is not recovering the model's signal; it is discarding it and
predicting something close to the average speed, which is why it only modestly beats a
constant. Per-session slopes range from 0.14 to 0.62, so there is no stable device constant
to ship either.

**Not deployed.** The measurement is still the useful part: on this device the model's output
is compressed roughly 3.7× and carries far less speed information than it does on IO-VNBD.
That is the cross-device domain gap stated numerically, and it is consistent with the
independently observed result that holding the last trusted GNSS speed through an outage
beats the model on along-track distance in all three field sessions.

Only 177 paired samples exist, from two sessions, because `learned_speed_ms` is logged only
on Phase 15+ builds and only while the model runs aided. The honest fix is retraining on
own-device data, which first requires re-running the model offline over the 105 minutes of
saved 100 Hz raw logs to generate a proper paired training set. That is a post-demo task,
not a calibration constant.
