# WayNova

**Offline navigation that keeps working when GPS doesn't.**

WayNova is an Android navigation app and research pipeline for estimating a car's position
through GNSS outages: tunnels, flyovers, dense buildings or jamming. When GPS drops out, it
carries the position forward using the phone's own inertial sensors, a small learned speed
model and an error-state Kalman filter. It runs fully offline on the phone.

It is a hybrid estimator, not a black box. A neural network predicts vehicle speed from IMU
data. That prediction feeds a physics-based filter constrained by vehicle kinematics, which
reports its own uncertainty. Every claim below is measured, and failed ideas are documented
as carefully as the ones that worked.

## Highlights

- **Dead reckoning through GPS loss.** A causal speed TCN (24k parameters) plus a
  six-state ES-EKF with gyro bias and yaw-scale states. Measured on a driver held out from
  training, it halves median drift at 120 s compared with plain inertial integration.
- **Runs on a phone.** A Kotlin port of the full estimator matches the Python reference to
  within 0.1 m. The whole navigation loop takes 0.177 ms (p95) per update on an Android 16
  ARM64 phone, against a 100 ms budget.
- **Fully offline maps.** A bundled MapLibre/PMTiles map of Greater Noida with route preview
  and turn guidance. No connectivity is needed while driving.
- **Road lock.** Detects when the displayed position drifts off the road and glides the
  marker back onto the most plausible road. It is display-only, so the estimator and
  recorded evidence are untouched.
- **Clean GPS recovery.** A blackout state machine checks returning fixes against
  multipath, and the displayed track is slew-limited, so the marker never teleports when GPS
  comes back. The jump at recovery drops by 91–95%.
- **Built-in field evidence.** One button records raw 100 Hz sensors, 10 Hz estimator
  diagnostics and a JSON summary. A controlled 60 s test hides GPS from the estimator while
  keeping the real fixes as a hidden reference for scoring.

## How it works

```
 phone IMU (100 Hz) ─┬─> online calibration (mount alignment, gyro bias)
                     │
                     ├─> 6 s feature window ─> speed TCN ─> learned speed ± σ
                     │                                           │
                     └─> yaw rate ─────────────> ES-EKF  <───────┘
 GNSS fixes ──> blackout state machine ──────────┘   │
                                                     ├─> position, heading, uncertainty
                                                     └─> display smoother ─> map (+ road lock)
```

While GPS is available, the filter fuses GNSS position, speed and course. During an outage,
speed comes from the learned model, and heading is integrated from the gyroscope.

## Results

Drift is measured as position error divided by distance travelled during an outage. The
target is **under 10%**.

On the public IO-VNBD dataset, for a held-out driver (median, p90 in brackets):

| Outage | Constant velocity | Raw INS | ES-EKF | **TCN + ES-EKF** |
|---|---|---|---|---|
| 10 s | 15.7% | 14.8% | **9.7%** (55.7) | 10.5% (**34.7**) |
| 30 s | 38.5% | 10.0% | **10.8%** (56.1) | 16.3% (**32.0**) |
| 60 s | 69.2% | 17.9% | 15.0% (61.1) | **14.0%** (**34.6**) |
| 120 s | 89.1% | 28.9% | 26.0% (91.3) | **14.7%** (**51.2**) |

The speed model has an RMSE of 3.2 m/s, against 5.9 m/s for a naive baseline.

**On our own drives the target is not yet met.** Controlled 60 s field outages measured
roughly 20–70% drift. The latest diagnosis
([docs/speed_diagnosis_2026-11-25.md](docs/speed_diagnosis_2026-11-25.md)) traces most of
the remaining error to the learned speed. It is off by about ±1.5 m/s while moving, and the
direction of the error changes minute to minute. Heading is the smaller part. More varied
own-device driving data is the next lever.

### Things that were tried and rejected

- **Map matching into the filter** made results worse. Beyond the ~17 m spacing between
  roads, a confident wrong-road match drags the solution away
  ([phase3](docs/phase3_map_matching.md)).
- **Road direction as a heading measurement** reduced pooled drift only from 21.6% to 20.1%,
  which is not statistically supported, so it is off
  ([road_heading_aiding](docs/road_heading_aiding.md)).
- **INT8 quantisation** cost 7.8% speed accuracy for latency headroom that was not needed
  ([phase4](docs/phase4_edge_deployment.md)).
- **Retraining on a new validation split** improved one driver and regressed another, so
  it was not deployed ([phase10](docs/phase10_representative_validation.md)).

## Android app

- Live navigation with GPS-loss estimation, uncertainty display and voice callouts
- Offline Greater Noida map, with Google Maps as an option when an API key is configured
- Destination search and driving-route preview (OSRM), saved for offline use
- Road lock (Settings → Navigation)
- A **demo ride** from Sector XU-1 to Bennett University on the offline map, with route,
  guidance and a scripted GPS loss. It is labelled as simulated. A long press on *Replay
  demo* plays a recorded real-sensor drive instead.
- Saved-drive history and replay, diagnostics, and a full-loop benchmark

