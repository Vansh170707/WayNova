"""The yaw-gyro scale factor: pinned when off, observable and useful when on."""
import numpy as np
import pytest

from neuronav.fusion.es_ekf import IDX_SW, ESEKFConfig, PlanarESEKF, wrap_angle

DT = 0.1
TRUE_SCALE = 1.12      # the gyro reads 12% high
TRUE_BIAS = 0.004      # rad/s


def synthetic_drive(seconds: float = 240.0):
    """A drive that alternates straights and turns, with a miscalibrated gyro.

    Turning is what makes a scale error separable from a bias, so a drive that never
    turns would be a test of nothing.
    """
    t = np.arange(0.0, seconds, DT)
    # 20 s straight, 10 s turning, repeating; alternating turn direction
    phase = (t % 30.0)
    turning = phase >= 20.0
    direction = np.where((t // 30.0) % 2 == 0, 1.0, -1.0)
    w_true = np.where(turning, 0.25 * direction, 0.0)
    psi_true = wrap_angle(np.cumsum(w_true) * DT)
    w_meas = TRUE_SCALE * w_true + TRUE_BIAS
    return t, w_true, psi_true, w_meas


def run(estimate_scale: bool, aided_seconds: float, total_seconds: float = 240.0):
    """Aid with bearing for `aided_seconds`, then propagate open loop and score heading."""
    t, w_true, psi_true, w_meas = synthetic_drive(total_seconds)
    cfg = ESEKFConfig(estimate_gyro_scale=estimate_scale)
    ekf = PlanarESEKF(cfg)
    ekf.initialize(0.0, 0.0, psi_true[0], 12.0, gyro_bias=0.0)

    rng = np.random.default_rng(0)
    for i in range(1, len(t)):
        ekf.propagate(DT, w_meas[i], 0.0)
        if t[i] <= aided_seconds and i % 10 == 0:
            ekf.update_gnss_bearing(psi_true[i] + rng.normal(0.0, np.radians(3.0)))
    return ekf, abs(wrap_angle(ekf.state.heading - psi_true[-1]))


def test_scale_state_is_pinned_when_disabled():
    """With the flag off the sixth state must never move, so B3/B5 stay reproducible."""
    ekf, _ = run(estimate_scale=False, aided_seconds=200.0)
    assert ekf.state.x[IDX_SW] == 0.0
    assert ekf.state.P[IDX_SW, IDX_SW] == 0.0
    assert np.all(ekf.state.P[IDX_SW, :] == 0.0)


def test_scale_error_is_recovered_from_bearing():
    """The filter should learn the correction that cancels the gyro's gain error."""
    ekf, _ = run(estimate_scale=True, aided_seconds=200.0)
    expected = 1.0 / TRUE_SCALE - 1.0        # ~ -0.107
    assert ekf.state.gyro_scale_error == pytest.approx(expected, abs=0.03)


def test_scale_state_reduces_open_loop_heading_error():
    """Converging the scale under aiding must pay off once aiding stops."""
    _, err_off = run(estimate_scale=False, aided_seconds=150.0)
    _, err_on = run(estimate_scale=True, aided_seconds=150.0)
    assert err_on < err_off
    # the residual should be a small fraction of what an uncorrected 12% gain costs
    assert np.degrees(err_on) < 5.0


def test_straight_driving_leaves_the_scale_unobservable_but_stable():
    """Without turning there is nothing to estimate; the filter must not invent a value."""
    t = np.arange(0.0, 120.0, DT)
    ekf = PlanarESEKF(ESEKFConfig(estimate_gyro_scale=True))
    ekf.initialize(0.0, 0.0, 0.0, 15.0, gyro_bias=0.0)
    for i in range(1, len(t)):
        ekf.propagate(DT, TRUE_BIAS, 0.0)          # dead straight, bias only
        if i % 10 == 0:
            ekf.update_gnss_bearing(0.0)
    assert abs(ekf.state.gyro_scale_error) < 0.05
