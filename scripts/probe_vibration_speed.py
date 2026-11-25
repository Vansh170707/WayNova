"""Probe: does 100 Hz road vibration predict vehicle speed across held-out drives?

The shipped speed TCN only sees features decimated to 10 Hz (its IO-VNBD training data was
10 Hz). The phone records at 100 Hz, so vibration content between 5 and 50 Hz, which tyre
and road excitation push up with speed, never reaches the model. This script measures how
much speed information that band carries, with leave-one-drive-out evaluation so no drive
is ever scored by a model that saw it.

It is a probe, not a deployable model. Labels are the phone's own GNSS speed (not survey
truth) on fresh, accurate fixes.

    envs/bin/python scripts/probe_vibration_speed.py data/raw/road_heading_fixtures \
        data/raw/phone_backups/20261125_121352/external
"""
from __future__ import annotations

import glob
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.signal import welch
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.linear_model import RidgeCV
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

WINDOW_S = 2.0
RATE = 100.0
BANDS = [(0.3, 2), (2, 5), (5, 10), (10, 15), (15, 25), (25, 35), (35, 49)]


def band_powers(signal: np.ndarray) -> list[float]:
    f, p = welch(signal - signal.mean(), fs=RATE, nperseg=100)
    return [float(np.log(p[(f >= lo) & (f < hi)].sum() + 1e-9)) for lo, hi in BANDS]


def drive_windows(path: Path) -> pd.DataFrame | None:
    d = pd.read_csv(path)
    if len(d) < 6000:
        return None
    t = d.timestamp_ms.to_numpy() / 1000.0
    if np.median(np.diff(t)) > 0.015:          # not a 100 Hz recording
        return None
    acc = d[["ax", "ay", "az"]].to_numpy(float)
    grav = d[["grav_x", "grav_y", "grav_z"]].to_numpy(float)
    gyro = d[["gx", "gy", "gz"]].to_numpy(float)
    g_unit = grav / np.maximum(np.linalg.norm(grav, axis=1, keepdims=True), 1e-6)
    lin = acc - grav
    vert = (lin * g_unit).sum(1)
    horiz = np.linalg.norm(lin - vert[:, None] * g_unit, axis=1)
    gyro_mag = np.linalg.norm(gyro, axis=1)
    fresh = (d.gps_fresh == 1) & d.speed.notna() & (d.accuracy <= 12)
    rows = []
    n = int(WINDOW_S * RATE)
    for start in range(0, len(d) - n, n // 2):
        sl = slice(start, start + n)
        if np.any(np.diff(t[sl]) > 0.05):
            continue
        fix = fresh.iloc[sl]
        if fix.sum() == 0:
            continue
        speed = float(d.speed.iloc[sl][fix].mean())
        rows.append([*band_powers(vert[sl]), *band_powers(horiz[sl]), *band_powers(gyro_mag[sl]),
                     float(np.log(np.std(np.diff(vert[sl])) + 1e-9)), speed])
    cols = ([f"v{i}" for i in range(len(BANDS))] + [f"h{i}" for i in range(len(BANDS))]
            + [f"g{i}" for i in range(len(BANDS))] + ["jerk", "speed"])
    out = pd.DataFrame(rows, columns=cols)
    out["drive"] = path.stem.removeprefix("nav_drive_")
    return out


def main(roots: list[str]) -> None:
    seen, frames = set(), []
    for root in roots:
        for path in sorted(glob.glob(f"{root}/nav_drive_*.csv")):
            sid = Path(path).stem
            if sid in seen:
                continue
            seen.add(sid)
            w = drive_windows(Path(path))
            if w is not None and (w.speed > 3).sum() >= 30:
                frames.append(w)
    data = pd.concat(frames, ignore_index=True)
    features = [c for c in data.columns if c not in ("speed", "drive")]
    print(f"{len(frames)} drives, {len(data)} windows of {WINDOW_S:.0f} s")

    corr = {c: float(np.corrcoef(data[c], data.speed)[0, 1]) for c in features}
    print("strongest single-feature correlations with GPS speed:")
    for c, r in sorted(corr.items(), key=lambda kv: -abs(kv[1]))[:6]:
        print(f"  {c:5s} r={r:+.2f}")

    results = []
    for held in data.drive.unique():
        train, test = data[data.drive != held], data[data.drive == held]
        moving = test.speed > 3
        if moving.sum() < 20:
            continue
        ridge = make_pipeline(StandardScaler(), RidgeCV(alphas=np.logspace(-2, 3, 12)))
        ridge.fit(train[features], train.speed)
        gbr = HistGradientBoostingRegressor(max_iter=300, learning_rate=0.05, max_leaf_nodes=15)
        gbr.fit(train[features], train.speed)
        y = test.speed[moving].to_numpy()
        row = {"drive": held, "windows": int(moving.sum()), "mean_speed": float(y.mean()),
               "mae_constant": float(np.abs(y - train.speed[train.speed > 3].mean()).mean())}
        for name, model in (("ridge", ridge), ("gbr", gbr)):
            pred = np.clip(model.predict(test[features][moving]), 0, None)
            row[f"mae_{name}"] = float(np.abs(pred - y).mean())
            row[f"corr_{name}"] = float(np.corrcoef(pred, y)[0, 1])
            row[f"bias_{name}"] = float((pred - y).mean())
        results.append(row)
    res = pd.DataFrame(results)
    pd.set_option("display.width", 160)
    print(res.round(2).to_string(index=False))
    print("\nmean over held-out drives (moving windows, >3 m/s):")
    print(res[[c for c in res.columns if c.startswith(("mae", "corr", "bias"))]].mean().round(2).to_string())
    out = Path("outputs/own_drive/vibration_speed_probe.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"folds": results, "correlations": corr}, indent=2))
    print(f"\nwrote {out}")


if __name__ == "__main__":
    main(sys.argv[1:])
