# Phase 12 — Controlled Field Blackout

The 29 August Greater Noida session proved that the offline map and satellite positioning
work without internet. It did **not** produce a GNSS outage: airplane mode left the GPS
receiver active, all 631 fixes arrived at ~1 Hz, the longest fix gap was 1.11 s, and the
estimator remained `AIDED` for the whole drive.

Physically disabling Android Location would create an outage but would also remove the
only field reference available for scoring. Phase 12 therefore creates the outage at the
estimator boundary:

1. The user arms one 60 s test while parked.
2. Calibration must pass and 15 s of normal aided navigation must follow.
3. Real GPS callbacks continue into `nav_drive_<time>.csv` unchanged.
4. The same fixes are withheld only from `Navigator` for 60 s.
5. The ordinary three-second health detector enters `BLACKOUT`; no test-only mode is
   injected into the estimator.
6. Returning fixes go through the ordinary three-fix `REACQUIRING` path.
7. Hidden GPS is projected into the estimator frame to report outage error and recovery.

This preserves a noisy phone-GNSS reference rather than pretending it is survey truth, but
it is sufficient to compare 30/60/120 s designs on the same real route.

## Field controls

Start live navigation, then press **Arm 60s test** while still parked. The button becomes
**Cancel test** until calibration succeeds. After calibration the UI counts down the 15 s
lead-in, then shows `TEST WITHHOLDING`, `GNSS DENIED`, `RE-ACQUIRING`, and `TEST COMPLETE`.
No one should touch the phone while the vehicle is moving.

The diagnostics CSV adds:

- `test_phase`, `test_elapsed_s`, `test_withheld_fixes`;
- `reference_east_m`, `reference_north_m`, `reference_error_m`.

The JSON summary adds `controlled_blackout` with requested/actual duration, withheld and
reference fix counts, maximum and outage-end error, recovery success, and reacquisition
time.

`mobile/pull_drives.sh` now runs `scripts/analyze_live_blackout.py` for every live summary.
It de-duplicates the 10 Hz held reference, reports absolute error and incremental error-vector
growth, divides that growth by hidden-reference distance travelled, and labels the project's
<10% target as a **candidate** result because phone GNSS is not survey-grade truth.

## Calibration hardening

The same field log exposed premature calibration lock: its causal fit chose gyro channel 0
with yaw correlation 0.30, while the whole-drive fit selected channel 1. The known-good
parity replay reaches 0.79 at first lock and 0.92 over the full drive. Navigation now stops
at 99% until:

- the winning yaw channel has `|correlation| >= 0.65`;
- it leads the runner-up channel by at least 0.20;
- forward/yaw scales and gyro bias are finite and physically bounded.

The forward correlation has only a degeneracy floor because the audited good replay is
itself ~0.13; making it the primary gate would reject known-good device data.

## Verification

- The controlled test regression withholds healthy fixes, enters `BLACKOUT`, retains the
  reference, passes through `REACQUIRING`, and returns to `AIDED`.
- The online calibration parity fixture still converges to its audited yaw channel/scale.
- All 13 Android instrumentation tests pass on the `neuronavx` Android 16 emulator.
- Physical V2422 installation/field execution remains the empirical step; emulator results
  do not count as latency or drift evidence.