Build and install details: [mobile/README.md](mobile/README.md) and
[mobile/OFFLINE_MAPS.md](mobile/OFFLINE_MAPS.md).

## Getting started

### Python pipeline

```bash
python3 -m venv envs && ./envs/bin/pip install -e . && ./envs/bin/pip install -r requirements.txt
```

Data is not in the repo. [docs/data_audit.md](docs/data_audit.md) explains which IO-VNBD
sessions are usable and why. Files are fetched into `data/raw/IO-VNBD/`.

```bash
./envs/bin/python scripts/download_data.py                   # fetch paired phone + vehicle sessions
./envs/bin/python scripts/audit_paired.py                    # which segments are usable, and why
./envs/bin/python scripts/train_tcn.py --held-out B          # train the speed model
./envs/bin/python scripts/run_baselines.py --drivers B \
    --checkpoint outputs/checkpoints/speed_tcn_holdout_B.pt  # compare estimators
./envs/bin/python scripts/eval_recovery.py --drivers B       # GPS-recovery jump
./envs/bin/python scripts/demo_replay.py                     # replay a drive through the estimator
./envs/bin/python -m pytest tests/                           # contract, convention and causality tests
```

### Android

```bash
cd mobile && ./gradlew assembleDebug        # build
./gradlew testDebugUnitTest                 # JVM unit tests
bash test_emulator.sh emulator-5554         # instrumentation, emulator only
bash install_phone.sh                       # install on a connected phone
```

> **Field-data safety:** never run Gradle `connected*AndroidTest` tasks or uninstall the
> app on a phone holding recordings. Back up the phone's recordings before every update.
> See [AGENTS.md](AGENTS.md).

## Repository layout

| Path | Contents |
|---|---|
| `src/neuronav/data` | IO-VNBD and own-drive loaders, vehicle-reference pairing, simulated GNSS |
| `src/neuronav/calibration` | derived inertial signals, phone-to-vehicle alignment, gyro bias |
| `src/neuronav/fusion` | error-state EKF, blackout state machine, baseline runners |
| `src/neuronav/models` | windowed dataset, causal speed TCN, predictor, own-device tools |
| `src/neuronav/mapmatch` | OSM road graph, HMM and online map matching |
| `src/neuronav/evaluation` | drift ratio, ATE, path length |
| `scripts/` | download, audit, training, evaluation, export, plotting, map extraction |
| `mobile/` | Android app: estimator port, navigation UI, offline maps, recorder, tests |
| `docs/` | design notes and evidence for every stage (see below) |
| `tests/` | Python tests, including the smartphone-only feature contract |

## Documentation

Each stage of the project has a note with its evidence and limitations:

- **Data:** [data audit](docs/data_audit.md). Read this before trusting any session.
- **Estimator:** [learned speed](docs/phase2_learned_speed.md),
  [map matching](docs/phase3_map_matching.md),
  [edge deployment](docs/phase4_edge_deployment.md),
  [blackout state machine](docs/phase5_blackout_state_machine.md),
  [physics-guided model](docs/phase6_physics_guided.md)
- **Android and field work:** [navigation UI](docs/phase7_navigation_ui.md),
  [real-device validation](docs/phase8_real_device_validation.md),
  [own-drive evidence](docs/phase9_own_drive_evidence.md),
  [representative validation](docs/phase10_representative_validation.md),
  [live field recording](docs/phase11_live_field_recording.md),
  [controlled blackout](docs/phase12_controlled_field_blackout.md),
  [native heading](docs/phase13_native_heading.md),
  [field robustness](docs/phase14_field_robustness.md),
  [live speed adaptation](docs/phase15_live_speed_adaptation.md),
  [motion and interruptions](docs/phase16_motion_and_interruptions.md),
  [round two](docs/phase17_round_two.md),
  [degenerate bias guard](docs/phase18_degenerate_bias_guard.md)
- **Latest:** [road lock](docs/road_lock.md), [demo ride](docs/demo_ride.md),
  [road heading aiding](docs/road_heading_aiding.md),
  [speed diagnosis](docs/speed_diagnosis_2026-11-25.md)

## Data notes

The released IO-VNBD files break several reasonable assumptions (full detail in
[docs/data_audit.md](docs/data_audit.md)):

- some session files join multiple drives together, and the timestamp resets mid-file;
- `GPS SPEED (Kmh)` is actually m/s;
- phone GNSS is often 0.1 Hz, and the sessions with good GNSS have a filtered accelerometer;
- the `ORIENTATION` columns are inconsistent with gravity and are not used;
- the gyro yaw channel differs per session family, so it is detected from the data;
- `V-*.csv` is the same drive as `S-*.csv`, row for row at 10 Hz, and supplies ground truth.

**Feature contract:** model inputs are smartphone-only. Vehicle-derived columns are prefixed
`ref_` and used only as ground truth and supervision. `tests/test_feature_contract.py`
enforces this.

## Map data

Offline basemap © OpenStreetMap contributors, packaged by Protomaps. Driving routes come from
OSRM using OpenStreetMap data. Licences are in `mobile/app/src/main/assets/offline_map/licenses/`.
