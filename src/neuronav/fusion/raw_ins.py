import numpy as np
from scipy.spatial.transform import Rotation

from neuronav.utils.geo import latlon_to_enu


def device_linear_accel_to_world(df):
    """Rotate device-frame linear acceleration (accel - gravity) into a world ENU frame.

    Assumes the IO-VNBD orientation_{yaw,pitch,roll}_deg columns follow Android's
    classic TYPE_ORIENTATION convention (azimuth/pitch/roll) and reconstructs the
    device->world rotation as the intrinsic Z (yaw) -> X (pitch) -> Y (roll) composition.
    This is a Phase-1 approximation pending the dedicated phone-to-vehicle alignment
    module (Section 6.2); it is not claimed to be exact for all device orientations.
    """
    lin_accel_device = np.column_stack([
        df["ax"] - df["grav_x"],
        df["ay"] - df["grav_y"],
        df["az"] - df["grav_z"],
    ])
    rot = Rotation.from_euler(
        "ZXY",
        np.column_stack([df["yaw_deg"], df["pitch_deg"], df["roll_deg"]]),
        degrees=True,
    )
    return rot.apply(lin_accel_device)  # (N, 3) in world East-North-Up


def raw_dead_reckoning(df, t_start: float, t_end: float):
    """B1 baseline: propagate position/velocity from IMU alone over [t_start, t_end].

    State is initialized from the GNSS fix at t_start (position + speed/heading), then
    mechanized purely from world-frame linear acceleration -- no GNSS is consumed
    inside the window. Implements the discrete mechanization of Section 3.2:
        v[t+1] = v[t] + a_world[t] * dt
        p[t+1] = p[t] + v[t] * dt + 0.5 * a_world[t] * dt^2
    """
    window = df[(df["timestamp"] >= t_start) & (df["timestamp"] <= t_end)].reset_index(drop=True)
    if len(window) < 2:
        raise ValueError("Blackout window too short for the given session sampling rate")

    a_world = device_linear_accel_to_world(window)
    t = window["timestamp"].to_numpy()
    dt = np.diff(t, prepend=t[0])
    dt[0] = 0.0

    lat0, lon0, alt0 = window["lat"].iloc[0], window["lon"].iloc[0], window["alt"].iloc[0]
    heading0 = np.radians(window["gps_heading_deg"].iloc[0])
    speed0 = window["speed"].iloc[0]
    v = np.array([speed0 * np.sin(heading0), speed0 * np.cos(heading0), 0.0])  # E, N, U
    p = np.zeros(3)

    n = len(window)
    positions = np.zeros((n, 3))
    velocities = np.zeros((n, 3))
    positions[0], velocities[0] = p, v
    for i in range(1, n):
        a = a_world[i - 1]
        p = p + v * dt[i] + 0.5 * a * dt[i] ** 2
        v = v + a * dt[i]
        positions[i], velocities[i] = p, v

    ref_e, ref_n, ref_u = latlon_to_enu(window["lat"], window["lon"], window["alt"], lat0, lon0, alt0)

    return {
        "t": t,
        "ins_position": positions,       # (N,3) E,N,U relative to blackout-entry fix
        "ins_velocity": velocities,
        "gnss_reference": np.column_stack([ref_e, ref_n, ref_u]),
        "gnss_accuracy": window["accuracy"].to_numpy(),
    }
