"""HMM map matching over a road graph (Newson & Krumm style), adapted for dead reckoning.

The usual formulation matches noisy GPS fixes. Here the input is a dead-reckoned track
during a GNSS outage, which changes the error structure in a way the scoring has to
respect: DR error is not independent per sample but a slowly growing drift, while the
*shape* of the track (its turns, and the distance covered between samples) stays good for
much longer than its absolute position.

So the transition term does the heavy lifting -- it asks that the distance travelled along
the road matches the distance the filter thinks it covered -- and the emission term is
kept deliberately loose, widening as the filter's own covariance grows.

Safety condition from the blueprint: map matching must not overrule strong inertial
evidence when the map is wrong or incomplete. `match()` reports a per-step confidence, and
the caller applies the correction only where that confidence holds up.
"""
from dataclasses import dataclass

import numpy as np

from neuronav.mapmatch.graph import Candidate, RoadGraph

LOG_ZERO = -1e9


@dataclass
class MatcherConfig:
    emission_sigma_floor_m: float = 12.0   # never trust position better than this
    emission_sigma_scale: float = 1.5      # multiplier on the filter's own sigma
    transition_beta_m: float = 12.0        # tolerance on route-vs-DR distance disagreement
    heading_weight: float = 2.0            # strength of road/heading agreement
    max_candidates: int = 6
    search_radius_m: float = 60.0
    search_radius_scale: float = 3.0       # radius grows with filter sigma
    max_route_distance_m: float = 400.0
    min_confidence: float = 0.35           # below this the match is not applied


@dataclass
class MatchResult:
    east: np.ndarray          # matched position per step
    north: np.ndarray
    confidence: np.ndarray    # posterior share of the winning candidate, 0..1
    matched: np.ndarray       # bool: a candidate was found for this step
    edge_id: np.ndarray


def _angle_difference(a: np.ndarray | float, b: np.ndarray | float):
    return (a - b + np.pi) % (2 * np.pi) - np.pi


def _heading_logprob(road_bearing: float, track_bearing: float, weight: float) -> float:
    """Roads are bidirectional unless one-way, so agreement is modulo 180 degrees."""
    diff = _angle_difference(road_bearing, track_bearing)
    aligned = min(abs(diff), np.pi - abs(diff))
    return -weight * (1.0 - np.cos(aligned))


def match(graph: RoadGraph, east: np.ndarray, north: np.ndarray, sigma: np.ndarray,
          heading: np.ndarray, step_distance: np.ndarray,
          config: MatcherConfig = None) -> MatchResult:
    """Viterbi decode of the most likely road path for a dead-reckoned track.

    step_distance[i] is the distance the estimator believes was travelled between
    step i-1 and step i; it is what ties the decode to the road network's geometry.
    """
    cfg = config or MatcherConfig()
    n = len(east)
    out = MatchResult(np.array(east, dtype=float).copy(), np.array(north, dtype=float).copy(),
                      np.zeros(n), np.zeros(n, dtype=bool), np.full(n, -1, dtype=int))
    if n == 0:
        return out

    # --- candidate generation -----------------------------------------------------
    per_step: list[list[Candidate]] = []
    for i in range(n):
        radius = max(cfg.search_radius_m, cfg.search_radius_scale * float(sigma[i]))
        per_step.append(graph.candidates(float(east[i]), float(north[i]), radius,
                                         cfg.max_candidates))

    def emission(i: int, c: Candidate) -> float:
        s = max(cfg.emission_sigma_floor_m, cfg.emission_sigma_scale * float(sigma[i]))
        return -0.5 * (c.distance_m / s) ** 2

    # --- forward pass -------------------------------------------------------------
    scores: list[np.ndarray] = []
    backpointers: list[np.ndarray] = []
    first = next((i for i in range(n) if per_step[i]), None)
    if first is None:
        return out

    scores_prev = np.array([emission(first, c) + _heading_logprob(
        c.bearing_rad, float(heading[first]), cfg.heading_weight) for c in per_step[first]])
    scores.append(scores_prev)
    backpointers.append(np.full(len(per_step[first]), -1))
    active_index = [first]

    for i in range(first + 1, n):
        current = per_step[i]
        if not current:
            continue
        previous = per_step[active_index[-1]]
        travelled = float(np.sum(step_distance[active_index[-1] + 1:i + 1]))

        transition = np.full((len(previous), len(current)), LOG_ZERO)
        for p_idx, p in enumerate(previous):
            reachable = graph.route_distance(p, current, cfg.max_route_distance_m)
            for c_idx, c in enumerate(current):
                route = reachable.get(c.edge_id)
                if route is None:
                    # unreachable on-road: fall back to straight-line, heavily penalised
                    straight = float(np.hypot(c.east - p.east, c.north - p.north))
                    transition[p_idx, c_idx] = -abs(straight - travelled) / cfg.transition_beta_m - 5.0
                else:
                    transition[p_idx, c_idx] = -abs(route - travelled) / cfg.transition_beta_m

        emissions = np.array([
            emission(i, c) + _heading_logprob(c.bearing_rad, float(heading[i]),
                                              cfg.heading_weight)
            for c in current])

        total = scores[-1][:, None] + transition
        best_prev = np.argmax(total, axis=0)
        scores_now = total[best_prev, np.arange(len(current))] + emissions

        scores.append(scores_now)
        backpointers.append(best_prev)
        active_index.append(i)

    # --- backtrace ----------------------------------------------------------------
    k = int(np.argmax(scores[-1]))
    for step in range(len(active_index) - 1, -1, -1):
        i = active_index[step]
        candidate = per_step[i][k]
        # posterior share of the winner, as a simple confidence signal
        s = scores[step] - scores[step].max()
        weights = np.exp(s)
        out.confidence[i] = float(weights[k] / weights.sum())
        out.east[i] = candidate.east
        out.north[i] = candidate.north
        out.edge_id[i] = candidate.edge_id
        out.matched[i] = True
        k = int(backpointers[step][k]) if backpointers[step][k] >= 0 else k

    return out
