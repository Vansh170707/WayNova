# Phase 14 — Field Robustness After the September Drives

Two 2 September Greater Noida sessions exposed failures that synthetic navigation replays
did not cover. Session `1788349780387` completed the controlled 60-second outage and ordinary
recovery protocol, but accumulated **698.8 m incremental drift over 613.2 m of hidden-reference
travel (114.0%)**. Session `1788350535228` retained 215 phone fixes and 21,584 IMU rows but
never left calibration and then crashed before a JSON summary could be written.

## Evidence and root causes

The completed run began within about 10 degrees of hidden GNSS course, then ended about
145 degrees away. Its calibrated projected-gyro bias was `-0.00733 rad/s`, while the heading
trajectory implies an effective EKF bias of approximately `+0.0346 rad/s` during the outage.
The native projected channel already has a physical unit gain, but the six-state filter was
still allowed to relearn bias and scale from a short aided interval. Low-speed course/phone
motion was therefore converted into a persistent sensor parameter and extrapolated for the
whole outage.

Speed was a separate residual: the estimator averaged 7.14 m/s against 10.39 m/s hidden
reference speed. Replacing only the catastrophic heading trajectory in a saved-log
counterfactual reduced incremental drift from 114.0% to 38.8%; perfect reference heading
with recorded speed still left 30.8%. Heading stability is therefore the first correction,
with phone-specific speed calibration next.

The unfinished run had an excellent gravity-projected yaw fit (correlation 0.976, fitted
gain 0.961 and fixed-gain bias about `-0.0021 rad/s`). It stayed at 99% because two individual
raw axes were almost tied (`|r|=0.961` versus `0.935`), below the old 0.20 winner margin.
That gate is valid for legacy datasets which require one selected axis, but irrelevant when
Android gravity projection is the deployed heading measurement.

Logcat also captured the session-ending exception: the map-source button called
`MapLibreOfflineRenderer.covers()` while calibration intentionally exposed NaN position.
Constructing a MapLibre `LatLng` from that value raised `IllegalArgumentException` and killed
the recorder.

## Deployed correction

- MapLibre marker projection validates origin and marker coordinates before constructing a
  `LatLng`; unavailable calibration position now selects the safe canvas fallback.
- Live gravity-projected calibration is gated by the projected channel itself and no longer
  requires an arbitrary winning raw axis. Legacy 10 Hz replay retains its original raw-axis
  correlation, margin, gain and bias gates.
- The live model window receives the full-rate, gravity-projected yaw mean directly instead
  of rebuilding a vehicle yaw feature from an ambiguous device axis.
- Native Android navigation fixes projected gain at one and keeps the calibrated gyro bias
  fixed. GNSS course can still correct current heading while aided, but cannot rewrite those
  sensor parameters. Course fusion below 5 m/s is suppressed on the native path.
- Diagnostics now record `ekf_gyro_bias_rads` and `ekf_gyro_scale_error` on every state, and
  the JSON summary records their final values. A field repeat can therefore prove that the
  protected states remained fixed rather than inferring them from trajectory afterward.

## Verification and remaining gate

Three new on-device regressions cover NaN map coverage, a perfect projected-yaw calibration
with tied raw axes, and adversarial aided course updates followed by a no-fix interval. All
**18 Android instrumentation tests pass on the physical Android 16 V2422**, including the
three-route desktop parity checks below 0.1 m and the full-loop timing gate. All 56 Python
tests pass (one optional map test skipped).

The corrected APK still needs one controlled 60-second physical repeat. The acceptance gate
remains successful `AIDED -> BLACKOUT -> REACQUIRING -> AIDED`, fixed native bias/scale in the
new diagnostic columns, and incremental drift below 10%. If heading is stable but speed
remains about 25–30% low, the next phase is aided phone-specific speed-affine calibration.
