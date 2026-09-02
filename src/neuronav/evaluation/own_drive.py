"""Evidence extraction for drives recorded by the Android app.

Own-drive GNSS is not survey-grade ground truth, but it is exactly the aided signal the
deployed calibration sees. These helpers answer the two questions tomorrow's drive exists
to answer: when the online calibration becomes stable, and whether an unfiltered phone
recovers the centripetal speed observable that IO-VNBD's filtered accelerometer destroyed.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from neuronav.calibration.alignment import Alignment, estimate_alignment
from neuronav.calibration.signals import horizontal_acceleration


def with_aiding_columns(df: pd.DataFrame) -> pd.DataFrame:
    """Translate the logger schema into the aiding contract used by calibration.

    Only rows marked ``gnss_new`` become measurements. The other rows contain held values
    for convenient logging and must never be mistaken for independent fixes.
    """
    required = {"gnss_new", "speed", "gps_heading_deg", "lat", "lon"}
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"own-drive segment is missing {sorted(missing)}")

    out = df.copy()
    fresh = out["gnss_new"].fillna(False).astype(bool).to_numpy()
    speed = out["speed"].to_numpy(dtype=float)
    position_ok = (np.isfinite(out["lat"].to_numpy(dtype=float)) &
                   np.isfinite(out["lon"].to_numpy(dtype=float)))
    aid_valid = fresh & position_ok & np.isfinite(speed)
    bearing_deg = out["gps_heading_deg"].to_numpy(dtype=float)
    bearing_valid = aid_valid & np.isfinite(bearing_deg)

    out["aid_valid"] = aid_valid
    out["aid_speed"] = np.where(aid_valid, speed, np.nan)
    out["aid_bearing_valid"] = bearing_valid
    out["aid_bearing"] = np.where(bearing_valid, np.radians(bearing_deg), np.nan)
    if "gps_heading_acc_deg" in out:
        acc = out["gps_heading_acc_deg"].to_numpy(dtype=float)
        out["aid_bearing_sigma"] = np.where(
            bearing_valid & np.isfinite(acc), np.radians(acc), np.nan)
    return out


def wrap_angle(angle: float) -> float:
    return float((angle + np.pi) % (2 * np.pi) - np.pi)


def calibration_convergence(
    df: pd.DataFrame,
    prefix_minutes: list[float] | tuple[float, ...] | None = None,
) -> tuple[pd.DataFrame, Alignment]:
    """Fit calibration on increasing causal prefixes and compare with the full drive.

    Failures are rows in the returned table rather than exceptions. Early prefixes are
    expected to fail when they have too few bearing baselines; preserving those failures
    measures time-to-first-solution instead of silently plotting only successful fits.
    The full-drive fit must succeed because it supplies the comparison reference.
    """
    aided = with_aiding_columns(df)
    t = aided["timestamp"].to_numpy(dtype=float)
    if len(t) < 2:
        raise ValueError("own-drive segment has fewer than two samples")
    duration_min = float((t[-1] - t[0]) / 60.0)
    if duration_min < 5.0:
        raise ValueError(f"drive is only {duration_min:.1f} min; need at least 5 min")

    try:
        reference = estimate_alignment(aided, use_reference=False, robust=True)
    except ValueError as exc:
        raise ValueError(f"full-drive calibration failed: {exc}") from exc

    if prefix_minutes is None:
        prefixes = list(np.arange(5.0, duration_min, 5.0)) + [duration_min]
    else:
        prefixes = [float(v) for v in prefix_minutes if 0 < float(v) <= duration_min]
        prefixes.append(duration_min)
    prefixes = sorted(set(round(v, 6) for v in prefixes))

    rows = []
    for minutes in prefixes:
        stop = t[0] + minutes * 60.0
        # Rounding the displayed duration must not drop the final sample from the
        # full-drive row, or its nominal zero error becomes a tiny numerical mismatch.
        chunk = (aided.copy() if abs(minutes - duration_min) < 1e-4 else
                 aided.loc[aided["timestamp"] <= stop].reset_index(drop=True))
        base = {
            "prefix_min": round(minutes, 3),
            "n_rows": int(len(chunk)),
            "gnss_fixes": int(chunk["aid_valid"].sum()),
        }
        try:
            fit = estimate_alignment(chunk, use_reference=False, robust=True)
            angle_error = abs(wrap_angle(fit.forward_angle_rad -
                                         reference.forward_angle_rad))
            rows.append({
                **base,
                "status": "ok",
                "error": "",
                "forward_angle_deg": float(np.degrees(fit.forward_angle_rad)),
                "forward_angle_error_deg": float(np.degrees(angle_error)),
                "forward_accel_scale": fit.forward_accel_scale,
                "forward_accel_corr": fit.forward_accel_corr,
                "yaw_channel": fit.yaw_channel,
                "yaw_scale": fit.yaw_scale,
                "yaw_scale_error_pct": (
                    100.0 * (fit.yaw_scale - reference.yaw_scale) /
                    max(abs(reference.yaw_scale), 1e-9)),
                "gyro_bias_rad_s": fit.gyro_bias_rad_s,
                "gyro_bias_error_deg_120s": float(np.degrees(
                    abs(fit.gyro_bias_rad_s - reference.gyro_bias_rad_s) * 120.0)),
                "yaw_corr": fit.yaw_corr,
                "alignment_samples": fit.n_samples,
            })
        except (ValueError, np.linalg.LinAlgError) as exc:
            rows.append({
                **base,
                "status": "failed",
                "error": str(exc),
                "forward_angle_deg": np.nan,
                "forward_angle_error_deg": np.nan,
                "forward_accel_scale": np.nan,
                "forward_accel_corr": np.nan,
                "yaw_channel": np.nan,
                "yaw_scale": np.nan,
                "yaw_scale_error_pct": np.nan,
                "gyro_bias_rad_s": np.nan,
                "gyro_bias_error_deg_120s": np.nan,
                "yaw_corr": np.nan,
                "alignment_samples": 0,
            })
    return pd.DataFrame(rows), reference


def centripetal_metrics(
    df: pd.DataFrame,
    alignment: Alignment,
    min_speed_ms: float = 3.0,
    min_turn_rad_s: float = 0.10,
) -> dict:
    """Test ``a_lat = v * omega`` without fitting and scoring on the same samples.

    Lateral gain/offset are fitted on the first half of the drive. Implied-speed RMSE is
    measured on turning samples in the second half. This makes the result a small temporal
    holdout rather than a circular fit-quality number.
    """
    aided = with_aiding_columns(df)
    t = aided["timestamp"].to_numpy(dtype=float)
    fixes = aided["aid_valid"].to_numpy(dtype=bool)
    if fixes.sum() < 20:
        raise ValueError("not enough fresh GNSS speed fixes for centripetal analysis")
    speed = np.interp(t, t[fixes], aided.loc[fixes, "aid_speed"].to_numpy(dtype=float))

    h = horizontal_acceleration(aided)
    c, s = np.cos(alignment.forward_angle_rad), np.sin(alignment.forward_angle_rad)
    lateral_accel = -h[:, 0] * s + h[:, 1] * c
    yaw_rate = alignment.corrected_heading_rate(aided)
    expected_lateral = speed * yaw_rate

    finite = (np.isfinite(speed) & np.isfinite(lateral_accel) &
              np.isfinite(yaw_rate) & (speed > min_speed_ms))
    if finite.sum() < 100:
        raise ValueError("not enough moving samples for centripetal analysis")
    corr = float(np.corrcoef(expected_lateral[finite], lateral_accel[finite])[0, 1])

    midpoint = 0.5 * (t[0] + t[-1])
    fit_mask = finite & (t < midpoint) & (np.abs(yaw_rate) > 0.03)
    test_mask = finite & (t >= midpoint) & (np.abs(yaw_rate) > min_turn_rad_s)
    if fit_mask.sum() < 50 or test_mask.sum() < 50:
        raise ValueError(
            f"not enough turns: {fit_mask.sum()} gain-fit and {test_mask.sum()} test samples")

    gain, offset = np.polyfit(expected_lateral[fit_mask], lateral_accel[fit_mask], 1)
    if not np.isfinite(gain) or abs(gain) < 0.05:
        raise ValueError(f"lateral gain is degenerate ({gain})")
    implied_speed = ((lateral_accel[test_mask] - offset) /
                     (gain * yaw_rate[test_mask]))
    residual = implied_speed - speed[test_mask]
    rmse = float(np.sqrt(np.mean(np.clip(residual, -50.0, 50.0) ** 2)))
    mae = float(np.mean(np.abs(residual)))
    turning_duty = float(np.mean(np.abs(yaw_rate[finite]) > min_turn_rad_s))

    # This marks a candidate for navigation experiments, not a production acceptance.
    promising = bool(corr >= 0.70 and rmse <= 5.0 and abs(gain) >= 0.20)
    return {
        "phone_corr": corr,
        "lateral_gain": float(gain),
        "lateral_offset_ms2": float(offset),
        "implied_speed_rmse_ms": rmse,
        "implied_speed_mae_ms": mae,
        "turning_duty_pct": 100.0 * turning_duty,
        "gain_fit_samples": int(fit_mask.sum()),
        "heldout_turn_samples": int(test_mask.sum()),
        "promising_candidate": promising,
    }
