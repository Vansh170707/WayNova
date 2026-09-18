"""Load the phone's own `nav_drive_*.csv` recordings as a trainable dataset.

The shipped speed model was trained on IO-VNBD, which is a different set of phones in a
different set of vehicles. On the V2422 its output is compressed by roughly 3.7x, and
because outage drift tracks speed error almost one-for-one, that compression is what puts
field drift at 26-58% against a <10% target.

This module turns the raw 100 Hz logs the app already writes into the same windowed
representation `neuronav.models.dataset` produces for IO-VNBD, so the existing model can
be fine-tuned on the hardware it actually has to run on.

Two properties are deliberate:

* Labels are the phone's own GNSS speed, taken only on samples the recorder marked
  `gps_fresh`. No interpolation across fixes, so a window is labelled only when a real
  fix landed at its final sample.
* The per-drive alignment is fitted from phone-only channels, exactly as the on-device
  calibration does. Nothing here uses a signal the live estimator could not obtain.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

from neuronav.calibration.alignment import Alignment, robust_line
from neuronav.calibration.signals import heading_rate, horizontal_acceleration
from neuronav.models.dataset import WindowedSet, compute_features

# The recorder samples the IMU at 100 Hz; the live estimator consumes 10 Hz. Decimation
# is by integer sample count, never by a time threshold -- timestamp jitter of 0.091 to
# 0.109 s made a threshold drop 17% of samples in an earlier build.
RAW_RATE_HZ = 100.0
TARGET_RATE_HZ = 10.0
DECIMATION = int(round(RAW_RATE_HZ / TARGET_RATE_HZ))

# A pause in recording shows up as a single multi-second gap. Windows must never span
# one, so drives are cut into contiguous segments at any gap this long.
MAX_GAP_S = 1.0
MIN_SEGMENT_S = 30.0

RAW_COLUMNS = ["ax", "ay", "az", "gx", "gy", "gz", "grav_x", "grav_y", "grav_z"]


@dataclass
class DriveSegment:
    """One contiguous stretch of a recorded drive.

    `frame` is decimated to the estimator's 10 Hz rate and is what windows are cut from.
    `raw_frame` keeps the full 100 Hz samples, because the mount alignment is a
    regression over the whole drive and wants every sample it can get.
    """

    session_id: str
    index: int
    frame: pd.DataFrame
    raw_frame: pd.DataFrame

    @property
    def route_id(self) -> str:
        return f"{self.session_id}_seg{self.index:02d}"

    @property
    def duration_s(self) -> float:
        t = self.frame["timestamp"].to_numpy()
        return float(t[-1] - t[0]) if len(t) > 1 else 0.0

    @property
    def labelled_samples(self) -> int:
        return int(np.isfinite(self.frame["ref_speed"].to_numpy()).sum())


def _boolean(series: pd.Series) -> np.ndarray:
    """CSV booleans arrive as either 1/0 or true/false depending on the writer."""
    if series.dtype == object:
        return series.astype(str).str.strip().str.lower().isin(["1", "true", "yes"]).to_numpy()
    return series.fillna(0).astype(bool).to_numpy()


def load_drive(path: Path, min_speed_label: float = 0.0) -> list[DriveSegment]:
    """Read one `nav_drive_*.csv` into decimated, gap-free segments."""
    raw = pd.read_csv(path)
    missing = [c for c in RAW_COLUMNS + ["timestamp_ms", "speed", "gps_fresh"]
               if c not in raw.columns]
    if missing:
        raise ValueError(f"{path.name} is missing columns: {missing}")

    t = raw["timestamp_ms"].to_numpy(dtype=float) / 1000.0
    fresh = _boolean(raw["gps_fresh"])
    speed = pd.to_numeric(raw["speed"], errors="coerce").to_numpy(dtype=float)

    # Label only where a real fix landed. Everything else is NaN and yields no window.
    label = np.where(fresh & np.isfinite(speed), speed, np.nan)
    if min_speed_label > 0:
        label = np.where(label >= min_speed_label, label, np.nan)

    frame = pd.DataFrame({c: raw[c].to_numpy(dtype=float) for c in RAW_COLUMNS})
    frame["timestamp"] = t
    frame["ref_speed"] = label
    # The alignment fit consumes the same GNSS the live app has while aided.
    frame["aid_valid"] = fresh & np.isfinite(speed)
    frame["aid_speed"] = np.where(frame["aid_valid"], speed, np.nan)

    session_id = path.stem.replace("nav_drive_", "")
    cuts = np.flatnonzero(np.diff(t) > MAX_GAP_S) + 1
    bounds = np.concatenate([[0], cuts, [len(frame)]])

    segments: list[DriveSegment] = []
    for index, (start, stop) in enumerate(zip(bounds[:-1], bounds[1:])):
        piece = frame.iloc[start:stop].reset_index(drop=True)
        if len(piece) < DECIMATION * 2:
            continue
        decimated = _decimate(piece)
        segment = DriveSegment(session_id, index, decimated, piece)
        if segment.duration_s < MIN_SEGMENT_S:
            continue
        decimated["route_id"] = segment.route_id
        piece["route_id"] = segment.route_id
        segments.append(segment)
    return segments


def _decimate(piece: pd.DataFrame) -> pd.DataFrame:
    """Keep every Nth sample, but never lose a GNSS fix that landed between them.

    GNSS arrives at about 1 Hz against a 100 Hz IMU, so nine times out of ten a fix sits
    on a sample plain decimation throws away. Dropping those would discard 90% of the
    labels. Each fix is therefore carried forward onto the next surviving sample, which
    is the same thing the Android reader does with its pending-fix buffer.
    """
    kept = np.arange(0, len(piece), DECIMATION)
    labels = piece["ref_speed"].to_numpy()
    aid_valid = piece["aid_valid"].to_numpy()
    aid_speed = piece["aid_speed"].to_numpy()

    carried_label = np.full(len(kept), np.nan)
    carried_valid = np.zeros(len(kept), dtype=bool)
    carried_speed = np.full(len(kept), np.nan)

    fixes = np.flatnonzero(aid_valid)
    for source in fixes:
        # The first kept sample at or after the fix; a fix past the last one is dropped.
        target = int(np.ceil(source / DECIMATION))
        if target >= len(kept):
            continue
        carried_valid[target] = True
        carried_speed[target] = aid_speed[source]
        if np.isfinite(labels[source]):
            carried_label[target] = labels[source]

    decimated = piece.iloc[kept].reset_index(drop=True)
    decimated["ref_speed"] = carried_label
    decimated["aid_valid"] = carried_valid
    decimated["aid_speed"] = carried_speed
    return decimated


def discover_drives(roots: list[Path]) -> list[Path]:
    """Every `nav_drive_*.csv` under the given directories, one per session id.

    The same drive is often present in more than one phone snapshot. Keeping both would
    leak identical windows across the train/test split, so the largest copy of each
    session id wins and the rest are dropped.
    """
    best: dict[str, Path] = {}
    for root in roots:
        if not root.exists():
            continue
        for path in sorted(root.rglob("nav_drive_*.csv")):
            session_id = path.stem.replace("nav_drive_", "")
            current = best.get(session_id)
            if current is None or path.stat().st_size > current.stat().st_size:
                best[session_id] = path
    return [best[key] for key in sorted(best)]


class GravityProjectedAlignment(Alignment):
    """An alignment whose yaw rate comes from the gravity projection.

    On the phone's native 100 Hz path the estimator does not select a gyro channel at
    all: it uses `-dot(gyro, gravity_unit)`, because Android reports gravity and angular
    rate in a common axis order. IO-VNBD does not, which is the only reason the
    channel-selection code exists. Training features must be computed the way the device
    computes them, so own-device windows use this.
    """

    def raw_yaw_rate(self, df: pd.DataFrame) -> np.ndarray:
        return heading_rate(df)


def estimate_own_device_alignment(segment: DriveSegment,
                                  min_speed: float = 2.0) -> GravityProjectedAlignment | None:
    """Fit the forward axis and yaw bias for one own-device drive.

    Only the forward-axis regression is borrowed from the IO-VNBD path. Yaw-channel
    selection is skipped entirely: it needs 200 bearing fixes and 100 baselines above
    8 m/s, which a 5-minute city drive at 1 Hz GNSS cannot supply, and the device does
    not use a selected channel anyway.

    Returns None when the drive has too little aided motion to fit anything.
    """
    frame = segment.raw_frame
    fitted = _fit_forward_axis(frame, min_speed=min_speed)
    if fitted is None:
        return None
    angle, scale, corr, samples = fitted

    yaw = heading_rate(frame)
    speed = pd.Series(frame["aid_speed"]).ffill().bfill().to_numpy()
    still = np.isfinite(speed) & (speed < 0.5)
    bias = float(np.mean(yaw[still])) if still.sum() >= 200 else float(np.median(yaw))

    return GravityProjectedAlignment(
        forward_angle_rad=angle,
        gyro_bias_rad_s=bias,
        n_samples=samples,
        forward_accel_corr=corr,
        forward_accel_scale=scale,
    )


def _fit_forward_axis(df: pd.DataFrame, min_speed: float,
                      smooth_s: float = 1.0) -> tuple[float, float, float, int] | None:
    """Regress the two horizontal accelerometer channels against d(aided speed)/dt.

    Same closed form as `estimate_alignment`, without the yaw-channel step, so a drive
    that cannot support channel selection still yields a usable forward direction.
    """
    t = df["timestamp"].to_numpy()
    rate = len(df) / max(t[-1] - t[0], 1e-6)
    win = max(int(round(smooth_s * rate)), 1)

    def smooth(x):
        return pd.Series(x).rolling(win, center=True, min_periods=1).mean().to_numpy()

    h = horizontal_acceleration(df)
    h1, h2 = smooth(h[:, 0]), smooth(h[:, 1])

    aid = df["aid_valid"].to_numpy().astype(bool)
    at = t[aid]
    av = df["aid_speed"].to_numpy()[aid]
    if len(at) < 20:
        return None

    dv = np.gradient(av, at)
    target = np.interp(t, at, dv)
    speed = np.interp(t, at, av)
    ok = (np.isfinite(target) & np.isfinite(h1) & np.isfinite(h2)
          & (speed > min_speed) & (np.abs(target) < 5.0))
    if ok.sum() < 200:
        return None

    A = np.column_stack([h1[ok], h2[ok]])
    coef, *_ = np.linalg.lstsq(A, target[ok], rcond=None)
    angle = float(np.arctan2(coef[1], coef[0]))
    projected = A @ np.array([np.cos(angle), np.sin(angle)])
    scale, _, corr = robust_line(projected, target[ok])
    return angle, float(scale), float(corr), int(ok.sum())


def windows_for_segment(segment: DriveSegment, align: Alignment,
                        window_s: float = 6.0,
                        min_speed: float | None = None) -> WindowedSet:
    """One causal window per GNSS fix, ending on the sample that fix labelled.

    `dataset.make_windows` walks a fixed stride and keeps only the windows whose final
    sample happens to carry a label. That is the right shape for IO-VNBD, where the
    reference is dense. Here the labels are 1 Hz against a 10 Hz feature stream, so a
    fixed stride throws away more than half of the little supervision there is. Anchoring
    each window on a fix instead keeps all of it, and the windows stay causal: every
    sample still precedes the instant being predicted.
    """
    frame = segment.frame
    t = frame["timestamp"].to_numpy()
    rate = len(frame) / max(t[-1] - t[0], 1e-6)
    win = max(int(round(window_s * rate)), 8)

    feats = compute_features(frame, align)
    speed = frame["ref_speed"].to_numpy()

    ends = np.flatnonzero(np.isfinite(speed))
    ends = ends[ends >= win - 1]
    if min_speed is not None:
        ends = ends[speed[ends] >= min_speed]

    if len(ends) == 0:
        empty = np.zeros((0, feats.shape[1], win), np.float32)
        return WindowedSet(empty, np.zeros(0, np.float32), np.array([]), np.array([]))

    X = np.stack([feats[end - win + 1:end + 1].T for end in ends]).astype(np.float32)
    return WindowedSet(
        X=X,
        y=speed[ends].astype(np.float32),
        route=np.array([segment.route_id] * len(ends)),
        driver=np.array([segment.session_id] * len(ends)),
    )
