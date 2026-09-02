# Phase 3 — Road-Graph Map Matching (B4 / B4b): Gate 4 fails

> **Renamed in Phase 6.** What this document calls B6 (TCN + map) is now
> `B4b_tcn_map_es_ekf`. The blueprint's B6 is the physics-guided variant in
> `docs/phase6_physics_guided.md`. The numbers below are unchanged.

**Outcome: map aiding does not improve blackout drift on a held-out driver, and is not
enabled in the fusion loop by default.** The blueprint anticipates this exact outcome —
Gate 4 says "if no: use only as low-confidence correction" — so this is a planned branch,
not a dead end. What follows is the evidence and the conditions under which it would pay.

## What was built

| Module | Role |
|---|---|
| `mapmatch/osm.py` | Overpass fetch, cached as `.npz` for fully offline use (8.3 MB for all 7 segments) |
| `mapmatch/graph.py` | Local-ENU road graph, KD-tree candidate search, bounded Dijkstra routing |
| `mapmatch/hmm.py` | Offline Viterbi matcher (Newson & Krumm style) |
| `mapmatch/online.py` | Causal multi-hypothesis matcher that aids the ES-EKF during an outage |

The ES-EKF gained two map measurements: an **anisotropic position update** (tight across
the road, deliberately loose along it) and a **heading update** modulo the road's two
travel directions.

## The map itself is not the problem

Measured against the 10 Hz vehicle reference on S1:

* truth sits **1.7 m** from the nearest road (p90 4.5 m), 0% unmatched
* the next **distinct** road is ~17-19 m away (p25 7-8 m)

So the network is accurate and complete, and the road that matches is identifiable — but
only while position error stays inside roughly a 17 m margin.

## Why it fails, in three measured steps

**1. Snapping position post-hoc does nothing.** Corrections averaged 7 m against errors of
100 m+: 60 s drift 30.9% → 31.1%. By the time the track is matched it is far enough away
that the nearest road is confidently the wrong one.

**2. Naive online feedback actively harms.** Correcting toward the nearest road during the
outage made things clearly worse (60 s: 27.4% → 39.7%; 120 s: 37.7% → 55.2%, helping only
17% of windows). Two mechanisms: the correction is **self-confirming** — once pulled onto a
road, the position agrees with that road forever — and a heading correction toward a wrong
road steers the solution actively wrong rather than merely failing to help.

**3. No configuration rescues it.** A 16-point sweep over the balance between position
evidence, heading evidence, correction strength and confidence threshold produced *no*
configuration beating the 30.3% baseline. The trend is the finding: configurations applying
the map on 59-76% of steps drifted 42-49%, those applying it on 21-28% drifted 30-33%.
Less map, less harm.

Two implementation bugs were found and fixed along the way, and are worth remembering
because both produced plausible output rather than errors:

* candidates were generated **per edge**, but a way is split into one edge per vertex pair —
  so the candidate list filled with consecutive segments of the *same* road, crowding out
  genuinely different roads and splitting the posterior across one hypothesis. Fixed by
  deduplicating per way, which raised the measured road-discrimination margin from an
  apparent 6.6 m to a true 17-19 m.
* `service` and `living_street` ways (car parks, driveways) were included as drivable and
  are now excluded.

## The uncertainty gate, and why it is not enough

Since the failure is confined to the regime where error exceeds road spacing, map aiding is
gated on the filter's own position sigma (`max_sigma_for_aiding_m = 18`). On driver A this
converts harm into a small consistent gain:

| Outage (driver A, S1) | no map | ungated | **gated** |
|---|---|---|---|
| 60 s, B3 | 30.3% | 50.1% | **27.5%** |
| 120 s, B3 | 36.9% | 49.3% | 36.9% |
| 60 s, B5 | 12.6% | — | **11.5%** |

But on **held-out driver B** it still loses:

| Outage | B3 | B4 (+map) | B5 | B4b (+map) |
|---|---|---|---|---|
| 30 s | **10.8%** | 18.0% | **16.3%** | 16.6% |
| 60 s | **15.0%** | 19.0% | **14.0%** | 21.8% |
| 120 s | **26.0%** | 37.0% | **14.7%** | 19.6% |

The gate depends on the filter's *self-reported* sigma, and that is not calibrated well
enough to carry the decision: the ratio of true final error to reported sigma has a median
of 1.32 but ranges from **0.56 to 2.15** across routes. Sigma also only sits below 18 m in
the first seconds of an outage, so the corrections that do get applied are early ones whose
damage then persists for the rest of the window.

Tuning the gate on driver B would of course close the gap, and would also destroy the only
claim worth making — that this generalises. So it is left as is and reported as a failure.

## What would make map matching pay

1. **Lower drift.** The technique works inside ~17 m of position error. B5 already halves
   120 s drift; another factor of two would put much of a typical outage inside the regime
   where the correct road is identifiable.
2. **Calibrated position uncertainty.** The gate is the right idea and needs a sigma that
   means the same thing on every route (blueprint section 11 wants this anyway, for the
   confidence display).
3. **Use it for display, not fusion.** Snapping the *rendered* position to the road makes
   the demo look right — users expect the marker on the road — without letting a wrong-road
   decision corrupt the estimate. This is what many production navigation apps do, and it
   is the recommended use here for the finale.

## Reproducing

```bash
./envs/bin/python scripts/download_osm.py     # once, with connectivity
./envs/bin/python scripts/run_baselines.py --drivers B --map \
    --checkpoint outputs/checkpoints/speed_tcn_holdout_B.pt
```

Map aiding is **off** unless `--map` is passed. The recommended stack is `B0/B1/B3/B5`,
plus the yaw-scale state of `B6` (`deployment_config()`) as of Phase 6.
