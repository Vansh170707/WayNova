# Three-ride accuracy audit — 2026-09-13

## Decision

The 2 Hz learned-speed correction candidate was rejected and removed. It increased
incremental drift on all three completed 60-second rides. The production estimator
therefore remains at its previously verified 1 Hz correction cadence.

Saved reference GPS is used only after replay for scoring. It is never fed into the
estimator or the route constraint during a simulated outage.

| Completed session | 1 Hz baseline | 2 Hz candidate | Decision |
| --- | ---: | ---: | --- |
| 1788784042813 | 26.4994% | 27.7833% | Worse; reject |
| 1788784767348 | 57.9112% | 59.4831% | Worse; reject |
| 1789275278630 | 32.7321% | 33.6946% | Worse; reject |

The newest restored-baseline replay scored 160.19 m incremental drift over 489.41 m
of reference travel and reacquired GPS in 2.93 seconds. These are development replay
results, not an accuracy-pass claim.

## Combined diagnosis

The phone's live calibration substantially reduces the raw learned-speed bias, but
the remaining error changes by driving regime. During the outage, adapted speed MAE
was 1.50, 2.14, and 1.90 m/s across the three completed rides. Mean heading error was
14.16°, 3.65°, and 5.09°. A single stronger global correction therefore does not
transfer safely across the rides; the replay experiment confirms this.

The retained improvement is the separately labelled route-constrained presentation
hypothesis. When a route is selected, it can keep the displayed outage estimate on a
direction-compatible road while preserving the original estimator track and
covariance. It releases on ambiguity, heading conflict, sample gaps, recovery, route
end, or excessive uncertainty. This improves usefulness but is not reported as raw
estimator accuracy until a new route-selected field drive measures it.

## Reproducible evidence

- `scripts/analyze_three_completed_rides.py` uses only Python's standard library.
- `outputs/own_drive/three_completed_rides_analysis_2026-09-13.json` contains the
  combined speed and heading measurements.
- `outputs/own_drive/accuracy_baseline_2026-09-13.json` and
  `outputs/own_drive/accuracy_baseline_latest_2026-09-13.json` contain baselines.
- `outputs/own_drive/accuracy_candidate_2hz_sep7.json` and
  `outputs/own_drive/accuracy_candidate_2hz_latest.json` preserve the rejected result.

Verification after reversion: 61/61 non-field emulator tests passed; two optional
online smoke checks were skipped offline. The latest private replay passed separately.
No connected-phone instrumentation, uninstall, or data clearing was performed.
