# IO-VNBD Field Audit — Gate 0 Evidence

Everything here is reproducible with `scripts/audit_dataset.py` and `scripts/run_baselines.py`.
Blueprint Gate 0 asks for a production loader that uses smartphone-only inputs and preserves
GNSS accuracy metadata. Auditing the dataset first turned out to matter more than expected:
several of the blueprint's working assumptions do not hold for the released files.

## Summary of findings

| # | Finding | Consequence |
|---|---|---|
| 1 | Some session files concatenate **multiple drives**; `TIME SINCE START (ms)` resets mid-file | Sorting by timestamp interleaves separate drives and corrupts the trajectory |
| 2 | `GPS SPEED (Kmh)` values are actually **m/s** | A `/3.6` conversion makes speed wrong by 3.6x |
| 3 | Phone GNSS is **sample-and-hold**, as slow as 0.1 Hz (9 s between fixes) | Cannot evaluate 10-120 s blackouts or supply dense supervision |
| 4 | The `ORIENTATION` columns are **not consistent with gravity** under any Android Euler convention | Unusable as an attitude reference; gravity is used instead |
| 5 | `accel - gravity` has the **sustained component filtered out** | Forward acceleration cannot be integrated for speed on most sessions |
| 6 | The gyro **yaw channel differs per session family** and matches no column label | The yaw axis must be detected from data, per session |
| 7 | At least one file (`S-A4`) has a **stray empty column** shifting every sensor | Positional column slicing silently misaligns the whole file |
| 8 | `V-*.csv` is the **same drive as `S-*.csv`, row for row**, at a true 10 Hz | Provides the dense ground truth the phone files lack |
| 9 | Despite the "Synchronised" label, phone/vehicle offsets of **up to ~9 s** exist, and some pairs are invalid | Sync lag must be estimated and quality-gated per segment |

## 1. Multiple drives per file

`S-S2` and `S-S4` reset their timestamp to ~0 mid-file (rows 1863; 35185 and 90966). Sorting
such a file by timestamp interleaves rows from different drives that share a timestamp value,
putting points ~3 km apart next to each other. Integrating that gave a **305,178 km** path for a
trip spanning 7 km. Splitting on the reset instead yields sane per-drive segments.

## 2-3. Units and GNSS rate

The speed column matches GNSS-derived speed far better when read as m/s (MAE 1.8 m/s) than as
km/h (MAE 5.6 m/s), and the regression slope is 0.83 vs 2.97.

Phone GNSS rate varies enormously by session, and the *median gap is exactly 9.0 s* on every
S-series segment — a deliberate downsample, not a receiver property:

| Family | IMU rate | GNSS rate | Accelerometer p99 |
|---|---|---|---|
| S1-S4, M | 10 Hz | **0.10 Hz** | 3.4-6.7 m/s^2 (alive) |
| A4-A8 | 10 Hz | 0.91-0.99 Hz | **1.05-1.48 m/s^2** (filtered) |

**No phone session has both usable GNSS and a live accelerometer.** That is what motivates the
vehicle-reference pairing in finding 8.

## 4-5. What the inertial channels actually contain

The GRAVITY columns agree with the accelerometer to within 0.02-0.44 deg, so gravity is a
trustworthy attitude reference and the phone is flat and rigidly mounted throughout. The
ORIENTATION columns, by contrast, correlate ~0.00 with gravity-derived tilt (a session reporting
`pitch = -81 deg` has a gravity vector saying the phone is flat). They are loaded for auditing
and never used.

`accel - gravity` behaves as a high-pass filter of the accelerometer:

* autocorrelation falls to ~0.06 by 0.5 s — vibration, not vehicle motion
* a GPS-measured **-2.5 m/s^2 brake shows up as -0.18 m/s^2** in the IMU
* correlation with centripetal `v*omega` is ~0.02 on A-series

Speed is therefore not recoverable by integrating acceleration on most sessions. Windowed
vibration statistics carry only weak speed information (r = 0.53-0.58 on A5/A6 but 0.04-0.12 on
A7/A8), so that is not a reliable substitute either. On S1 the accelerometer *is* alive:
projected forward acceleration correlates **0.56** with the vehicle's longitudinal channel.

## 6. The gyro yaw channel is not where the header says

Measured against the vehicle yaw-rate reference:

| Segment | `omega . gravity` | raw `gy` |
|---|---|---|
| S1_seg00 | corr +0.69, slope +4.08 | corr **+0.92**, slope **+0.95** |
| S3c_seg00 | corr -0.57, slope -6.75 | corr **+0.97**, slope **+0.97** |

