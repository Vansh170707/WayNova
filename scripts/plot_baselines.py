"""Produce the Phase-1 comparison figure: reference vs raw INS vs ES-EKF in a blackout."""
import argparse
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from neuronav.calibration.alignment import estimate_alignment
from neuronav.data.gnss_sim import GNSSSimConfig, add_simulated_gnss, apply_blackout, pick_blackout_windows
from neuronav.data.reference import load_paired_segments
from neuronav.evaluation.metrics import drift_ratio, path_length
from neuronav.fusion.run import (SPEED_MODE_HOLD, SPEED_MODE_TCN, constant_velocity_baseline,
                                 run_es_ekf, run_raw_ins)
from neuronav.models.predictor import SpeedPrediction, SpeedPredictor

REPO_ROOT = Path(__file__).resolve().parents[1]
WARMUP_S = 300.0


def main(driver: str, session: str, duration: float, seed: int, checkpoint: str = None):
    s_path = REPO_ROOT / f"data/raw/IO-VNBD/paired/{driver}/S-{session}.csv"
    v_path = REPO_ROOT / f"data/raw/IO-VNBD/paired/{driver}/V-{session}.csv"
    seg = max(load_paired_segments(str(s_path), str(v_path), session), key=len)
    seg, _ = add_simulated_gnss(seg, GNSSSimConfig(seed=seed))
    align = estimate_alignment(seg)
    predictor = SpeedPredictor(checkpoint) if checkpoint else None
    prediction = predictor.predict_segment(seg, align) if predictor else None

    starts = pick_blackout_windows(seg, duration, 12, seed=seed)
    t_all = seg["timestamp"].to_numpy()

    # pick the window closest to the median drift so the figure is representative
    scored = []
    for start in starts:
        lo = int(np.searchsorted(t_all, start - WARMUP_S))
        hi = int(np.searchsorted(t_all, start + duration)) + 1
        sl = apply_blackout(seg.iloc[lo:hi].reset_index(drop=True), start, duration)
        t = sl["timestamp"].to_numpy()
        i0, i1 = int(np.searchsorted(t, start)), int(np.searchsorted(t, start + duration))
        ref = np.column_stack([sl["ref_e"], sl["ref_n"]])[i0:i1]
        if i1 - i0 < 10 or path_length(ref) < 50:
            continue
        est = run_es_ekf(sl, align, speed_mode=SPEED_MODE_HOLD)["estimate"][i0:i1]
        scored.append((drift_ratio(est, ref), start))
    if not scored:
        print("no usable window")
        return
    scored.sort()
    _, start = scored[len(scored) // 2]

    lo = int(np.searchsorted(t_all, start - WARMUP_S))
    hi = int(np.searchsorted(t_all, start + duration)) + 1
    sl = apply_blackout(seg.iloc[lo:hi].reset_index(drop=True), start, duration)
    t = sl["timestamp"].to_numpy()
    i0, i1 = int(np.searchsorted(t, start)), int(np.searchsorted(t, start + duration))

    ref = np.column_stack([sl["ref_e"], sl["ref_n"]])[i0:i1]
    tracks = {
        "Constant velocity": (constant_velocity_baseline(sl, i0, i1), "#999999", "--"),
        "B1 raw INS": (run_raw_ins(sl, align, i0, i1, SPEED_MODE_HOLD), "#E8710A", "-"),
        "B3 ES-EKF": (run_es_ekf(sl, align, speed_mode=SPEED_MODE_HOLD)["estimate"][i0:i1],
                      "#1B7F4B", "-"),
    }
    if prediction is not None:
        sl_pred = SpeedPrediction(prediction.speed[lo:hi], prediction.sigma[lo:hi],
                                  prediction.valid[lo:hi])
        tracks["B5 TCN + ES-EKF"] = (
            run_es_ekf(sl, align, speed_mode=SPEED_MODE_TCN,
                       prediction=sl_pred)["estimate"][i0:i1], "#1A5FB4", "-")
    distance = path_length(ref)

    fig, axes = plt.subplots(1, 3, figsize=(17, 5.4))

    ax = axes[0]
    ax.plot(ref[:, 0] - ref[0, 0], ref[:, 1] - ref[0, 1], color="black", lw=2.5,
            label="Reference (vehicle, 10 Hz)", zorder=5)
    for name, (track, color, style) in tracks.items():
        ax.plot(track[:, 0] - ref[0, 0], track[:, 1] - ref[0, 1], color=color, lw=2,
                ls=style, label=f"{name}  ({drift_ratio(track, ref):.1%})")
    ax.scatter([0], [0], color="#1B7F4B", s=90, zorder=6, marker="o", label="blackout entry")
    ax.set_title(f"{int(duration)} s GNSS blackout — {distance:.0f} m travelled")
    ax.set_xlabel("East (m)"); ax.set_ylabel("North (m)")
    ax.axis("equal"); ax.legend(fontsize=8, loc="best"); ax.grid(alpha=0.25)

    ax = axes[1]
    tt = t[i0:i1] - t[i0]
    for name, (track, color, style) in tracks.items():
        ax.plot(tt, np.linalg.norm(track - ref, axis=1), color=color, lw=2, ls=style, label=name)
    ax.set_title("Position error growth during outage")
    ax.set_xlabel("time since blackout entry (s)"); ax.set_ylabel("position error (m)")
    ax.legend(fontsize=8); ax.grid(alpha=0.25)

    ax = axes[2]
    summary_path = REPO_ROOT / "outputs/results/baseline_summary.csv"
    if summary_path.exists():
        summary = pd.read_csv(summary_path)
        styles = {"B0_const_velocity": ("#999999", "--"), "B1_raw_ins_hold": ("#E8710A", "-"),
                  "B3_es_ekf_hold": ("#1B7F4B", "-"), "B5_tcn_es_ekf": ("#1A5FB4", "-"),
                  "B6_phys_es_ekf": ("#7B2D8E", "-")}
        for method, (color, style) in styles.items():
            sub = summary[summary["method"] == method].sort_values("blackout_s")
            if len(sub):
                ax.plot(sub["blackout_s"], sub["drift_median_%"], marker="o",
                        color=color, ls=style, lw=2, label=method)
        ax.axhline(10, color="crimson", ls=":", lw=2, label="SIH target <10%")
        ax.set_title("Median drift ratio vs blackout duration")
        ax.set_xlabel("blackout duration (s)"); ax.set_ylabel("drift ratio (%)")
        ax.legend(fontsize=8); ax.grid(alpha=0.25)

    fig.suptitle(f"NeuroNav-X — GNSS blackout, held-out driver | IO-VNBD  {seg['route_id'].iloc[0]}",
                 fontsize=13)
    fig.tight_layout()
    out = REPO_ROOT / "outputs" / "plots" / f"baselines_{session}_{int(duration)}s.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=140)
    print(f"plot -> {out}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--driver", default="B")
    ap.add_argument("--session", default="M")
    ap.add_argument("--duration", type=float, default=120.0)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--checkpoint", default=None)
    args = ap.parse_args()
    main(args.driver, args.session, args.duration, args.seed, args.checkpoint)
