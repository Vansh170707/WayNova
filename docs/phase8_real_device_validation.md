# Phase 8 — Real-Device Validation and Own-Vehicle Evidence

Phase 7 proved that the Android estimator matches the desktop on one replay. Phase 8 is
the field-validation phase: broaden the parity evidence first, make the complete loop
measurable from the app, then take the two measurements that cannot be manufactured in a
development environment — sustained latency on a physical phone and behaviour on a real
drive.

The engineering preparation and physical-device latency measurement are complete. A V2422
ARM64 phone passed the full suite and measured 0.177 ms p95 against the 100 ms budget. The
first own-vehicle run exposed a GNSS-decimator defect; that defect is fixed, automatic raw
and estimator recording is installed, and the corrected evidence-drive repeat remains open.

---

## 1. Replay parity now spans routes and drivers

Phase 7's centimetre-level port check covered only driver B's `M_seg00`. Two more replay
fixtures were generated with `scripts/make_replay_log.py`:

| Fixture | Driver | Route | Rows | Outage | Purpose |
|---|---:|---|---:|---:|---|
| `replay_M_seg00` | B | `M_seg00` | 7,796 | 120 s | shipped signature demo |
| `replay_M_seg01` | B | `M_seg01` | 7,801 | 120 s | second route, same phone/vehicle family |
| `replay_S1_seg00` | A | `S1_seg00` | 7,792 | 120 s | second driver and phone/vehicle |

The two additional CSVs live in `androidTest/assets`, not the production APK. Their JSON
sidecars contain the desktop answer on those exact files. `NavigationParityTest` now runs
the same checks for all three: full-track worst gap below 0.1 m, outage-exit position,
speed, heading and uncertainty, final position, and observed blackout state.

Reproduction:

```bash
./envs/bin/python scripts/make_replay_log.py \
  --driver B --session M --route M_seg01 --blackout-start 720 \
  --blackout-s 120 --warmup-s 600 --tail-s 60 --seed 1 \
  --out replay_M_seg01 --install-test-assets

./envs/bin/python scripts/make_replay_log.py \
  --driver A --session S1 --route S1_seg00 --blackout-start 720 \
  --blackout-s 120 --warmup-s 600 --tail-s 60 --seed 2 \
  --out replay_S1_seg00 --install-test-assets

cd mobile && ./gradlew :app:connectedDebugAndroidTest
```

The suite has since expanded to **12/12 passing tests on the physical V2422**, including all
three route-parity checks and live field-artifact generation.

---

## 2. Gate 5 now has an honest in-app measurement path

The old main-screen benchmark times 300 graph-only inferences. It remains useful for
isolating ONNX Runtime, but it cannot close a gate that explicitly requires the complete
sensor/fusion loop.

The new **Benchmark full navigation loop** screen runs the bundled drive five times through:

```text
100 Hz sensor callbacks and integer decimation
    -> online calibration
    -> feature ring buffer and TCN inference
    -> ES-EKF propagation and measurement updates
    -> blackout/re-acquisition manager
    -> display track smoother
```

The 10 Hz replay is expanded into ten calls per estimator cycle, so the measurement
includes the nine early-return callbacks that a live 100 Hz accelerometer produces. CSV
parsing and UI drawing are outside the timed region because they are replay/presentation
work rather than estimator work. Results include the device model, Android version, ABI,
median, p95, maximum, number of aided/unaided cycles, raw callback count, and elapsed time.

One-pass regression result on the emulator:

| Device | Navigating cycles | Unaided | Raw callbacks | Median | p95 | Max |
|---|---:|---:|---:|---:|---:|---:|
| Android 36 ARM64 emulator | 4,266 | 1,200 | 77,960 | 0.012 ms | 0.226 ms | 6.860 ms |
| V2422 Android 16 ARM64 | 4,266 | 1,200 | 77,960 | 0.024 ms | **0.177 ms** | 6.488 ms |

The physical p95 uses 0.177% of the 100 ms estimator budget and closes Gate 5. The remaining
field work concerns estimator quality and calibration on the corrected own-drive log.

---

## 3. Validation-route selection no longer depends on filename ordering

`train_tcn.py` previously used:

```python
sorted(set(train.route))[-1]
```

That accidentally selected `Vta2_seg00` for both historical held-out-driver runs. It is
only 2,185 windows from a third vehicle and was already known to be a poor calibration
source. Early stopping, sigma calibration, and the rejected affine correction all depended
on it.

The replacement still holds out an entire route, so the split remains group-honest. It
scores each candidate by:

1. the distance between its 10/25/50/75/90th speed percentiles and those of all remaining
   training routes; and
2. a small penalty when its window count is far from the median route size.

Only training labels are used; the held-out driver remains untouched. Selection is
deterministic and can be overridden with `--validation-route ROUTE` for a deliberate
experiment. On the current inventory, automatic selection is:

| Held-out driver | Old accidental route | New automatic route |
|---|---|---|
| B | `Vta2_seg00` | `S4_seg00` |
| A | `Vta2_seg00` | `M_seg00` |

This changes future training only. Existing checkpoints and published Phase 6 comparisons
are not silently rewritten. A newly trained checkpoint records the selected route in its
`train_config`.

---

## 4. Physical-phone protocol

### 4.1 Pre-flight

Use an ARM64 phone with developer mode and USB debugging enabled. Charge it, disable
battery-saver restrictions for the app, and note the model, Android version, approximate
battery level and ambient conditions.

```bash
cd mobile
./install_phone.sh
```

Confirm that location permission is granted and the app identifies GNSS as available.

### 4.2 Close the latency measurement

From the main screen, open **Benchmark full navigation loop** and run it once with the phone
cool. Keep the screen open and do not use other apps. Record or screenshot the full result.
Run it a second time immediately to expose obvious thermal degradation.

Gate 5 acceptance for this project:

- physical ARM64 Android phone, not an emulator;
- full-loop p95 below the 100 ms budget;
- at least 1,000 unaided cycles exercised;
- no failure or increasing latency trend severe enough to threaten the budget;
- device identity and conditions recorded with the result.

### 4.3 Collect the own-vehicle drive

The driver must not operate the app while the vehicle is moving. A passenger should handle
the phone, or the drive should be configured while parked.

1. Rigidly mount the phone and do not move it relative to the vehicle for the whole log.
2. While parked, open Navigation and press **Start live navigation**. Raw logging and
   estimator diagnostics start automatically; verify the phone-fix counter increases.
3. Collect at least 30 minutes of continuous aided driving.
4. Include roughly ten minutes of ordinary urban driving, repeated acceleration/braking,
   several stops, both left and right turns/roundabouts, and a safe sustained faster-road
   section. The goal is speed and manoeuvre diversity, not aggressive driving.
5. Stop only after parking.
6. To collect controlled outage evidence, press **Arm 60s test** while still parked. It
   starts automatically 15 seconds after calibration, withholds fixes only from the
   estimator, and keeps raw GNSS as the scoring reference. A passenger may monitor it, but
   no one should manipulate the phone while driving.

### 4.4 Pull and audit immediately

```bash
cd mobile
./pull_drives.sh
```

The audit flags an IMU rate below 40 Hz, GNSS or bearing below 0.5 Hz, a suspiciously
filtered accelerometer (`p99 < 1.5 m/s²`), feature NaNs, or a segment shorter than 20
minutes. `bearing_acc` is recorded and summarized when the receiver provides it; old logs
without it remain readable and use the filter fallback.

Do not train on a log that fails these checks until the reason is understood.

---

## 5. Analysis after the drive

The first analysis is diagnostic, not retraining:

```bash
./envs/bin/python scripts/analyze_own_drive.py data/raw/own_drives/nav_drive_<time>.csv
```

1. Estimate calibration on increasing aided prefixes and plot forward angle, forward
   acceleration scale, yaw channel/scale and gyro bias versus elapsed time.
2. Check whether the forward axis stabilizes and how long it takes. Phase 7's 13-minute
   IO-VNBD slice was still about 37 degrees away from the whole-session answer.
3. Re-run the centripetal relation `a_lat = v * omega` on the unfiltered phone data. The
   vehicle physics worked in Phase 6; the public phone accelerometer did not.
4. Inspect speed coverage. Only after the data passes those checks should it augment model
   training or sigma calibration.
5. Evaluate any new checkpoint on unchanged held-out IO-VNBD drivers. Compare navigation
   methods per-window with `scripts/compare_methods.py` and paired bootstrap intervals.

Own-drive GNSS is useful supervision but not independent high-grade truth. It can improve
training and reveal deployment failures; it must not be presented as centimetre-accurate
blackout ground truth.

The automated evidence package and its interpretation are documented in
`docs/phase9_own_drive_evidence.md`.

---

## 6. Phase status

| Deliverable | Status |
|---|---|
| Three-route Android/Desktop parity across two drivers | **complete** |
| User-facing full-loop benchmark | **complete** |
| 100 Hz callback + full-loop emulator regression | **complete** |
| Representative deterministic validation route | **complete** |
| Full-loop measurement on physical phone | **complete — V2422, 0.177 ms p95 / 100 ms** |
| Real `Navigate live` drive | **completed once; corrected-build repeat pending** |
| 30+ minute audited own-vehicle log | **10.5 min captured; full controlled repeat open** |
| Forward-axis and centripetal re-analysis | **first drive exposed weak lock; corrected repeat open** |

Phase 8 closes only when the physical-device and drive rows are filled with measured
results. Until then, the repository is ready for that trip but does not claim it happened.
