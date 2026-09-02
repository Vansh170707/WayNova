"""Windowed IMU dataset for the learned speed model.

Feature design follows the blueprint's architecture: raw sensors are first put through
alignment/calibration, and the network then sees vehicle-frame quantities rather than
arbitrary device axes. That matters here for a concrete reason -- the audit showed the
gyro yaw channel and the accelerometer gain both vary per session, so a network fed raw
device axes would have to memorise per-phone conventions instead of learning motion.

Every feature is derived from smartphone-only channels plus the online calibration, so
the same computation is available on-device. Labels come from the vehicle reference and
are never inputs.
"""
from dataclasses import dataclass

import numpy as np
import pandas as pd

from neuronav.calibration.alignment import Alignment
from neuronav.calibration.signals import gravity_unit, horizontal_acceleration

FEATURE_NAMES = [
    "a_forward",     # longitudinal acceleration, vehicle frame
    "a_lateral",     # lateral acceleration, vehicle frame
    "a_vertical",    # specific force along gravity, minus 1 g
    "yaw_rate",      # calibrated vehicle yaw rate
    "a_horiz_mag",   # magnitude of horizontal specific force
    "jerk",          # |d/dt| of total specific force -- a vibration proxy
]
N_FEATURES = len(FEATURE_NAMES)

DEFAULT_WINDOW_S = 6.0
DEFAULT_STRIDE_S = 0.5


def compute_features(seg: pd.DataFrame, align: Alignment) -> np.ndarray:
    """(N, N_FEATURES) array of calibrated, vehicle-frame inertial features."""
    h = horizontal_acceleration(seg)
    c, s = np.cos(align.forward_angle_rad), np.sin(align.forward_angle_rad)
    a_forward = (h[:, 0] * c + h[:, 1] * s) * align.forward_accel_scale
    a_lateral = (-h[:, 0] * s + h[:, 1] * c) * align.forward_accel_scale

    a = np.column_stack([seg["ax"], seg["ay"], seg["az"]])
    up = gravity_unit(seg)
    a_vertical = np.sum(a * up, axis=1) - 9.80665

    yaw = align.raw_yaw_rate(seg)
    a_horiz_mag = np.hypot(h[:, 0], h[:, 1])
    jerk = np.abs(np.diff(np.linalg.norm(a, axis=1), prepend=np.linalg.norm(a[0])))

    feats = np.column_stack([a_forward, a_lateral, a_vertical, yaw, a_horiz_mag, jerk])
    return np.nan_to_num(feats, nan=0.0, posinf=0.0, neginf=0.0).astype(np.float32)


@dataclass
class WindowedSet:
    X: np.ndarray          # (n_windows, N_FEATURES, window_len)
    y: np.ndarray          # (n_windows,) forward speed at the window's final sample
    route: np.ndarray      # (n_windows,) route id per window
    driver: np.ndarray     # (n_windows,) driver id per window

    def __len__(self):
        return len(self.y)


def make_windows(seg: pd.DataFrame, align: Alignment, driver: str,
                 window_s: float = DEFAULT_WINDOW_S,
                 stride_s: float = DEFAULT_STRIDE_S,
                 min_speed: float = None) -> WindowedSet:
    """Slice one segment into causal windows labelled by speed at the window's END.

    Labelling at the end (not the centre) keeps the model causal: everything it sees
    precedes the instant it predicts, which is what a live navigation loop can supply.
    """
    t = seg["timestamp"].to_numpy()
    rate = len(seg) / max(t[-1] - t[0], 1e-6)
    win = max(int(round(window_s * rate)), 8)
    stride = max(int(round(stride_s * rate)), 1)

    feats = compute_features(seg, align)
    speed = seg["ref_speed"].to_numpy()
    route = seg["route_id"].iloc[0]

    starts = np.arange(0, len(seg) - win, stride)
    keep, X = [], []
    for a in starts:
        b = a + win
        label = speed[b - 1]
        if not np.isfinite(label):
            continue
        if min_speed is not None and label < min_speed:
            continue
        X.append(feats[a:b].T)
        keep.append(b - 1)

    if not X:
        return WindowedSet(np.zeros((0, N_FEATURES, win), np.float32),
                           np.zeros(0, np.float32), np.array([]), np.array([]))
    keep = np.asarray(keep)
    return WindowedSet(
        X=np.stack(X).astype(np.float32),
        y=speed[keep].astype(np.float32),
        route=np.array([route] * len(keep)),
        driver=np.array([driver] * len(keep)),
    )


def concat(sets: list[WindowedSet]) -> WindowedSet:
    sets = [s for s in sets if len(s)]
    if not sets:
        raise ValueError("no windows")
    return WindowedSet(
        X=np.concatenate([s.X for s in sets]),
        y=np.concatenate([s.y for s in sets]),
        route=np.concatenate([s.route for s in sets]),
        driver=np.concatenate([s.driver for s in sets]),
    )


class FeatureScaler:
    """Per-channel standardisation, fitted on training windows only."""

    def __init__(self):
        self.mean = None
        self.std = None

    def fit(self, X: np.ndarray) -> "FeatureScaler":
        self.mean = X.mean(axis=(0, 2), keepdims=True).astype(np.float32)
        self.std = (X.std(axis=(0, 2), keepdims=True) + 1e-6).astype(np.float32)
        return self

    def transform(self, X: np.ndarray) -> np.ndarray:
        return ((X - self.mean) / self.std).astype(np.float32)

    def state_dict(self) -> dict:
        return {"mean": self.mean.tolist(), "std": self.std.tolist()}

    def load_state_dict(self, state: dict) -> "FeatureScaler":
        self.mean = np.asarray(state["mean"], dtype=np.float32)
        self.std = np.asarray(state["std"], dtype=np.float32)
        return self
