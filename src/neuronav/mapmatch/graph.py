"""Road graph: local-ENU geometry, a spatial index for candidates, and bounded routing.

Two structures are kept deliberately separate:

* **Topology** uses the original OSM vertices, so junctions stay exactly where OSM puts
  them and routing follows real connectivity.
* **The spatial index** samples each edge every few metres. Densifying the index rather
  than the topology means nearest-edge queries stay accurate on long edges without
  inventing graph nodes that would distort route distances.
"""
import heapq
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from scipy.spatial import cKDTree

from neuronav.mapmatch.osm import CLASS_SPEED, DEFAULT_SPEED
from neuronav.utils.geo import latlon_to_enu

INDEX_SPACING_M = 8.0


@dataclass
class Candidate:
    """One possible on-road position for a given estimate."""
    edge_id: int
    east: float
    north: float
    distance_m: float      # perpendicular distance from the query point
    fraction: float        # position along the edge, 0..1
    bearing_rad: float     # direction of travel along the edge, clockwise from North


# Classes that flood the candidate set without carrying real through-traffic. Including
# service ways (car parks, driveways, alleys) puts the second-nearest road a median of
# 6.6 m away, which leaves any matcher unable to discriminate even at zero position error.
AMBIGUOUS_CLASSES = ("service", "living_street")


