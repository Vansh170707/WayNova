"""The own-drive loader must accept exactly what the Android logger writes.

Written against the app's CSV header rather than against a captured file, so a mismatch
between the two shows up here instead of after a wasted hour of driving.
"""
import numpy as np
import pandas as pd
import pytest

from neuronav.calibration.signals import heading_rate
from neuronav.data.own_drive import (OPTIONAL_COLUMNS, OWN_DRIVE_COLUMNS, load_own_drive,
                                     summarise)

# Kept byte-identical to SensorLogger.HEADER in mobile/.../SensorLogger.kt
APP_HEADER = ("timestamp_ms,ax,ay,az,gx,gy,gz,mx,my,mz,grav_x,grav_y,grav_z,"
              "lat,lon,alt,speed,accuracy,bearing,bearing_acc,gps_fresh")


def synthetic_drive(path, n=3000, rate_hz=100.0, gap_at=None):
    """A plausible logged drive: 100 Hz IMU, 1 Hz GNSS, phone flat."""
    rng = np.random.default_rng(0)
    t_ms = np.arange(n) / rate_hz * 1000.0
    if gap_at is not None:
        t_ms[gap_at:] += 30_000.0          # a 30 s hole, as if the app was backgrounded

    fresh = np.zeros(n, dtype=int)
    fresh[:: int(rate_hz)] = 1             # a fix every second

    frame = pd.DataFrame({
        "timestamp_ms": t_ms,
        "ax": rng.normal(0, 1.2, n), "ay": rng.normal(0, 1.2, n),
        "az": rng.normal(9.81, 0.6, n),
        "gx": rng.normal(0, 0.02, n), "gy": rng.normal(0, 0.05, n),
        "gz": rng.normal(0, 0.02, n),
        "mx": rng.normal(-6, 1, n), "my": rng.normal(-26, 1, n), "mz": rng.normal(30, 1, n),
        "grav_x": np.zeros(n), "grav_y": np.zeros(n), "grav_z": np.full(n, 9.80665),
        "lat": 52.4 + np.arange(n) * 1e-6, "lon": -1.5 + np.arange(n) * 1e-6,
        "alt": np.full(n, 110.0), "speed": np.abs(rng.normal(12, 2, n)),
        "accuracy": np.full(n, 5.0), "bearing": np.full(n, 45.0),
        "bearing_acc": np.full(n, 3.0),
        "gps_fresh": fresh,
    })
    frame.to_csv(path, index=False)
    return path


def test_header_matches_the_android_logger():
    """If the app's header drifts from the loader, a collected drive is unreadable."""
    expected = OWN_DRIVE_COLUMNS[:-1] + OPTIONAL_COLUMNS + OWN_DRIVE_COLUMNS[-1:]
    assert APP_HEADER.split(",") == expected


def test_loads_legacy_log_without_bearing_accuracy(tmp_path):
    """Logs collected before bearing_acc was added remain usable with a safe fallback."""
    path = synthetic_drive(tmp_path / "legacy.csv")
    frame = pd.read_csv(path).drop(columns="bearing_acc")
    frame.to_csv(path, index=False)

    seg = load_own_drive(str(path))[0]
    assert seg["gps_heading_acc_deg"].isna().all()


def test_loads_a_synthetic_drive(tmp_path):
    path = synthetic_drive(tmp_path / "drive.csv")
    segments = load_own_drive(str(path))
    assert len(segments) == 1

    seg = segments[0]
    assert np.all(np.diff(seg["timestamp"].to_numpy()) > 0)
    # gnss_new comes from the app's flag, not inferred from value changes
    assert seg["gnss_new"].sum() == pytest.approx(30, abs=1)
    assert not seg["gnss_new"].all(), "every row flagged as a fresh fix"


def test_recording_gap_splits_segments(tmp_path):
    """A hole in the IMU stream must break the segment, not be interpolated across."""
    path = synthetic_drive(tmp_path / "gap.csv", n=4000, gap_at=2000)
    segments = load_own_drive(str(path), max_gap_s=5.0)
    assert len(segments) == 2
    for seg in segments:
        assert np.all(np.diff(seg["timestamp"].to_numpy()) < 5.0)


def test_output_schema_matches_the_iovnbd_pipeline(tmp_path):
    """Own drives must flow through the existing calibration code unchanged."""
    path = synthetic_drive(tmp_path / "drive.csv")
    seg = load_own_drive(str(path))[0]
    required = {"timestamp", "ax", "ay", "az", "gx", "gy", "gz",
                "grav_x", "grav_y", "grav_z", "lat", "lon", "speed",
                "accuracy", "gps_heading_deg", "gnss_new", "route_id", "blackout_mask"}
    assert required <= set(seg.columns)
    # a downstream signal computes without special-casing
    assert np.isfinite(heading_rate(seg)).all()


def test_summary_reports_the_audit_metrics(tmp_path):
    path = synthetic_drive(tmp_path / "drive.csv")
    report = summarise(load_own_drive(str(path)))
    assert {"imu_hz", "gnss_hz", "accel_p99", "nan_in_features"} <= set(report.columns)
    assert report["imu_hz"].iloc[0] == pytest.approx(100.0, rel=0.05)
    assert report["gnss_hz"].iloc[0] == pytest.approx(1.0, abs=0.1)
    assert report["nan_in_features"].iloc[0] == 0
