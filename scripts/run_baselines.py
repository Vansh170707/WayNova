"""Gate 1 deliverable: reproduce the classical baselines on IO-VNBD.

Evaluates B0' (constant velocity), B1 (raw INS) and B3 (ES-EKF) across many randomly
placed blackout windows of several durations, and reports drift ratio, ATE and final
position error against the 10 Hz vehicle reference.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from neuronav.calibration.alignment import estimate_alignment
from neuronav.data.gnss_sim import GNSSSimConfig, add_simulated_gnss, apply_blackout, pick_blackout_windows
from neuronav.data.reference import load_paired_segments
from neuronav.evaluation.metrics import ate_rmse, drift_ratio, final_position_error, path_length
from neuronav.fusion.es_ekf import ESEKFConfig
from neuronav.fusion.run import (SPEED_MODE_ACCEL, SPEED_MODE_HOLD, SPEED_MODE_TCN,
                                 constant_velocity_baseline, run_es_ekf, run_raw_ins)
from neuronav.mapmatch.graph import RoadGraph
from neuronav.mapmatch.online import OnlineMapConfig, OnlineMapMatcher
from neuronav.mapmatch.osm import road_network_path
from neuronav.models.predictor import SpeedPredictor, SpeedPrediction

REPO_ROOT = Path(__file__).resolve().parents[1]
DURATIONS = [10.0, 30.0, 60.0, 120.0]


WARMUP_S = 300.0  # aided time before the outage, for bias/heading convergence


def evaluate_window(df, align, start, duration, config=None, prediction=None,
                    graph=None, phys_config=None):
    """Run every baseline through one blackout window and score them against truth.

    Only a slice around the window is simulated: WARMUP_S of aided driving lets the
    filter converge, and everything after the outage is irrelevant to these metrics.
    """
    t_all = df["timestamp"].to_numpy()
    lo = int(np.searchsorted(t_all, start - WARMUP_S))
    hi = int(np.searchsorted(t_all, start + duration)) + 1
    if hi - lo < 100:
        return []
    phys_config = phys_config or ESEKFConfig(estimate_gyro_scale=True)
    sliced = df.iloc[lo:hi].reset_index(drop=True)
    sliced_prediction = None
    if prediction is not None:
        sliced_prediction = SpeedPrediction(prediction.speed[lo:hi],
                                            prediction.sigma[lo:hi],
                                            prediction.valid[lo:hi])

    seg = apply_blackout(sliced, start, duration)
    t = seg["timestamp"].to_numpy()
    i0 = int(np.searchsorted(t, start))
    i1 = int(np.searchsorted(t, start + duration))
    if i1 - i0 < 10:
        return []

    ref = np.column_stack([seg["ref_e"].to_numpy(), seg["ref_n"].to_numpy()])[i0:i1]
    if not np.isfinite(ref).all():
        return []
    distance = path_length(ref)
    if distance < 20.0:
        return []

    candidates = {
        "B0_const_velocity": constant_velocity_baseline(seg, i0, i1),
        "B1_raw_ins_accel": run_raw_ins(seg, align, i0, i1, SPEED_MODE_ACCEL),
        "B1_raw_ins_hold": run_raw_ins(seg, align, i0, i1, SPEED_MODE_HOLD),
        "B3_es_ekf_accel": run_es_ekf(seg, align, config, SPEED_MODE_ACCEL)["estimate"][i0:i1],
        "B3_es_ekf_hold": run_es_ekf(seg, align, config, SPEED_MODE_HOLD)["estimate"][i0:i1],
    }
    if sliced_prediction is not None:
        candidates["B5_tcn_es_ekf"] = run_es_ekf(
            seg, align, config, SPEED_MODE_TCN, sliced_prediction)["estimate"][i0:i1]
        # B6 = the same learned speed through a filter that also estimates the yaw-gyro
        # scale factor. The checkpoint supplies the speed de-shrinking; the config
        # supplies the heading half. Both target the two terms the Phase 6 ablation
        # measured, and nothing else differs from B5.
        candidates["B6_phys_es_ekf"] = run_es_ekf(
            seg, align, phys_config, SPEED_MODE_TCN, sliced_prediction)["estimate"][i0:i1]
    if graph is not None:
        candidates["B4_es_ekf_map"] = run_es_ekf(
            seg, align, config, SPEED_MODE_HOLD,
            map_matcher=OnlineMapMatcher(graph, OnlineMapConfig()))["estimate"][i0:i1]
        if sliced_prediction is not None:
            # renamed from B6 in Phase 6: map aiding failed Gate 4 and is off by default,
            # while B6 in the blueprint's ladder is the physics-guided variant above
            candidates["B4b_tcn_map_es_ekf"] = run_es_ekf(
                seg, align, config, SPEED_MODE_TCN, sliced_prediction,
                map_matcher=OnlineMapMatcher(graph, OnlineMapConfig()))["estimate"][i0:i1]

    rows = []
    for name, est in candidates.items():
        if est.shape != ref.shape or not np.isfinite(est).all():
            continue
        rows.append({
            "method": name,
            "blackout_s": duration,
            "start_s": round(start, 1),
            "distance_m": round(distance, 1),
            "final_error_m": round(final_position_error(est, ref), 2),
            "drift_ratio": round(drift_ratio(est, ref), 4),
            "ate_m": round(ate_rmse(est, ref), 2),
        })
    return rows


def main(drivers: str, n_windows: int, seed: int, checkpoint: str = None,
         use_map: bool = False, tag: str = ""):
    all_rows, align_rows = [], []
    predictor = SpeedPredictor(checkpoint) if checkpoint else None
    if predictor:
        print(f"speed model: {checkpoint} (held-out driver {predictor.held_out}, "
              f"RMSE {predictor.metrics.get('rmse', float('nan')):.2f} m/s)")

    inventory = pd.read_csv(REPO_ROOT / "outputs/results/paired_inventory.csv")
    inventory = inventory[inventory["trainable"]]
    wanted = [d for d in drivers.split(",") if d]
    if wanted:
        inventory = inventory[inventory["driver"].isin(wanted)]

    for _, row in inventory.iterrows():
        driver, route = row["driver"], row["route_id"]
        session = route.rsplit("_seg", 1)[0]
        s_path = REPO_ROOT / f"data/raw/IO-VNBD/paired/{driver}/S-{session}.csv"
        v_path = REPO_ROOT / f"data/raw/IO-VNBD/paired/{driver}/V-{session}.csv"
        if not s_path.exists():
            continue

        for seg in load_paired_segments(str(s_path), str(v_path), session):
            if seg["route_id"].iloc[0] != route:
                continue
            sync = seg.attrs["sync"]
            seg, origin = add_simulated_gnss(seg, GNSSSimConfig(seed=seed))
            try:
                align = estimate_alignment(seg, use_reference=False)
            except ValueError as exc:
                print(f"  {route}: alignment failed ({exc})")
                continue

            prediction = predictor.predict_segment(seg, align) if predictor else None

            graph = None
            osm_path = road_network_path(route, REPO_ROOT / "data/external/osm")
            if use_map and osm_path.exists():
                graph = RoadGraph(str(osm_path), origin=origin)
            align_rows.append({
                "driver": driver, "route_id": route,
                "sync_corr": round(sync.correlation, 3),
                "align_deg": round(np.degrees(align.forward_angle_rad), 2),
                "align_corr": round(align.forward_accel_corr, 3),
                "yaw_corr": round(align.yaw_corr, 3),
            })
            print(f"  {route} (driver {driver}): sync={sync.correlation:.3f} "
                  f"yaw_corr={abs(align.yaw_corr):.3f} accel_corr={abs(align.forward_accel_corr):.3f}")

            for duration in DURATIONS:
                for start in pick_blackout_windows(seg, duration, n_windows, seed=seed):
                    for r in evaluate_window(seg, align, start, duration,
                                             prediction=prediction, graph=graph):
                        r["route_id"] = route
                        r["driver"] = driver
                        all_rows.append(r)

    if not all_rows:
        print("no windows evaluated")
        return

    results = pd.DataFrame(all_rows)
    out_dir = REPO_ROOT / "outputs" / "results"
    out_dir.mkdir(parents=True, exist_ok=True)
    suffix = f"_{tag}" if tag else ""
    results.to_csv(out_dir / f"baseline_windows{suffix}.csv", index=False)
    pd.DataFrame(align_rows).to_csv(out_dir / f"alignment{suffix}.csv", index=False)

    summary = (results
               .groupby(["blackout_s", "method"])
               .agg(n=("drift_ratio", "size"),
                    drift_median=("drift_ratio", "median"),
                    drift_p90=("drift_ratio", lambda s: s.quantile(0.90)),
                    final_err_median=("final_error_m", "median"),
                    ate_median=("ate_m", "median"))
               .reset_index())
    summary["drift_median"] = (summary["drift_median"] * 100).round(1)
    summary["drift_p90"] = (summary["drift_p90"] * 100).round(1)
    summary = summary.rename(columns={"drift_median": "drift_median_%", "drift_p90": "drift_p90_%"})
    summary.to_csv(out_dir / f"baseline_summary{suffix}.csv", index=False)

    with pd.option_context("display.width", 200):
        print("\n" + summary.to_string(index=False))
    print(f"\nwindows evaluated: {len(results)}")
    print(f"summary -> {out_dir / f'baseline_summary{suffix}.csv'}")
    return summary


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--drivers", default="", help="comma-separated, e.g. B")
    ap.add_argument("--n-windows", type=int, default=25)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--checkpoint", default=None, help="speed TCN checkpoint for B5")
    ap.add_argument("--map", action="store_true", help="also evaluate B4/B4b map aiding")
    ap.add_argument("--tag", default="", help="suffix for the output CSVs, for ablations")
    args = ap.parse_args()
    main(args.drivers, args.n_windows, args.seed, args.checkpoint, args.map, args.tag)
