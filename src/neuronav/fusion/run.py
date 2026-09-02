"""Drive the classical baselines across a segment with a simulated GNSS blackout.

B1  raw inertial dead reckoning  -- propagates heading and speed open-loop
B3  error-state EKF              -- fuses simulated consumer GNSS, propagates through
                                    the outage using its converged bias estimates

Both are initialised identically at blackout entry so the comparison isolates what
happens DURING the outage.
"""
from dataclasses import dataclass

import numpy as np
import pandas as pd

from neuronav.calibration.alignment import Alignment
from neuronav.fusion.blackout import NavMode
from neuronav.fusion.es_ekf import (IDX_PE as IDX_PE_, IDX_PN as IDX_PN_,
                                    IDX_PSI as IDX_PSI_, IDX_V as IDX_V_, ESEKFConfig,
                                    PlanarESEKF, wrap_angle)

SPEED_MODE_ACCEL = "accel"   # integrate the phone's forward acceleration
SPEED_MODE_HOLD = "hold"     # freeze speed at its last aided value
SPEED_MODE_TCN = "tcn"       # fuse the learned speed pseudo-measurement (B5)


@dataclass
class Oracle:
    """EVALUATION ONLY: overwrite state with truth during the outage.

    Nothing here is available to a deployed system. It exists to answer one question --
    given the mechanization and the geometry, how much of the remaining blackout drift is
    caused by not knowing speed, and how much by not knowing heading? Feeding one of them
    in and measuring what is left is the only way to aim the next piece of work at the
    term that actually dominates. Never wire an Oracle into a shipped path.
    """
    speed: np.ndarray = None      # (N,) true forward speed, m/s
    heading: np.ndarray = None    # (N,) true heading, rad clockwise from North


def _initial_state(df: pd.DataFrame, start_idx: int):
    """Position, heading and speed at a given row, taken from the aiding stream.

    Heading comes from the most recent VALID reported bearing at or before the row --
    the receiver withholds bearing while stopped, so the last moving value is used.
    """
    valid = df["aid_valid"].to_numpy()
    prior = np.flatnonzero(valid[:start_idx + 1])
    k = prior[-1] if len(prior) else int(np.argmax(valid))

    bearing_valid = df["aid_bearing_valid"].to_numpy()
    bearing = df["aid_bearing"].to_numpy()
    b_prior = np.flatnonzero(bearing_valid[:start_idx + 1])
    heading = bearing[b_prior[-1]] if len(b_prior) else 0.0

    return (df["aid_e"].to_numpy()[k], df["aid_n"].to_numpy()[k],
            heading, df["aid_speed"].to_numpy()[k])


