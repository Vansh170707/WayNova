"""Vehicle-derived ground truth (V-*.csv), paired with phone features (S-*.csv).

Why this pairing exists
-----------------------
The field audit showed IO-VNBD's phone files cannot supply both halves of what the
project needs at once:

* Sessions whose phone GNSS is dense enough to evaluate a blackout (A4-A8, ~1 Hz)
  have a heavily attenuated accelerometer -- a GPS-measured 2.5 m/s^2 brake shows up
  as ~0.2 m/s^2 in the IMU.
* Sessions whose accelerometer is alive (S1-S4, M) log phone GNSS at only ~0.1 Hz,
  one fix every 9 s, which cannot support 10-120 s blackout evaluation.

The Synchronised release resolves this: V-*.csv is the SAME drive as S-*.csv, row for
row, recorded from the research vehicle at a true 10 Hz. Using it as the reference
gives dense ground truth on exactly the sessions whose phone IMU is usable.

Feature-contract discipline
---------------------------
Every column sourced from the vehicle is prefixed `ref_` and is ground truth ONLY --
never a model input. The production feature set stays smartphone-only
(loader.PRODUCTION_FEATURES), which is what the deployed Android app will have. Using
a higher-grade reference for supervision and evaluation while deploying phone-only
inputs is the arrangement the blueprint asks for in Section 5.2.
"""
from dataclasses import dataclass

import numpy as np
import pandas as pd

from neuronav.data.loader import _split_indices_on_time_reset, _mark_new_gnss, MIN_SEGMENT_ROWS
from neuronav.data.loader import _read_and_resolve_columns

# vehicle column -> canonical reference name, with the scale needed to reach SI units
VEHICLE_COLUMNS = {
    "Latitude (degrees)": ("ref_lat", 1.0),
    "Longitude (degrees)": ("ref_lon", 1.0),
    "Height (km)": ("ref_alt", 1000.0),
    "Velocity (km/hr)": ("ref_speed", 1.0 / 3.6),
    "Heading (degrees)": ("ref_heading_deg", 1.0),
    "Yaw Rate (deg/sec)": ("ref_yaw_rate_deg_s", 1.0),
    "Indicated Longitudinal Acceleration (g)": ("ref_a_long", 9.80665),
    "Indicated Lateral Acceleration (g)": ("ref_a_lat", 9.80665),
    "Wheel Speed Front Left (rad/sec)": ("ref_wheel_fl", 1.0),
    "Time Since Start of Day (seconds)": ("ref_time_s", 1.0),
}

REFERENCE_PREFIX = "ref_"


def load_vehicle_reference(path: str) -> pd.DataFrame:
    """Load a V-*.csv into canonical `ref_*` columns in SI units."""
    raw = pd.read_csv(path, encoding="latin-1", low_memory=False)
    raw.columns = [c.strip() for c in raw.columns]

    out = {}
    for source, (name, scale) in VEHICLE_COLUMNS.items():
        if source in raw.columns:
            out[name] = pd.to_numeric(raw[source], errors="coerce") * scale
        else:
            out[name] = np.nan
    return pd.DataFrame(out)


@dataclass
class SyncResult:
    """Outcome of aligning the phone and vehicle clocks for one segment."""
    lag_samples: int
    correlation: float
    background: float
    reliable: bool

    @property
    def lag_seconds(self) -> float:
        return self.lag_samples / 10.0


# Despite the "Synchronised" label, per-segment offsets of up to a few seconds occur,
# and at least one segment (S4_seg01) is not a genuine pair at all -- its optimal lag
# wanders between -32 s and +31 s across the drive. A shift is applied only when the
# correlation peak is both absolutely meaningful and far above the background.
#
# Both signals are smoothed before correlating: the phone gyro is noisy at 10 Hz, and
# on a correctly paired segment smoothing lifts the peak correlation from ~0.25 to
# ~0.7-0.9 while leaving the recovered lag unchanged.
MIN_SYNC_CORR = 0.35
MIN_PEAK_RATIO = 3.0
SYNC_SMOOTH_SAMPLES = 20  # 2 s at 10 Hz


def _lag_scores(p: np.ndarray, v: np.ndarray, ok: np.ndarray, lags) -> np.ndarray:
    scores = np.full(len(lags), np.nan)
    for i, lag in enumerate(lags):
        shifted = np.roll(p, lag)
        m = ok.copy()
        m[:abs(lag) + 1] = False
        m[len(m) - abs(lag) - 1:] = False
        if m.sum() < 1000:
            continue
        scores[i] = abs(np.corrcoef(shifted[m], v[m])[0, 1])
    return scores


