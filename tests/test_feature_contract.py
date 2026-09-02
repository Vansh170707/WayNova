"""Guard the smartphone-only feature contract and the audit's hard-won conventions.

The blueprint's risk register calls out "training accidentally uses vehicle-only channels"
as a High severity risk whose mitigation is a schema check in the loader plus a CI test.
"""
from pathlib import Path

import numpy as np
import pytest

from neuronav.calibration.alignment import estimate_alignment
from neuronav.data.gnss_sim import add_simulated_gnss
from neuronav.data.loader import OPTIONAL_FEATURES, PRODUCTION_FEATURES, load_iovnbd_segments
from neuronav.data.reference import REFERENCE_PREFIX, load_paired_segments

REPO_ROOT = Path(__file__).resolve().parents[1]
S1 = REPO_ROOT / "data/raw/IO-VNBD/S_DriverA/S-S1.csv"
V1 = REPO_ROOT / "data/raw/IO-VNBD/V_DriverA/V-S1.csv"

pytestmark = pytest.mark.skipif(
    not (S1.exists() and V1.exists()),
    reason="IO-VNBD data not downloaded; see scripts/audit_dataset.py")


@pytest.fixture(scope="module")
def segment():
    seg = max(load_paired_segments(str(S1), str(V1), "S1"), key=len)
    seg, _ = add_simulated_gnss(seg)
    return seg


def test_production_features_are_phone_only(segment):
    """No vehicle-derived column may appear in the production feature set."""
    for name in PRODUCTION_FEATURES + OPTIONAL_FEATURES:
        assert not name.startswith(REFERENCE_PREFIX), f"{name} is vehicle-derived"


def test_reference_columns_are_namespaced(segment):
    """Every vehicle channel stays behind the ref_ prefix so leakage is greppable."""
    vehicle_ish = {"ref_lat", "ref_lon", "ref_speed", "ref_heading_deg", "ref_yaw_rate_deg_s"}
    assert vehicle_ish <= set(segment.columns)
    for col in vehicle_ish:
        assert col.startswith(REFERENCE_PREFIX)


def test_phone_loader_never_reads_vehicle_file():
    """The phone-only loader must produce no ref_* columns at all."""
    seg = max(load_iovnbd_segments(str(S1), "S1"), key=len)
    assert not [c for c in seg.columns if c.startswith(REFERENCE_PREFIX)]
    assert set(PRODUCTION_FEATURES) <= set(seg.columns)


def test_segments_are_monotonic_in_time(segment):
    """Time resets must have been split, not sorted -- see audit finding 1."""
    t = segment["timestamp"].to_numpy()
    assert np.all(np.diff(t) > 0)


def test_speed_column_is_metres_per_second(segment):
    """Header says Kmh but the values are m/s; a 3.6x error would show up here."""
    speed = segment["speed"].to_numpy()
    assert np.nanmax(speed) < 60.0, "speed looks like km/h, not m/s"


def test_yaw_channel_matches_vehicle_reference(segment):
    """The data-driven yaw channel should reproduce the vehicle's yaw rate.

    The vehicle channel is left-positive while compass heading is right-positive, so the
    expected relationship is a slope near -1 (audit finding 6).
    """
    align = estimate_alignment(segment)
    w = np.degrees(align.corrected_heading_rate(segment))
    ref = segment["ref_yaw_rate_deg_s"].to_numpy()
    moving = segment["ref_speed"].to_numpy() > 3
    ok = np.isfinite(w) & np.isfinite(ref) & moving

    slope = np.polyfit(w[ok], ref[ok], 1)[0]
    assert -1.25 < slope < -0.75, f"yaw scale off: slope={slope:.3f}"
    assert abs(np.corrcoef(w[ok], ref[ok])[0, 1]) > 0.5


def test_simulated_bearing_beats_differenced_positions(segment):
    """Guard the fix that stopped the filter diverging: bearing must not be differenced."""
    aid = segment["aid_bearing_valid"].to_numpy()
    bearing = segment["aid_bearing"].to_numpy()[aid]
    truth = np.radians(segment["ref_heading_deg"].to_numpy()[aid])
    err = np.degrees(np.abs(np.arctan2(np.sin(bearing - truth), np.cos(bearing - truth))))
    assert np.nanmedian(err) < 10.0, "simulated bearing is too noisy to aid heading"