def run_es_ekf(df: pd.DataFrame, align: Alignment, config: ESEKFConfig = None,
               speed_mode: str = SPEED_MODE_ACCEL, prediction=None,
               map_matcher=None, blackout_manager=None, smoother=None,
               oracle: "Oracle" = None, use_manager_mode: bool = False) -> dict:
    """Run the ES-EKF across the whole segment, withholding aiding inside the blackout.

    `prediction` is a models.predictor.SpeedPrediction, required for SPEED_MODE_TCN. The
    learned speed is fused only while GNSS is absent: with aiding available the real fix
    is strictly better, so the network is there to carry the outage, not to compete.

    `use_manager_mode` decides what "GNSS is absent" means. By default it is the evaluation
    mask, which is right for scoring a known outage window. A deployed system has no mask
    and must infer the outage from GNSS health, so the Android port keys off the blackout
    manager instead -- including the few seconds before the manager has declared a
    blackout, during which it still integrates the accelerometer. Setting this makes the
    desktop run reproduce that behaviour exactly, which is what the port is checked against.
    """
    t = df["timestamp"].to_numpy()
    a_fwd = align.forward_acceleration(df)
    w_yaw = align.corrected_heading_rate(df)

    aid_valid = df["aid_valid"].to_numpy()
    aid_e = df["aid_e"].to_numpy()
    aid_n = df["aid_n"].to_numpy()
    aid_speed = df["aid_speed"].to_numpy()
    aid_acc = df["aid_accuracy"].to_numpy()
    aid_bearing = df["aid_bearing"].to_numpy()
    aid_bearing_valid = df["aid_bearing_valid"].to_numpy()
    bearing_sigma = float(df["aid_bearing_sigma"].iloc[0])
    blackout = df["blackout_mask"].to_numpy()

    ekf = PlanarESEKF(config)
    e0, n0, psi0, v0 = _initial_state(df, 0)
    ekf.initialize(e0, n0, psi0, v0, align.gyro_bias_rad_s)

    n = len(df)
    est = np.zeros((n, 2))
    est_speed = np.zeros(n)
    est_heading = np.zeros(n)
    sigma = np.zeros(n)
    held_speed = v0
    display = np.zeros((n, 2))
    modes = np.empty(n, dtype=object)
    if blackout_manager is not None:
        blackout_manager.reset(t[0])
    if smoother is not None:
        smoother.reset()

    if speed_mode == SPEED_MODE_TCN and prediction is None:
        raise ValueError("SPEED_MODE_TCN needs a SpeedPrediction")
    oracle_speed = oracle is not None and oracle.speed is not None
    learned_period = 1.0 / max(ekf.cfg.learned_speed_update_hz, 1e-6)
    last_learned_update = -np.inf
    map_period = (1.0 / max(map_matcher.config.update_hz, 1e-6)) if map_matcher else np.inf
    last_map_update = -np.inf
    if map_matcher is not None:
        map_matcher.reset()

    for i in range(n):
        if i > 0:
            dt = t[i] - t[i - 1]
            was_dark = (blackout_manager.mode is not NavMode.AIDED
                        if use_manager_mode else bool(blackout[i]))
            if was_dark and speed_mode in (SPEED_MODE_HOLD, SPEED_MODE_TCN):
                # let the learned estimate (or the held value) carry speed rather than
                # integrating an accelerometer the audit showed to be unreliable
                accel = 0.0
            else:
                accel = a_fwd[i]
            ekf.propagate(dt, w_yaw[i], accel)

        # An oracle speed supersedes the network: running both would measure the learned
        # innovation against truth and inject the difference into POSITION through the
        # cross-covariance, which corrupts exactly the number the ablation is reading.
        # what counts as "dark" this sample: the evaluation mask, or what a live system
        # could actually know (see use_manager_mode)
        if use_manager_mode:
            if blackout_manager is None:
                raise ValueError("use_manager_mode needs a blackout_manager")
            dark = blackout_manager.mode is not NavMode.AIDED
        else:
            dark = bool(blackout[i])

        if (speed_mode == SPEED_MODE_TCN and not oracle_speed and dark
                and prediction.valid[i] and t[i] - last_learned_update >= learned_period):
            if ekf.update_learned_speed(prediction.speed[i], prediction.sigma[i]):
                last_learned_update = t[i]

        # Map aiding runs only during the outage: with GNSS available the real fix is
        # strictly better, and the road constraint would only add a wrong-road failure mode.
        if (map_matcher is not None and dark
                and t[i] - last_map_update >= map_period):
            elapsed = t[i] - last_map_update if np.isfinite(last_map_update) else 0.0
            map_matcher.step(ekf, ekf.state.x[IDX_PE_], ekf.state.x[IDX_PN_],
                             ekf.state.heading, ekf.position_sigma, ekf.state.speed,
                             distance=ekf.state.speed * elapsed)
            last_map_update = t[i]

        fuse = aid_valid[i]
        if blackout_manager is not None:
            # The manager decides from GNSS health, not from the evaluation mask, so the
            # same path runs live and in replay.
            fix = ((aid_e[i], aid_n[i], aid_acc[i]) if aid_valid[i] else None)
            dt_step = t[i] - t[i - 1] if i > 0 else 0.0
            fuse = blackout_manager.step(t[i], dt_step, ekf.state.speed, fix)
            modes[i] = blackout_manager.mode.value

        if fuse:
            ekf.update_gnss_position(aid_e[i], aid_n[i], aid_acc[i])
            if np.isfinite(aid_speed[i]):
                ekf.update_gnss_speed(aid_speed[i])
                held_speed = aid_speed[i]
                if aid_speed[i] < ekf.cfg.zupt_speed:
                    ekf.update_zero_velocity()
            if aid_bearing_valid[i] and np.isfinite(aid_bearing[i]):
                ekf.update_gnss_bearing(aid_bearing[i], bearing_sigma)

        if oracle is not None and blackout[i]:
            # applied after every update so truth wins, and before the state is recorded
            if oracle.speed is not None and np.isfinite(oracle.speed[i]):
                ekf.state.x[IDX_V_] = oracle.speed[i]
            if oracle.heading is not None and np.isfinite(oracle.heading[i]):
                ekf.state.x[IDX_PSI_] = wrap_angle(oracle.heading[i])

        est[i] = ekf.state.position
        est_speed[i] = ekf.state.speed
        est_heading[i] = ekf.state.heading
        sigma[i] = ekf.position_sigma
        if smoother is not None:
            display[i] = smoother.update(est[i], t[i] - t[i - 1] if i > 0 else 0.0)
        else:
            display[i] = est[i]

    return {
        "t": t,
        "estimate": est,
        "speed": est_speed,
        "heading": est_heading,
        "sigma": sigma,
        "n_rejected": ekf.n_rejected,
        "n_updates": ekf.n_updates,
        "n_resets": ekf.n_resets,
        "n_map_applied": map_matcher.n_applied if map_matcher else 0,
        "n_map_steps": map_matcher.n_steps if map_matcher else 0,
        "display": display,
        "mode": modes,
        "max_display_lag_m": smoother.max_lag_m if smoother else 0.0,
    }


def run_raw_ins(df: pd.DataFrame, align: Alignment, start_idx: int, end_idx: int,
                speed_mode: str = SPEED_MODE_ACCEL) -> np.ndarray:
    """B1: open-loop inertial propagation over [start_idx, end_idx)."""
    t = df["timestamp"].to_numpy()
    a_fwd = align.forward_acceleration(df)
    w_yaw = align.corrected_heading_rate(df)

    e0, n0, psi0, v0 = _initial_state(df, start_idx)
    pos = np.array([e0, n0], dtype=float)
    psi, v = float(psi0), float(v0)

    out = np.zeros((end_idx - start_idx, 2))
    for k, i in enumerate(range(start_idx, end_idx)):
        dt = t[i] - t[i - 1] if i > 0 else 0.0
        pos = pos + np.array([v * np.sin(psi), v * np.cos(psi)]) * dt
        psi = wrap_angle(psi + w_yaw[i] * dt)
        if speed_mode == SPEED_MODE_ACCEL:
            v = v + a_fwd[i] * dt
        out[k] = pos
    return out


def constant_velocity_baseline(df: pd.DataFrame, start_idx: int, end_idx: int) -> np.ndarray:
    """B0': straight-line extrapolation at the last aided velocity, ignoring the IMU.

    This is the floor any inertial method must beat -- if dead reckoning cannot improve
    on simply continuing in a straight line, the IMU is contributing nothing.
    """
    t = df["timestamp"].to_numpy()
    e0, n0, psi0, v0 = _initial_state(df, start_idx)
    dt = t[start_idx:end_idx] - t[start_idx]
    return np.column_stack([e0 + v0 * np.sin(psi0) * dt, n0 + v0 * np.cos(psi0) * dt])
