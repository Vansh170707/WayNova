"""Build an on-device replay log, plus the desktop answer it must reproduce.

Why this exists
---------------
The Android estimator is a second implementation of a pipeline that already works on the
desktop, and a second implementation that is never compared against the first is a
liability rather than a deliverable. This script emits a drive in the **logger's own CSV
schema** -- the same file the app would have recorded -- so replaying it on the phone
exercises the real ingestion path, and a sidecar JSON carrying the desktop estimator's
output on that exact file, so the two can be compared numerically.

It also unblocks the phase: a scripted GNSS outage in a recorded log is available today,
whereas driving through a real one is not.

The written file contains smartphone channels only. The vehicle reference is kept out of
the CSV and lives in the sidecar, so nothing the app can read is ground truth.
"""
import argparse
import json
import shutil
from pathlib import Path

import numpy as np
import pandas as pd

from neuronav.calibration.alignment import estimate_alignment
from neuronav.data.gnss_sim import GNSSSimConfig, add_simulated_gnss, apply_blackout
from neuronav.data.reference import load_paired_segments
from neuronav.evaluation.metrics import drift_ratio, final_position_error, path_length
from neuronav.fusion.blackout import BlackoutConfig, BlackoutManager, TrackSmoother
from neuronav.fusion.es_ekf import deployment_config
from neuronav.fusion.run import SPEED_MODE_HOLD, SPEED_MODE_TCN, run_es_ekf
from neuronav.models.predictor import SpeedPredictor
from neuronav.utils.geo import enu_to_latlon

REPO_ROOT = Path(__file__).resolve().parents[1]
LOGGER_COLUMNS = [
    "timestamp_ms", "ax", "ay", "az", "gx", "gy", "gz", "mx", "my", "mz",
    "grav_x", "grav_y", "grav_z", "lat", "lon", "alt", "speed", "accuracy",
    "bearing", "bearing_acc", "gps_fresh",
]


def load_segment(driver: str, session: str, route: str, seed: int):
    phone = REPO_ROOT / f"data/raw/IO-VNBD/paired/{driver}/S-{session}.csv"
    vehicle = REPO_ROOT / f"data/raw/IO-VNBD/paired/{driver}/V-{session}.csv"
    for seg in load_paired_segments(str(phone), str(vehicle), session):
        if seg["route_id"].iloc[0] == route:
            return add_simulated_gnss(seg, GNSSSimConfig(seed=seed))
    raise SystemExit(f"route {route} not found in {phone}")


def to_logger_csv(seg: pd.DataFrame, origin) -> pd.DataFrame:
    """Render a simulated-aiding segment in the Android logger's schema.

    Aiding is written back as lat/lon because that is what `Location` reports; inside the
    blackout the position columns are left empty and `gps_fresh` is 0, which is exactly
    what the app sees when the receiver stops producing fixes. Nothing marks the outage --
    the on-device state machine has to notice it from GNSS health, the same as live.
    """
    t = seg["timestamp"].to_numpy()
    valid = seg["aid_valid"].to_numpy()
    lat, lon, alt = enu_to_latlon(seg["aid_e"].to_numpy(), seg["aid_n"].to_numpy(),
                                  np.zeros(len(seg)), *origin)
    blank = ~valid
    lat = np.where(blank, np.nan, lat)
    lon = np.where(blank, np.nan, lon)
    alt = np.where(blank, np.nan, alt)

    bearing_valid = seg["aid_bearing_valid"].to_numpy()
    bearing_deg = np.degrees(seg["aid_bearing"].to_numpy()) % 360.0

    out = pd.DataFrame({
        "timestamp_ms": (t - t[0]) * 1000.0,
        "ax": seg["ax"], "ay": seg["ay"], "az": seg["az"],
        "gx": seg["gx"], "gy": seg["gy"], "gz": seg["gz"],
        "mx": seg.get("mx", np.nan), "my": seg.get("my", np.nan),
        "mz": seg.get("mz", np.nan),
        "grav_x": seg["grav_x"], "grav_y": seg["grav_y"], "grav_z": seg["grav_z"],
        "lat": lat, "lon": lon, "alt": alt,
        "speed": np.where(valid, seg["aid_speed"].to_numpy(), np.nan),
        "accuracy": np.where(valid, seg["aid_accuracy"].to_numpy(), np.nan),
        "bearing": np.where(valid & bearing_valid, bearing_deg, np.nan),
        # the accuracy the simulated receiver would report for that course
        "bearing_acc": np.where(valid & bearing_valid,
                                np.degrees(seg["aid_bearing_sigma"].to_numpy()), np.nan),
        "gps_fresh": valid.astype(int),
    })
    return out[LOGGER_COLUMNS]


