import numpy as np
import pytest

from scripts.train_tcn import select_validation_route


def test_validation_route_is_representative_not_lexicographically_last():
    # Identical speed distributions isolate the size term. The old name-based rule would
    # select z_tiny; the representative policy should retain the median-sized route.
    route = np.array(
        ["a_long"] * 200 + ["m_middle"] * 100 + ["z_tiny"] * 20)
    speed = np.concatenate([
        np.linspace(0.0, 20.0, 200),
        np.linspace(0.0, 20.0, 100),
        np.linspace(0.0, 20.0, 20),
    ])

    assert select_validation_route(route, speed) == "m_middle"


def test_validation_route_can_be_overridden_explicitly():
    route = np.array(["route_a"] * 20 + ["route_b"] * 20)
    speed = np.arange(40, dtype=float)

    assert select_validation_route(route, speed, "route_b") == "route_b"


def test_validation_route_rejects_unknown_override():
    route = np.array(["route_a"] * 20 + ["route_b"] * 20)
    speed = np.arange(40, dtype=float)

    with pytest.raises(ValueError, match="not available"):
        select_validation_route(route, speed, "missing")
