"""Online, causal map aiding for the ES-EKF during a GNSS outage.

A weighted multi-hypothesis filter over road positions -- effectively an HMM forward pass
run incrementally, which is what the blueprint means by "probabilistic candidates with
uncertainty" rather than nearest-road snapping.

Why the hypotheses ADVANCE rather than being re-selected each step
------------------------------------------------------------------
Picking the nearest road at every step fails badly here. Dead-reckoned position drifts
hundreds of metres, so the nearest road soon becomes confidently the *wrong* road, and a
heading correction towards it then steers the solution actively wrong. Measured on S1 that
approach made 60 s drift worse (27% -> 40%).

Instead each hypothesis is carried forward along the graph by the distance the filter
believes was travelled, branching at junctions. Parallel roads may look equally good for a
moment, but their topology diverges -- one turns, one does not -- and the accumulated
evidence separates them. Speed is the filter's best-known quantity during an outage, so
distance travelled is a reliable thing to propagate on.

What the map observes
---------------------
* **Cross-track** position: well observed, roughly lane width.
* **Along-track** position: not observed at all -- given a very loose variance.
* **Heading**: well observed modulo the road's two travel directions, and this is the term
  that pays, since heading error is what makes position error grow.

Safety condition (blueprint section 10): the correction is applied only when the posterior
concentrates on one hypothesis, and its strength is scaled by that confidence, so an
ambiguous or missing map degrades towards plain dead reckoning instead of yanking the
solution onto the wrong street.
"""
from dataclasses import dataclass, field

import numpy as np

from neuronav.mapmatch.graph import RoadGraph


@dataclass
class OnlineMapConfig:
    update_hz: float = 1.0
    init_radius_m: float = 50.0
    max_hypotheses: int = 12
    branch_limit: int = 3                  # successors kept per hypothesis at a junction

    sigma_cross_m: float = 6.0             # lateral confidence when fully certain
    sigma_along_m: float = 500.0           # effectively unconstrained along the road
    sigma_heading_rad: float = 0.12        # ~7 deg

    emission_sigma_m: float = 25.0         # tolerance of DR-to-road distance
    heading_weight: float = 1.5
    min_confidence: float = 0.60           # posterior mass before any correction applies
    min_lock_steps: int = 4                # consecutive confident steps before trusting
    min_speed_for_heading: float = 3.0

    # Hard gate on the filter's own uncertainty. Measured on this network, the nearest
    # road sits ~1.7 m from the truth while the next DISTINCT road is ~17-19 m away, so
    # roughly 17 m is the margin within which the correct road is identifiable. Beyond
    # it the matcher picks a wrong road confidently and drags the solution there --
    # 60 s outages went from 30% to 50% drift with the gate removed. Above this sigma
    # the map contributes nothing rather than something wrong.
    max_sigma_for_aiding_m: float = 18.0


@dataclass
class _Hypothesis:
    edge_id: int
    fraction: float
    log_weight: float


def _axis_alignment(road_bearing: float, heading: float) -> float:
    """Angular disagreement with a road's axis, in [0, pi/2]."""
    diff = (road_bearing - heading + np.pi) % (2 * np.pi) - np.pi
    return min(abs(diff), np.pi - abs(diff))


