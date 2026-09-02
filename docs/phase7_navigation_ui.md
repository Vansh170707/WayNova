# Phase 7 — On-Device Navigation and the Section 15 UI

The estimator now runs **on the phone**, not only on the desktop: calibration, features,
the speed network, the ES-EKF, the blackout state machine and the display smoother are all
ported to Kotlin, wired to a live sensor source and to a replayed drive, and drawn as a
trajectory with drift and confidence (blueprint section 15). Replay is section 16's
demonstration fallback and is what makes the port testable at all.

**The port reproduces the desktop estimator to under 0.1 m** over a 13-minute drive
containing a 120 s GNSS outage and its recovery. Getting there found three real bugs, one
of which was in the desktop pipeline.

| Artefact | Role |
|---|---|
| `mobile/.../nav/Signals.kt` | gravity, horizontal basis, vehicle-frame acceleration |
| `mobile/.../nav/Calibration.kt` | online phone-to-vehicle calibration, O(1) memory |
| `mobile/.../nav/Features.kt` | the six model inputs, as a ring buffer |
| `mobile/.../nav/SpeedModel.kt` | ONNX graph + scaler + sigma calibration |
| `mobile/.../nav/EsEkf.kt` | the 6-state error-state filter, including Phase 6's yaw scale |
| `mobile/.../nav/Blackout.kt` | outage detection, re-acquisition validation, display slew |
| `mobile/.../nav/Navigator.kt` | the loop |
| `mobile/.../nav/LiveSource.kt` / `ReplaySource.kt` | sensors, or a recorded drive |
| `mobile/.../nav/NavigationView.kt` | the map, marker, confidence circle, scale bar |
| `scripts/export_model.py --install-assets` | ships the graph *and* its runtime config |
| `scripts/make_replay_log.py` | a replay drive plus the answer the port must reproduce |

---

## 1. Verifying a second implementation

A port that is never compared against the original is a liability. `make_replay_log.py`
writes a drive in the **logger's own CSV schema** — the file the app would have recorded —
plus a sidecar carrying the desktop estimator's output on that exact file. The app reads
only the CSV; the sidecar never ships.

Two properties make the comparison meaningful:

* **The reference is computed under live semantics.** `run_es_ekf` gained
  `use_manager_mode`, which makes the desktop detect the outage from GNSS health instead of
  being handed the evaluation mask. Without it the desktop knows things the phone cannot,
  and "parity" would be unachievable by construction.
* **The file starts on a GNSS fix.** Offline, `_initial_state` reaches forward to the first
  fix in a segment and places the filter there at *t=0*. A live system has to wait for that
  fix to arrive. Starting the slice on a fix removes the asymmetry.

`NavigationParityTest` then runs the replay through the on-device loop and compares:

| Quantity at outage exit | Tolerance | Result |
|---|---|---|
| East / North | 0.1 m | pass |
| Speed | 0.01 m/s | pass |
| Heading | 0.05 deg | pass |
| Reported 1-sigma | 0.5 m | pass |
| Worst gap along the whole track | 0.1 m | pass |

The worst gap is a few centimetres. It was **15–25 m** before the two bugs below.

### 1.1 The bug that mattered: an unshipped measurement

The device called `updateGnssBearing` without a sigma, so it used the filter's conservative
default course noise (0.15 rad) while the desktop used the accuracy the simulated receiver
reported (3 deg). The filter under-trusted a perfectly good Doppler bearing; heading was
2 deg out *before the outage began* and 13 deg out by its end — **171 m of position error,
entirely from discarding a measurement's uncertainty.**

The fix is not a constant. `Location.getBearingAccuracyDegrees()` exists, the logger was
never recording it, so it could not flow through the pipeline. It is now a column
(`bearing_acc`) in the logger schema, in the replay writer, in `own_drive.py` as an optional
field so pre-existing logs still load, and in `GnssFix`. When absent the estimator falls
back to the configured value, as it must.

### 1.2 The bug that would have been invisible: threshold decimation

The model consumes 10 Hz windows while the phone's IMU runs at 100 Hz, so the loop
decimates. The obvious rule — emit when at least one period has elapsed — is wrong, and
wrong in a way that produces no error and no warning.

Recorded IMU streams are nominally 10 Hz but jitter: in this drive, between 0.091 s and
0.109 s per sample. A one-period threshold **silently dropped 1301 of 7796 samples (17%)**
and the estimator integrated across the holes. It is now integer-factor decimation on a
sample count, which cannot drop anything, and is exactly every tenth sample at 100 Hz.

### 1.3 An instrumentation trap worth naming

The first parity run reported the endpoint only, and the endpoint disagreed. That says
nothing about *where* the two implementations parted company, and a port debugged from a
single end-of-run number stays broken for a long time. The sidecar now carries a downsampled
trace of the desktop trajectory and the test reports the first row where the gap exceeds
5 m. That one change turned "the numbers differ" into "they differ from row 19, during the
aided phase" — which is what identified §1.1 in minutes.

The same discipline caught a UI defect: an exception in the replay thread was being posted
to the status line and then **overwritten by a render queued moments earlier**, so a dead
replay looked merely slow. Failures are now logged as well as displayed, and a reported
failure is never overwritten by a stale state line.

---

## 2. Calibration had to be made deployable

The desktop calibrates over a whole session before running anything. A phone has to earn
its calibration while driving, which exposed three problems.

