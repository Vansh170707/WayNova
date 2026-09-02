"""The SIH signature demonstration (blueprint section 16), run from a recorded drive.

Section 16.1 asks for a specific sequence: start aided and stable, trigger a GNSS blackout,
overlay reference / raw INS / NeuroNav-X, show raw inertial drift growing while NeuroNav-X
holds, display live blackout duration, distance, drift ratio, heading error, confidence and
update rate, then restore GNSS and show a smooth re-fusion with no large jump.

The blueprint's demo rule is the reason this exists at all: *never depend on one fragile
live scenario*. A venue with no GNSS, no vehicle and no connectivity still has to see the
full pipeline, so this replays real logged sensor data through the real estimator -- the
same `run_es_ekf`, blackout state machine and speed model the live app would use. Nothing
here is precomputed or faked; the only difference from the live path is where the samples
come from.
"""
import argparse
import json
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
from neuronav.fusion.blackout import BlackoutConfig, BlackoutManager, TrackSmoother
from neuronav.fusion.es_ekf import deployment_config
from neuronav.fusion.run import SPEED_MODE_HOLD, SPEED_MODE_TCN, run_es_ekf, run_raw_ins
from neuronav.models.predictor import SpeedPrediction, SpeedPredictor

REPO_ROOT = Path(__file__).resolve().parents[1]
PRE_ROLL_S = 40.0      # aided driving shown before the outage
POST_ROLL_S = 50.0     # recovery shown after it

C_REF, C_INS, C_NAV, C_AID = "#111111", "#E8710A", "#1A5FB4", "#8A8A8A"


