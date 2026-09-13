# Offline route-assisted positioning

## Use

Select a start/destination route, then start live navigation with GPS available. The saved route is automatically eligible as a reference path; there is no chatbot or network call in the assistance loop. During GPS loss the map marker can follow that route using estimated speed, but only after a recent, consistent GPS-aided anchor has been established.

The screen explicitly says **Route-assisted · GPS unavailable**. The blue/orange original estimator track stays visible. `M EST.` is a heuristic uncertainty estimate, not a calibrated confidence level or lane-accuracy claim. Existing turn announcements remain suspended during GPS loss.

## Implementation boundaries

- RouteConstraint consumes estimated position, speed, heading and uncertainty. It cannot modify Navigator/EsEkf state or covariance.
- An anchor needs at least one continuous second of compatible aided samples, GPS age at most 1.5 seconds, sigma at most 15 m, distance to the route at most 20 m, and compatible heading while moving. Controlled withheld references never establish anchors.
- After aiding stops, trapezoidal speed integration advances along the stored polyline. Heading must agree with the predicted road segment; a conflicting bend or wrong turn releases the constraint rather than inventing a turn.
- Reject ambiguous separated portions of the same route, large raw-estimate disagreement, invalid samples, gaps over one second, GPS recovery, route end, and excessive uncertainty. A new aided anchor is needed after release.
- Limit assistance to 60 seconds from the last anchor, 60 m raw/heuristic uncertainty, at most 5,000 route vertices and a local geometry extent within 100 km of its origin. These are conservative prototype rules, not validated accuracy guarantees.
- No nearby-road graph is loaded: ambiguity checks cover the selected polyline, not every nearby road. A shallow wrong turn onto a parallel road may not be detectable immediately. Wrong speed still causes along-route error.
- Replacing or clearing the route resets assistance. Saved-ride/demo replay continues displaying original recorded estimates, not newly constrained results.

## Evidence

Original raw and diagnostic position columns remain unchanged. Diagnostics append:

`route_assist_status,route_assist_lat,route_assist_lon,route_progress_m,route_assist_uncertainty_m`

These columns are already included when sharing the diagnostic CSV in the evidence ZIP. Existing blackout error reports still score the original estimator, not a cosmetically road-aligned marker.

## Verification

The emulator-only suite completed successfully: RouteConstraint, RouteGuidance, LiveRobustness, NavigationParity, RoundTwo, SavedRide and GlassUi tests. It checks road progress, bend/heading agreement, wrong-turn release, overlaps, gaps, GPS recovery, uncertainty, stops, end-of-route behavior, preservation of original diagnostic coordinates and existing app behavior. Optional online-service checks were not enabled. Navigator/EsEkf source was not changed.

Not field-validated yet. A passenger-assisted test should include a straight section, a planned turn, a deliberately different route while safe/legal, a stop and GPS recovery. Back up the recordings before any phone update. No phone installation or physical-phone instrumentation was performed for this feature.
