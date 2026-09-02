# Phase 4 — Edge Deployment (Gate 5): export verified; hardware gate closed in Phase 11

**Current Gate 5 status: CLOSED on a physical V2422 ARM64 phone — 0.177 ms p95 against
the 100 ms budget.** The measurements below preserve what was known at Phase 4, when only
the emulator was available. The blueprint's edge acceptance gate is explicit — "no neural
backbone is finale-ready until it sustains the target update rate on the actual Android
device with the complete sensor/fusion loop active. Desktop inference speed does not count."
Phase 11 supplied that missing physical-device measurement and 12/12 phone tests.

## Export: all three runtimes verified numerically

Every export is checked against the eager PyTorch model rather than merely produced —
a silently divergent export is worse than a failed one.

| Runtime | max abs diff vs eager | Artefact size |
|---|---|---|
| TorchScript | 0.00e+00 | 167.9 KB |
| ONNX Runtime | 1.91e-06 | 308.4 KB |
| **ExecuTorch + XNNPACK** (blueprint's primary path) | 4.77e-06 | **123.4 KB** |

`scripts/export_model.py` regenerates and re-verifies all three.

**One deployment bug worth remembering:** `torch.onnx.export` defaults to splitting weights
into a sibling `.onnx.data`. Shipping only the `.onnx` gives a model that loads on device
and has no weights — a failure that looks like a bad model rather than a missing file. The
script now passes `external_data=False` and a test asserts the sibling file is absent.

## Latency budget (desktop CPU)

| Stage | Median | Notes |
|---|---|---|
| model — ONNX Runtime fp32 | **0.107 ms** | fastest path |
| model — TorchScript fp32 | 0.363 ms | |
| model — eager fp32 | 0.475 ms | |
| feature computation | 0.35 ms | over a 120-sample buffer |
| ES-EKF step | 0.04 ms | propagate + position + speed update |
| **total per update** | **0.74 ms** | **0.7% of the 100 ms budget at 10 Hz** |

Roughly 135x headroom. Even a mobile CPU 20x slower than this desktop would use ~14% of
the budget, which is the result that makes the rest of the loop (sensors, map, UI, logging)
comfortable rather than tight.

## INT8: measured, and not recommended

| | RMSE (held-out driver B) | vs fp32 |
|---|---|---|
| fp32 | 3.228 m/s | — |
| INT8 (PT2E + XNNPACK quantizer) | 3.480 m/s | **+0.252 m/s (+7.8%)** |

The blueprint asks for accuracy and latency to be measured *together*, and doing so settles
it: fp32 already fits the rate target with two orders of magnitude to spare, so INT8 buys
nothing that matters and costs 7.8% of the speed accuracy the whole learned component
exists to provide. **Ship fp32.** Revisit only if battery or memory profiling on device
turns up a reason, which the 24k-parameter model makes unlikely.

## Android app (`mobile/`) — built and verified on an emulator

Builds with Gradle 9.4.1 / AGP 8.13.0 / Kotlin 2.1.20 / JDK 25, installs, and passes three
on-device tests on an Android 36 arm64 emulator. End to end: the app logged a session, the
CSV was pulled, and `load_own_drive` parsed it at **100.0 Hz IMU / 1.0 Hz GNSS with zero
NaNs** in the production features.

Running it caught **three real bugs that reading the code had not**:

1. `ClassCastException: float[] cannot be cast to Float[]` — ONNX Runtime returns a
   *primitive* `float[]` for a rank-1 output. The boxed cast compiles fine and throws at
   runtime, so it would have surfaced on a phone in a car rather than here.
2. `requestLocationUpdates(provider, minTime, minDistance, listener)` binds callbacks to the
   **calling thread's** Looper and throws if it has none. It happened to work from the UI
   thread; the fix passes `Looper.getMainLooper()` explicitly.
3. `start()` threw `SecurityException` when location permission had not been granted yet —
   a crash mid-drive. It now degrades to IMU-only logging and surfaces `GNSS UNAVAILABLE`
   in the UI, because an IMU-only log is still useful and a crash loses everything.

A fourth issue was found by inspection: ONNX Runtime ships native libraries for four ABIs,
which alone made the debug APK 76 MB. `abiFilters` (arm64-v8a + x86_64) brings it to 42 MB.

The in-app benchmark on the emulator reported **median 0.134 ms, p95 0.238 ms**. That number
is not evidence for Gate 5 — an emulator is virtualised on the host CPU, not a phone SoC —
but it does confirm the whole path works: model loaded from the APK's own assets, run through
the app's real code, result rendered.

Two functions:

1. **Sensor logger** writing the exact schema `neuronav.data.own_drive` reads — verified by
   a test that compares the app's header constant against the loader's column list, so a
   drift between them fails in CI rather than after an hour of driving.
2. **On-device benchmark** — 300 single-window inferences, batch 1, one thread. Both
   choices are deliberate: the live loop has one new window per update and shares the
   device with sensor ingestion and the UI, so batched multi-threaded numbers would flatter
   the result.

Threading follows the blueprint's runtime table: sensor callbacks never touch the
filesystem, they hand rows to a writer thread, so a slow flush cannot drop IMU samples.
Timestamps come from `elapsedRealtimeNanos` — monotonic, and immune to the wall-clock
resets that the audit found silently interleaving separate drives in IO-VNBD.

## Gate 5 closure recorded after this phase

The original remaining steps are now complete:

1. Installed and measured on the V2422: 0.177 ms p95.
2. The complete feature/TCN/ES-EKF/blackout/display loop runs on-device.
3. A real drive exercised the live loop and exposed a GNSS-decimator defect, now fixed and
   guarded across all ten callback phases. The corrected evidence-drive repeat remains a
   navigation-quality task, not a latency blocker.

## Why the logger matters beyond Gate 5

The field audit found IO-VNBD cannot supply both halves of what training needs: sessions
with usable GNSS have a filtered accelerometer, and sessions with a live accelerometer log
GNSS at 0.1 Hz. Only 8.9 h across 3 drivers survives the data contract, which is why
"unseen driver" claims rest on a thin sample. A modern phone has neither pathology. Own-drive
collection is the single highest-value addition to the project, and this app is what unblocks
it — `load_own_drive()` puts the result straight into the existing calibration, fusion and
evaluation code with no bespoke handling.
