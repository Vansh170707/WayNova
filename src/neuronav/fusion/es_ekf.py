"""Error-state EKF for planar GNSS+INS fusion (blueprint Section 9).

Formulation
----------
The blueprint specifies an error-state filter whose nominal state carries position,
velocity, attitude and biases while the filter estimates small errors around that
nominal trajectory. This implementation specialises that structure to the planar
ground-vehicle case, which is what the data supports: across every audited IO-VNBD
segment the phone is rigidly mounted and effectively flat (gravity constant to
<0.03 deg), so roll and pitch are directly observable from gravity and carry no
useful navigation information. Filtering them would add three weakly-observable
states without improving horizontal position, and the reported ORIENTATION columns
were shown to be unusable as an attitude reference.

Nominal state x = [pE, pN, psi, v, b_w, s_w]
    pE, pN  position in the local ENU frame (m)
    psi     vehicle heading, radians clockwise from North
    v       forward speed (m/s)
    b_w     yaw-gyro bias (rad/s)
    s_w     yaw-gyro scale-factor error (dimensionless)

Error state dx = [dpE, dpN, dpsi, dv, db_w, ds_w], propagated with covariance P and
injected into the nominal state after each measurement update, then reset to zero.
Keeping heading in the nominal state and only ever filtering a small heading ERROR
is what makes the wrap-around well behaved.

Why the scale factor is a state (Phase 6)
-----------------------------------------
A bias and a scale error look nothing alike over a blackout. Bias accumulates with
ELAPSED TIME; scale error accumulates with TOTAL TURNING, so it is invisible on a
straight road and dominant on a twisty one. Measured against the vehicle reference, the
per-segment yaw scale left by the offline calibration is wrong by 2-7%, and on one
segment that scale term contributes 44 deg of the 50 deg median heading error over
120 s -- nine times the bias term. Estimating only a bias cannot absorb it, because no
constant offset reproduces an error proportional to turn rate. The scale factor is
observable from GNSS bearing while the vehicle turns under aiding, so the filter can
enter an outage with it already converged.
"""
from dataclasses import dataclass, field

import numpy as np

IDX_PE, IDX_PN, IDX_PSI, IDX_V, IDX_BW, IDX_SW = range(6)
N_STATES = 6

MAX_GYRO_SCALE_ERROR = 0.3   # a phone gyro this far out is broken, not miscalibrated


def wrap_angle(a: float) -> float:
    """Wrap to (-pi, pi]."""
    return (a + np.pi) % (2 * np.pi) - np.pi


@dataclass
class ESEKFConfig:
    # process noise (continuous-time spectral densities)
    sigma_accel: float = 0.6        # m/s^2, forward-acceleration uncertainty
    sigma_gyro: float = 0.02        # rad/s, yaw-rate uncertainty
    sigma_gyro_bias: float = 2e-4   # rad/s/sqrt(s), bias random walk
    sigma_speed_process: float = 0.4  # m/s/sqrt(s), unmodelled speed dynamics

    # yaw-gyro scale factor (Phase 6). Off by default so that ESEKFConfig() reproduces
    # the Phase 1-5 filter exactly and B3/B5 stay comparable to their published numbers;
    # B6 turns it on explicitly. With the flag off the state is pinned at zero -- no
    # process noise, no transition coupling and no initial variance, so its Kalman gain
    # is identically zero.
    estimate_gyro_scale: bool = False
    sigma_gyro_scale: float = 0.05        # prior 1-sigma on the scale error
    sigma_gyro_scale_walk: float = 1e-5   # 1/sqrt(s), how fast the scale may drift
    # Residual rate-proportional heading noise, for what a single scale factor cannot
    # capture: gyro nonlinearity, bandwidth limits and cradle flex during hard turns.
    # Deliberately smaller than sigma_gyro_scale -- the state absorbs the linear part,
    # and counting the same error twice would make the filter needlessly deaf.
    sigma_gyro_rate_noise: float = 0.01   # rad/s per rad/s of turn rate

    # measurement noise
    sigma_gnss_pos_floor: float = 3.0   # m, floor on reported GNSS accuracy
    sigma_gnss_speed: float = 0.7       # m/s
    sigma_gnss_course: float = 0.15     # rad, at good speed

    # gating
    course_min_speed: float = 2.0       # m/s, below this a reported bearing is unreliable
    innovation_gate_sigma: float = 5.0  # reject measurements beyond this many sigma
    zupt_speed: float = 0.3             # m/s, treat as stationary below this

    # learned speed pseudo-measurement
    min_learned_speed_sigma: float = 0.8  # never trust the network more than this
    # The TCN sees a 6 s window, so consecutive predictions are strongly correlated.
    # Fusing them every sample would treat one piece of evidence as ~10 independent
    # measurements per second and let the network overwhelm a still-good held speed --
    # which showed up as B5 losing to B3 on short outages. Fuse at roughly the rate at
    # which genuinely new information arrives instead.
    learned_speed_update_hz: float = 1.0

    # divergence recovery
    reject_streak_for_reset: int = 5
    reset_position_var: float = 100.0 ** 2
    reset_heading_var: float = 0.5 ** 2


