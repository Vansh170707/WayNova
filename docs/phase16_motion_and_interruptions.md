# Phase 16 — Motion-aware speed calibration and sensor interruptions

## September 5 evidence

The four latest sessions include a near-stationary outage, a session stopped while
calibrating, a promising 51-second partial outage, and a 60-second outage followed by an
accidentally unfinished session. The preceding moving session supplies another completed
outage. The raw snapshot and audit are preserved locally under
`data/raw/phone_snapshot_2026-09-05` and `outputs/own_drive/audit_2026-09-05.md`.

The newest outage retained a 7.65 m/s correction learned largely at higher speeds even
after the vehicle slowed to approximately 3 m/s before withholding. It underestimated
speed and recorded 55.7% incremental drift. A later 1,479.78-second IMU gap passed directly
into filter propagation, producing 929,162.86 m reported uncertainty on resume.

## Implementation

- `LiveSpeedAdapter` uses aided pairs from the most recent 15 seconds of trusted GPS
  input. While moving, pairs must be within 2 m/s of the latest trusted speed. Stopped
  history retains the recent sample set because a five-prediction window overreacted to
  vibration in replay. Blackout reference fixes never enter this calibration.
- Three comparable pairs establish the bias. With fewer than two pairs, fallback retains
  trusted speed. With two pairs, model changes can follow the latest aided anchor, with
  the existing broad 6 m/s minimum uncertainty and acceleration/deceleration limits.
  Non-finite, non-positive and excessive GPS accuracy values cannot earn calibration.
- `StationaryHold` requires three GNSS-confirmed stopped fixes, followed by compatible
  sensor motion. Quiet motion alone cannot classify steady cruising as a stop. Sustained
  horizontal acceleration or rotation releases the hold. While active, zero-velocity
  updates replace learned-speed fusion. This conservative policy does **not** solve the
  disturbed near-stationary field recording; see results below.
- A live sensor gap exceeding one second invalidates continuous navigation. Alignment,
  feature history, yaw integration, speed calibration and displayed track are reset;
  fresh calibration and GPS are required before navigation resumes. Non-finite and
  non-increasing sample times are ignored. The 10 Hz dataset replay contract is retained.
- Controlled tests interrupted by a sensor gap become `INTERRUPTED`. They stop withholding
  fixes and cannot later become complete. The UI describes recalibration and incomplete
  test status. Session summaries account for gap time separately.
- Diagnostic CSVs append `stationary_hold` and `sensor_interruptions`. Summaries include
  these values and `sensor_gap_s`. The analyzer retains the numeric below-10% candidate
  field for compatibility but adds `completed_under_10pct` and labels incomplete results
  as partial rather than a candidate pass.

## Verification

The Python suite passed 58 tests, with one skipped. `git diff --check` passed.

26 Android instrumentation tests passed on the existing Android 16 ARM64 emulator,
including four new behavioral regressions and one optional private field-replay test.
All three IO-VNBD replay routes retain desktop parity below 0.1 m. This is emulator
verification of the new build, not a new physical-phone benchmark. The physical phone
disconnected before testing; this APK has not been installed on that phone.

The optional `FieldLogReplayTest` feeds original 100 Hz raw measurements through the live
Android pipeline and bundled ONNX model. It withholds fixes over each original controlled
outage and scores against the saved reference coordinates. Calibration is earned online;
reference fixes are used only for scoring. Private fixtures are not included in APK assets.

| Session start, IST | Original saved result | Phase 16 Android replay |
|---|---|---|
| 11:17 | 155.55 m / 398.83 m = 39.00% | 155.55 m / 398.83 m = 39.00% |
| 11:23, near stationary | 25.04 m incremental drift | 25.04 m incremental drift |
| 12:00, 51-second partial | 26.43 m / 487.99 m = 5.42% | 26.43 m / 487.99 m = 5.42% |
| 12:04 | 161.79 m / 290.50 m = 55.69% | 72.65 m / 290.50 m = 25.01% |

The newest replay ends in `CALIBRATING` after its long sensor gap, with no current position
uncertainty reported, rather than claiming continuous tracking. The two completed sessions
still recover in approximately three seconds. Replaying the partial session cannot verify
its missing final nine seconds or GPS recovery.

These are development replays of inspected data, not independent new field results.
The below-10% completed moving-outage gate remains open. The 11:17 drift and disturbed-stop
drift also remain open. Keep the phone rigidly mounted; further stop discrimination needs
to distinguish actual departures from phone handling without falsely freezing a moving
vehicle. Do not promote the 5.4% partial measurement into a completed-test claim.

## Reproduction and next drive

Build with `cd mobile && ./gradlew --offline assembleDebug assembleDebugAndroidTest`.
Run the standard Android suite with `connectedDebugAndroidTest` on a connected device.
For private replay, place the saved CSV/JSON fixtures in the target app's internal
`files/phase16-replay` directory and run instrumentation with `-e fieldReplay true`.
The report is written there as `replay_results.json`.

The new field-test APK is `output/WayNova_Phase16_FieldTest.apk`. Install it when the phone
is reconnected. Start live navigation while parked, arm the test, keep the phone mounted
and the app visible, and drive normally. Let the full countdown and recovery finish;
stop the session after parking. A later separately recorded stationary test should verify
when the hold activates and releases. Do not operate the phone while driving.
