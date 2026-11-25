# Outage speed-error diagnosis (2026-11-25)

Follows `docs/road_heading_aiding.md`, which found that along-track (speed) error is the
larger part of outage drift. This note asks where that speed error comes from.

## Data and caveats

- 14 own Greater Noida drives (100 Hz). There are 20 GPS-loss windows of 60 s, each
  starting 20 s or 140 s after calibration. Phone GPS is the hidden reference, not survey
  truth.
- **The shipped TCN (`speed_tcn_own_device_balanced`) was fine-tuned on 13 of these 14
  drives.** Only 1789275278630 is genuinely held out, so every replay number here is
  optimistic for the other 13.
- The replay runs the real Kotlin estimator on the emulator (`RoadHeadingReplayTest`,
  `ROAD_REPLAY_TRACE_ONLY=true`), with a 10 Hz trace of estimator speed, learned speed,
  adapted speed, stationary-hold state and the hidden GPS speed.

## Findings

1. **Speed error while moving dominates.** The summed absolute distance error is 1,676 m.
   Of that, 1,587 m accrues while GPS speed is above 3 m/s and 247 m while below 3 m/s.
   The mean speed error per window is about ±1.5 m/s (mean |error| 1.50 m/s for the
   estimator) and changes sign between windows, including between two windows of the
   same drive.
2. **It does not depend on speed.** Per-window error against mean GPS speed gives r = −0.12,
   so a rescale or affine correction would not help. Phase 18 also rejected that.
3. **The Phase 15 live adapter is roughly neutral.** Mean |error| is 1.62 m/s for the raw
   learned speed and 1.59 m/s for the adapted speed. In some windows the adapter flips the
   sign (1788179114713 at 140 s: −4.2 → +2.6 m/s). An offset learned from the aided period
   does not carry into the next minute.
4. **Stops are mostly fine.** While truly stopped inside an outage, learned speed has a
   median of 0.01 m/s and stays below 1 m/s 68% of the time. The 247 m below 3 m/s comes
   mostly from crawling, braking and pull-away, plus the remaining 32% of stopped samples.
5. **`StationaryHold` never engages during an outage, by design.** It needs 3 GNSS fixes
   showing a stop, so a stop that begins after GNSS is lost is never held.
6. **An IMU-only stop detector does not generalise.** Thresholds on horizontal
   acceleration, gyro and vertical vibration catch 55% of stops with zero false alarms when
   fitted on all drives. With leave-one-drive-out it catches only 16% of held-out stops and
   raises 3 false alarms in 9,246 moving windows. Idle vibration differs too much between
   drives and mounts. *Not recommended.*
7. **100 Hz vibration alone is not a better speed sensor**
   (`scripts/probe_vibration_speed.py`). With leave-one-drive-out it has MAE 2.8 m/s
   (gradient boosting), against 4.3 m/s for a constant guess and about 1.9 m/s for the
   shipped TCN on its held-out drive. Each drive carries its own offset of ±1–3 m/s.
   Combining vibration features with the TCN is untested.

## What this means

The remaining drift comes from the learned speed model generalising poorly: an offset of
±1.5 m/s that shifts from minute to minute. It is not caused by calibration offsets,
speed-dependent scaling, missing stop handling or the heading. No estimator-side tweak
tested here addresses it. Getting below 10% at 60 s needs mean outage speed error under
about 0.5 m/s.

## Recommended next steps, in order

1. **Collect more varied own-device driving** (different roads, times, mount positions).
   The model has about 16 short drives. Data volume is the most reliable lever, and the
   fine-tune and leave-one-drive-out tooling already exist
   (`scripts/finetune_own_device.py`, `scripts/crossval_own_device.py`).
2. **Re-measure honestly.** Re-run `crossval_own_device.py` and score drift only on drives
   the deployed checkpoint never saw.
3. **Then test TCN plus 100 Hz vibration features** in that cross-validation. Only if it
   wins on held-out drives is it worth porting the features to Kotlin.

Nothing in the shipped estimator was changed by this diagnosis.
