# Phase 11 — One-Button Live Field Evidence

The first physical navigation drive exposed a real asynchronous-input defect but produced
only screenshots: **Start live navigation did not retain a replayable field log**. Phase 11
makes every future drive reproducible from the same button that runs the estimator.

## Artifacts per session

| File | Rate | Purpose |
|---|---:|---|
| `nav_drive_<time>.csv` | ~100 Hz | Raw IMU plus sample-and-held GNSS in the existing own-drive schema |
| `nav_diagnostics_<time>.csv` | 10 Hz | Phase, mode, pose, speed, uncertainty, fix health and calibration |
| `nav_summary_<time>.json` | once | Duration, counts, gaps, transitions, maxima, writer errors and final alignment |

Raw and diagnostics lines are written on background `HandlerThread`s. Sensor callbacks do
no filesystem I/O. Stopping waits briefly for queued rows to flush, then the paused panel
shows the raw filename, duration, IMU count, phone-fix count and fused-fix count.

The raw header remains byte-for-byte compatible with
`neuronav.data.own_drive.load_own_drive`; diagnostics are deliberately separate so estimator
metadata cannot shift columns in training data.

## Pull and audit

```bash
cd mobile
./pull_drives.sh
```

The pull script retrieves raw, diagnostics and JSON files but sends only `drive_*.csv` and
`nav_drive_*.csv` through the Phase 9 calibration/physics analysis.

## Verification

- 12/12 Android tests pass on the physical V2422, including a live-sensor session that
  injects a fresh GNSS fix and validates all three artifacts.
- Android lint and debug assembly pass.
- Desktop own-drive loader/analysis tests pass (10/10 combined).
- A phone-visible smoke session produced 1,764 raw rows at 99.9 Hz, 13 GNSS fixes at
  0.74 Hz, 177 diagnostic rows and a valid JSON summary with no writer errors.

The smoke session was stationary and short; it validates plumbing, not navigation quality.
The corrected 30-minute mounted drive remains the empirical acceptance step.
