# Road lock (display-only snap to road)

## Use

On by default; toggle in Settings → Navigation → **Road lock**. Works with the offline MapLibre
basemap only (Google Maps exposes no road geometry offline). The map badge shows
`OFFLINE GN · ROAD LOCK` while the marker is on a road and `OFFLINE GN · SNAPPING TO ROAD`
(amber) while the estimate has drifted beyond the road and is being held on it.

## How it works

- `MapLibreOfflineRenderer.drivableRoadsNear` reads `roads` features (kinds `highway`,
  `major_road`, `minor_road`; paths, rail, runways and piers excluded) from offline tiles
  already loaded within 400 m of the marker. Refreshes after 120 m of movement or 5 s.
- `RoadSnapper` picks the road by lateral distance (scaled by estimator sigma), heading
  agreement (one-way aware, 60° gate when moving) and continuity with the previous locked
  point, which keeps the marker off a parallel service road during blackout drift.
- Raw estimate more than 6 m from the chosen centreline = drift detected (`CORRECTING`).
  While locked, the marker is pinned to the road even if the estimate jumps sideways.
  When a road is first acquired after none, the marker glides on (0.35 s time constant,
  at most 40 m/s); on release it eases back to the raw estimate. No teleporting.
- Released states: `OFF_NETWORK` (no drivable road within 25–60 m, e.g. car park),
  `AMBIGUOUS` (two separated roads equally plausible with no history), `NO_ROADS`.

## Boundaries

- Never modifies Navigator, EsEkf, their covariance, recordings or drift scoring. Phase 3
  showed a confident wrong-road match corrupts the filter; this lock only moves the marker.
- Snaps to the nearest plausible road, not the true one: wrong-road locks are still
  possible where roads are closer than the estimate error. The original estimate track stays
  visible.
- Parked near a road within the search radius, the marker sits on the road.

## Verification

JVM unit tests: `RoadSnapperTest` (11). Emulator: `RoadLockTest` reads real roads from the
bundled Greater Noida archive and snaps an offset estimate onto one; RoundTwo, GlassUi,
RouteConstraint and MapProjection suites still pass (26/26). Not field-tested yet.
