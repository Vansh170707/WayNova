"""Blackout recovery evaluation: the seam where GNSS comes back.

`blackout recovery jump -- position discontinuity at GNSS return` is one of the blueprint's
primary metrics (section 13.1) and "GNSS return causes jump" is a High entry in the risk
register, but every earlier evaluation stopped at the end of the outage and never looked at
it.

Reports two things that must both hold, because improving one at the other's expense would
be a cosmetic win dressed up as an engineering one:

  * the DISPLAYED track must not teleport when aiding resumes
  * the ESTIMATE must be no less accurate afterwards than it was without the machinery
"""
import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from neuronav.calibration.alignment import estimate_alignment
from neuronav.data.gnss_sim import GNSSSimConfig, add_simulated_gnss, apply_blackout, pick_blackout_windows
from neuronav.data.reference import load_paired_segments
from neuronav.fusion.blackout import BlackoutConfig, BlackoutManager, TrackSmoother
from neuronav.fusion.es_ekf import deployment_config
from neuronav.fusion.run import SPEED_MODE_HOLD, SPEED_MODE_TCN, run_es_ekf
from neuronav.models.predictor import SpeedPrediction, SpeedPredictor

REPO_ROOT = Path(__file__).resolve().parents[1]
DURATIONS = [30.0, 60.0, 120.0]
WARMUP_S, TAIL_S = 300.0, 60.0
SETTLE_TOLERANCE_M = 15.0


def seam_metrics(track, ref, end_i, t):
    """Jump, settling time and residual error at the moment aiding resumes."""
    step = np.linalg.norm(np.diff(track, axis=0), axis=1)
    window = slice(end_i, min(end_i + 60, len(step)))
    jump = float(step[window].max()) if window.stop > window.start else np.nan

    err = np.linalg.norm(track - ref, axis=1)
    after = err[end_i:]
    good = np.flatnonzero(after < SETTLE_TOLERANCE_M)
    settle = float(t[end_i + good[0]] - t[end_i]) if len(good) else np.nan
    return {
        "jump_m": jump,
        "settle_s": settle,
        "err_at_return_m": float(err[end_i]),
        "err_10s_after_m": float(err[min(end_i + 100, len(err) - 1)]),
    }


def main(drivers: str, n_windows: int, seed: int, checkpoint: str = None):
    predictor = SpeedPredictor(checkpoint) if checkpoint else None
    inventory = pd.read_csv(REPO_ROOT / "outputs/results/paired_inventory.csv")
    inventory = inventory[inventory["trainable"]]
    wanted = [d for d in drivers.split(",") if d]
    if wanted:
        inventory = inventory[inventory["driver"].isin(wanted)]

    rows = []
    for _, row in inventory.iterrows():
        driver, route = row["driver"], row["route_id"]
        session = route.rsplit("_seg", 1)[0]
        phone = REPO_ROOT / f"data/raw/IO-VNBD/paired/{driver}/S-{session}.csv"
        vehicle = REPO_ROOT / f"data/raw/IO-VNBD/paired/{driver}/V-{session}.csv"
        if not phone.exists():
            continue

        for seg in load_paired_segments(str(phone), str(vehicle), session):
            if seg["route_id"].iloc[0] != route:
                continue
            seg, _ = add_simulated_gnss(seg, GNSSSimConfig(seed=seed))
            try:
                align = estimate_alignment(seg)
            except ValueError:
                continue
            full_prediction = predictor.predict_segment(seg, align) if predictor else None
            mode = SPEED_MODE_TCN if full_prediction is not None else SPEED_MODE_HOLD
            t_all = seg["timestamp"].to_numpy()
            print(f"  {route} (driver {driver})")

            for duration in DURATIONS:
                for start in pick_blackout_windows(seg, duration, n_windows, seed=seed):
                    lo = int(np.searchsorted(t_all, start - WARMUP_S))
                    hi = int(np.searchsorted(t_all, start + duration + TAIL_S)) + 1
                    if hi - lo < 200:
                        continue
                    sl = apply_blackout(seg.iloc[lo:hi].reset_index(drop=True), start, duration)
                    t = sl["timestamp"].to_numpy()
                    end_i = int(np.searchsorted(t, start + duration))
                    if end_i >= len(sl) - 20:
                        continue
                    ref = np.column_stack([sl["ref_e"], sl["ref_n"]])
                    if not np.isfinite(ref).all():
                        continue

                    prediction = None
                    if full_prediction is not None:
                        prediction = SpeedPrediction(full_prediction.speed[lo:hi],
                                                     full_prediction.sigma[lo:hi],
                                                     full_prediction.valid[lo:hi])

                    plain = run_es_ekf(sl, align, deployment_config(), speed_mode=mode,
                                       prediction=prediction)
                    managed = run_es_ekf(
                        sl, align, deployment_config(), speed_mode=mode,
                        prediction=prediction,
                        blackout_manager=BlackoutManager(BlackoutConfig()),
                        smoother=TrackSmoother(BlackoutConfig()))

                    base = seam_metrics(plain["estimate"], ref, end_i, t)
                    shown = seam_metrics(managed["display"], ref, end_i, t)
                    rows.append({
                        "driver": driver, "route_id": route, "blackout_s": duration,
                        "jump_baseline_m": base["jump_m"],
                        "jump_managed_m": shown["jump_m"],
                        "err10_baseline_m": base["err_10s_after_m"],
                        "err10_managed_m": shown["err_10s_after_m"],
                        "settle_baseline_s": base["settle_s"],
                        "settle_managed_s": shown["settle_s"],
                        "err_at_return_m": base["err_at_return_m"],
                    })

    if not rows:
        print("no windows evaluated")
        return

    results = pd.DataFrame(rows)
    out_dir = REPO_ROOT / "outputs" / "results"
    out_dir.mkdir(parents=True, exist_ok=True)
    results.to_csv(out_dir / "recovery_windows.csv", index=False)

    summary = results.groupby("blackout_s").agg(
        n=("jump_managed_m", "size"),
        err_at_return=("err_at_return_m", "median"),
        jump_before=("jump_baseline_m", "median"),
        jump_after=("jump_managed_m", "median"),
        jump_after_p90=("jump_managed_m", lambda s: s.quantile(0.90)),
        err10_before=("err10_baseline_m", "median"),
        err10_after=("err10_managed_m", "median"),
        settle_after_s=("settle_managed_s", "median"),
    ).round(2)

    with pd.option_context("display.width", 200):
        print("\n" + summary.to_string())
    reduction = 100 * (1 - summary["jump_after"] / summary["jump_before"])
    print("\njump reduction: " + ", ".join(
        f"{int(d)}s {r:.0f}%" for d, r in reduction.items()))
    print("accuracy 10 s after recovery is the control: it must not get worse")

    summary.to_csv(out_dir / "recovery_summary.csv")
    (out_dir / "recovery_summary.json").write_text(
        json.dumps(summary.reset_index().to_dict("records"), indent=2))
    print(f"\nsummary -> {out_dir / 'recovery_summary.csv'}")
    return summary


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--drivers", default="B")
    ap.add_argument("--n-windows", type=int, default=12)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--checkpoint",
                    default=str(REPO_ROOT / "outputs/checkpoints/speed_tcn_holdout_B.pt"))
    args = ap.parse_args()
    main(args.drivers, args.n_windows, args.seed, args.checkpoint)
