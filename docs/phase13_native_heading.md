# Phase 13 — Native Android Heading Correction

The first completed controlled field blackout (`1788094863662`) proved that the test and
recovery machinery work, but it also produced a clear accuracy failure: **196.3 m of
incremental drift over 380.8 m (51.5%)**. Speed was not the dominant error. Replacing only
the logged heading with GNSS course reduced the endpoint error to 24.2 m; replacing only
speed left about 190 m.

## Root cause measured from the saved drive

Three effects compounded:

1. `Navigator` returned on nine of every ten live sensor callbacks before calibration saw
   the gyro. The 100 Hz signal was therefore sampled at 10 Hz without anti-aliasing. The
   exact field fit was yaw scale `-0.608`; integrating the same windows at full rate moved
   it to about `-0.89`.
2. The selected-axis workaround came from IO-VNBD, whose gravity and gyro columns do not
   share an axis order. Native Android `TYPE_GYROSCOPE` and `TYPE_GRAVITY` do share the same
   device frame, so heading rate is physically available as
   `-dot(gyro, gravity / |gravity|)` with unit gain. The phone tilt changed materially after
   calibration (`gravity z` about 0.98 to 0.94), making any frozen `gz` scale fragile.
3. `Alignment.correctedHeadingRate()` subtracted the calibrated bias, then `EsEkf` was
   initialized with the same bias and subtracted it again. That was worth roughly another
   10 degrees over this 60-second outage.

The saved hidden reference validates the native projection independently: projected yaw
correlates 0.997 with hidden GNSS course during the outage and its fitted gain is 1.008.

## Deployed correction

- `HeadingRateAccumulator` trapezoid-integrates every live callback and supplies a
  time-weighted mean to each 10 Hz estimator update. A missing-sensor gap contributes no
  invented rotation.
- Live Android navigation uses gravity-projected yaw. The existing calibrated-channel path
  stays in place for IO-VNBD replay parity and for the TCN feature contract.
- Native calibration uses moving fixes from 2 m/s, holds projected gain at its physical
  value of one, estimates only additive bias, and requires the projected-yaw correlation
  gate to pass.
- The native path passes raw projected yaw to the EKF, which subtracts the initialized bias
  exactly once.
- Diagnostics and summaries now record `heading_rate_source=GRAVITY_PROJECTED`.

Using the production-style first-lock bias and the speed already estimated in the saved
drive, offline counterfactual propagation ends **27.3 m** from the hidden reference,
**7.2%** of reference travel. This is a regression estimate, not a replacement for the next
physical repeat, but it is below the project's 10% candidate target.

## Regression coverage

- A 100 Hz adversarial yaw signal contains a 10 Hz component whose peak lands on every
  estimator callback. The new test verifies that full-rate pre-integration cancels it and
  that calibrated bias is applied once (heading error below 1 degree).
- A tilted-phone test verifies gravity projection across three device attitudes.
- The existing three-route desktop/Android parity tests remain unchanged below 0.1 m.
- The field analyzer now checks navigation mode `REACQUIRING` rather than the test phase
  `RECOVERING`, so a valid completed protocol is no longer mislabeled incomplete.
- 56 Python tests pass (one optional map test skipped), and all 15 Android instrumentation
  tests pass on both the Android 16 `neuronavx` emulator and the physical V2422.

The remaining empirical step is one repeat of the same 60-second controlled drive with the
new APK. The pass condition is incremental drift below 10% with successful ordinary
`BLACKOUT -> REACQUIRING -> AIDED` recovery.