def desktop_answer(seg, align, prediction, i0, i1) -> dict:
    """Run the desktop estimator on the same data, as the target for the port.

    Configured to match what a live system can actually do: the blackout state machine
    detects the outage from GNSS health rather than being handed the mask, and the track
    smoother runs, so the numbers here are reproducible by the phone rather than by an
    offline evaluator with privileged information.
    """
    mode = SPEED_MODE_TCN if prediction is not None else SPEED_MODE_HOLD
    manager = BlackoutManager(BlackoutConfig())
    out = run_es_ekf(seg, align, deployment_config(), mode, prediction,
                     blackout_manager=manager, smoother=TrackSmoother(BlackoutConfig()),
                     use_manager_mode=True)
    ref = np.column_stack([seg["ref_e"].to_numpy(), seg["ref_n"].to_numpy()])
    est = out["estimate"]
    window = slice(i0, i1)
    stride = max(len(est) // 400, 1)
    return {
        # a downsampled trace of the desktop estimate, so a port that disagrees can be
        # told WHERE it started disagreeing instead of only that it did
        "track_stride": stride,
        "track_enu": [[round(float(e), 2), round(float(n), 2)]
                      for e, n in est[::stride]],
        "final_error_m": round(float(final_position_error(est[window], ref[window])), 3),
        "drift_ratio": round(float(drift_ratio(est[window], ref[window])), 5),
        "distance_m": round(float(path_length(ref[window])), 2),
        "speed_at_exit_ms": round(float(out["speed"][i1 - 1]), 4),
        "heading_at_exit_deg": round(float(np.degrees(out["heading"][i1 - 1])) % 360.0, 3),
        "sigma_at_exit_m": round(float(out["sigma"][i1 - 1]), 3),
        "east_at_exit_m": round(float(est[i1 - 1, 0]), 3),
        "north_at_exit_m": round(float(est[i1 - 1, 1]), 3),
        "display_east_at_exit_m": round(float(out["display"][i1 - 1, 0]), 3),
        "display_north_at_exit_m": round(float(out["display"][i1 - 1, 1]), 3),
        "gnss_updates": int(out["n_updates"]),
        "east_at_end_m": round(float(est[-1, 0]), 3),
        "north_at_end_m": round(float(est[-1, 1]), 3),
        "max_display_lag_m": round(float(out["max_display_lag_m"]), 2),
    }


def main(driver, session, route, blackout_start, blackout_s, seed, checkpoint, out_name,
         warmup_s=600.0, tail_s=60.0, install_test_assets=False):
    full, origin = load_segment(driver, session, route, seed)
    t_full = full["timestamp"].to_numpy()
    start = t_full[0] + blackout_start

    # Only a slice around the outage is written. The warmup has to be long enough for the
    # on-device calibration to converge from nothing -- it is the same estimator the
    # desktop runs, but it has to earn its alignment, gyro bias and yaw scale from the
    # aided stretch rather than being handed them.
    lo = int(np.searchsorted(t_full, start - warmup_s))
    hi = int(np.searchsorted(t_full, start + blackout_s + tail_s)) + 1
    # Begin the file ON a fix. Offline, `_initial_state` reaches forward to the first fix
    # in the segment and places the filter there at t=0, which a live system cannot do --
    # it has to wait for the fix to arrive. Starting the slice at a fix removes that
    # asymmetry so the two implementations are comparable at all.
    aid_valid = full["aid_valid"].to_numpy()
    first_fix = np.flatnonzero(aid_valid[lo:hi])
    if len(first_fix) == 0:
        raise SystemExit("no GNSS fixes in the requested slice")
    lo += int(first_fix[0])
    seg = full.iloc[lo:hi].reset_index(drop=True)
    if len(seg) < 2000:
        raise SystemExit(f"slice is only {len(seg)} rows; widen --warmup-s or move the window")

    seg = apply_blackout(seg, start, blackout_s)
    t = seg["timestamp"].to_numpy()
    i0 = int(np.searchsorted(t, start))
    i1 = int(np.searchsorted(t, start + blackout_s))
    if i1 - i0 < 10:
        raise SystemExit("blackout window is outside the segment")

    # Calibrated on the FULL session, not on the written slice, and the reason is a
    # measured limitation worth stating plainly: the yaw channel, its scale and the gyro
    # bias all converge inside a few minutes, but the forward-axis ANGLE does not. On a
    # 13-minute slice it lands 37 deg away from the whole-session answer and drags the
    # forward-acceleration scale from 0.15 to 0.001, which leaves two of the six model
    # features effectively zeroed. Using the session calibration here keeps the replay a
    # test of the estimator rather than of how unlucky the slice was; how close the phone's
    # own online calibration gets is measured separately, in NavigationParityTest.
    align = estimate_alignment(full, use_reference=False, robust=True)
    predictor = SpeedPredictor(checkpoint) if checkpoint else None
    prediction = predictor.predict_segment(seg, align) if predictor else None

    out_dir = REPO_ROOT / "outputs" / "replay"
    out_dir.mkdir(parents=True, exist_ok=True)
    csv_path = out_dir / f"{out_name}.csv"
    to_logger_csv(seg, origin).to_csv(csv_path, index=False, float_format="%.6f")

    ref = np.column_stack([seg["ref_e"].to_numpy(), seg["ref_n"].to_numpy()])
    stride = max(len(seg) // 400, 1)
    sidecar = {
        "route_id": route, "driver": driver, "seed": seed,
        "origin_lat_lon_alt": [float(origin[0]), float(origin[1]), float(origin[2])],
        "rows": int(len(seg)),
        "rate_hz": round(float(len(seg) / max(t[-1] - t[0], 1e-6)), 3),
        "blackout": {"start_s": round(float(t[i0] - t[0]), 2),
                     "duration_s": float(blackout_s),
                     "first_row": i0, "last_row": i1 - 1},
        # the calibration the desktop settled on, so a port can be checked stage by stage
        # rather than only at the end, where every error looks the same
        "alignment": {
            "forward_angle_rad": round(float(align.forward_angle_rad), 6),
            "forward_accel_scale": round(float(align.forward_accel_scale), 6),
            "gyro_bias_rad_s": round(float(align.gyro_bias_rad_s), 8),
            "yaw_channel": int(align.yaw_channel),
            "yaw_scale": round(float(align.yaw_scale), 6),
        },
        "desktop": desktop_answer(seg, align, prediction, i0, i1),
        "reference_enu": [[round(float(e), 2), round(float(n), 2)]
                          for e, n in ref[::stride]],
        "reference_stride": stride,
    }
    json_path = out_dir / f"{out_name}.json"
    json_path.write_text(json.dumps(sidecar, indent=2))

    if install_test_assets:
        asset_dir = REPO_ROOT / "mobile/app/src/androidTest/assets"
        asset_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(csv_path, asset_dir / csv_path.name)
        shutil.copy2(json_path, asset_dir / json_path.name)
        print(f"test assets  -> {asset_dir}")

    size_mb = csv_path.stat().st_size / 1e6
    print(f"replay log  -> {csv_path}  ({sidecar['rows']} rows, "
          f"{sidecar['rate_hz']:.1f} Hz, {size_mb:.1f} MB)")
    print(f"sidecar     -> {json_path}")
    print(f"blackout    :  {blackout_s:.0f} s from t+{blackout_start:.0f} s "
          f"(rows {i0}-{i1 - 1})")
    print("desktop answer the port must reproduce:")
    for k, v in sidecar["desktop"].items():
        print(f"  {k:22s} {v}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--driver", default="B")
    ap.add_argument("--session", default="M")
    ap.add_argument("--route", default="M_seg00")
    ap.add_argument("--blackout-start", type=float, default=646.5,
                    help="seconds from the START OF THE SEGMENT, not of the written file")
    ap.add_argument("--warmup-s", type=float, default=600.0)
    ap.add_argument("--tail-s", type=float, default=60.0)
    ap.add_argument("--blackout-s", type=float, default=120.0)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--checkpoint",
                    default=str(REPO_ROOT / "outputs/checkpoints/speed_tcn_holdout_B.pt"))
    ap.add_argument("--out", default="replay_M_seg00")
    ap.add_argument("--install-test-assets", action="store_true",
                    help="copy the generated CSV and sidecar into Android test assets")
    args = ap.parse_args()
    main(args.driver, args.session, args.route, args.blackout_start, args.blackout_s,
         args.seed, args.checkpoint, args.out, args.warmup_s, args.tail_s,
         args.install_test_assets)
