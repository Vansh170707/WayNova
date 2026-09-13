# Map-only navigation patch

Scope: preserve the existing UI; remove canvas navigation and provide route reselection.

- NavigationActivity no longer attaches NavigationView to the view hierarchy. It is retained only as an internal legacy snapshot sink.
- The map badge switches between available Google and MapLibre renderers, never canvas.
- MapLibre continues drawing valid estimator snapshots outside the downloaded archive. The badge reports `NO OFFLINE COVERAGE`; no additional street coverage is implied.
- Invalid calibration positions do not trigger an out-of-coverage transition.
- If neither renderer is ready, the shell reports map unavailable rather than showing a substitute navigation canvas.
- The top back arrow offers route reselection or dashboard return. Route reselection during a live session requires a parked-speed check and confirmation to save the recording. The current route is retained until a replacement is selected.

Regression coverage: RoundTwoTest checks the unattached canvas even through the fallback method, and the route-back control. Existing route serialization, session-state and map-projection tests are the nearest gate.

Verification (2026-09-12): debug app and test APKs built successfully. Focused emulator suite finished successfully; XML reports 13 tests, zero failures/errors, and two opt-in online tests skipped. No physical phone was connected. The dashboard smoke test was aligned with the existing redesigned button labels.

Not included in this patch: worldwide street maps, live turn-by-turn progress, turn announcements, own-drive replay, evidence sharing, full readiness checklist, UI redesign, or estimator accuracy changes. Existing voice telemetry and recorded benchmark are not equivalents of those features.
