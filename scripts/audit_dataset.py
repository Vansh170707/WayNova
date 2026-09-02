"""Gate 0 deliverable: audit IO-VNBD smartphone segments and freeze the data contract.

Reports per-segment IMU/GNSS rates, trip geometry, GNSS accuracy distribution, and
whether the segment is usable for blackout evaluation. Writes a CSV inventory that
downstream split/selection logic reads instead of hard-coding session names.
"""
import argparse
import glob
import json
from pathlib import Path

import numpy as np
import pandas as pd

from neuronav.data.loader import load_iovnbd_segments, PRODUCTION_FEATURES, OPTIONAL_FEATURES
from neuronav.utils.geo import latlon_to_enu

REPO_ROOT = Path(__file__).resolve().parents[1]

# A segment must sustain this GNSS rate for its reference trajectory to support
# honest drift evaluation over short (10-120 s) blackout windows.
MIN_GNSS_HZ_FOR_EVAL = 0.8


def audit_segment(df: pd.DataFrame) -> dict:
    t = df["timestamp"].to_numpy()
    dur = float(t[-1] - t[0])
    imu_hz = len(df) / dur if dur > 0 else np.nan

    fixes = df[df["gnss_new"]]
    gnss_hz = len(fixes) / dur if dur > 0 else np.nan

    lat0, lon0, alt0 = fixes["lat"].iloc[0], fixes["lon"].iloc[0], fixes["alt"].iloc[0]
    e, n, _ = latlon_to_enu(fixes["lat"], fixes["lon"], fixes["alt"], lat0, lon0, alt0)
    step = np.hypot(np.diff(e), np.diff(n))
    dist_km = float(step.sum()) / 1000.0

    fix_t = fixes["timestamp"].to_numpy()
    fix_gaps = np.diff(fix_t)

    usable = (
        gnss_hz >= MIN_GNSS_HZ_FOR_EVAL
        and imu_hz >= 5.0
        and dur >= 300
        and dist_km >= 1.0
    )

    return {
        "route_id": df["route_id"].iloc[0],
        "n_rows": len(df),
        "duration_s": round(dur, 1),
        "imu_hz": round(float(imu_hz), 2),
        "gnss_hz": round(float(gnss_hz), 3),
        "n_gnss_fixes": len(fixes),
        "gnss_gap_median_s": round(float(np.median(fix_gaps)), 2) if len(fix_gaps) else np.nan,
        "gnss_gap_max_s": round(float(fix_gaps.max()), 2) if len(fix_gaps) else np.nan,
        "distance_km": round(dist_km, 2),
        "mean_speed_ms": round(float(df["speed"].mean()), 2),
        "max_speed_ms": round(float(df["speed"].max()), 2),
        "gnss_acc_median_m": round(float(fixes["accuracy"].median()), 2),
        "gnss_acc_p90_m": round(float(fixes["accuracy"].quantile(0.90)), 2),
        "nan_in_features": int(df[PRODUCTION_FEATURES].isna().sum().sum()),
        "has_mag": bool(df[OPTIONAL_FEATURES].notna().any().any()),
        "usable_for_eval": bool(usable),
    }


def main(pattern: str):
    rows = []
    for path in sorted(glob.glob(pattern)):
        session_id = Path(path).stem.replace("S-", "")
        for seg in load_iovnbd_segments(path, session_id):
            rows.append(audit_segment(seg))

    inv = pd.DataFrame(rows)
    out_dir = REPO_ROOT / "outputs" / "results"
    out_dir.mkdir(parents=True, exist_ok=True)
    inv_path = out_dir / "segment_inventory.csv"
    inv.to_csv(inv_path, index=False)

    with pd.option_context("display.width", 220, "display.max_columns", None):
        print(inv.to_string(index=False))

    print(f"\nsegments found      : {len(inv)}")
    print(f"usable for eval     : {int(inv['usable_for_eval'].sum())} "
          f"(GNSS >= {MIN_GNSS_HZ_FOR_EVAL} Hz, IMU >= 5 Hz, >=300 s, >=1 km)")
    print(f"total drive distance: {inv['distance_km'].sum():.1f} km")
    print(f"feature NaNs        : {int(inv['nan_in_features'].sum())}")
    print(f"\ninventory -> {inv_path}")

    if not inv["usable_for_eval"].any():
        print("\nGATE 0: NO segment meets the evaluation GNSS-rate floor.")
    else:
        print("\nGATE 0 candidates:")
        print(inv[inv["usable_for_eval"]][["route_id", "duration_s", "gnss_hz", "distance_km"]]
              .to_string(index=False))
    return inv


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--pattern", default=str(REPO_ROOT / "data/raw/IO-VNBD/**/S-*.csv"))
    args = ap.parse_args()
    main(args.pattern)
