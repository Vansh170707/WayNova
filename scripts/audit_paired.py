"""Inventory every paired (phone + vehicle) session and score its usability for modelling.

Three independent things must hold for a segment to be worth training on:
  1. the phone/vehicle pair is genuinely aligned (sync correlation)
  2. the gyro yaw channel is identifiable  (yaw_corr)  -> heading is observable
  3. the accelerometer carries longitudinal motion (accel_corr) -> speed is observable

Audit findings show these fail independently and often, so they are reported separately.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from neuronav.calibration.alignment import estimate_alignment
from neuronav.data.gnss_sim import add_simulated_gnss
from neuronav.data.reference import load_paired_segments

REPO_ROOT = Path(__file__).resolve().parents[1]
PAIRED_ROOT = REPO_ROOT / "data/raw/IO-VNBD/paired"

MIN_SYNC_CORR = 0.35
MIN_YAW_CORR = 0.60      # below this the heading channel is not trustworthy
MIN_ACCEL_CORR = 0.20    # below this the accelerometer carries no longitudinal signal
MIN_ROWS = 6000


def audit_all() -> pd.DataFrame:
    rows = []
    for phone_path in sorted(PAIRED_ROOT.glob("*/S-*.csv")):
        driver = phone_path.parent.name
        session = phone_path.stem[2:]
        vehicle_path = phone_path.parent / f"V-{session}.csv"
        if not vehicle_path.exists():
            continue
        try:
            segments = load_paired_segments(str(phone_path), str(vehicle_path), session)
        except Exception as exc:
            rows.append({"driver": driver, "route_id": session, "error": f"{type(exc).__name__}"})
            continue

        for seg in segments:
            route = seg["route_id"].iloc[0]
            sync = seg.attrs["sync"]
            t = seg["timestamp"].to_numpy()
            duration = float(t[-1] - t[0])
            record = {
                "driver": driver, "route_id": route, "n_rows": len(seg),
                "duration_min": round(duration / 60, 1),
                "sync_corr": round(sync.correlation, 3),
                "sync_lag_s": round(sync.lag_seconds, 2),
                "sync_ok": bool(sync.reliable),
                "ref_speed_max": round(float(np.nanmax(seg["ref_speed"])), 1),
                "yaw_corr": np.nan, "accel_corr": np.nan, "yaw_channel": "",
            }
            if sync.reliable and len(seg) >= MIN_ROWS:
                try:
                    seg2, _ = add_simulated_gnss(seg)
                    align = estimate_alignment(seg2)
                    record["yaw_corr"] = round(abs(align.yaw_corr), 3)
                    record["accel_corr"] = round(abs(align.forward_accel_corr), 3)
                    record["yaw_channel"] = ["gx", "gy", "gz"][align.yaw_channel]
                except ValueError:
                    pass
            rows.append(record)

    inv = pd.DataFrame(rows)
    inv["heading_ok"] = inv["yaw_corr"] >= MIN_YAW_CORR
    inv["speed_ok"] = inv["accel_corr"] >= MIN_ACCEL_CORR
    inv["trainable"] = (inv["sync_ok"] & inv["heading_ok"] & inv["speed_ok"]
                        & (inv["n_rows"] >= MIN_ROWS))
    return inv


def main():
    inv = audit_all()
    out_dir = REPO_ROOT / "outputs" / "results"
    out_dir.mkdir(parents=True, exist_ok=True)
    inv.to_csv(out_dir / "paired_inventory.csv", index=False)

    cols = ["driver", "route_id", "n_rows", "duration_min", "sync_corr", "sync_ok",
            "yaw_corr", "yaw_channel", "accel_corr", "heading_ok", "speed_ok", "trainable"]
    with pd.option_context("display.width", 220, "display.max_rows", 100):
        print(inv[[c for c in cols if c in inv.columns]].to_string(index=False))

    total_h = inv.loc[inv["sync_ok"], "duration_min"].sum() / 60
    train_h = inv.loc[inv["trainable"], "duration_min"].sum() / 60
    print(f"\nsegments                 : {len(inv)}")
    print(f"pair-aligned             : {int(inv['sync_ok'].sum())}  ({total_h:.1f} h)")
    print(f"heading observable       : {int(inv['heading_ok'].sum())}")
    print(f"speed observable         : {int(inv['speed_ok'].sum())}")
    print(f"trainable (all three)    : {int(inv['trainable'].sum())}  ({train_h:.1f} h)")
    print(f"drivers with trainable   : {sorted(inv.loc[inv['trainable'],'driver'].unique())}")
    print(f"\ninventory -> {out_dir / 'paired_inventory.csv'}")
    return inv


if __name__ == "__main__":
    argparse.ArgumentParser().parse_args()
    main()
