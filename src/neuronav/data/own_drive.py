"""Loader for drives recorded by the Android logger in `mobile/`.

Kept deliberately close to the IO-VNBD loader's output schema so own-drive data flows
through the existing calibration, fusion and evaluation code unchanged.

Two differences from IO-VNBD, both deliberate:

* `gps_fresh` is recorded by the app, so GNSS updates are known exactly rather than being
  inferred from value changes. IO-VNBD needs that inference because its GNSS is
  sample-and-hold with no flag.
* There is no vehicle reference. Own drives are for robustness, cross-device checks and
  training augmentation; the 10 Hz paired sessions remain the source of ground truth
  unless a higher-grade receiver is added.
"""
import numpy as np
import pandas as pd

from neuronav.data.loader import MIN_SEGMENT_ROWS, PRODUCTION_FEATURES

OWN_DRIVE_COLUMNS = [
    "timestamp_ms", "ax", "ay", "az", "gx", "gy", "gz", "mx", "my", "mz",
    "grav_x", "grav_y", "grav_z", "lat", "lon", "alt", "speed", "accuracy",
    "bearing", "gps_fresh",
]
# Added after the first logs were recorded, so it is read when present and left as NaN
# otherwise; the estimator falls back to its configured course noise in that case.
OPTIONAL_COLUMNS = ["bearing_acc"]


def load_own_drive(path: str, route_id: str = None, max_gap_s: float = 5.0) -> list[pd.DataFrame]:
    """Load one logger CSV, split on recording gaps, and return usable segments.

    A gap longer than `max_gap_s` (app backgrounded, sensors stalled) is treated as a
    segment boundary rather than interpolated across -- propagating a filter through a
    hole in the IMU stream produces confident nonsense.
    """
    raw = pd.read_csv(path)
    missing = [c for c in OWN_DRIVE_COLUMNS if c not in raw.columns]
    if missing:
        raise ValueError(f"{path}: missing columns {missing}")

    for column in OWN_DRIVE_COLUMNS:
        raw[column] = pd.to_numeric(raw[column], errors="coerce")
    for column in OPTIONAL_COLUMNS:
        raw[column] = (pd.to_numeric(raw[column], errors="coerce")
                       if column in raw.columns else np.nan)

    raw = raw.dropna(subset=["timestamp_ms"]).reset_index(drop=True)
    raw = raw[raw["timestamp_ms"].diff().fillna(1) > 0].reset_index(drop=True)

    t = raw["timestamp_ms"].to_numpy() / 1000.0
    boundaries = np.flatnonzero(np.diff(t) > max_gap_s) + 1
    bounds = np.concatenate([[0], boundaries, [len(raw)]])

    name = route_id or path.split("/")[-1].replace(".csv", "")
    segments = []
    for i, (a, b) in enumerate(zip(bounds[:-1], bounds[1:])):
        chunk = raw.iloc[a:b].reset_index(drop=True)
        if len(chunk) < MIN_SEGMENT_ROWS:
            continue

        df = pd.DataFrame({
            "timestamp": chunk["timestamp_ms"] / 1000.0,
            "ax": chunk["ax"], "ay": chunk["ay"], "az": chunk["az"],
            "gx": chunk["gx"], "gy": chunk["gy"], "gz": chunk["gz"],
            "mx": chunk["mx"], "my": chunk["my"], "mz": chunk["mz"],
            "grav_x": chunk["grav_x"], "grav_y": chunk["grav_y"], "grav_z": chunk["grav_z"],
            "lat": chunk["lat"], "lon": chunk["lon"], "alt": chunk["alt"],
            "speed": chunk["speed"], "accuracy": chunk["accuracy"],
            "gps_heading_deg": chunk["bearing"],
            "gps_heading_acc_deg": chunk["bearing_acc"],
        })
        df["gnss_new"] = chunk["gps_fresh"].fillna(0).astype(bool)
        df["route_id"] = f"{name}_seg{i:02d}"
        df["blackout_mask"] = False
        segments.append(df)

    return segments


def summarise(segments: list[pd.DataFrame]) -> pd.DataFrame:
    """Quick quality report -- the same checks the IO-VNBD audit applies."""
    rows = []
    for df in segments:
        t = df["timestamp"].to_numpy()
        duration = float(t[-1] - t[0])
        fixes = int(df["gnss_new"].sum())
        bearing_fixes = int((df["gnss_new"] & df["gps_heading_deg"].notna()).sum())
        bearing_accuracy = df.loc[
            df["gnss_new"] & df["gps_heading_acc_deg"].notna(),
            "gps_heading_acc_deg",
        ]
        horizontal = np.hypot(df["ax"] - df["grav_x"], df["ay"] - df["grav_y"])
        rows.append({
            "route_id": df["route_id"].iloc[0],
            "n_rows": len(df),
            "duration_min": round(duration / 60, 1),
            "imu_hz": round(len(df) / max(duration, 1e-6), 1),
            "gnss_hz": round(fixes / max(duration, 1e-6), 2),
            "gnss_acc_median_m": round(float(df.loc[df["gnss_new"], "accuracy"].median()), 1),
            "bearing_hz": round(bearing_fixes / max(duration, 1e-6), 2),
            "bearing_acc_median_deg": (
                round(float(bearing_accuracy.median()), 1)
                if len(bearing_accuracy) else np.nan),
            "accel_p99": round(float(np.nanpercentile(horizontal, 99)), 2),
            "max_speed_ms": round(float(np.nanmax(df["speed"])), 1),
            "nan_in_features": int(df[PRODUCTION_FEATURES].isna().sum().sum()),
        })
    return pd.DataFrame(rows)
