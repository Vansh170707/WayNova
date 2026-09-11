# Phase 15 — Live Speed Adaptation

The two completed 3 September field rides proved that Phase 14 fixed the catastrophic
heading failure. During their controlled 60-second outages, median heading error was only
4.8 and 2.9 degrees, native EKF gyro bias remained exactly fixed, and GNSS recovery completed
in 3.0 and 2.9 seconds. Drift nevertheless remained **61.0%** and **86.0%**.

## Field evidence and root cause

The error was almost entirely along track. The estimator travelled 398 m while the hidden
reference displaced 240 m in the first ride, and 503 m versus 255 m in the second. Net
bearing differed by only 5.0 and 4.2 degrees, so another heading change would address the
wrong state.

During withholding, phone GNSS reported mean speeds of 4.42 and 4.88 m/s while the deployed
TCN reported 9.63 and 10.68 m/s. Replaying the same model over the aided lead-in showed the
same approximately additive +4.5 to +5 m/s offset. This is a live phone/domain bias: the
model still changes with motion, but its absolute level is wrong. A short nearly
constant-speed lead-in cannot identify a reliable multiplicative slope, while every paired
prediction identifies the additive offset directly.

## Deployed correction

Live Android navigation now runs the TCN at one hertz while GNSS is healthy and pairs each
prediction with the same instant's trusted Doppler speed. A rolling median of raw-model
minus GNSS speed becomes the per-drive bias after three valid pairs. The controlled test's
normal aided lead-in is long enough to fill the six-second model window and earn those
pairs before withholding starts.

During blackout:

- the learned bias is subtracted before the speed pseudo-measurement reaches the EKF;
- until three pairs exist, the estimator carries the last trusted GNSS speed instead of
  accepting an uncalibrated model;
- corrected predictions are limited to 3 m/s² acceleration and 5 m/s² deceleration;
- the fused speed sigma is at least the robust aided residual, or 6 m/s before calibration;
- any amount removed by the physical jump guard is added to measurement uncertainty.

This policy is live-only. The 10 Hz dataset replays retain their original model contract and
continue to match the Python reference below 0.1 m.

## Diagnostics and verification

Each diagnostic row now includes raw and adapted speed, adapted sigma, learned bias,
residual sigma and pair count. The JSON summary contains the final speed-adapter snapshot.
`scripts/analyze_live_blackout.py` reports these values when reading a Phase 15 log while
remaining backward-compatible with older sessions.

Three new physical-device regressions verify robust bias removal, safe uncalibrated fallback,
jump limiting and a complete synthetic blackout where the model reports 10 m/s while true
speed is 5 m/s. All **21 Android tests pass on the Android 16 V2422**, and all **57 Python
tests pass** with one optional map test skipped.

The correction is algorithmically verified but its drift result is not claimed from the old
logs or from a synthetic route. The remaining gate is one new controlled 60-second drive.
The acceptance target remains successful recovery and incremental phone-reference drift
below 10%, with `speed_model_adapter.ready=true` and adapted speed materially closer to the
hidden reference than raw model speed.
