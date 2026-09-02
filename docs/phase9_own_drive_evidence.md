# Phase 9 — Own-Drive Evidence Pipeline

Phase 8 made the phone ready for tomorrow's physical test. Phase 9 makes the repository
ready for the file that test produces. Pulling a drive now performs both the immediate
quality audit and a reproducible analysis of the two unresolved deployment questions:

1. how long phone-to-vehicle calibration takes to stabilize; and
2. whether unfiltered phone acceleration supports the centripetal identity
   `a_lat = v * omega` well enough to provide an independent speed observation in turns.

The pipeline is implemented and tested against a synthetic drive with known physics. It is
waiting for a real Android logger CSV; it does not claim a real-world result before one
exists.

---

## 1. One command after the drive

```bash
cd mobile
./pull_drives.sh
```

The script pulls every logger CSV into `data/raw/own_drives/`, runs the schema/rate/quality
audit, and then calls:

```bash
./envs/bin/python scripts/analyze_own_drive.py data/raw/own_drives/nav_drive_<time>.csv
```

Evidence is written below `outputs/own_drive/<route>/`:

| File | Meaning |
|---|---|
| `quality.csv` | IMU/GNSS/bearing rates, accuracy, acceleration range and feature NaNs |
| `calibration_convergence.csv` | every five-minute causal prefix, including failures |
| `calibration_convergence.png` | forward-axis, acceleration-scale, yaw-scale and bias convergence |
| `analysis.json` | machine-readable quality, final alignment and centripetal result |

Raw logs are never modified.

---

## 2. Calibration convergence

The desktop normally calibrates on a whole session, which hides deployment time. The new
analysis fits the same robust smartphone-only calibration on 5, 10, 15, ... minute causal
prefixes and compares each with the full-drive fit.

The table preserves early failures such as "not enough bearing baselines". That makes
time-to-first-solution measurable instead of plotting only successful fits. It reports:

- forward-axis angle and error relative to the full drive;
- forward-acceleration scale and correlation;
- selected yaw channel and yaw scale;
- yaw-scale error relative to the full drive;
- gyro bias and the heading consequence of its error over 120 seconds;
- number of fresh fixes and usable alignment samples.

The full-drive estimate is only an internal convergence reference, not guaranteed truth.
Stability across later prefixes is the evidence that matters.

---

## 3. Centripetal observable without circular scoring

Phase 6 fitted and evaluated the phone relation on the same public-data segment. Here the
lateral gain and offset are fitted only on the **first half** of the drive. Implied-speed
RMSE is measured on turning samples in the **second half**.

Reported fields include phone correlation, fitted lateral gain/offset, turning duty,
held-out turn count, implied-speed RMSE/MAE, and a conservative `promising_candidate` flag.
The flag requires correlation at least 0.70, held-out RMSE at most 5 m/s and a non-degenerate
gain. It means "worth a navigation ablation," not "ready for deployment."

GNSS speed is the comparison signal, so this remains a deployment diagnostic rather than
independent high-grade ground truth.

---

## 4. Guards established tonight

Synthetic tests construct a drive with known:

- forward mounting angle;
- longitudinal and centripetal acceleration;
- yaw channel, scale and gyro bias;
- 1 Hz fresh GNSS fixes inside a 10 Hz inertial stream.

They verify that held GNSS rows are never treated as fresh measurements, the centripetal
gain and held-out speed are recovered, causal convergence retains every requested prefix,
and the CLI writes the complete CSV/JSON/PNG package. The test is a contract check, not a
substitute for tomorrow's phone data.

---

## 5. Phase status

| Deliverable | Status |
|---|---|
| Logger-to-calibration aiding adapter | **complete** |
| Five-minute causal calibration convergence | **complete** |
| Temporally held-out centripetal evaluation | **complete** |
| Machine-readable report and plot | **complete** |
| Automatic analysis from `pull_drives.sh` | **complete** |
| Automatic raw + estimator capture from live navigation | **complete** |
| Real-phone evidence | **open — tomorrow's drive** |

When the real report exists, its result belongs in this document and in Phase 8's field
status table. Until then, the tooling is complete and the empirical conclusion is open.