def estimate_sync_lag(phone: pd.DataFrame, vehicle: pd.DataFrame,
                      max_lag_samples: int = 3000) -> SyncResult:
    """Rows by which the phone stream leads (+) or trails (-) the vehicle stream.

    Both platforms measure yaw rate independently, so cross-correlating them recovers the
    offset between the two logging clocks. Two details matter:

    * All three gyro channels are tried. Which one carries yaw differs per session and
      matches no column label, and projecting onto gravity does NOT pick it out (the
      gravity and gyro columns are not in a common axis order) -- so assuming a channel
      here silently discards well-paired sessions.
    * The search spans several minutes. Offsets are not small: one session pairs at
      +115 s, which a narrow window would miss entirely.

    Searched coarse-to-fine, since a full single-sample scan over minutes is wasteful.
    """
    from neuronav.calibration.signals import angular_rate

    smooth = lambda x: pd.Series(x).rolling(
        SYNC_SMOOTH_SAMPLES, center=True, min_periods=1).mean().to_numpy()
    omega = np.degrees(angular_rate(phone))
    v = smooth(vehicle["ref_yaw_rate_deg_s"].to_numpy())
    if not np.isfinite(v).any() or np.nanstd(v) < 0.5:
        # the vehicle's own yaw channel is flat -- nothing to correlate against
        return SyncResult(0, 0.0, 0.0, False)

    best = None
    for channel in range(omega.shape[1]):
        p = smooth(omega[:, channel])
        ok = np.isfinite(p) & np.isfinite(v)
        if ok.sum() < 1000:
            continue

        coarse = np.arange(-max_lag_samples, max_lag_samples + 1, 25)
        scores = _lag_scores(p, v, ok, coarse)
        if not np.any(np.isfinite(scores)):
            continue
        peak_i = int(np.nanargmax(scores))
        background = float(np.nanmedian(scores))

        fine = np.arange(coarse[peak_i] - 30, coarse[peak_i] + 31)
        fine_scores = _lag_scores(p, v, ok, fine)
        if not np.any(np.isfinite(fine_scores)):
            continue
        fine_i = int(np.nanargmax(fine_scores))
        peak = float(fine_scores[fine_i])

        if best is None or peak > best.correlation:
            best = SyncResult(int(fine[fine_i]), peak, background, False)

    if best is None:
        return SyncResult(0, 0.0, 0.0, False)
    best.reliable = (best.correlation >= MIN_SYNC_CORR
                     and best.correlation >= MIN_PEAK_RATIO * max(best.background, 1e-6))
    return best


def load_paired_segments(phone_path: str, vehicle_path: str, session_id: str,
                         correct_lag: bool = True) -> list[pd.DataFrame]:
    """Load phone features and vehicle ground truth as aligned per-drive segments."""
    phone_raw = _read_and_resolve_columns(phone_path)
    vehicle = load_vehicle_reference(vehicle_path)

    n = min(len(phone_raw), len(vehicle))
    if abs(len(phone_raw) - len(vehicle)) > 1:
        raise ValueError(
            f"{session_id}: phone has {len(phone_raw)} rows but vehicle has {len(vehicle)} -- "
            "these files are not the row-aligned pair this loader expects")
    phone_raw = phone_raw.iloc[:n].reset_index(drop=True)
    vehicle = vehicle.iloc[:n].reset_index(drop=True)

    t_ms = phone_raw["time_since_start_ms"].to_numpy()
    segments = []
    for seg_i, (a, b) in enumerate(_split_indices_on_time_reset(t_ms)):
        if b - a < MIN_SEGMENT_ROWS:
            continue
        p = phone_raw.iloc[a:b].reset_index(drop=True)
        v = vehicle.iloc[a:b].reset_index(drop=True)

        df = pd.DataFrame({
            "timestamp": p["time_since_start_ms"].astype(float) / 1000.0,
            "ax": p["accel_x"], "ay": p["accel_y"], "az": p["accel_z"],
            "gx": p["gyro_yaw"], "gy": p["gyro_pitch"], "gz": p["gyro_roll"],
            "mx": p["mag_x"], "my": p["mag_y"], "mz": p["mag_z"],
            "grav_x": p["gravity_x"], "grav_y": p["gravity_y"], "grav_z": p["gravity_z"],
            "lat": p["gps_lat"], "lon": p["gps_lon"], "alt": p["gps_alt"],
            "speed": p["gps_speed"], "accuracy": p["gps_accuracy_m"],
            "gps_heading_deg": p["gps_orientation_deg"],
        })
        for col in v.columns:
            df[col] = v[col]

        keep = df["timestamp"].diff().fillna(1) > 0
        df = df[keep].reset_index(drop=True)
        if len(df) < MIN_SEGMENT_ROWS:
            continue

        sync = estimate_sync_lag(df, df) if correct_lag else SyncResult(0, 0.0, 0.0, True)
        if correct_lag and sync.reliable and sync.lag_samples:
            # estimate_sync_lag maximises corr(roll(phone, lag), vehicle), i.e. phone index j
            # corresponds to vehicle index j+lag. Bringing the reference onto the phone's
            # index therefore needs shift(-lag): shift(+lag) doubles the offset instead,
            # which is invisible at lag ~5 but destroys a segment paired at +1157 samples.
            ref_cols = [c for c in df.columns if c.startswith(REFERENCE_PREFIX)]
            df[ref_cols] = df[ref_cols].shift(-sync.lag_samples)
            df = df.dropna(subset=["ref_lat", "ref_lon"]).reset_index(drop=True)
        if len(df) < MIN_SEGMENT_ROWS:
            continue
        df.attrs["sync"] = sync

        df["gnss_new"] = _mark_new_gnss(df)
        df["route_id"] = f"{session_id}_seg{seg_i:02d}"
        df["blackout_mask"] = False
        segments.append(df)

    return segments
