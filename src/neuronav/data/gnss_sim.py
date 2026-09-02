"""Simulated consumer-grade GNSS aiding, derived from the vehicle reference.

Rationale
---------
IO-VNBD's phone files log GNSS at ~0.1 Hz (one fix every 9 s), which is an artefact of
the logging app rather than a property of smartphone GNSS -- a real Android device
reports roughly 1 Hz. Driving the filter from the 0.1 Hz phone track would therefore
understate what the deployed system actually receives, and it leaves a 30 s blackout
with only about three reference points.

So aiding is synthesised from the 10 Hz vehicle reference: subsampled to a realistic
rate and corrupted with realistic horizontal noise. Evaluation is still against the
clean reference. This keeps the aiding honest (no better than a phone would get) while
making blackout windows precisely controllable.

The IMU features consumed by the estimator remain smartphone-only throughout.
"""
from dataclasses import dataclass

import numpy as np
import pandas as pd

from neuronav.utils.geo import latlon_to_enu


@dataclass
class GNSSSimConfig:
    rate_hz: float = 1.0
    horizontal_sigma_m: float = 5.0   # typical smartphone open-sky horizontal error
    speed_sigma_ms: float = 0.5
    reported_accuracy_m: float = 5.0  # what the receiver claims, fed to the filter
    # Real receivers derive bearing from Doppler, not from differencing positions:
    # at 1 Hz and 7 m/s, differencing 5 m-noisy fixes gives ~40 deg of course error,
    # whereas a Doppler bearing is good to a few degrees whenever the vehicle moves.
    bearing_sigma_deg: float = 3.0
    bearing_min_speed_ms: float = 2.0
    seed: int = 0


def reference_enu(df: pd.DataFrame, origin=None):
    """Vehicle-reference trajectory in local ENU, at the full 10 Hz rate."""
    if origin is None:
        origin = (df["ref_lat"].iloc[0], df["ref_lon"].iloc[0], df["ref_alt"].iloc[0])
    e, n, _ = latlon_to_enu(df["ref_lat"], df["ref_lon"], df["ref_alt"], *origin)
    return np.column_stack([np.asarray(e), np.asarray(n)]), origin


def add_simulated_gnss(df: pd.DataFrame, config: GNSSSimConfig = None, origin=None):
    """Attach `aid_*` columns holding simulated consumer GNSS fixes.

    Returns (df, origin). Rows without a fix carry aid_valid=False.
    """
    cfg = config or GNSSSimConfig()
    rng = np.random.default_rng(cfg.seed)

    out = df.copy()
    ref, origin = reference_enu(out, origin)
    t = out["timestamp"].to_numpy()

    # choose fix instants on a fixed cadence
    step = max(1, int(round((1.0 / cfg.rate_hz) / np.median(np.diff(t)))))
    idx = np.arange(0, len(out), step)
    valid = np.zeros(len(out), dtype=bool)
    valid[idx] = True
    valid &= np.isfinite(ref[:, 0]) & np.isfinite(ref[:, 1])

    noise = rng.normal(0.0, cfg.horizontal_sigma_m, size=(len(out), 2))
    out["aid_valid"] = valid
    out["aid_e"] = np.where(valid, ref[:, 0] + noise[:, 0], np.nan)
    out["aid_n"] = np.where(valid, ref[:, 1] + noise[:, 1], np.nan)

    ref_speed = out["ref_speed"].to_numpy()
    out["aid_speed"] = np.where(
        valid, ref_speed + rng.normal(0.0, cfg.speed_sigma_ms, size=len(out)), np.nan)
    out["aid_accuracy"] = np.where(valid, cfg.reported_accuracy_m, np.nan)

    # Doppler-style bearing, reported only while genuinely moving
    bearing = np.radians(out["ref_heading_deg"].to_numpy())
    bearing = bearing + np.radians(rng.normal(0.0, cfg.bearing_sigma_deg, size=len(out)))
    bearing_ok = valid & np.isfinite(bearing) & (ref_speed >= cfg.bearing_min_speed_ms)
    out["aid_bearing"] = np.where(bearing_ok, bearing, np.nan)
    out["aid_bearing_valid"] = bearing_ok
    out["aid_bearing_sigma"] = np.radians(cfg.bearing_sigma_deg)

    out["ref_e"] = ref[:, 0]
    out["ref_n"] = ref[:, 1]
    return out, origin


def apply_blackout(df: pd.DataFrame, t_start: float, duration: float) -> pd.DataFrame:
    """Mark a GNSS outage: aiding is withheld inside the window, truth is untouched."""
    out = df.copy()
    t = out["timestamp"].to_numpy()
    window = (t >= t_start) & (t <= t_start + duration)
    out["blackout_mask"] = window
    out.loc[window, "aid_valid"] = False
    out.loc[window, "aid_bearing_valid"] = False
    return out


def pick_blackout_windows(df: pd.DataFrame, duration: float, n_windows: int,
                          min_mean_speed: float = 5.0, seed: int = 0) -> list[float]:
    """Choose blackout start times that contain genuine driving, not parked time.

    A window spent stationary would report a flatteringly small drift ratio over a
    near-zero distance, so candidate windows are required to be actually moving.
    """
    rng = np.random.default_rng(seed)
    t = df["timestamp"].to_numpy()
    speed = df["ref_speed"].to_numpy()
    t0, t1 = t[0], t[-1] - duration
    if t1 <= t0:
        return []

    candidates = []
    for start in rng.uniform(t0, t1, size=n_windows * 40):
        m = (t >= start) & (t <= start + duration)
        if m.sum() < 10:
            continue
        seg_speed = speed[m]
        if np.isfinite(seg_speed).mean() < 0.9:
            continue
        if np.nanmean(seg_speed) < min_mean_speed:
            continue
        candidates.append(float(start))
        if len(candidates) >= n_windows:
            break
    return candidates
