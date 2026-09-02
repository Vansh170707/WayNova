"""Which physical constraints are actually measurable on this data? (Phase 6 evidence)

Two candidate physics-guided mechanisms were considered before any were built, and this
script is the measurement that decided between them.

1. The centripetal identity  a_lat = v * omega.
   If the phone's lateral accelerometer obeyed it, then v = a_lat / omega would be an
   IMU-ONLY speed observable available during every turn -- exactly where a blackout
   hurts most, and completely independent of the learned model. The vehicle reference
   obeys it to corr ~0.95, so the physics and the sign conventions are sound. The phone
   does not, for the reason the Phase 0 audit already documented: IO-VNBD's accelerometer
   has its sustained component filtered out, and a steady turn is precisely a sustained
   lateral acceleration. VERDICT: unusable here, worth re-testing on unfiltered hardware.

2. The split of heading error into a bias-like and a scale-like part.
   Bias accumulates with ELAPSED TIME, scale error with TOTAL TURNING. The filter already
   estimates a bias; it had no way to represent a scale error. Regressing the accumulated
   120 s heading error on both terms says whether that missing state is worth adding.
   VERDICT: worth it -- on M_seg00 the scale term is 44 deg of the 50 deg median error.
"""
import argparse
from pathlib import Path

import numpy as np
import pandas as pd

from neuronav.calibration.alignment import estimate_alignment
from neuronav.calibration.signals import horizontal_acceleration
from neuronav.data.gnss_sim import GNSSSimConfig, add_simulated_gnss
from neuronav.data.reference import load_paired_segments

REPO_ROOT = Path(__file__).resolve().parents[1]
PAIRED = REPO_ROOT / "data/raw/IO-VNBD/paired"
DT = 0.1
HORIZON_S = 120.0


def segments(drivers: str = ""):
    inv = pd.read_csv(REPO_ROOT / "outputs/results/paired_inventory.csv")
    inv = inv[inv["trainable"]]
    wanted = [d for d in drivers.split(",") if d]
    if wanted:
        inv = inv[inv["driver"].isin(wanted)]
    for _, row in inv.iterrows():
        driver, route = row["driver"], row["route_id"]
        session = route.rsplit("_seg", 1)[0]
        phone, vehicle = PAIRED / driver / f"S-{session}.csv", PAIRED / driver / f"V-{session}.csv"
        if not phone.exists():
            continue
        for seg in load_paired_segments(str(phone), str(vehicle), session):
            if seg["route_id"].iloc[0] != route:
                continue
            seg, _ = add_simulated_gnss(seg, GNSSSimConfig(seed=0))
            try:
                yield driver, route, seg, estimate_alignment(seg, use_reference=False)
            except ValueError as exc:
                print(f"  {route}: alignment failed ({exc})")
            break


def centripetal_row(route, driver, seg, align):
    """Does a_lat = v * omega hold, for the vehicle and then for the phone?"""
    h = horizontal_acceleration(seg)
    c, s = np.cos(align.forward_angle_rad), np.sin(align.forward_angle_rad)
    a_lat_phone = -h[:, 0] * s + h[:, 1] * c          # unscaled vehicle-lateral
    w_phone = align.corrected_heading_rate(seg)
    v_ref = seg["ref_speed"].to_numpy()
    a_lat_ref = seg["ref_a_lat"].to_numpy()
    w_ref = np.radians(seg["ref_yaw_rate_deg_s"].to_numpy())

    moving = v_ref > 3.0
    ok_v = moving & np.isfinite(a_lat_ref) & np.isfinite(w_ref)
    ok_p = moving & np.isfinite(a_lat_phone) & np.isfinite(w_phone)
    if ok_v.sum() < 500 or ok_p.sum() < 500:
        return None

    veh_corr = float(np.corrcoef((v_ref * w_ref)[ok_v], a_lat_ref[ok_v])[0, 1])
    ph_pred = (v_ref * w_phone)[ok_p]
    ph_corr = float(np.corrcoef(ph_pred, a_lat_phone[ok_p])[0, 1])
    ph_scale = float(np.polyfit(ph_pred, a_lat_phone[ok_p], 1)[0])

    # if we inverted it anyway, how wrong would the implied speed be?
    turning = ok_p & (np.abs(w_phone) > 0.10)
    v_hat = (a_lat_phone[turning] / ph_scale) / w_phone[turning]
    rmse = float(np.sqrt(np.mean(np.clip(v_hat - v_ref[turning], -50, 50) ** 2)))
    return {"route": route, "driver": driver,
            "vehicle_corr": round(veh_corr, 3), "phone_corr": round(ph_corr, 3),
            "turning_duty_%": round(100 * float(np.mean(np.abs(w_phone)[moving] > 0.10)), 1),
            "implied_speed_rmse_ms": round(rmse, 1)}


def heading_row(route, driver, seg, align):
    """Split the 120 s heading error into an elapsed-time part and a turning part."""
    w_used = align.corrected_heading_rate(seg)
    w_veh = np.radians(seg["ref_yaw_rate_deg_s"].to_numpy())
    ok = np.isfinite(w_veh) & np.isfinite(w_used)
    if ok.sum() < 1000:
        return None
    # the reference yaw-rate channel is signed in the vehicle's own convention
    sign = np.sign(np.corrcoef(w_used[ok], w_veh[ok])[0, 1])
    w_true = sign * w_veh
    corr = float(np.corrcoef(w_used[ok], w_true[ok])[0, 1])

    resid = np.where(ok, w_used - w_true, 0.0)
    turn = np.where(ok, np.abs(w_true), 0.0)
    n = int(HORIZON_S / DT)
    c_res, c_turn = np.cumsum(resid) * DT, np.cumsum(turn) * DT
    if len(c_res) < n + 10:
        return None
    d_hdg, d_turn = c_res[n:] - c_res[:-n], c_turn[n:] - c_turn[:-n]

    design = np.column_stack([np.full(len(d_turn), HORIZON_S), d_turn])
    (bias_rate, scale_err), *_ = np.linalg.lstsq(design, d_hdg, rcond=None)
    return {"route": route, "driver": driver, "yaw_corr": round(corr, 3),
            "turning_120s_deg": round(float(np.degrees(np.median(d_turn)))),
            "hdg_err_120s_deg": round(float(np.degrees(np.median(np.abs(d_hdg)))), 1),
            "from_bias_deg": round(float(np.degrees(abs(bias_rate) * HORIZON_S)), 1),
            "from_scale_deg": round(float(np.degrees(abs(scale_err) * np.median(d_turn))), 1),
            "scale_err_%": round(float(scale_err) * 100, 1)}


def main(drivers: str):
    cent, head = [], []
    for driver, route, seg, align in segments(drivers):
        print(f"  {route} (driver {driver})")
        for row, sink in ((centripetal_row(route, driver, seg, align), cent),
                          (heading_row(route, driver, seg, align), head)):
            if row:
                sink.append(row)

    out_dir = REPO_ROOT / "outputs" / "results"
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, rows in (("centripetal", cent), ("heading_error_split", head)):
        if not rows:
            continue
        df = pd.DataFrame(rows)
        df.to_csv(out_dir / f"phase6_{name}.csv", index=False)
        print(f"\n--- {name} ---")
        with pd.option_context("display.width", 200):
            print(df.to_string(index=False))
    print(f"\ntables -> {out_dir}/phase6_*.csv")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--drivers", default="")
    main(ap.parse_args().drivers)
