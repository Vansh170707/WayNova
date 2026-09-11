# Waynova Android — live positioning, route preview and drive evidence

Round-two entry point: **Waynova home → Open live navigation**, **Watch the recorded demo**,
or **Find a destination**. The logger and benchmarks remain under **Tools**. Destination
search uses Android's geocoder; coordinates are a fallback. Driving-route requests use the
public OSRM demo service only after confirmation. This is a preview plus saved maneuver
list, not production turn-by-turn guidance. See `../docs/phase17_round_two.md` for limits.

The current build saves and stops a live session when the screen leaves the foreground;
keep Waynova visible during a test. Incomplete controlled tests are marked interrupted.
The screen remains awake while live navigation or the demo is running.

The premium navigation screen supports Google Maps, a bundled fully offline MapLibre/PMTiles
map of Greater Noida, and the built-in trajectory canvas. See [OFFLINE_MAPS.md](OFFLINE_MAPS.md)
for coverage, attribution, import, and refresh details.

Three deployment jobs:

1. **Log a drive** in the exact schema the offline pipeline reads, so own-drive data flows
   through the same loader as IO-VNBD with no bespoke code.
2. **Run the complete estimator live** — online calibration, TCN, ES-EKF, blackout manager,
   recovery smoothing and navigation UI.
3. **Benchmark the full navigation loop on real hardware** — the measurement needed to
   close the blueprint's edge acceptance gate.

> **Status: the Phase 14 build passes 18/18 tests on a physical Android 16 ARM64 V2422.**
> Android/Desktop parity is verified below 0.1 m on three routes across two drivers, and the
> full loop measured 0.177 ms p95 against its 100 ms budget. Live navigation has been driven;
> field-derived MapLibre, projected-calibration and native gyro-state failures are fixed and
> regression-tested. The corrected own-vehicle evidence repeat remains open.

## Build

The Gradle wrapper is committed, so no Android Studio install is required.

```bash
export JAVA_HOME=$(/usr/libexec/java_home)
export ANDROID_HOME=~/Library/Android/sdk
cd mobile
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Online and offline basemaps

The navigation cockpit uses Google Maps when a valid key and map tiles are available, a
bundled MapLibre/PMTiles map of Greater Noida when offline, and the existing local trajectory
canvas outside downloaded coverage. Both map SDKs are only basemaps: the route, vehicle marker,
heading and uncertainty circle are produced from `Navigator`'s estimate. Neither SDK's own
location layer is enabled. Long-press the map-source badge to import a replacement Protomaps v4
PMTiles archive. See [OFFLINE_MAPS.md](OFFLINE_MAPS.md).

1. In a Google Cloud project with billing enabled, enable **Maps SDK for Android**.
2. Create an API key restricted to Android apps, package `org.neuronavx.logger`, and the
   SHA-1 fingerprint of the signing certificate. Also restrict the key's API scope to
   **Maps SDK for Android**. Use the release certificate fingerprint for release builds.
3. Add the key only to the ignored `mobile/local.properties` file:

```properties
MAPS_API_KEY=your_restricted_key_here
```

`local.defaults.properties` contains a non-secret placeholder so the app still builds and
runs without Cloud credentials. With no valid key, the badge selects **OFFLINE GN** and
the complete Greater Noida street map remains functional with no network.

Verified with Gradle 9.4.1, AGP 8.13.0, Kotlin 2.1.20, JDK 25, compileSdk 36. The debug APK is
approximately 88 MB, including ONNX Runtime, two supported ABIs, MapLibre Native, and the 12 MB
Greater Noida archive.

## Testing on an emulator

An emulator verifies that the app builds, launches, loads the model from its own assets, and
writes a parseable CSV. It says **nothing** useful about latency — it is virtualised on the
host CPU, not a phone SoC — so no timing assertion here counts toward Gate 5.

```bash
SDK=~/Library/Android/sdk
$SDK/cmdline-tools/latest/bin/sdkmanager "system-images;android-36;google_apis;arm64-v8a"
$SDK/cmdline-tools/latest/bin/avdmanager create avd -n neuronavx \
    -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_6
$SDK/emulator/emulator -avd neuronavx -no-window -no-audio -gpu swiftshader_indirect &
adb wait-for-device

