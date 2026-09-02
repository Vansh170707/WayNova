"""Derived inertial signals, using conventions validated empirically against IO-VNBD.

Validation summary (see docs/data_audit.md):

* The GRAVITY columns agree with the accelerometer to within 0.02-0.44 deg, so the
  gravity vector is the trustworthy attitude reference. The ORIENTATION columns are
  NOT self-consistent with it and are never used here.
* The reported gravity vector points UP (it is the specific-force direction: +Z when
  the phone lies flat, screen up).
* Heading rate is the angular-rate component along that up axis, negated, because a
  right-handed rotation about "up" is counter-clockwise while compass heading grows
  clockwise. Regressing integrated yaw against GNSS course change over 5 s baselines
  gave slopes of -0.974 / -0.927 / -0.963 on segments A5 / A7 / A8.
"""
import numpy as np
import pandas as pd

GRAVITY_MAGNITUDE = 9.80665


def gravity_unit(df: pd.DataFrame) -> np.ndarray:
    """(N,3) unit vector along the device-frame gravity ('up') direction."""
    g = np.column_stack([df["grav_x"], df["grav_y"], df["grav_z"]])
    norm = np.linalg.norm(g, axis=1, keepdims=True)
    return g / np.maximum(norm, 1e-9)


def angular_rate(df: pd.DataFrame) -> np.ndarray:
    """(N,3) device-frame angular rate in rad/s, in the CSV's column order."""
    return np.column_stack([df["gx"], df["gy"], df["gz"]])


def heading_rate(df: pd.DataFrame) -> np.ndarray:
    """(N,) vehicle heading rate in rad/s, positive clockwise (compass sense)."""
    return -np.sum(angular_rate(df) * gravity_unit(df), axis=1)


def linear_acceleration(df: pd.DataFrame) -> np.ndarray:
    """(N,3) device-frame acceleration with gravity removed, m/s^2."""
    a = np.column_stack([df["ax"], df["ay"], df["az"]])
    g = np.column_stack([df["grav_x"], df["grav_y"], df["grav_z"]])
    return a - g


def horizontal_basis(df: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
    """Two orthonormal device-frame vectors spanning the local horizontal plane.

    Built by projecting the device X axis onto the plane perpendicular to gravity,
    falling back to device Y where the phone is held such that X is near-vertical.
    """
    up = gravity_unit(df)
    ref = np.tile(np.array([1.0, 0.0, 0.0]), (len(up), 1))
    degenerate = np.abs(np.sum(ref * up, axis=1)) > 0.9
    ref[degenerate] = np.array([0.0, 1.0, 0.0])

    e1 = ref - up * np.sum(ref * up, axis=1, keepdims=True)
    e1 /= np.maximum(np.linalg.norm(e1, axis=1, keepdims=True), 1e-9)
    e2 = np.cross(up, e1)
    return e1, e2


def horizontal_acceleration(df: pd.DataFrame) -> np.ndarray:
    """(N,2) linear acceleration resolved in the (e1, e2) horizontal basis, m/s^2."""
    lin = linear_acceleration(df)
    e1, e2 = horizontal_basis(df)
    return np.column_stack([np.sum(lin * e1, axis=1), np.sum(lin * e2, axis=1)])


def tilt_angles(df: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
    """(pitch, roll) in radians derived from gravity, Android sign convention."""
    up = gravity_unit(df)
    pitch = np.arcsin(np.clip(-up[:, 1], -1.0, 1.0))
    roll = np.arctan2(-up[:, 0], up[:, 2])
    return pitch, roll
