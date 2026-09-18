"""Attribute a recorded outage's position drift between speed error and heading error.

The three-ride audit measured drift but not what caused it. This does, using only what
the app already logged: during `WITHHOLDING` the recorder keeps the withheld GNSS fixes
as `reference_east_m` / `reference_north_m` alongside the estimator's own speed and
heading, so the outage can be re-integrated with either channel replaced by truth.

Four integrations per ride:

* as recorded -- the estimator's own speed and heading
* oracle speed -- true speed, estimator heading
* oracle heading -- estimator speed, true heading
* oracle both -- a closure check that should land on zero

If oracle speed collapses the error and oracle heading does not, the outage is
speed-limited, and the reverse for heading. On the 13 September ride it is speed by a
wide margin, which is what justified fine-tuning the speed model on own-device data.

Optionally re-scores the same outage against one or more checkpoints' raw predictions,
which isolates model quality from the live bias adapter sitting in front of it.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from pathlib import Path

import numpy as np

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "src"))


def number(row: dict, key: str) -> float:
    value = row.get(key, "")
    try:
        return float(value) if value not in ("", None) else math.nan
    except (TypeError, ValueError):
        return math.nan


class Outage:
    """The withheld-GNSS stretch of one recorded session, ready to re-integrate."""

    def __init__(self, diagnostics: Path):
        rows = list(csv.DictReader(diagnostics.open()))
        dark = [r for r in rows if r.get("test_phase") == "WITHHOLDING"]
        if len(dark) < 20:
            raise ValueError(f"{diagnostics.name} has no usable withholding phase")

        self.t = np.array([number(r, "timestamp_ms") / 1000.0 for r in dark])
        self.heading = np.radians(np.array([number(r, "heading_deg") for r in dark]))
        self.live_speed = np.array([number(r, "speed_ms") for r in dark])
        self.adapted = np.array([number(r, "adapted_speed_ms") for r in dark])
        self.learned = np.array([number(r, "learned_speed_ms") for r in dark])
        self.bias = np.array([number(r, "speed_model_bias_ms") for r in dark])

        east = np.array([number(r, "reference_east_m") for r in dark])
        north = np.array([number(r, "reference_north_m") for r in dark])
        # A held reference repeats between 1 Hz fixes; score each fix once.
        keep = [i for i in range(len(dark))
                if np.isfinite(east[i]) and (i == 0 or east[i] != east[i - 1]
                                             or north[i] != north[i - 1])]
        if len(keep) < 5:
            raise ValueError(f"{diagnostics.name} has too few reference fixes")
        keep = np.array(keep)

        self.ref_t = self.t[keep]
        self.dt = np.diff(self.ref_t)
        self.dE = np.diff(east[keep])
        self.dN = np.diff(north[keep])
        self.true_east = np.cumsum(self.dE)
        self.true_north = np.cumsum(self.dN)
        self.distance_m = float(np.sum(np.hypot(self.dE, self.dN)))
        self.duration_s = float(self.ref_t[-1] - self.ref_t[0])

        self.ref_speed = np.hypot(self.dE, self.dN) / np.maximum(self.dt, 1e-6)
        self.ref_heading = np.arctan2(self.dE, self.dN)
        self.mid_t = (self.ref_t[:-1] + self.ref_t[1:]) / 2
        # Averaging the unit vector avoids the wrap discontinuity at north.
        self.est_heading = np.unwrap(np.arctan2(
            np.interp(self.mid_t, self.t, np.sin(self.heading)),
            np.interp(self.mid_t, self.t, np.cos(self.heading))))
        self.est_speed = np.interp(self.mid_t, self.t, self.live_speed)

    def end_error(self, speed: np.ndarray, heading: np.ndarray) -> float:
        east = np.cumsum(speed * np.sin(heading) * self.dt)
        north = np.cumsum(speed * np.cos(heading) * self.dt)
        return float(math.hypot(east[-1] - self.true_east[-1],
                                north[-1] - self.true_north[-1]))

    def pct(self, error_m: float) -> float:
        return 100.0 * error_m / max(self.distance_m, 1e-6)

    def heading_error_deg(self) -> float:
        delta = (self.est_heading - self.ref_heading + math.pi) % (2 * math.pi) - math.pi
        return float(np.degrees(np.mean(np.abs(delta))))


def raw_model_speed(drive_csv: Path, mid_t: np.ndarray, checkpoint: Path) -> np.ndarray:
    """Prediction of one checkpoint, with no live bias adapter in front of it."""
    import torch

    from neuronav.models.dataset import FEATURE_NAMES, FeatureScaler, compute_features
    from neuronav.models.own_device import estimate_own_device_alignment, load_drive
    from neuronav.models.tcn import SpeedTCN

    segment = load_drive(drive_csv)[0]
    align = estimate_own_device_alignment(segment)
    if align is None:
        raise ValueError(f"{drive_csv.name}: alignment did not converge")

    frame = segment.frame
    feats = compute_features(frame, align)
    times = frame["timestamp"].to_numpy()

    state = torch.load(checkpoint, map_location="cpu", weights_only=False)
    window = int(round(float(state["window_s"]) * 10))
    ends = np.arange(window - 1, len(frame))
    X = np.stack([feats[e - window + 1:e + 1].T for e in ends]).astype(np.float32)

    model = SpeedTCN(len(FEATURE_NAMES), channels=int(state["channels"]))
    model.load_state_dict(state["model"])
    model.eval()
    X = FeatureScaler().load_state_dict(state["scaler"]).transform(X)

    chunks = []
    with torch.no_grad():
        for start in range(0, len(X), 1024):
            chunks.append(model(torch.from_numpy(X[start:start + 1024]))[0].numpy())
    return np.interp(mid_t, times[ends], np.concatenate(chunks))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("sessions", nargs="+",
                        help="directories holding nav_diagnostics_*.csv and nav_drive_*.csv")
    parser.add_argument("--checkpoint", action="append", default=[],
                        help="also score this checkpoint's raw output; repeatable")
    parser.add_argument("--output", type=Path,
                        default=REPO_ROOT / "outputs/results/outage_attribution.json")
    args = parser.parse_args()

    report = []
    for directory in args.sessions:
        folder = Path(directory)
        for diagnostics in sorted(folder.glob("nav_diagnostics_*.csv")):
            session = diagnostics.stem.replace("nav_diagnostics_", "")
            try:
                outage = Outage(diagnostics)
            except ValueError as exc:
                print(f"skip {session}: {exc}")
                continue

            print(f"\nsession {session}  {outage.distance_m:.0f} m over "
                  f"{outage.duration_s:.0f} s  (mean true speed "
                  f"{outage.ref_speed.mean():.2f} m/s)")
            entry = {
                "session_id": session,
                "reference_distance_m": round(outage.distance_m, 1),
                "duration_s": round(outage.duration_s, 1),
                "true_speed_mean_ms": round(float(outage.ref_speed.mean()), 2),
                "live_speed_bias_ms": round(float(np.mean(outage.est_speed - outage.ref_speed)), 2),
                "heading_error_deg": round(outage.heading_error_deg(), 2),
                "variants": {},
            }

            variants = {
                "as recorded (live pipeline)": (outage.est_speed, outage.est_heading),
                "oracle speed": (outage.ref_speed, outage.est_heading),
                "oracle heading": (outage.est_speed, outage.ref_heading),
                "oracle both": (outage.ref_speed, outage.ref_heading),
            }

            drive = folder / f"nav_drive_{session}.csv"
            for path in args.checkpoint:
                if not drive.exists():
                    print(f"  (no raw drive log for {session}; skipping checkpoints)")
                    break
                try:
                    speed = raw_model_speed(drive, outage.mid_t, REPO_ROOT / path)
                except (ValueError, IndexError) as exc:
                    print(f"  {path}: {exc}")
                    continue
                variants[f"raw {Path(path).stem}, no adapter"] = (speed, outage.est_heading)

            for name, (speed, heading) in variants.items():
                error = outage.end_error(speed, heading)
                bias = float(np.mean(speed - outage.ref_speed))
                entry["variants"][name] = {
                    "end_error_m": round(error, 1),
                    "drift_pct": round(outage.pct(error), 1),
                    "speed_bias_ms": round(bias, 2),
                }
                print(f"  {name:44s} bias={bias:+6.2f} m/s  "
                      f"end={error:7.1f} m  drift={outage.pct(error):5.1f}%")
            report.append(entry)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2))
    print(f"\nresults -> {args.output}")


if __name__ == "__main__":
    main()
