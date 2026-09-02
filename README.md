# NeuroNav-X — SIH26168

AI/ML-based intelligent dead reckoning for seamless navigation through GNSS-denied conditions
(ISRO, Smart India Hackathon 2026).

Hybrid estimator, not a black box: learned IMU error correction feeding an error-state EKF,
constrained by vehicle kinematics and (later) a road graph, with calibrated uncertainty.

## Setup

```bash
python3 -m venv envs && ./envs/bin/pip install -e . && ./envs/bin/pip install -r requirements.txt
```

Data is not in the repo. `docs/data_audit.md` explains which IO-VNBD sessions are usable and why;
files are fetched from the dataset's Git LFS media endpoint into `data/raw/IO-VNBD/`.

## Current state

Phases 1–13 are implemented through native Android heading correction. The complete estimator now
runs in the Android app and matches the Python reference below 0.1 m on three replay routes
across two drivers. A full-loop benchmark screen exercises 100 Hz callback overhead, online
calibration, TCN, ES-EKF, blackout management and display smoothing. Physical-device Gate 5
is closed on a V2422 ARM64 phone (0.177 ms p95 against a 100 ms budget). The first controlled
60-second field outage completed and recovered but measured 51.5% drift; its heading failure
is corrected in Phase 13, with a saved-log counterfactual of 7.2%. **One physical repeat with
the corrected APK remains open.** See
`docs/phase8_real_device_validation.md` for the field protocol and acceptance criteria.

Phase 10 retrained with the corrected representative validation split. It improved median
drift on one held-out driver but significantly regressed the other driver and a subset of
blackout windows, so the shipped Android model was deliberately left unchanged.

Phases 1 and 2 are reproducible end to end (Gates 0-3).

```bash
./envs/bin/python scripts/download_data.py       # fetch paired phone+vehicle sessions
./envs/bin/python scripts/audit_paired.py        # Gate 0: which segments are usable, and why
./envs/bin/python scripts/train_tcn.py --held-out B          # Gate 2: learned speed model
./envs/bin/python scripts/run_baselines.py --drivers B \
    --checkpoint outputs/checkpoints/speed_tcn_holdout_B.pt  # Gate 1/3: B0/B1/B3/B5
./envs/bin/python scripts/plot_baselines.py --driver B --session M --duration 120 \
    --checkpoint outputs/checkpoints/speed_tcn_holdout_B.pt
./envs/bin/python -m pytest tests/               # contract, convention and causality guards
```

Blackout drift on **held-out driver B** (never seen in training), against the 10 Hz vehicle
reference. Median, with p90 in brackets:

| Blackout | B0 const velocity | B1 raw INS | B3 ES-EKF | **B5 TCN + ES-EKF** |
|---|---|---|---|---|
| 10 s | 15.7% | 14.8% | **9.7%** (55.7) | 10.5% (**34.7**) |
| 30 s | 38.5% | 10.0% | **10.8%** (56.1) | 16.3% (**32.0**) |
| 60 s | 69.2% | 17.9% | 15.0% (61.1) | **14.0%** (**34.6**) |
| 120 s | 89.1% | 28.9% | 26.0% (91.3) | **14.7%** (**51.2**) |

The learned speed halves median drift at 120 s and cuts p90 drift by 35-45% at every duration.
Speed model itself: RMSE 3.2 m/s vs a 5.9 m/s naive baseline (skill +0.46), in 24k parameters.

**The deployment stack is TCN + six-state ES-EKF, including the yaw-scale state.** Map
aiding (B4b) is implemented but
**fails Gate 4** on a held-out driver and is off unless `--map` is passed: the technique only
works while position error stays inside the ~17 m spacing between distinct roads, and beyond
that a confident wrong-road match drags the solution away. Full evidence and the conditions
under which it would pay are in `docs/phase3_map_matching.md`.

Still above the <10% target beyond ~30 s. See `docs/phase2_learned_speed.md` for the oracle
ablation that shows where the remaining error lives.

### Blackout state machine and recovery

GNSS loss is detected from the fix stream (not from an evaluation mask), re-acquisition is
validated against multipath, and the *displayed* track is slew-limited so it never teleports
when the filter snaps back. On held-out driver B the re-acquisition jump drops **91-95%**
(173.7 m -> 8.8 m at 120 s) with position accuracy 10 s later unchanged to within 0.3 m.

```bash
./envs/bin/python scripts/eval_recovery.py --drivers B     # recovery-jump metric
./envs/bin/python scripts/demo_replay.py                   # section 16 signature demo
```