cd mobile && ./gradlew connectedDebugAndroidTest     # 18 on-device tests
```

To exercise the logger without driving, feed the emulator mock fixes:

```bash
adb emu geo fix -1.5053 52.4017 110
```

The exported model is already in `app/src/main/assets/speed_tcn.onnx`. To refresh it:

```bash
./envs/bin/python scripts/export_model.py --install-assets
```

Export it with `external_data=False` (the script does): the default splits weights into a
sibling `.onnx.data`, which silently produces a model that loads on device with no weights.

## Collecting a drive

1. Mount the phone **rigidly** — a slipping mount breaks the phone-to-vehicle alignment the
   pipeline estimates, and that estimate is what makes the accelerometer usable at all.
2. Grant location permission and open **Navigation view**.
3. Press **Start live navigation** while parked. Raw sensors, GNSS and estimator diagnostics
   begin recording automatically; no separate logger screen is required.
4. For a scored outage, press **Arm 60s test** while still parked. It begins automatically
   15 seconds after calibration; real GNSS remains in the raw log but is hidden from the
   estimator for exactly 60 seconds.
5. Drive normally for 30+ minutes. Deliberately include turns, roundabouts, hard braking,
   stop-and-go and a stretch of faster road — the evaluation slices in the blueprint's
   section 13.2 need all of them.
6. Stop only after parking. The paused panel confirms the raw file and session counts.
7. Pull and analyze all three artifacts:

```bash
adb shell ls /sdcard/Android/data/org.neuronavx.logger/files/
./pull_drives.sh
```

Each navigation session writes `nav_drive_<timestamp>.csv` (replay-compatible raw data),
`nav_diagnostics_<timestamp>.csv` (10 Hz estimator state), and
`nav_summary_<timestamp>.json` (health and calibration summary). `./pull_drives.sh` pulls
all of them, audits only the raw drive files, and generates the Phase 9 evidence package.
The original **Start logging** screen remains available for a raw-only collection.

Phase 14 diagnostics include the live `ekf_gyro_bias_rads` and
`ekf_gyro_scale_error`. On the native gravity-projected path these must remain equal to the
calibrated bias and zero respectively throughout a controlled outage.

Airplane mode is an offline-connectivity test, not a GNSS-denied test: modern Android phones
normally keep satellite reception active. Android's master **Location** switch disables GPS,
but leave it on for the controlled test so hidden fixes can score the estimator.

### Why collect at all

The field audit (`../docs/data_audit.md`) found IO-VNBD's phone files cannot supply both halves
of what training needs: sessions with usable GNSS have a filtered accelerometer (a
GPS-measured 2.5 m/s² brake shows as 0.2 m/s² in the IMU), and sessions with a live
accelerometer log GNSS at 0.1 Hz. Only 8.9 h across 3 drivers survives. A modern phone has
neither pathology — real `TYPE_LINEAR_ACCELERATION`-grade dynamics and ~1 Hz GNSS — so
own-drive data is the single highest-value addition to this project.

### Schema

Columns match `neuronav.data.loader`, plus deployment metadata:

`timestamp_ms, ax, ay, az, gx, gy, gz, mx, my, mz, grav_x, grav_y, grav_z, lat, lon, alt,
speed, accuracy, bearing, bearing_acc, gps_fresh`

* `timestamp_ms` — from `elapsedRealtimeNanos`, monotonic and immune to wall-clock changes
  mid-drive. The audit found IO-VNBD sessions whose timestamps *reset* mid-file, which
  silently interleaved separate drives.
* `gps_fresh` — 1 on the row carrying a genuinely new fix. GNSS is sample-and-hold between
  fixes, and without this flag the loader cannot tell a real update from a held value; that
  distinction is what the filter's measurement updates depend on.
* `bearing` — the receiver's Doppler-derived bearing. Do **not** reconstruct course by
  differencing positions: at 1 Hz and 7 m/s with 5 m noise that gives ~40° of course error,
  which on desktop drove the filter to 70° heading error and multi-km divergence.
* `bearing_acc` — the receiver's reported bearing uncertainty. Omitting it once caused the
  filter to under-trust good course data and added 171 m of position error in replay.

## Benchmarking (Gate 5)

Press **Benchmark full navigation loop**. It runs the bundled replay five times through
online calibration, 100 Hz callback/decimation overhead, feature construction, the model,
ES-EKF, blackout manager and display smoother. It reports the device identity, median,
p95, maximum, aided/unaided cycles and total callback count. Run it twice consecutively on
the physical phone to expose obvious thermal degradation.

**Benchmark speed model only** remains available to isolate ONNX Runtime. It runs 300
single-window inferences after 30 warmups, but that graph-only number does not close Gate 5.

Batch 1 and a single thread are deliberate: the live loop has exactly one new window per
update and shares the device with sensor ingestion and the UI, so a batched multi-threaded
figure would flatter the result relative to how the app actually runs.

Desktop reference for comparison (see `outputs/results/edge_profile.json`):

| Stage | Desktop |
|---|---|
| model, ONNX Runtime fp32 | 0.107 ms |
| feature computation | 0.35 ms |
| ES-EKF step | 0.04 ms |
| **total per update** | **0.74 ms** (0.7% of the 100 ms budget at 10 Hz) |

**Gate 5 is not closed by an emulator number.** The full-loop path is now measurable from
the app, but the result must be taken on a physical ARM64 phone. Record the device and both
cool and consecutive-run p95 values with the result.

## Runtime choice

ONNX Runtime is used here because it was both the fastest desktop path and the least
troublesome Android dependency. The blueprint's preferred path is ExecuTorch + XNNPACK, and
`outputs/export/speed_tcn_xnnpack.pte` (123 KB, the smallest artefact) is exported and
numerically verified ready for it — wiring in the ExecuTorch AAR is a drop-in replacement
for `SpeedModelBenchmark`. Both were checked against eager PyTorch output at export time, so
their numbers are directly comparable.