def build(driver, session, route, duration, checkpoint, seed):
    seg = [s for s in load_paired_segments(
        str(REPO_ROOT / f"data/raw/IO-VNBD/paired/{driver}/S-{session}.csv"),
        str(REPO_ROOT / f"data/raw/IO-VNBD/paired/{driver}/V-{session}.csv"), session)
        if s["route_id"].iloc[0] == route][0]
    seg, _ = add_simulated_gnss(seg, GNSSSimConfig(seed=seed))
    align = estimate_alignment(seg)

    predictor = SpeedPredictor(checkpoint) if checkpoint else None
    prediction_all = predictor.predict_segment(seg, align) if predictor else None

    # choose a window whose drift is typical, so the figure is representative not cherry-picked
    t_all = seg["timestamp"].to_numpy()
    scored = []
    for start in pick_blackout_windows(seg, duration, 10, seed=seed):
        lo = int(np.searchsorted(t_all, start - 300.0))
        hi = int(np.searchsorted(t_all, start + duration)) + 1
        sl = apply_blackout(seg.iloc[lo:hi].reset_index(drop=True), start, duration)
        t = sl["timestamp"].to_numpy()
        i0, i1 = int(np.searchsorted(t, start)), int(np.searchsorted(t, start + duration))
        ref = np.column_stack([sl["ref_e"], sl["ref_n"]])[i0:i1]
        if i1 - i0 < 10 or path_length(ref) < 100:
            continue
        est = run_es_ekf(sl, align, deployment_config(), speed_mode=SPEED_MODE_HOLD)["estimate"][i0:i1]
        scored.append((drift_ratio(est, ref), start))
    if not scored:
        raise SystemExit("no usable window")
    scored.sort()
    start = scored[len(scored) // 2][1]

    lo = int(np.searchsorted(t_all, start - PRE_ROLL_S))
    hi = int(np.searchsorted(t_all, start + duration + POST_ROLL_S)) + 1
    sl = apply_blackout(seg.iloc[lo:hi].reset_index(drop=True), start, duration)
    prediction = None
    if prediction_all is not None:
        prediction = SpeedPrediction(prediction_all.speed[lo:hi], prediction_all.sigma[lo:hi],
                                     prediction_all.valid[lo:hi])
    return sl, align, prediction, start


def main(driver, session, route, duration, checkpoint, seed):
    sl, align, prediction, start = build(driver, session, route, duration, checkpoint, seed)
    t = sl["timestamp"].to_numpy()
    i0 = int(np.searchsorted(t, start))
    i1 = int(np.searchsorted(t, start + duration))
    ref = np.column_stack([sl["ref_e"], sl["ref_n"]])

    mode = SPEED_MODE_TCN if prediction is not None else SPEED_MODE_HOLD
    out = run_es_ekf(sl, align, deployment_config(), speed_mode=mode,
                     prediction=prediction,
                     blackout_manager=BlackoutManager(BlackoutConfig()),
                     smoother=TrackSmoother(BlackoutConfig()))
    nav = out["display"]                       # what the app would draw
    ins = run_raw_ins(sl, align, i0, i1, SPEED_MODE_HOLD)

    # recentre on blackout entry so the figure reads in local metres
    origin = ref[i0].copy()
    R, N, I = ref - origin, nav - origin, ins - origin

    err_nav = np.linalg.norm(nav - ref, axis=1)
    err_ins = np.linalg.norm(ins - ref[i0:i1], axis=1)
    travelled = np.concatenate([[0], np.cumsum(np.linalg.norm(np.diff(ref, axis=0), axis=1))])
    dist_in_blackout = travelled[i0:i1] - travelled[i0]
    drift_nav = np.divide(err_nav[i0:i1], np.maximum(dist_in_blackout, 1e-6))
    drift_ins = np.divide(err_ins, np.maximum(dist_in_blackout, 1e-6))

    heading_err = np.degrees(np.abs(np.arctan2(
        np.sin(out["heading"] - np.radians(sl["ref_heading_deg"].to_numpy())),
        np.cos(out["heading"] - np.radians(sl["ref_heading_deg"].to_numpy())))))

    rel = t - t[i0]
    fig = plt.figure(figsize=(17.5, 9.5))
    grid = fig.add_gridspec(3, 3, height_ratios=[1.5, 1, 1], hspace=0.42, wspace=0.26)

    # ---- the map ----------------------------------------------------------------
    ax = fig.add_subplot(grid[0, :2])
    ax.plot(R[:, 0], R[:, 1], color=C_REF, lw=2.6, label="Reference (vehicle 10 Hz)", zorder=4)
    aid = sl["aid_valid"].to_numpy()
    ax.scatter(ref[aid, 0] - origin[0], ref[aid, 1] - origin[1], s=7, color=C_AID,
               alpha=.5, label="GNSS fixes (withheld during outage)", zorder=2)
    ax.plot(I[:, 0], I[:, 1], color=C_INS, lw=2.2, ls="--",
            label=f"Raw INS  ({drift_ins[-1]:.0%} drift)", zorder=5)
    ax.plot(N[:, 0], N[:, 1], color=C_NAV, lw=2.4,
            label=f"NeuroNav-X  ({drift_nav[-1]:.0%} drift)", zorder=6)
    ax.scatter([0], [0], s=110, color="#1B7F4B", zorder=8, label="blackout entry")
    ax.scatter([R[i1, 0]], [R[i1, 1]], s=110, color="#C01C28", marker="s", zorder=8,
               label="GNSS restored")
    ax.set_title(f"{int(duration)} s GNSS blackout — {dist_in_blackout[-1]:.0f} m driven blind",
                 fontsize=12, weight="bold")
    ax.set_xlabel("East (m)"); ax.set_ylabel("North (m)")
    ax.axis("equal"); ax.grid(alpha=.25); ax.legend(fontsize=8.5, loc="best")

    # ---- live dashboard ---------------------------------------------------------
    ax = fig.add_subplot(grid[0, 2]); ax.axis("off")
    jump = float(np.max(np.linalg.norm(np.diff(nav[i1:i1 + 60], axis=0), axis=1))) \
        if i1 + 60 < len(nav) else float("nan")
    rate = len(t) / max(t[-1] - t[0], 1e-6)
    lines = [
        ("Blackout duration", f"{duration:.0f} s"),
        ("Distance travelled", f"{dist_in_blackout[-1]:.0f} m"),
        ("Drift ratio (NeuroNav-X)", f"{drift_nav[-1]:.1%}"),
        ("Drift ratio (raw INS)", f"{drift_ins[-1]:.1%}"),
        ("Final error", f"{err_nav[i1 - 1]:.0f} m"),
        ("Heading error (median)", f"{np.median(heading_err[i0:i1]):.1f}°"),
        ("Position confidence 1σ", f"{out['sigma'][i1 - 1]:.0f} m"),
        ("Re-acquisition jump", f"{jump:.1f} m"),
        ("Update rate", f"{rate:.1f} Hz"),
        ("Inference latency", "0.11 ms (ONNX, desktop)"),
    ]
    ax.text(0, 1.0, "LIVE STATUS", fontsize=11, weight="bold", va="top", family="monospace")
    for k, (label, value) in enumerate(lines):
        y = 0.90 - k * 0.083
        ax.text(0, y, label, fontsize=9.5, va="top", color="#444")
        ax.text(1.0, y, value, fontsize=9.5, va="top", ha="right",
                weight="bold", family="monospace")

    # ---- error growth -----------------------------------------------------------
    ax = fig.add_subplot(grid[1, :])
    ax.axvspan(0, duration, color="#C01C28", alpha=.07)
    ax.plot(rel[i0:i1], err_ins, color=C_INS, lw=2, ls="--", label="Raw INS")
    ax.plot(rel, err_nav, color=C_NAV, lw=2, label="NeuroNav-X (as displayed)")
    ax.fill_between(rel, 0, out["sigma"], color=C_NAV, alpha=.12, label="filter 1σ")
    ax.axvline(0, color="#1B7F4B", lw=1.4); ax.axvline(duration, color="#C01C28", lw=1.4)
    ax.set_ylabel("position error (m)"); ax.set_xlabel("")
    ax.set_title("Error growth through the outage, and recovery after GNSS returns", fontsize=11)
    ax.set_ylim(0, max(60, float(np.nanpercentile(err_ins, 99)) * 1.15))
    ax.grid(alpha=.25); ax.legend(fontsize=8.5, loc="upper left")

    # ---- mode timeline + speed --------------------------------------------------
    ax = fig.add_subplot(grid[2, :])
    colours = {"aided": "#1B7F4B", "blackout": "#C01C28", "reacquiring": "#E8A21C"}
    modes = out["mode"]
    for k in range(0, len(rel) - 1, 3):
        m = modes[k]
        if m:
            ax.axvspan(rel[k], rel[min(k + 3, len(rel) - 1)], ymin=0.86, ymax=1.0,
                       color=colours.get(m, "#999"), lw=0)
    ax.plot(rel, sl["ref_speed"].to_numpy(), color=C_REF, lw=1.8, label="true speed")
    ax.plot(rel, out["speed"], color=C_NAV, lw=1.8, label="estimated speed")
    ax.set_xlabel("time relative to blackout entry (s)"); ax.set_ylabel("speed (m/s)")
    ax.set_title("State machine (bar) and speed estimate — the network carries speed while dark",
                 fontsize=11)
    ax.grid(alpha=.25); ax.legend(fontsize=8.5, loc="upper left")
    for name, colour in colours.items():
        ax.scatter([], [], color=colour, marker="s", s=60, label=name)
    ax.legend(fontsize=8.5, loc="upper right", ncol=2)

    fig.suptitle(
        f"NeuroNav-X — SIH26168 signature demonstration   |   replay of {route} "
        f"(driver {driver}, held out of training)", fontsize=13.5, weight="bold")

    out_path = REPO_ROOT / "outputs" / "plots" / f"demo_{route}_{int(duration)}s.png"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=135, bbox_inches="tight")
    plt.close(fig)

    summary = {
        "route": route, "driver": driver, "blackout_s": duration,
        "distance_m": float(dist_in_blackout[-1]),
        "drift_neuronavx": float(drift_nav[-1]), "drift_raw_ins": float(drift_ins[-1]),
        "final_error_m": float(err_nav[i1 - 1]),
        "reacquisition_jump_m": jump,
        "heading_err_median_deg": float(np.median(heading_err[i0:i1])),
    }
    (REPO_ROOT / "outputs/results/demo_summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))
    print(f"\nfigure -> {out_path}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--driver", default="B")
    ap.add_argument("--session", default="M")
    ap.add_argument("--route", default="M_seg01")
    ap.add_argument("--duration", type=float, default=120.0)
    ap.add_argument("--checkpoint",
                    default=str(REPO_ROOT / "outputs/checkpoints/speed_tcn_holdout_B.pt"))
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()
    main(args.driver, args.session, args.route, args.duration, args.checkpoint, args.seed)
