# Waynova round-two prototype

## Implemented

- Waynova launcher name, icon, dark dashboard, direct demo/navigation/destination entry
  points and separate developer tools. Internal package stays `org.neuronavx.logger`, so
  installing an update preserves phone recordings.
- Recent-drive history reads local summary files; corrupt entries are skipped. Details
  distinguish completed protocols from accuracy claims and show outage-end GPS-reference
  error, duration and recovery. There is no invented accuracy percentage on the home screen.
- Explicit place search using Android Geocoder, address disambiguation, coordinate fallback
  and user-confirmed HTTPS OSRM driving-route requests. Names are sent to the geocoding
  provider; route endpoints go to OSRM. Drive logs are never uploaded by this workflow.
- Distance, estimated driving duration without traffic, and a maneuver list. The mint planned
  route is separate from the blue/orange estimator track across map renderers and local canvas.
  Route geometry/instructions are saved privately for offline viewing and can be cleared.
- Precise-location and GPS-enabled checks; missing required motion sensors fail explicitly.
  Navigation/demo keeps the display awake. Leaving the foreground saves/stops the session;
  an active controlled test becomes interrupted instead of silently continuing with stale IMU.
- A controlled test requires a continuous aided lead-in. Live summaries now identify app
  version. Paused telemetry shows dashes rather than stale speed masquerading as live speed.
- Measured bottom-panel insets keep map controls above the panel; dashboard/history scroll.

## What this does not claim

The app is a hackathon prototype, not a production navigation product. Driving directions
are currently a **route preview and saved maneuver list**, not automatic turn alerts, voice
guidance, traffic-aware ETA or automatic rerouting. Getting a new route needs internet;
the bundled offline map is not an offline routing engine. Public services can be unavailable.
Address search availability varies by Android device, so coordinate input remains available.

The local estimator is unchanged in this UI/routing phase. New September 7 field tests
still measure 26.5% and 69.9% incremental drift; the completed below-10% gate remains open.
The disturbed stationary recording is also unresolved. See the full local audit at
`outputs/own_drive/audit_2026-09-11.md`. Do not market replay improvements as new road tests.

## Service and privacy references

- Android Geocoder: https://developer.android.com/reference/android/location/Geocoder
- OSRM route API: https://project-osrm.org/docs/v5.24.0/api/
- OSRM demo policy: https://github.com/Project-OSRM/osrm-backend/wiki/Api-usage-policy
- Current service policy/privacy: https://routing.openstreetmap.de/about.html
- OSM data attribution: https://www.openstreetmap.org/copyright

Requests are user-initiated, rate-limited, identified with a Waynova User-Agent, and have
timeouts/size bounds. No scraping or autocomplete is used. Before wider deployment, use a
managed or self-hosted routing service with an agreed capacity and privacy policy.

## Verification and remaining acceptance

Python suite: 58 passed, one skipped. Android verification: 36 tests passed on the Android 16
ARM64 emulator, including place lookup, an HTTPS route request and private field replay.
Home, navigation, route preview and directions were visually inspected. These checks do
not constitute another physical drive. Android regression and optional routing/replay tests
are in `RoundTwoTest` and `FieldLogReplayTest`. Run with `routingOnline=true` to exercise a
public landmark lookup and a route between synthetic Greater Noida endpoints, and
`fieldReplay=true` with private fixtures installed separately. Private logs are not APK assets.

Before calling the project complete:

1. Validate improved speed/heading behavior on unseen, mounted physical drives; meet the
   agreed completed-outage drift target, including recovery and stationary behavior.
2. Road-test route presentation and destination disambiguation; follow signs, not a prototype.
3. If round two requires live turn-by-turn guidance, implement and validate route progress,
   off-route handling and safe maneuver timing as a separate feature; do not relabel the list.
4. Confirm release signing, privacy/retention requirements and routing service deployment
   before public distribution. The supplied APK is a debug-signed test build.

No signing secrets, paid cloud resources, GitHub pushes or public deployments were created
as part of this local build. No new physical drive result has been claimed.
