"""Apply map matching to a dead-reckoned blackout track (baselines B4 and B6).

The matcher runs at a lower rate than the filter: road geometry only carries information
at the scale of metres, so decoding every 10 Hz sample would cost time without adding
evidence. Matched positions are then interpolated back onto the filter's time base.

The blueprint's safety condition is enforced here rather than inside the matcher: a match
is applied only where the decode is confident AND the correction it asks for is consistent
with the filter's own uncertainty. A map that is wrong or missing a road should degrade the
solution towards plain dead reckoning, never yank it onto the wrong street.
"""
from dataclasses import dataclass

import numpy as np

from neuronav.mapmatch.graph import RoadGraph
from neuronav.mapmatch.hmm import MatcherConfig, match


@dataclass
class MapMatchConfig:
    rate_hz: float = 2.0
    max_correction_sigma: float = 4.0   # reject snaps further than this many filter sigma
    blend: float = 1.0                  # 1.0 = fully trust the matched position
    matcher: MatcherConfig = None

    def matcher_config(self) -> MatcherConfig:
        return self.matcher or MatcherConfig()


def map_match_track(graph: RoadGraph, t: np.ndarray, east: np.ndarray, north: np.ndarray,
                    heading: np.ndarray, sigma: np.ndarray,
                    config: MapMatchConfig = None) -> dict:
    """Snap a dead-reckoned track to the road network.

    Returns the corrected track on the input time base plus diagnostics.
    """
    cfg = config or MapMatchConfig()
    n = len(t)
    if n < 2:
        return {"east": east.copy(), "north": north.copy(),
                "applied": np.zeros(n, dtype=bool), "confidence": np.zeros(n)}

    period = 1.0 / max(cfg.rate_hz, 1e-6)
    keep = [0]
    for i in range(1, n):
        if t[i] - t[keep[-1]] >= period:
            keep.append(i)
    if keep[-1] != n - 1:
        keep.append(n - 1)
    keep = np.array(keep)

    sub_e, sub_n = east[keep], north[keep]
    step = np.hypot(np.diff(sub_e, prepend=sub_e[0]), np.diff(sub_n, prepend=sub_n[0]))

    result = match(graph, sub_e, sub_n, sigma[keep], heading[keep], step,
                   cfg.matcher_config())

    # Accept only confident matches whose correction is plausible given filter sigma.
    correction = np.hypot(result.east - sub_e, result.north - sub_n)
    tolerance = cfg.max_correction_sigma * np.maximum(sigma[keep], 5.0)
    accept = (result.matched
              & (result.confidence >= cfg.matcher_config().min_confidence)
              & (correction <= tolerance))

    out_e, out_n = east.copy(), north.copy()
    applied = np.zeros(n, dtype=bool)
    confidence = np.zeros(n)

    if accept.any():
        idx = keep[accept]
        # interpolate the correction (not the position) so unmatched stretches keep
        # following the inertial solution instead of jumping between snapped points
        delta_e = np.interp(t, t[idx], result.east[accept] - sub_e[accept])
        delta_n = np.interp(t, t[idx], result.north[accept] - sub_n[accept])
        out_e = east + cfg.blend * delta_e
        out_n = north + cfg.blend * delta_n
        applied[:] = True
        confidence = np.interp(t, t[idx], result.confidence[accept])

    return {"east": out_e, "north": out_n, "applied": applied, "confidence": confidence,
            "n_matched": int(accept.sum()), "n_steps": int(len(keep)),
            "median_correction_m": float(np.median(correction[accept])) if accept.any() else np.nan}
