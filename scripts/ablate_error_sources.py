"""Where does the remaining blackout drift come from, on top of the CURRENT best stack?

The Phase 2 oracle was run against B3 and concluded that speed dominated, which is why the
learned component targets speed. B5 has since taken a large bite out of the speed term, so
that conclusion is stale: the ablation has to be re-run on top of B5 to aim Phase 6.

Each variant replaces one quantity with truth for the duration of the outage and leaves
everything else untouched, so the drop in drift is that quantity's share of the error.
Truth comes from the 10 Hz vehicle reference and is evaluation-only.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from neuronav.calibration.alignment import estimate_alignment
from neuronav.data.gnss_sim import GNSSSimConfig, add_simulated_gnss, apply_blackout, pick_blackout_windows
from neuronav.data.reference import load_paired_segments
from neuronav.evaluation.metrics import drift_ratio, final_position_error, path_length
from neuronav.fusion.run import SPEED_MODE_HOLD, SPEED_MODE_TCN, Oracle, run_es_ekf
from neuronav.models.predictor import SpeedPredictor, SpeedPrediction

REPO_ROOT = Path(__file__).resolve().parents[1]
DURATIONS = [30.0, 60.0, 120.0]
WARMUP_S = 300.0


def _wrap(a):
    return (a + np.pi) % (2 * np.pi) - np.pi


def evaluate_window(df, align, start, duration, prediction):
    t_all = df["timestamp"].to_numpy()
    lo = int(np.searchsorted(t_all, start - WARMUP_S))
    hi = int(np.searchsorted(t_all, start + duration)) + 1
    if hi - lo < 100:
        return []
    sliced = df.iloc[lo:hi].reset_index(drop=True)
    pred = None
    if prediction is not None:
        pred = SpeedPrediction(prediction.speed[lo:hi], prediction.sigma[lo:hi],
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

    true_speed = seg["ref_speed"].to_numpy()
    true_heading = np.radians(seg["ref_heading_deg"].to_numpy())

    base_mode = SPEED_MODE_TCN if pred is not None else SPEED_MODE_HOLD
    variants = {
        "neither": Oracle(),
        "true_speed": Oracle(speed=true_speed),
        "true_heading": Oracle(heading=true_heading),
        "both": Oracle(speed=true_speed, heading=true_heading),
    }

    rows = []
    for name, oracle in variants.items():
        out = run_es_ekf(seg, align, None, base_mode, pred,
                         oracle=(None if name == "neither" else oracle))
        est = out["estimate"][i0:i1]
        if est.shape != ref.shape or not np.isfinite(est).all():
            continue
        # heading error at the moment the outage ends -- the quantity Phase 6 must reduce
        hdg_err = np.degrees(abs(_wrap(out["heading"][i1 - 1] - true_heading[i1 - 1])))
        spd_err = abs(out["speed"][i1 - 1] - true_speed[i1 - 1])
        rows.append({
            "variant": name,
            "blackout_s": duration,
            "start_s": round(start, 1),
            "distance_m": round(distance, 1),
            "final_error_m": round(final_position_error(est, ref), 2),
            "drift_ratio": round(drift_ratio(est, ref), 4),
            "heading_err_deg": round(float(hdg_err), 2) if np.isfinite(hdg_err) else np.nan,
            "speed_err_ms": round(float(spd_err), 2) if np.isfinite(spd_err) else np.nan,
        })
    return rows


def main(drivers: str, n_windows: int, seed: int, checkpoint: str = None):
    predictor = SpeedPredictor(checkpoint) if checkpoint else None
    base = "B5 (TCN+ES-EKF)" if predictor else "B3 (ES-EKF, hold)"
    print(f"ablation base stack: {base}")

    inventory = pd.read_csv(REPO_ROOT / "outputs/results/paired_inventory.csv")
    inventory = inventory[inventory["trainable"]]
    wanted = [d for d in drivers.split(",") if d]
    if wanted:
        inventory = inventory[inventory["driver"].isin(wanted)]

    all_rows = []
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
            seg, _ = add_simulated_gnss(seg, GNSSSimConfig(seed=seed))
            try:
                align = estimate_alignment(seg, use_reference=False)
            except ValueError as exc:
                print(f"  {route}: alignment failed ({exc})")
                continue
            prediction = predictor.predict_segment(seg, align) if predictor else None
            print(f"  {route} (driver {driver})")
            for duration in DURATIONS:
                for start in pick_blackout_windows(seg, duration, n_windows, seed=seed):
                    for r in evaluate_window(seg, align, start, duration, prediction):
                        r["route_id"] = route
                        r["driver"] = driver
                        all_rows.append(r)

    if not all_rows:
        print("no windows evaluated")
        return

    results = pd.DataFrame(all_rows)
    out_dir = REPO_ROOT / "outputs" / "results"
    out_dir.mkdir(parents=True, exist_ok=True)
    results.to_csv(out_dir / "ablation_windows.csv", index=False)

    summary = (results.groupby(["blackout_s", "variant"])
               .agg(n=("drift_ratio", "size"),
                    drift_median=("drift_ratio", "median"),
                    final_err_median=("final_error_m", "median"),
                    heading_err_median=("heading_err_deg", "median"),
                    heading_err_p90=("heading_err_deg", lambda s: s.quantile(0.90)),
                    speed_err_median=("speed_err_ms", "median"))
               .reset_index())
    summary["drift_median"] = (summary["drift_median"] * 100).round(1)
    summary = summary.rename(columns={"drift_median": "drift_median_%"}).round(2)
    summary.to_csv(out_dir / "ablation_summary.csv", index=False)

    with pd.option_context("display.width", 200):
        print("\n" + summary.to_string(index=False))
    print(f"\nsummary -> {out_dir / 'ablation_summary.csv'}")
    return summary


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--drivers", default="B")
    ap.add_argument("--n-windows", type=int, default=15)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--checkpoint", default=None)
    args = ap.parse_args()
    main(args.drivers, args.n_windows, args.seed, args.checkpoint)