@dataclass
class OnlineMapMatcher:
    graph: RoadGraph
    config: OnlineMapConfig = field(default_factory=OnlineMapConfig)
    hypotheses: list = field(default_factory=list)
    confident_steps: int = 0
    n_applied: int = 0
    n_steps: int = 0
    last_confidence: float = 0.0

    def reset(self):
        self.hypotheses = []
        self.confident_steps = 0

    # ------------------------------------------------------------------- propagation
    def _advance(self, hypothesis: _Hypothesis, distance: float) -> list[_Hypothesis]:
        """Move a hypothesis `distance` along the graph, branching at junctions."""
        g = self.graph
        edge = hypothesis.edge_id
        length = float(g.edge_length[edge])
        travelled = hypothesis.fraction * length + distance

        if travelled <= length:
            return [_Hypothesis(edge, travelled / length, hypothesis.log_weight)]

        overshoot = travelled - length
        node = int(g.edge_to[edge])
        successors = []
        for next_edge, forward in g.outgoing[node][: self.config.branch_limit * 2]:
            if next_edge == edge:
                continue
            next_len = float(g.edge_length[next_edge])
            if next_len <= 0:
                continue
            frac = min(overshoot / next_len, 1.0)
            if not forward:
                frac = 1.0 - frac
            # branching splits belief across the possible continuations
            successors.append(_Hypothesis(next_edge, frac,
                                          hypothesis.log_weight - np.log(max(len(
                                              g.outgoing[node]), 1))))
            if len(successors) >= self.config.branch_limit:
                break

        if not successors:
            # dead end: hold at the end of the current edge
            return [_Hypothesis(edge, 1.0, hypothesis.log_weight - 1.0)]
        return successors

    def _position(self, hypothesis: _Hypothesis):
        g = self.graph
        a, b = g.edge_start[hypothesis.edge_id], g.edge_end[hypothesis.edge_id]
        e = g.point_e[a] + hypothesis.fraction * (g.point_e[b] - g.point_e[a])
        n = g.point_n[a] + hypothesis.fraction * (g.point_n[b] - g.point_n[a])
        return float(e), float(n), float(g.edge_bearing[hypothesis.edge_id])

    def _seed(self, east, north, heading, sigma):
        cfg = self.config
        radius = max(cfg.init_radius_m, 2.0 * sigma)
        candidates = self.graph.candidates(east, north, radius, cfg.max_hypotheses)
        self.hypotheses = [
            _Hypothesis(c.edge_id, c.fraction,
                        -0.5 * (c.distance_m / cfg.emission_sigma_m) ** 2
                        - cfg.heading_weight * (1 - np.cos(_axis_alignment(
                            c.bearing_rad, heading))))
            for c in candidates]

    # -------------------------------------------------------------------------- step
    def step(self, ekf, east: float, north: float, heading: float, sigma: float,
             speed: float, distance: float) -> bool:
        """Advance hypotheses, reweight against the filter, and aid it when confident."""
        self.n_steps += 1
        cfg = self.config

        # Once the filter is this uncertain, the true road is no longer distinguishable
        # from its neighbours; keep tracking hypotheses but stop correcting.
        aiding_allowed = sigma <= cfg.max_sigma_for_aiding_m

        if not self.hypotheses:
            self._seed(east, north, heading, sigma)
            if not self.hypotheses:
                return False
        else:
            advanced = []
            for h in self.hypotheses:
                advanced.extend(self._advance(h, distance))
            self.hypotheses = advanced

        # reweight by agreement with the filter's current estimate
        for h in self.hypotheses:
            he, hn, bearing = self._position(h)
            dist = float(np.hypot(east - he, north - hn))
            h.log_weight += (-0.5 * (dist / cfg.emission_sigma_m) ** 2
                             - cfg.heading_weight * (1 - np.cos(
                                 _axis_alignment(bearing, heading))))

        # merge duplicates on the same edge, keep the strongest
        best_per_edge: dict[int, _Hypothesis] = {}
        for h in self.hypotheses:
            existing = best_per_edge.get(h.edge_id)
            if existing is None or h.log_weight > existing.log_weight:
                best_per_edge[h.edge_id] = h
        self.hypotheses = sorted(best_per_edge.values(),
                                 key=lambda h: -h.log_weight)[: cfg.max_hypotheses]

        weights = np.array([h.log_weight for h in self.hypotheses])
        weights -= weights.max()
        posterior = np.exp(weights)
        posterior /= posterior.sum()
        for h, w in zip(self.hypotheses, posterior):
            h.log_weight = float(np.log(max(w, 1e-12)))

        confidence = float(posterior[0])
        self.last_confidence = confidence

        if confidence < cfg.min_confidence:
            self.confident_steps = 0
            return False
        self.confident_steps += 1
        if self.confident_steps < cfg.min_lock_steps or not aiding_allowed:
            return False

        # correction strength scales with confidence: an ambiguous map barely moves us
        he, hn, bearing = self._position(self.hypotheses[0])
        scale = 1.0 / max(confidence, 1e-3)
        ekf.update_map_position(he, hn, bearing,
                                cfg.sigma_cross_m * scale, cfg.sigma_along_m)
        if speed >= cfg.min_speed_for_heading:
            ekf.update_map_heading(bearing, cfg.sigma_heading_rad * scale)
        self.n_applied += 1
        return True