### 2.1 Aliased bearing baselines

The yaw channel and its scale are recovered by requiring integrated gyro over a 5 s baseline
to reproduce the change in reported bearing. During low-speed manoeuvring a vehicle can turn
more than 180 deg inside one baseline, and the wrapped bearing difference then reports the
wrong sign and size. A handful of such baselines dragged the fitted scale from −1.00 to
−0.55 — and scale error integrates into heading for the whole outage.

Baselines are now rejected from the **gyro** side as well as the bearing side (either
exceeding 150 deg is discarded), and the remaining fit is trimmed on residual spread
(`robust_line`). Measured across windows on full-session data the change is neutral
(paired median +0.07 points, CI [−0.01, +0.42]) — it is insurance against a failure mode,
not an improvement, and is opt-in on the desktop (`robust=True`) while always on in the
phone's online calibration.

### 2.2 The gyro bias was being estimated the obvious wrong way

Averaging yaw rate while stopped depends on a few hundred samples selected by a noisy speed
threshold. On one segment those samples included slow manoeuvring, and the estimator
returned **0.0198 rad/s against a true 0.0007** — worth 136 deg of heading over a two-minute
outage.

The same bearing baselines already contain the answer. Over a baseline of length `T`,

    d_bearing = scale * integral(omega) - bias * T

so regressing on both terms recovers scale and bias together, from thousands of samples of
ordinary driving:

| Route | joint-fit bias | truth | error over 120 s |
|---|---|---|---|
| M_seg00 | +0.000711 | −0.000963 | 11.5 deg |
| M_seg01 | −0.005194 | −0.006270 | 7.4 deg |
| S1_seg00 | +0.005175 | +0.005279 | **0.7 deg** |

Implemented on both sides — as a two-regressor least squares on the desktop, and as four
extra running moments per gyro channel on the phone.

### 2.3 What still needs a long drive

Yaw channel, yaw scale and gyro bias converge within a few minutes. **The forward axis does
not.** On a 13-minute window the fitted angle lands ~37 deg from the whole-session answer and
drags the forward-acceleration scale from 0.15 to 0.001, which effectively zeroes two of the
six model features. Calibrating on only ~300 s failed outright on 12 of 22 attempts, and was
*worse* than whole-history calibration on the 10 where it succeeded — so "use only recent
data" is not the fix either.

The app therefore has an explicit **CALIBRATING** state and refuses to navigate until the
regressions have enough evidence (200 angle samples and 100 yaw baselines). On the demo
drive it calibrates for ~350 s before its first navigation update. That is honest, and it is
also a real deployment constraint: this system cannot navigate through an outage in the
first few minutes after the app starts.

---

## 3. The UI, and one thing it deliberately does not claim

![Navigation view during a GNSS outage](../outputs/plots/navigation_ui_blackout.png)

The map draws **two tracks**. The filter's estimate is faint; the rendered position is
bright, and orange wherever aiding was absent. They are different quantities during a
recovery: the estimate is allowed — required — to snap to a returning fix, while the display
slews. Drawing only the smoothed track would hide the correction; drawing only the estimate
brings back the 245 m teleport section 9.2 forbids. The confidence circle is the filter's
own sigma, so it visibly grows through an outage and collapses on re-acquisition.

**The status line does not report drift.** Drift is `|estimate − truth| / distance`, and on
a live drive there is no truth — that is the entire problem being solved. What the phone can
honestly show is its own 1-sigma uncertainty as a fraction of the distance covered since
aiding was lost, and that is what it says. Labelling a self-assessment "drift" would present
a guess as a measurement, and it is exactly the number an audience would take at face value.

---

## 4. Gate 5: the loop, not just the graph

The edge gate asks for the target update rate "with the complete sensor/fusion loop active".
Until this phase there was no loop to measure — `SpeedModelBenchmark` timed the graph
against random input. `fullNavigationLoopFitsTheUpdateBudget` times what actually runs:
feature construction, the filter, the state machine, the smoother, and the network at its
1 Hz fusion cadence.

| | median | p95 | max | budget |
|---|---|---|---|---|
| Full navigation update | **0.017 ms** | 0.069 ms | 4.36 ms | 100 ms |

7,796 updates, 1,200 of them unaided. That is 0.07% of the budget at p95, with a comfortable
margin for the worst sample.

**This does not close Gate 5.** It is an x86_64 emulator; the gate names a physical device,
and the outstanding on-device figure is still outstanding. What has changed is that the
measurement now exercises the real thing, so taking it on hardware is a five-minute job
rather than a phase.

---

## 5. What the next phase should know

1. **Everything is verified against a replay, and the replay is one drive.** Parity holds to
   centimetres on `M_seg00`; it has not been checked on a second route or driver. Generating
   two more replay logs is cheap and would be the first thing to do.
2. **The forward-axis calibration is the weakest link on the phone** (§2.3), and it is the
   same limitation the desktop has — it is simply visible now. It is another argument for the
   outstanding 30-minute own-vehicle drive, which would also let §1.1's real
   `bearing_acc` values be recorded rather than simulated.
3. **The live path has been compiled and wired but not driven.** `LiveSource` runs the same
   `Navigator` the replay does and the schema test covers the logger, but no real drive has
   gone through it end to end. That, plus the on-device benchmark, is what the next trip to a
   car is for.
