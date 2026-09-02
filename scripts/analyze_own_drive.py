"""Turn an Android drive log into the Phase 9 calibration/physics evidence package."""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

from neuronav.data.own_drive import load_own_drive, summarise
from neuronav.evaluation.own_drive import calibration_convergence, centripetal_metrics

REPO_ROOT = Path(__file__).resolve().parents[1]


def plot_convergence(table, path: Path):
    ok = table[table["status"] == "ok"]
    fig, axes = plt.subplots(2, 2, figsize=(11, 7), sharex=True)
    specs = [
        ("forward_angle_error_deg", "Forward-axis error", "deg"),
        ("forward_accel_scale", "Forward-acceleration scale", "gain"),
        ("yaw_scale_error_pct", "Yaw-scale error", "% of full-drive fit"),
        ("gyro_bias_error_deg_120s", "Gyro-bias consequence over 120 s", "deg"),
    ]
    for ax, (column, title, unit) in zip(axes.flat, specs):
        ax.plot(ok["prefix_min"], ok[column], marker="o", linewidth=1.8)
        ax.set_title(title)
        ax.set_ylabel(unit)
        ax.grid(alpha=0.25)
    for ax in axes[-1]:
        ax.set_xlabel("aided driving prefix (minutes)")
    fig.suptitle("Phone-to-vehicle calibration convergence")
    fig.tight_layout()
    fig.savefig(path, dpi=160)
    plt.close(fig)


def json_ready(value):
    if isinstance(value, dict):
        return {k: json_ready(v) for k, v in value.items()}
    if isinstance(value, list):
        return [json_ready(v) for v in value]
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.floating,)):
        return None if not np.isfinite(value) else float(value)
    if isinstance(value, float) and not np.isfinite(value):
        return None
    return value


def analyse(path: Path, out_root: Path):
    segments = load_own_drive(str(path))
    if not segments:
        raise SystemExit(f"{path}: no usable segments")
    print(f"{path}: {len(segments)} usable segment(s)")

    for index, segment in enumerate(segments):
        route = str(segment["route_id"].iloc[0])
        out_dir = out_root / route
        out_dir.mkdir(parents=True, exist_ok=True)
        quality = summarise([segment])
        convergence, alignment = calibration_convergence(segment)
        centripetal = centripetal_metrics(segment, alignment)

        quality.to_csv(out_dir / "quality.csv", index=False)
        convergence.to_csv(out_dir / "calibration_convergence.csv", index=False)
        plot_convergence(convergence, out_dir / "calibration_convergence.png")

        final_alignment = {
            "forward_angle_deg": float(np.degrees(alignment.forward_angle_rad)),
            "forward_accel_scale": alignment.forward_accel_scale,
            "forward_accel_corr": alignment.forward_accel_corr,
            "yaw_channel": alignment.yaw_channel,
            "yaw_scale": alignment.yaw_scale,
            "yaw_corr": alignment.yaw_corr,
            "gyro_bias_rad_s": alignment.gyro_bias_rad_s,
        }
        report = {
            "input": str(path.resolve()),
            "segment_index": index,
            "route_id": route,
            "quality": quality.iloc[0].to_dict(),
            "full_drive_alignment": final_alignment,
            "centripetal": centripetal,
            "first_successful_calibration_min": (
                float(convergence.loc[convergence["status"] == "ok", "prefix_min"].min())),
            "outputs": {
                "quality_csv": "quality.csv",
                "convergence_csv": "calibration_convergence.csv",
                "convergence_plot": "calibration_convergence.png",
            },
        }
        (out_dir / "analysis.json").write_text(
            json.dumps(json_ready(report), indent=2) + "\n")

        print(f"\n{route}")
        print(quality.to_string(index=False))
        print("\ncalibration convergence:")
        cols = ["prefix_min", "status", "forward_angle_error_deg",
                "yaw_scale_error_pct", "gyro_bias_error_deg_120s"]
        print(convergence[cols].to_string(index=False))
        print("\ncentripetal holdout:")
        for key, value in centripetal.items():
            print(f"  {key:28s} {value}")
        print(f"evidence -> {out_dir}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("input", type=Path, help="CSV recorded by the Android logger")
    ap.add_argument("--out-dir", type=Path,
                    default=REPO_ROOT / "outputs" / "own_drive")
    args = ap.parse_args()
    analyse(args.input, args.out_dir)
