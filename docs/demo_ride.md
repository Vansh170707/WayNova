# Greater Noida demo ride

## Why

The old "Replay demo" played an IO-VNBD recording from Coventry, UK. The bundled offline map
only covers Greater Noida, so that demo had no basemap under it.

## What it is

Tap **Replay demo** (or Dashboard → Replay Route). It plays a **simulated** ride from Sector
XU-1 to Bennett University on a bundled OSRM route (`assets/demo_ride_xu1_bennett.json`,
3.5 km, 17 manoeuvres) on the offline MapLibre map, with the route drawn and turn guidance
from `RouteGuidance`. At 4× it takes about 2–3 minutes.

- It is not a recording and not an estimator run. The UI says "SIMULATED DEMO RIDE · NOT A
  RECORDING" throughout.
- `DemoRide` moves a "true" vehicle along the route, slowing for turns. After 30% of the route
  there is a scripted 60 s GPS loss. During it the estimate drifts at 7% along-track and 6%
  lateral per metre driven, about the scale of the field drift in the README. Recovery then
  eases back over 6 s.
- Road lock (`RoadSnapper`) runs on the simulated estimate, so the GPS-loss section shows the
  marker held on the road (`SNAPPING TO ROAD`) while the red estimate track drifts.
- **Long-press** Replay demo for the original recorded Coventry demo, which runs the real
  estimator on real sensor data (no basemap there).

## Start point

Sector XU-3 is not in OpenStreetMap, Nominatim or Photon, so the ride starts in the
neighbouring Sector XU-1 (28.4698, 77.5681). To change it, fetch a new route with
`https://router.project-osrm.org/route/v1/driving/<lon>,<lat>;77.5839,28.4578?overview=full&geometries=geojson&steps=true`
and replace the asset file.

## Verification

JVM: `DemoRideTest` (3 tests: route endpoints, slowing at corners, continuous drift and
recovery). Emulator: `DemoRideUiTest` (route asset parses and ends at Bennett; the demo runs on
the offline map with the route drawn and the marker moving). The emulator suite passed
30/30. Screenshots confirmed streets, route, guidance and the GPS-loss road lock.
