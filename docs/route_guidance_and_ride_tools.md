# Route guidance and ride tools — 2026-09-13

## Use

1. Choose a fresh destination/route, then start live navigation while parked. New routes cache maneuver coordinates; older caches remain readable as static previews.
2. When positioning is reliable, the existing navigation panel shows the next maneuver, approximate distance and remaining geometry distance. Voice-enabled sessions announce one-shot cues near 250 m, 80 m, and the turn point. Mute still works.
3. During GPS loss, spoken turns continue only while the separately labelled route estimate is active and its uncertainty is at most 30 m. Every such cue begins with “Route estimate”.
4. Open **Trips**, select a saved ride, then use **Replay this recorded ride · 4×** or **Share ride evidence ZIP**.
5. Replay uses the original diagnostic display coordinates and uncertainty, anchored to the first fresh GPS fix from the raw recording. It does not rerun the estimator, activate sensors, create a new drive, or claim new accuracy results. Gaps over two seconds break the trail; playback shortens long gaps. The visible trail retains the last 2,500 points.
6. Sharing asks for confirmation because the raw log contains location history. The ZIP includes exactly the selected raw CSV, diagnostics CSV and summary JSON. A read-only FileProvider exposes only the export cache directory; Android's chooser selects the recipient. No upload occurs automatically.

## Boundaries

- Guidance is presentation-only: no snapping/corrections enter Navigator and no route is treated as reference truth.
- Aided guidance requires GNSS age at most 2.5 seconds and sigma at most 20 m. GPS-loss guidance requires an active road-constrained estimate and sigma at most 30 m. Turns are suppressed during recovery, ambiguity, off-route/route conflict, sample gaps, route end, excessive uncertainty, or opposite travel direction. These are prototype heuristics, not an accuracy guarantee.
- “Near destination” requires both proximity and low speed; it does not claim a verified entrance or parking location.
- No automatic rerouting. Park and choose another route.
- Live guidance is bounded to 5,000 geometry points and 200 steps; larger routes remain preview-only. Geometry updates are throttled to twice per second.
- Replay limits: 200 MB raw file, 24 MB diagnostics, 100,000 frames. Missing origin or missing diagnostic coordinates fail explicitly.
- Evidence export requires all three files and is capped at 200 MB. Original files are not modified. Failed temporary ZIPs are removed; successful exports live in Android's cache.
- Speech uses the installed Android TTS engine and existing voice preference. Actual audibility/offline voice availability needs device verification; no claim of guaranteed offline speech is made by this feature.
- Existing permissions/GPS/calibration checks remain. The idle screen no longer claims position lock before starting. A comprehensive readiness checklist is not added.

## Verification

Focused tests cover geometry progress, uncertainty/stale-fix suppression, reverse direction, overlapping tracks, destination proximity, legacy caches, isolated evidence bundles, missing/mismatched evidence, original saved coordinates and replay without live recording. Existing RoundTwo and Glass UI tests are run alongside them.

These automated tests do not replace a passenger-assisted field check of real turn guidance or a measured GPS-loss accuracy audit. No estimator accuracy change is claimed.

Final run: build successful on 2026-09-13 IST. The verified emulator passed 61/61 non-field tests; two optional online-service smoke checks were skipped offline. The newest private field replay also passed separately with the restored estimator. `git diff --check` passed. No physical-phone instrumentation or data-clearing command was used in this phase. Physical turn-prompt timing, installed TTS audibility and actual share-recipient delivery remain manual checks.