The true yaw channel is `gy` on the S-series but `gz` on the A-series, and neither matches the
`GYROSCOPE Yaw/Pitch/Roll` labels. Projecting onto gravity does not disambiguate them, because
the gravity and gyro columns are not in a common axis order. `calibration.alignment.select_yaw_channel`
therefore identifies the channel and its signed scale from data, using only the reported bearing —
a calibration that remains valid at deployment time.

Note that `ref_yaw_rate_deg_s` is **left-positive** while compass heading is right-positive
(corr -0.99 against the derivative of `ref_heading_deg`), so a slope of -1 against that channel
is the correct result, not a sign error.

## 8-9. Vehicle reference pairing

`V-*.csv` matches its `S-*.csv` row for row and runs at a true 10 Hz (position 9.27 Hz,
velocity, heading, wheel speeds, yaw rate, longitudinal accel to +-0.59 g). It supplies the dense
ground truth the phone files lack.

**Feature-contract discipline:** every vehicle-derived column is prefixed `ref_` and is used only
as ground truth and supervision — never as a model input. Production features stay smartphone-only
(`loader.PRODUCTION_FEATURES`), matching what the Android app will have.

Sync offsets are real and must be gated. Smoothing both yaw signals over 2 s before
cross-correlating is essential — it lifts a correctly paired segment from ~0.25 to 0.7-0.9 while
leaving the recovered lag unchanged:

| Segment | Lag | Corr | Verdict |
|---|---|---|---|
| S1_seg00 | +0.5 s | 0.825 | paired |
| S2_seg01 | +8.9 s | 0.789 | paired |
| S3c_seg00 | +0.0 s | 0.576 | paired |
| S4_seg00 | +2.2 s | 0.690 | paired |
| S4_seg01 | wanders -32 s to +31 s | 0.059 | **not a valid pair** |

Total reliably paired driving: **6.2 hours**.

## 10. The sync correction is easy to get backwards (and it hides everything else)

Two bugs in the pairing code suppressed most of the usable dataset, and both produced
plausible-looking output rather than an error:

* `estimate_sync_lag` originally correlated the vehicle yaw rate against the *gravity
  projection* `omega . g` instead of the per-session yaw channel from finding 6, and
  searched only +-40 s. One valid pair sits at **+115 s**.
* The recovered lag was applied as `shift(+lag)` when the correlation is defined so that
  alignment needs `shift(-lag)`. At S1's lag of +5 samples this is invisible; at Y1's
  +1157 samples it doubles the offset into total decorrelation.

Fixing both changed the inventory substantially:

| | before | after |
|---|---|---|
| pair-aligned | 8 (8.0 h) | **14 (12.1 h)** |
| heading observable | 3 | **9** |
| speed observable | 2 | **7** |
| trainable (all three) | 1 (1.4 h) | **7 (8.9 h)** |
| drivers with trainable data | A | **A, B, E** |

This also invalidated an earlier conclusion. Speed regression measured on the mis-aligned
labels appeared **not** to generalise (negative skill on 3 of 4 routes). Re-run on
correctly aligned data, leave-one-driver-out skill is positive everywhere (+0.06 to
+0.36). Any learnability result computed before this fix should be discarded.

## Usable-data conclusion

`S1_seg00` is the reference segment for Phase 1: 86 min, live accelerometer, sync correlation
0.825, and a phone-only mount-angle estimate matching the vehicle-derived one to **0.64 deg**.
`S3c_seg00` is the strongest second segment (yaw correlation 0.97). `S2` and `S4` pair acceptably
but have weak inertial channels, and their yaw-channel selection is unreliable
(selection correlation 0.06 and 0.42) — they should be excluded from modelling until reviewed.

## Evaluation design that follows from this

Because phone GNSS at 0.1 Hz is a logging artefact rather than a property of smartphone GNSS
(real Android reports ~1 Hz), aiding is **simulated** from the vehicle reference:

* subsampled to 1 Hz, with 5 m horizontal noise
* bearing from a Doppler-style model (3 deg noise, withheld below 2 m/s) rather than differenced
  positions — differencing 5 m-noisy fixes 1 s apart at 7 m/s gives **40 deg** of course error,
  which by itself drove the filter to 70 deg heading error and multi-km divergence
* evaluation always against the clean 10 Hz reference

This keeps aiding no better than a real phone would receive while making blackout windows exactly
controllable.
