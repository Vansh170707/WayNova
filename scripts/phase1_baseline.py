"""Phase 1 screening deliverable (Section 24 of the blueprint):

Load an IO-VNBD smartphone session, plot the GNSS reference trajectory with its
accuracy metadata, simulate a GNSS blackout, run the raw inertial-integration (B1)
baseline through it, and report drift ratio / ATE.
"""
import argparse
import json
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

from neuronav.data.loader import load_iovnbd_session
from neuronav.data.blackout import add_blackout_window
from neuronav.fusion.raw_ins import raw_dead_reckoning
from neuronav.evaluation.metrics import drift_ratio, ate_rmse, final_position_error, path_length
from neuronav.utils.geo import latlon_to_enu

REPO_ROOT = Path(__file__).resolve().parents[1]


def run(session_csv: Path, route_id: str, blackout_start: float, blackout_len: float, out_dir: Path):
    df = load_iovnbd_session(str(session_csv), route_id=route_id)
    df = add_blackout_window(df, blackout_start, blackout_start + blackout_len)

    result = raw_dead_reckoning(df, blackout_start, blackout_start + blackout_len)
    est, ref = result["ins_position"], result["gnss_reference"]

    metrics = {
        "route_id": route_id,
        "session_csv": str(session_csv),
        "blackout_start_s": blackout_start,
        "blackout_duration_s": blackout_len,
        "n_samples_in_window": len(result["t"]),
        "distance_travelled_m": path_length(ref),
        "final_position_error_m": final_position_error(est, ref),
        "drift_ratio_final": drift_ratio(est, ref, use_max=False),
        "drift_ratio_max": drift_ratio(est, ref, use_max=True),
        "ate_rmse_m": ate_rmse(est, ref),
        "gnss_accuracy_mean_m": float(np.mean(result["gnss_accuracy"])),
        "gnss_accuracy_median_m": float(np.median(result["gnss_accuracy"])),
    }

    lat0, lon0, alt0 = df["lat"].iloc[0], df["lon"].iloc[0], df["alt"].iloc[0]
    full_e, full_n, _ = latlon_to_enu(df["lat"], df["lon"], df["alt"], lat0, lon0, alt0)

    out_dir.mkdir(parents=True, exist_ok=True)
    fig, axes = plt.subplots(1, 3, figsize=(16, 5))

    axes[0].plot(full_e, full_n, color="#888", lw=1, label="GNSS reference (full trip)")
    mask = df["blackout_mask"]
    axes[0].plot(full_e[mask], full_n[mask], color="crimson", lw=2, label="blackout window")
    axes[0].set_title(f"{route_id}: full trip")
    axes[0].set_xlabel("East (m)"); axes[0].set_ylabel("North (m)")
    axes[0].axis("equal"); axes[0].legend(fontsize=8)

    axes[1].plot(ref[:, 0], ref[:, 1], color="black", lw=2, label="GNSS reference")
    axes[1].plot(est[:, 0], est[:, 1], color="crimson", lw=2, label="Raw INS (B1)")
    axes[1].scatter([ref[0, 0]], [ref[0, 1]], color="green", zorder=5, label="blackout entry")
    axes[1].set_title(f"Blackout window ({blackout_len:.0f}s): GNSS vs raw INS")
    axes[1].set_xlabel("East (m)"); axes[1].set_ylabel("North (m)")
    axes[1].axis("equal"); axes[1].legend(fontsize=8)

    axes[2].hist(df["accuracy"].dropna(), bins=30, color="#4472C4")
    axes[2].set_title("GNSS reported accuracy distribution")
    axes[2].set_xlabel("accuracy (m)"); axes[2].set_ylabel("count")

    fig.suptitle(
        f"drift_ratio(final)={metrics['drift_ratio_final']:.2%}  "
        f"ATE={metrics['ate_rmse_m']:.1f} m  "
        f"dist={metrics['distance_travelled_m']:.0f} m"
    )
    fig.tight_layout()
    plot_path = out_dir / f"phase1_{route_id}.png"
    fig.savefig(plot_path, dpi=130)
    plt.close(fig)

    metrics_path = REPO_ROOT / "outputs" / "results" / f"phase1_{route_id}.json"
    metrics_path.parent.mkdir(parents=True, exist_ok=True)
    metrics_path.write_text(json.dumps(metrics, indent=2))

    return metrics, plot_path, metrics_path


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--session", default="data/raw/IO-VNBD/S_DriverA/S-S1.csv")
    parser.add_argument("--route-id", default="DriverA_S1")
    parser.add_argument("--blackout-start", type=float, default=60.0)
    parser.add_argument("--blackout-len", type=float, default=30.0)
    args = parser.parse_args()

    metrics, plot_path, metrics_path = run(
        REPO_ROOT / args.session,
        args.route_id,
        args.blackout_start,
        args.blackout_len,
        REPO_ROOT / "outputs" / "plots",
    )
    print(json.dumps(metrics, indent=2))
    print(f"\nplot -> {plot_path}")
    print(f"metrics -> {metrics_path}")
