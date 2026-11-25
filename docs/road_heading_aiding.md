# Road-direction heading aiding (experimental, not enabled)

## Idea

During GNSS loss, use the direction of the local road as a heading pseudo-measurement.
Phase 3 map aiding failed because a confident wrong-road **position** match pulled the
solution onto the wrong road. A **heading** measurement avoids that failure: a parallel road
points the same way, so a wrong match still gives the right heading.

## Implementation

- `scripts/extract_offline_roads.py` writes drivable roads from the bundled PMTiles into
  `assets/offline_roads_greater_noida.bin` (90k lines, 1.9 MB). `RoadNetwork` reads it with a
  50 m grid index, so the estimator thread never depends on MapLibre.
- `RoadHeadingAid` accepts a road direction only when all of these hold:
  - speed is at least 5 m/s and yaw rate is at most 3°/s;
  - every drivable segment within 15–40 m (1.5σ) lies within ±12° of one consensus
    direction;
  - the consensus is within 20° of the filter heading;
  - no other update was accepted in the last second.

  Measurement σ is 4°. Junctions, bends, roundabouts, opposed one-ways and car parks fail the
  consensus check.
- `EsEkf.updateRoadHeading` uses the normal innovation gate, but a rejection is not counted as
  a rejected GNSS fix, so it cannot trigger the Sentinel multipath flag or divergence reset.
- `Navigator(roadHeadingAid = …)`. The default `null` leaves the estimator unchanged. **The
  live app does not pass an aid. Nothing shipped changes.**

## Evidence (2026-11-25)

`RoadHeadingReplayTest` is a paired A/B on 14 of our own Greater Noida drives. Both
estimators see the identical stream. GNSS is withheld for 60 s windows, 120 s apart, after
calibration. The phone fixes are the hidden reference: phone GPS, not survey truth. 18
windows had at least 150 m of travel.

| | baseline | road heading |
|---|---|---|
| median drift | 20.3% | 17.8% |
| p90 drift | 39.7% | 38.8% |
| pooled drift (Σ error / Σ distance) | 21.6% | 20.1% |
| windows better / worse / unchanged (±1 pt) | | 5 / 2 / 11 |

The best cases improved a lot: 27.1 → 12.9% and 14.8 → 6.5%. Two windows got worse:
6.0 → 13.3% and 24.1 → 31.2%. In 11 windows the aid accepted few or no updates (0–4 per
60 s), so it had nothing to change.

**Verdict: not enough to ship.** A 5/2 split on 18 windows is not significant (sign test,
p ≈ 0.45). Below-10% is still out of reach either way: most remaining drift is probably
along-track (speed), which a heading cannot fix. This follows the same rule as Phase 10:
an unproven estimator change is not deployed.

## Diagnosis (runs 2 and 3, same 18 windows)

The replay now splits error into along-track (speed) and cross-track (heading) components,
measures speed and heading error against the hidden phone fixes, and counts why the aid
refused.

**Speed error is the bigger component.** Summed over the 18 windows, baseline along-track
error is 1,229 m and cross-track error is 946 m. Speed error is ±1–3 m/s per window, and its
sign changes from window to window (mean −0.15 m/s), so it is not one fixed bias. Even a
perfect heading would leave pooled drift at about 14.5% (1,229 m over 8,454 m), still above
the 10% target.

**Road heading only fixes cross-track error:** 946 → 795 m (−16%). Along-track error is
unchanged (1,217 m).

**Why it rarely fires:**

1. *Gyro noise blocked it.* The raw 10 Hz yaw rate exceeds 3°/s 62% of the time, even when
   parked, while GPS shows real turning only 34% of moving time. A ~1 s low-pass
   (`smoothYawRate`) halves the TURNING refusals (4,810 → 2,172 ticks).
2. *The roads then disagree.* The refusals move to NO_CONSENSUS (1,927 → 4,237). Greater
   Noida's dense sector grid puts crossing roads within 15–40 m of the estimate most of the
   time. Acceptances only rise from 123 to 142.

**Variants against the same baseline:**

| arm | median | p90 | pooled | better/worse | sign test |
|---|---|---|---|---|---|
| baseline | 20.3% | 39.7% | 21.6% | | |
| v1 (defaults) | 17.8% | 38.8% | 20.1% | 5/2 | p = 0.45 |
| v2 (smoothed yaw, ≥20 m road) | 18.1% | 39.8% | 20.1% | 6/2 | p = 0.29 |
| v2, heading-only gain | 19.3% | 39.0% | 20.6% | 3/2 | p = 1.00 |

Heading-only gain fixes the 1788439116256 regression (13.3 → 6.0%), but it also removes the
biggest gain (12.9 → 19.2%). The heading/position coupling is useful more often than harmful.

**Conclusion:** road heading gives about 1.5 points of pooled drift at best, and the gain is not
statistically supported. It stays off. Drift below 10% needs better **speed** during outages,
not heading.

## Next lever: speed

Speed error of ±1–3 m/s that changes sign per window points at the learned speed model's
accuracy, not at a calibration offset. Directions worth measuring, in this same paired
harness:

- per-window learned speed vs GPS speed, to see whether errors follow road type, speed
  regime or phone mount;
- fine-tuning the TCN on these own-device drives (`scripts/finetune_own_device.py` exists);
  hold out whole drives, never windows from the same drive;
- zero-velocity resets at stops during an outage, which are already partly in place through
  `StationaryHold`.

## Earlier next steps (done above)

1. Log the status histogram and along-track versus cross-track error per window, to find out
   whether heading or speed dominates and why acceptances are rare.
2. Look at the two regressions, starting with 1788439116256 at 21 s, where a single update
   doubled drift.
3. Re-run on new drives before any live use.

Reproduce:

```bash
bash mobile/test_emulator.sh emulator-5554 org.neuronavx.logger.RoadHeadingReplayTest "$PWD/data/raw/road_heading_fixtures"
```

The fixtures (private drives) stay in `data/raw/`, which is gitignored.