def deployment_config() -> ESEKFConfig:
    """What Phase 6 recommends actually shipping, as opposed to what the baselines use.

    `ESEKFConfig()` deliberately keeps the yaw-scale state off so that B3 and B5 reproduce
    their published numbers. A deployed system has no such obligation: measured on two
    held-out drivers the scale state never hurt, and on the driver whose heading error is
    scale-dominated it cut p90 blackout drift by ~6 points at 30-120 s, for the cost of one
    extra state. Worst-case drift is what a live demonstration is judged on.
    """
    return ESEKFConfig(estimate_gyro_scale=True)


@dataclass
class ESEKFState:
    x: np.ndarray = field(default_factory=lambda: np.zeros(N_STATES))
    P: np.ndarray = field(
        default_factory=lambda: np.diag([25.0, 25.0, 0.5, 4.0, 1e-4, 0.0]))

    @property
    def position(self) -> np.ndarray:
        return self.x[[IDX_PE, IDX_PN]]

    @property
    def heading(self) -> float:
        return float(self.x[IDX_PSI])

    @property
    def speed(self) -> float:
        return float(self.x[IDX_V])

    @property
    def gyro_scale_error(self) -> float:
        return float(self.x[IDX_SW])


class PlanarESEKF:
    """Error-state EKF fusing IMU-driven dead reckoning with GNSS aiding."""

    def __init__(self, config: ESEKFConfig = None):
        self.cfg = config or ESEKFConfig()
        self.state = ESEKFState()
        self.n_rejected = 0
        self.n_updates = 0
        self.n_resets = 0
        self._consecutive_rejects = 0

    # ---------------------------------------------------------------- propagate
    def propagate(self, dt: float, yaw_rate: float, forward_accel: float):
        """Advance the nominal state and the error covariance by dt seconds.

        yaw_rate is the bias-uncorrected measured rate; the filter's own bias
        estimate is removed here so that the bias state stays observable.
        """
        if dt <= 0:
            return
        cfg = self.cfg
        x, P = self.state.x, self.state.P

        psi, v, bw, sw = x[IDX_PSI], x[IDX_V], x[IDX_BW], x[IDX_SW]
        w_debiased = yaw_rate - bw
        w = (1.0 + sw) * w_debiased

        # nominal propagation (heading clockwise from North -> East = sin, North = cos)
        x[IDX_PE] += v * np.sin(psi) * dt + 0.5 * forward_accel * np.sin(psi) * dt * dt
        x[IDX_PN] += v * np.cos(psi) * dt + 0.5 * forward_accel * np.cos(psi) * dt * dt
        x[IDX_PSI] = wrap_angle(psi + w * dt)
        x[IDX_V] = v + forward_accel * dt

        # error-state transition
        F = np.eye(N_STATES)
        F[IDX_PE, IDX_PSI] = v * np.cos(psi) * dt
        F[IDX_PE, IDX_V] = np.sin(psi) * dt
        F[IDX_PN, IDX_PSI] = -v * np.sin(psi) * dt
        F[IDX_PN, IDX_V] = np.cos(psi) * dt
        F[IDX_PSI, IDX_BW] = -(1.0 + sw) * dt
        if cfg.estimate_gyro_scale:
            # the scale error only reaches heading in proportion to how fast we are
            # turning, which is exactly what makes it separable from the bias
            F[IDX_PSI, IDX_SW] = w_debiased * dt

        Q = np.zeros((N_STATES, N_STATES))
        Q[IDX_PE, IDX_PE] = (0.5 * cfg.sigma_accel * dt * dt) ** 2
        Q[IDX_PN, IDX_PN] = (0.5 * cfg.sigma_accel * dt * dt) ** 2
        rate_noise = cfg.sigma_gyro_rate_noise if cfg.estimate_gyro_scale else 0.0
        Q[IDX_PSI, IDX_PSI] = ((cfg.sigma_gyro ** 2)
                               + (rate_noise * abs(w_debiased)) ** 2) * dt
        Q[IDX_V, IDX_V] = (cfg.sigma_accel * dt) ** 2 + (cfg.sigma_speed_process ** 2) * dt
        Q[IDX_BW, IDX_BW] = (cfg.sigma_gyro_bias ** 2) * dt
        if cfg.estimate_gyro_scale:
            Q[IDX_SW, IDX_SW] = (cfg.sigma_gyro_scale_walk ** 2) * dt

        self.state.P = F @ P @ F.T + Q

    # ------------------------------------------------------------------ updates
    def _apply(self, H: np.ndarray, innovation: np.ndarray, R: np.ndarray) -> bool:
        """Shared Kalman update. Returns False when the innovation gate rejects it."""
        P = self.state.P
        S = H @ P @ H.T + R
        try:
            S_inv = np.linalg.inv(S)
        except np.linalg.LinAlgError:
            return False

        nis = float(innovation @ S_inv @ innovation)
        if nis > self.cfg.innovation_gate_sigma ** 2 * len(innovation):
            self.n_rejected += 1
            return False

        K = P @ H.T @ S_inv
        dx = K @ innovation

        self.state.x += dx
        self.state.x[IDX_PSI] = wrap_angle(self.state.x[IDX_PSI])
        # An unbounded scale error can only come from a badly conditioned update, and
        # letting it run inverts the sign of every turn.
        self.state.x[IDX_SW] = float(np.clip(self.state.x[IDX_SW],
                                             -MAX_GYRO_SCALE_ERROR, MAX_GYRO_SCALE_ERROR))

        # Joseph form keeps P symmetric and positive-definite under gating
        I_KH = np.eye(N_STATES) - K @ H
        self.state.P = I_KH @ P @ I_KH.T + K @ R @ K.T
        self.state.P = 0.5 * (self.state.P + self.state.P.T)
        self.n_updates += 1
        return True

    def update_gnss_position(self, east: float, north: float, accuracy_m: float) -> bool:
        """Fuse a GNSS position fix, with recovery if the filter has lost lock.

        A diverged filter produces huge innovations, which the gate then rejects --
        so the very measurements that would fix it are discarded and the divergence
        becomes permanent. After a run of consecutive rejections the covariance is
        inflated, letting the filter re-acquire instead of drifting away forever.
        """
        sigma = max(accuracy_m, self.cfg.sigma_gnss_pos_floor)
        H = np.zeros((2, N_STATES))
        H[0, IDX_PE] = 1.0
        H[1, IDX_PN] = 1.0
        innovation = np.array([east - self.state.x[IDX_PE], north - self.state.x[IDX_PN]])

        accepted = self._apply(H, innovation, np.diag([sigma ** 2, sigma ** 2]))
        if accepted:
            self._consecutive_rejects = 0
            return True

        self._consecutive_rejects += 1
        if self._consecutive_rejects >= self.cfg.reject_streak_for_reset:
            self.state.P[IDX_PE, IDX_PE] += self.cfg.reset_position_var
            self.state.P[IDX_PN, IDX_PN] += self.cfg.reset_position_var
            self.state.P[IDX_PSI, IDX_PSI] += self.cfg.reset_heading_var
            self.n_resets += 1
            self._consecutive_rejects = 0
        return False

    def update_gnss_speed(self, speed: float) -> bool:
        H = np.zeros((1, N_STATES))
        H[0, IDX_V] = 1.0
        innovation = np.array([speed - self.state.x[IDX_V]])
        return self._apply(H, innovation, np.array([[self.cfg.sigma_gnss_speed ** 2]]))

    def update_gnss_bearing(self, bearing_rad: float, sigma_rad: float = None) -> bool:
        """Aid heading with the receiver's reported bearing (Doppler-derived)."""
        sigma = sigma_rad if sigma_rad is not None else self.cfg.sigma_gnss_course
        H = np.zeros((1, N_STATES))
        H[0, IDX_PSI] = 1.0
        innovation = np.array([wrap_angle(bearing_rad - self.state.x[IDX_PSI])])
        return self._apply(H, innovation, np.array([[max(sigma, 1e-3) ** 2]]))

    def update_learned_speed(self, speed: float, sigma: float) -> bool:
        """Fuse the neural speed estimate as a pseudo-measurement (blueprint B5).

        This is the learned component's only route into the navigation state: the network
        never writes position or heading directly, so a bad prediction degrades the
        solution gracefully through the filter's own gating rather than teleporting it.
        The network's own sigma sets how far the filter is willing to move.
        """
        if not np.isfinite(speed) or not np.isfinite(sigma) or sigma <= 0:
            return False
        H = np.zeros((1, N_STATES))
        H[0, IDX_V] = 1.0
        innovation = np.array([speed - self.state.x[IDX_V]])
        sigma = max(sigma, self.cfg.min_learned_speed_sigma)
        return self._apply(H, innovation, np.array([[sigma ** 2]]))

    def update_map_position(self, east: float, north: float, bearing_rad: float,
                            sigma_cross: float, sigma_along: float) -> bool:
        """Constrain position to a road, tightly across it and loosely along it.

        Knowing which road you are on fixes your lateral position to roughly the lane
        width, but says almost nothing about how far along that road you have travelled.
        Snapping both components would inject along-track error the map cannot actually
        observe, so the measurement covariance is deliberately anisotropic: small
        perpendicular to the road, large parallel to it.
        """
        H = np.zeros((2, N_STATES))
        H[0, IDX_PE] = 1.0
        H[1, IDX_PN] = 1.0
        innovation = np.array([east - self.state.x[IDX_PE], north - self.state.x[IDX_PN]])

        # rotate a diagonal (along, cross) covariance into the ENU frame
        s, c = np.sin(bearing_rad), np.cos(bearing_rad)
        rot = np.array([[s, c], [c, -s]])   # columns: along-road, cross-road
        R = rot @ np.diag([sigma_along ** 2, sigma_cross ** 2]) @ rot.T
        return self._apply(H, innovation, R)

    def update_map_heading(self, bearing_rad: float, sigma_rad: float) -> bool:
        """Pull heading towards the road direction, resolving the 180 deg ambiguity.

        A road carries traffic both ways, so the constraint is on the axis, not the
        direction: whichever of bearing / bearing+pi is closer to the current heading is
        the one used.
        """
        current = self.state.x[IDX_PSI]
        options = (bearing_rad, bearing_rad + np.pi)
        target = min(options, key=lambda b: abs(wrap_angle(b - current)))
        H = np.zeros((1, N_STATES))
        H[0, IDX_PSI] = 1.0
        innovation = np.array([wrap_angle(target - current)])
        return self._apply(H, innovation, np.array([[max(sigma_rad, 1e-3) ** 2]]))

    def update_zero_velocity(self) -> bool:
        """ZUPT: while stationary, speed is known to be zero to high precision."""
        H = np.zeros((1, N_STATES))
        H[0, IDX_V] = 1.0
        innovation = np.array([0.0 - self.state.x[IDX_V]])
        return self._apply(H, innovation, np.array([[0.05 ** 2]]))

    # -------------------------------------------------------------------- setup
    def initialize(self, east: float, north: float, heading: float, speed: float,
                   gyro_bias: float = 0.0, gyro_scale_error: float = 0.0):
        self.state.x = np.array([east, north, wrap_angle(heading), speed, gyro_bias,
                                 gyro_scale_error])
        scale_var = self.cfg.sigma_gyro_scale ** 2 if self.cfg.estimate_gyro_scale else 0.0
        self.state.P = np.diag([9.0, 9.0, 0.2 ** 2, 1.0 ** 2, 1e-4, scale_var])

    @property
    def position_sigma(self) -> float:
        """1-sigma horizontal position uncertainty (m), for the confidence display."""
        return float(np.sqrt(self.state.P[IDX_PE, IDX_PE] + self.state.P[IDX_PN, IDX_PN]))
