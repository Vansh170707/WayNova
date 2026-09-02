import numpy as np
import pandas as pd
import pytest

from neuronav.calibration.alignment import Alignment
from neuronav.evaluation.own_drive import (calibration_convergence, centripetal_metrics,
                                           with_aiding_columns)
from scripts.analyze_own_drive import analyse


def physics_drive(duration_s=720.0, rate_hz=10.0):
    """A phone log where both longitudinal and centripetal physics are known exactly."""
    t = np.arange(0.0, duration_s, 1.0 / rate_hz)
    speed = 12.0 + 2.0 * np.sin(2 * np.pi * t / 70.0) + np.sin(2 * np.pi * t / 31.0)
    a_long = np.gradient(speed, t)
    yaw_rate = (0.13 * np.sin(2 * np.pi * t / 23.0) +
                0.05 * np.sin(2 * np.pi * t / 11.0))
    a_lat = speed * yaw_rate

    forward_angle = 0.42
    c, s = np.cos(forward_angle), np.sin(forward_angle)
    h1 = a_long * c - a_lat * s
    h2 = a_long * s + a_lat * c
    gyro_bias = 0.0015

    fresh = np.zeros(len(t), dtype=bool)
    fresh[:: int(rate_hz)] = True
    heading = np.cumsum(yaw_rate) / rate_hz
    bearing = np.degrees(heading) % 360.0
    # Logger columns are sample-and-hold, while gnss_new says which rows are measurements.
    fix_index = np.maximum.accumulate(np.where(fresh, np.arange(len(t)), 0))

    frame = pd.DataFrame({
        "timestamp": t,
        "ax": h1,
        "ay": h2,
        "az": np.full(len(t), 9.80665),
        "gx": 0.03 * np.sin(0.17 * t),
        "gy": 0.02 * np.cos(0.11 * t),
        "gz": yaw_rate + gyro_bias,
        "grav_x": np.zeros(len(t)),
        "grav_y": np.zeros(len(t)),
        "grav_z": np.full(len(t), 9.80665),
        "lat": np.full(len(t), 28.6),
        "lon": np.full(len(t), 77.2),
        "alt": np.full(len(t), 220.0),
        "speed": speed[fix_index],
        "accuracy": np.full(len(t), 3.0),
        "gps_heading_deg": bearing[fix_index],
        "gps_heading_acc_deg": np.full(len(t), 2.0),
        "gnss_new": fresh,
        "route_id": "physics_seg00",
        "blackout_mask": False,
    })
    alignment = Alignment(
        forward_angle_rad=forward_angle,
        gyro_bias_rad_s=gyro_bias,
        n_samples=len(t),
        forward_accel_corr=1.0,
        forward_accel_scale=1.0,
        yaw_channel=2,
        yaw_scale=1.0,
        yaw_corr=1.0,
    )
    return frame, alignment


def test_aiding_contract_uses_only_fresh_fixes():
    drive, _ = physics_drive(duration_s=20.0)
    aided = with_aiding_columns(drive)

    assert aided["aid_valid"].sum() == drive["gnss_new"].sum()
    assert aided.loc[~drive["gnss_new"], "aid_speed"].isna().all()
    assert aided["aid_bearing_valid"].equals(aided["aid_valid"])


def test_centripetal_observable_is_scored_on_later_turns():
    drive, alignment = physics_drive()
    result = centripetal_metrics(drive, alignment)

    assert result["phone_corr"] > 0.999
    assert result["lateral_gain"] == pytest.approx(1.0, abs=0.01)
    assert result["implied_speed_rmse_ms"] < 0.1
    assert result["gain_fit_samples"] > 100
    assert result["heldout_turn_samples"] > 100
    assert result["promising_candidate"]


def test_calibration_convergence_keeps_prefix_evidence():
    drive, _ = physics_drive()
    table, final = calibration_convergence(drive, prefix_minutes=[5.0, 10.0])

    assert list(table["prefix_min"]) == [5.0, 10.0, 11.998]
    assert table.iloc[-1]["status"] == "ok"
    assert table.iloc[-1]["forward_angle_error_deg"] < 1e-6
    assert final.yaw_channel == 2
    assert abs(final.forward_angle_rad - 0.42) < 0.1


def test_analysis_cli_writes_an_evidence_package(tmp_path):
    drive, _ = physics_drive()
    raw = pd.DataFrame({
        "timestamp_ms": drive["timestamp"] * 1000.0,
        "ax": drive["ax"], "ay": drive["ay"], "az": drive["az"],
        "gx": drive["gx"], "gy": drive["gy"], "gz": drive["gz"],
        "mx": 0.0, "my": 0.0, "mz": 0.0,
        "grav_x": drive["grav_x"], "grav_y": drive["grav_y"],
        "grav_z": drive["grav_z"],
        "lat": drive["lat"], "lon": drive["lon"], "alt": drive["alt"],
        "speed": drive["speed"], "accuracy": drive["accuracy"],
        "bearing": drive["gps_heading_deg"],
        "bearing_acc": drive["gps_heading_acc_deg"],
        "gps_fresh": drive["gnss_new"].astype(int),
    })
    path = tmp_path / "field.csv"
    raw.to_csv(path, index=False)

    analyse(path, tmp_path / "evidence")

    evidence = tmp_path / "evidence" / "field_seg00"
    assert (evidence / "quality.csv").is_file()
    assert (evidence / "calibration_convergence.csv").is_file()
    assert (evidence / "calibration_convergence.png").stat().st_size > 10_000
    report = (evidence / "analysis.json").read_text()
    assert '"promising_candidate": true' in report