class RoadGraph:
    def __init__(self, npz_path: str | Path, origin: tuple[float, float, float] = None,
                 exclude_classes: tuple = AMBIGUOUS_CLASSES):
        data = np.load(npz_path, allow_pickle=False)
        lat, lon = data["lat"], data["lon"]
        offsets = data["offset"]
        node_ids = data["node"]
        self.excluded_classes = tuple(exclude_classes or ())

        if origin is None:
            origin = (float(np.median(lat)), float(np.median(lon)), 0.0)
        self.origin = origin
        east, north, _ = latlon_to_enu(lat, lon, np.zeros_like(lat), *origin)
        self.point_e = np.asarray(east)
        self.point_n = np.asarray(north)

        # --- topology: one edge per consecutive vertex pair within a way -------------
        starts, ends, way_index = [], [], []
        for w in range(len(offsets) - 1):
            a, b = offsets[w], offsets[w + 1]
            if b - a < 2:
                continue
            idx = np.arange(a, b - 1)
            starts.append(idx)
            ends.append(idx + 1)
            way_index.append(np.full(len(idx), w))
        self.edge_start = np.concatenate(starts)
        self.edge_end = np.concatenate(ends)
        self.edge_way = np.concatenate(way_index)

        de = self.point_e[self.edge_end] - self.point_e[self.edge_start]
        dn = self.point_n[self.edge_end] - self.point_n[self.edge_start]
        self.edge_length = np.hypot(de, dn)
        self.edge_bearing = np.arctan2(de, dn)  # clockwise from North

        highway = data["highway"]
        keep = self.edge_length > 0.05
        if self.excluded_classes:
            keep &= ~np.isin(highway[self.edge_way], self.excluded_classes)
        for name in ("edge_start", "edge_end", "edge_way", "edge_length", "edge_bearing"):
            setattr(self, name, getattr(self, name)[keep])

        self.edge_class = highway[self.edge_way]
        self.edge_speed = np.array([CLASS_SPEED.get(h, DEFAULT_SPEED) for h in self.edge_class])
        self.edge_oneway = data["oneway"][self.edge_way]

        # --- node identity: OSM id where present, else a snapped coordinate key ------
        self._node_key = self._build_node_keys(node_ids)
        self._build_adjacency()
        self._build_spatial_index()

    # ------------------------------------------------------------------ construction
    def _build_node_keys(self, node_ids: np.ndarray) -> np.ndarray:
        """Map each vertex to an integer identifying its physical location."""
        keys = np.full(len(self.point_e), -1, dtype=np.int64)
        has_osm = node_ids >= 0
        if has_osm.any():
            unique, inverse = np.unique(node_ids[has_osm], return_inverse=True)
            keys[has_osm] = inverse
            next_key = len(unique)
        else:
            next_key = 0
        # vertices without an OSM id are merged on a 0.5 m grid
        missing = ~has_osm
        if missing.any():
            grid = np.column_stack([np.round(self.point_e[missing] * 2).astype(np.int64),
                                    np.round(self.point_n[missing] * 2).astype(np.int64)])
            _, inverse = np.unique(grid, axis=0, return_inverse=True)
            keys[missing] = next_key + inverse
        return keys

    def _build_adjacency(self):
        """For each node key, which edges leave it (and in which direction)."""
        self.edge_from = self._node_key[self.edge_start]
        self.edge_to = self._node_key[self.edge_end]
        n_nodes = int(max(self.edge_from.max(), self.edge_to.max())) + 1

        outgoing = [[] for _ in range(n_nodes)]
        for e in range(len(self.edge_from)):
            outgoing[self.edge_from[e]].append((e, True))
            if not self.edge_oneway[e]:
                outgoing[self.edge_to[e]].append((e, False))
        self.outgoing = outgoing
        self.n_nodes = n_nodes

    def _build_spatial_index(self):
        """Sample points along every edge so nearest-edge queries stay accurate."""
        sample_e, sample_n, sample_edge, sample_frac = [], [], [], []
        for e in range(len(self.edge_length)):
            length = self.edge_length[e]
            n = max(int(np.ceil(length / INDEX_SPACING_M)) + 1, 2)
            fractions = np.linspace(0.0, 1.0, n)
            a, b = self.edge_start[e], self.edge_end[e]
            sample_e.append(self.point_e[a] + fractions * (self.point_e[b] - self.point_e[a]))
            sample_n.append(self.point_n[a] + fractions * (self.point_n[b] - self.point_n[a]))
            sample_edge.append(np.full(n, e))
            sample_frac.append(fractions)

        self.sample_e = np.concatenate(sample_e)
        self.sample_n = np.concatenate(sample_n)
        self.sample_edge = np.concatenate(sample_edge)
        self.sample_frac = np.concatenate(sample_frac)
        self.tree = cKDTree(np.column_stack([self.sample_e, self.sample_n]))

    # ---------------------------------------------------------------------- queries
    def project_to_edge(self, edge_id: int, east: float, north: float):
        """Closest point on one edge: returns (east, north, distance, fraction)."""
        a, b = self.edge_start[edge_id], self.edge_end[edge_id]
        ax, ay = self.point_e[a], self.point_n[a]
        bx, by = self.point_e[b], self.point_n[b]
        dx, dy = bx - ax, by - ay
        length_sq = dx * dx + dy * dy
        if length_sq <= 0:
            return ax, ay, float(np.hypot(east - ax, north - ay)), 0.0
        t = float(np.clip(((east - ax) * dx + (north - ay) * dy) / length_sq, 0.0, 1.0))
        px, py = ax + t * dx, ay + t * dy
        return px, py, float(np.hypot(east - px, north - py)), t

    def candidates(self, east: float, north: float, radius_m: float,
                   max_candidates: int = 8, per_way: bool = True) -> list[Candidate]:
        """Nearby road candidates, projected, nearest first.

        With `per_way` (the default) at most one candidate is returned per OSM way. A way
        is split into one edge per vertex pair, so without this the candidate list fills
        with consecutive segments of the SAME road -- they sit metres apart, crowd out
        genuinely different roads, and split the matcher's posterior across what is
        really a single hypothesis.
        """
        hits = self.tree.query_ball_point([east, north], radius_m + INDEX_SPACING_M)
        if not hits:
            return []
        best: dict[int, Candidate] = {}
        seen_edges: set[int] = set()
        for sample in hits:
            edge_id = int(self.sample_edge[sample])
            if edge_id in seen_edges:
                continue
            seen_edges.add(edge_id)
            px, py, dist, frac = self.project_to_edge(edge_id, east, north)
            if dist > radius_m:
                continue
            key = int(self.edge_way[edge_id]) if per_way else edge_id
            existing = best.get(key)
            if existing is None or dist < existing.distance_m:
                best[key] = Candidate(edge_id, px, py, dist, frac,
                                      float(self.edge_bearing[edge_id]))
        found = sorted(best.values(), key=lambda c: c.distance_m)
        return found[:max_candidates]

    def route_distance(self, source: Candidate, targets: list[Candidate],
                       max_distance_m: float) -> dict[int, float]:
        """On-road distance from `source` to each target, by bounded Dijkstra.

        Returns edge_id -> distance for whatever is reachable within the budget. The
        bound matters: over a 1 s step the vehicle travels tens of metres, so exploring
        the whole network for every candidate pair would dominate runtime.
        """
        wanted = {c.edge_id: c for c in targets}
        results: dict[int, float] = {}

        # distance from the source position to the end of its own edge
        src_edge = source.edge_id
        remaining = float(self.edge_length[src_edge]) * (1.0 - source.fraction)

        if src_edge in wanted:
            target = wanted[src_edge]
            if target.fraction >= source.fraction:
                results[src_edge] = (target.fraction - source.fraction) * float(
                    self.edge_length[src_edge])

        start_node = int(self.edge_to[src_edge])
        best_node = {start_node: remaining}
        queue = [(remaining, start_node)]

        while queue:
            dist, node = heapq.heappop(queue)
            if dist > best_node.get(node, np.inf) or dist > max_distance_m:
                continue
            for edge_id, forward in self.outgoing[node]:
                length = float(self.edge_length[edge_id])
                if edge_id in wanted and edge_id not in results:
                    target = wanted[edge_id]
                    along = target.fraction if forward else (1.0 - target.fraction)
                    total = dist + along * length
                    if total <= max_distance_m:
                        results[edge_id] = total
                next_node = int(self.edge_to[edge_id] if forward else self.edge_from[edge_id])
                nd = dist + length
                if nd <= max_distance_m and nd < best_node.get(next_node, np.inf):
                    best_node[next_node] = nd
                    heapq.heappush(queue, (nd, next_node))

        return results

    def __repr__(self):
        return (f"RoadGraph(edges={len(self.edge_length)}, nodes={self.n_nodes}, "
                f"index_points={len(self.sample_e)})")