`demo_replay.py` is the finale fallback the blueprint's demo rule asks for: it replays real
logged sensor data through the real estimator, so the full pipeline can be shown at a venue
with no GNSS, no vehicle and no connectivity. See `docs/phase5_blackout_state_machine.md`.

### Edge deployment

The model exports to TorchScript, ONNX and ExecuTorch/XNNPACK, each verified against eager
output (max diff 4.8e-06). Desktop budget: **0.74 ms per update, 0.7% of the 100 ms available
at 10 Hz**. INT8 was measured and is **not** recommended — it costs 7.8% speed accuracy to buy
headroom that is already there.

`mobile/` holds the Android logger, complete navigation UI, Google/MapLibre/PMTiles basemaps,
replay fallback, automatic live-session evidence recorder and full-loop benchmark. It
**builds and passes 15/15 tests on a physical Android 16 ARM64 V2422 and emulator**, including the bundled
Greater Noida offline-map integrity check; desktop/Android parity is below 0.1 m on three
routes across two drivers.

Live navigation saves a replay-compatible 100 Hz raw CSV, a 10 Hz estimator diagnostics
CSV and a JSON health summary from the same button. A 60 s field test now withholds GNSS
only from the estimator while retaining real fixes as a hidden drift reference. The
remaining field gate is empirical execution of that controlled test on the vehicle.

## Layout

- `src/neuronav/data` — IO-VNBD loader, vehicle-reference pairing, simulated GNSS aiding
- `src/neuronav/calibration` — derived inertial signals, phone-to-vehicle alignment, yaw-channel
  and gyro-bias estimation
- `src/neuronav/fusion` — planar error-state EKF, blackout state machine, baseline runners
- `src/neuronav/models` — windowed dataset, causal speed TCN, batched predictor
- `src/neuronav/mapmatch` — OSM fetch/cache, road graph, HMM and online matchers
- `mobile/` — Android logger, live/replay navigation UI and full-loop benchmark
- `src/neuronav/evaluation` — drift ratio, ATE, path length
- `scripts` — download, audit, training, baseline evaluation, plotting
- `docs/data_audit.md` — Gate 0 evidence; **read this before trusting any session**
- `docs/phase2_learned_speed.md` — why the model predicts speed, and what it buys
- `docs/phase3_map_matching.md` — why Gate 4 fails, and what would change that
- `docs/phase4_edge_deployment.md` — export verification, latency budget, INT8 trade-off
- `docs/phase5_blackout_state_machine.md` — recovery jump, state machine, signature demo
- `docs/phase6_physics_guided.md` — yaw-scale state, paired statistics, remaining speed error
- `docs/phase7_navigation_ui.md` — Kotlin port, replay parity and navigation UI
- `docs/phase8_real_device_validation.md` — multi-route parity and physical-phone protocol
- `docs/phase9_own_drive_evidence.md` — automatic calibration and phone-physics analysis
- `docs/phase10_representative_validation.md` — retraining result and why it was not deployed
- `docs/phase11_live_field_recording.md` — one-button raw, estimator and summary evidence
- `docs/phase12_controlled_field_blackout.md` — scored live GNSS withholding and calibration gate
- `docs/phase13_native_heading.md` — full-rate Android yaw, tilt-safe projection and field regression
- `outputs/` — plots, metrics, checkpoints, inventories (gitignored)

## Non-obvious things the audit established

The released IO-VNBD files break several reasonable assumptions. Full detail in
`docs/data_audit.md`; the short version:

- some session files concatenate multiple drives (timestamp resets mid-file)
- `GPS SPEED (Kmh)` is actually m/s
- phone GNSS is often 0.1 Hz; the sessions with good GNSS have a filtered accelerometer
- the `ORIENTATION` columns are inconsistent with gravity and are not used
- the gyro yaw channel differs per session family and matches no column label, so it is detected
  from data
- `V-*.csv` is the same drive as `S-*.csv` row for row at 10 Hz, and supplies ground truth

**Feature contract:** model inputs are smartphone-only. Vehicle-derived columns are prefixed
`ref_` and used only as ground truth and supervision — enforced by `tests/test_feature_contract.py`.

## Baseline ladder

B0 reference → B1 raw INS → B2 calibrated INS → B3 ES-EKF → B4 + map matching → B5 TCN + ES-EKF
→ B6 + physics loss + uncertainty. Transformer/Mamba stay desktop-only research ablations until
the deployable path clears its Android export and latency gates.
