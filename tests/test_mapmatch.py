"""Guards for the road graph and map aiding.

These pin down the two bugs that made map matching look worse than it is, plus the
safety condition that keeps a wrong-road decision from corrupting the estimate.
"""
from pathlib import Path

import numpy as np
import pytest

from neuronav.fusion.es_ekf import IDX_PE, IDX_PN, IDX_PSI, PlanarESEKF
from neuronav.mapmatch.graph import AMBIGUOUS_CLASSES, RoadGraph
from neuronav.mapmatch.online import OnlineMapConfig, OnlineMapMatcher

REPO_ROOT = Path(__file__).resolve().parents[1]
OSM = REPO_ROOT / "data/external/osm/roads_S1_seg00.npz"

pytestmark = pytest.mark.skipif(
    not OSM.exists(), reason="OSM cache absent; run scripts/download_osm.py")


@pytest.fixture(scope="module")
def graph():
    return RoadGraph(str(OSM))


def test_ambiguous_classes_are_excluded_by_default(graph):
    assert not set(np.unique(graph.edge_class)) & set(AMBIGUOUS_CLASSES)
    unfiltered = RoadGraph(str(OSM), exclude_classes=())
    assert len(unfiltered.edge_length) > len(graph.edge_length)


def test_candidates_are_deduplicated_per_way(graph):
    """A way is one edge per vertex pair, so per-edge candidates return the same road
    many times, crowding out real alternatives and splitting the matcher's posterior."""
    i = len(graph.point_e) // 2
    e, n = float(graph.point_e[i]), float(graph.point_n[i])

    per_way = graph.candidates(e, n, 80.0, 8, per_way=True)
    per_edge = graph.candidates(e, n, 80.0, 8, per_way=False)

    ways = [int(graph.edge_way[c.edge_id]) for c in per_way]
    assert len(ways) == len(set(ways)), "per-way search returned a way twice"
    assert len(set(ways)) >= len(set(int(graph.edge_way[c.edge_id]) for c in per_edge))


def test_candidates_are_sorted_and_within_radius(graph):
    i = len(graph.point_e) // 3
    e, n = float(graph.point_e[i]) + 5.0, float(graph.point_n[i]) + 5.0
    found = graph.candidates(e, n, 60.0, 6)
    assert found
    assert all(c.distance_m <= 60.0 for c in found)
    assert found == sorted(found, key=lambda c: c.distance_m)


def test_route_distance_is_non_negative_and_bounded(graph):
    i = len(graph.point_e) // 2
    found = graph.candidates(float(graph.point_e[i]), float(graph.point_n[i]), 60.0, 5)
    if len(found) < 2:
        pytest.skip("not enough candidates here")
    reachable = graph.route_distance(found[0], found, max_distance_m=300.0)
    assert all(0.0 <= d <= 300.0 for d in reachable.values())


def test_map_position_update_is_anisotropic():
    """The map observes cross-track position, not along-track progress.

    A snap that also moved the estimate along the road would inject error the map
    cannot actually observe.
    """
    ekf = PlanarESEKF()
    ekf.initialize(east=0.0, north=0.0, heading=0.0, speed=10.0)
    ekf.state.P = np.diag([100.0, 100.0, 0.05, 1.0, 1e-4, 0.0])

    # road runs due North through a point 30 m North and 10 m East of the estimate
    ekf.update_map_position(east=10.0, north=30.0, bearing_rad=0.0,
                            sigma_cross=3.0, sigma_along=500.0)

    # the across-road (East) component should move; the along-road (North) barely
    assert ekf.state.x[IDX_PE] > 5.0, "cross-track correction was not applied"
    assert abs(ekf.state.x[IDX_PN]) < 5.0, "along-track was corrected but is unobservable"


def test_map_heading_update_resolves_direction_ambiguity():
    """A road carries traffic both ways: the constraint is on the axis, not direction."""
    ekf = PlanarESEKF()
    ekf.initialize(east=0.0, north=0.0, heading=np.pi, speed=10.0)  # heading South
    ekf.state.P = np.diag([25.0, 25.0, 0.25, 1.0, 1e-4, 0.0])
    ekf.update_map_heading(bearing_rad=0.0, sigma_rad=0.05)         # road drawn N-S
    # should snap towards South (pi), not flip the vehicle around to North
    assert abs(abs(ekf.state.heading) - np.pi) < 0.4


def test_aiding_is_gated_on_filter_uncertainty(graph):
    """Above the road-discrimination margin the map must contribute nothing.

    The correct road is only identifiable while position error stays inside the spacing
    between distinct roads (~17 m here); beyond that a confident wrong-road match drags
    the solution away, which is worse than not correcting at all.
    """
    cfg = OnlineMapConfig(max_sigma_for_aiding_m=18.0, min_lock_steps=1)
    matcher = OnlineMapMatcher(graph, cfg)
    i = len(graph.point_e) // 2
    e, n = float(graph.point_e[i]), float(graph.point_n[i])

    ekf = PlanarESEKF()
    ekf.initialize(east=e, north=n, heading=float(graph.edge_bearing[0]), speed=12.0)
    before = ekf.state.x.copy()

    for _ in range(8):
        applied = matcher.step(ekf, e, n, ekf.state.heading, sigma=90.0,
                               speed=12.0, distance=12.0)
        assert not applied, "map was applied despite sigma far above the gate"
    assert np.allclose(ekf.state.x, before)
