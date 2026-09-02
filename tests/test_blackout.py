"""Guards for the blackout state machine and display continuity (blueprint section 9.2)."""
import numpy as np
import pytest

from neuronav.fusion.blackout import (BlackoutConfig, BlackoutManager, NavMode, TrackSmoother)


def feed(manager, times, fixes, speed=15.0):
    """Drive the manager over a sequence, returning the mode after each step."""
    modes, fused = [], []
    previous = times[0]
    for t, fix in zip(times, fixes):
        fused.append(manager.step(t, t - previous, speed, fix))
        modes.append(manager.mode)
        previous = t
    return modes, fused


def test_starts_aided_and_fuses_healthy_fixes():
    m = BlackoutManager()
    m.reset(0.0)
    times = np.arange(0.0, 5.0, 1.0)
    fixes = [(i * 15.0, 0.0, 5.0) for i in range(len(times))]
    modes, fused = feed(m, times, fixes)
    assert all(mode is NavMode.AIDED for mode in modes)
    assert all(fused)


def test_detects_blackout_from_missing_fixes():
    """Loss must be inferred from the GNSS stream, not announced by the caller."""
    m = BlackoutManager(BlackoutConfig(max_fix_gap_s=3.0))
    m.reset(0.0)
    times = np.arange(0.0, 12.0, 0.5)
    fixes = [None] * len(times)
    modes, fused = feed(m, times, fixes)
    assert modes[-1] is NavMode.BLACKOUT
    assert not any(fused)


def test_poor_accuracy_counts_as_loss():
    """A fix reporting 200 m accuracy is not aiding, whatever the receiver claims."""
    m = BlackoutManager(BlackoutConfig(max_fix_gap_s=2.0, max_accuracy_m=30.0))
    m.reset(0.0)
    times = np.arange(0.0, 8.0, 0.5)
    fixes = [(0.0, 0.0, 200.0)] * len(times)
    modes, fused = feed(m, times, fixes)
    assert modes[-1] is NavMode.BLACKOUT
    assert not any(fused)


def test_single_returning_fix_is_not_trusted():
    """After an outage the first fix may be multipath; accepting it could be worse than DR."""
    cfg = BlackoutConfig(max_fix_gap_s=2.0, reacquire_fixes=3)
    m = BlackoutManager(cfg)
    m.reset(0.0)
    feed(m, np.arange(0.0, 10.0, 0.5), [None] * 20)
    assert m.mode is NavMode.BLACKOUT

    fused_now = m.step(10.5, 0.5, 15.0, (0.0, 0.0, 5.0))
    assert not fused_now, "a lone returning fix was trusted immediately"
    assert m.mode is NavMode.REACQUIRING


def test_consistent_fixes_restore_aided_mode():
    cfg = BlackoutConfig(max_fix_gap_s=2.0, reacquire_fixes=3, reacquire_window_s=6.0)
    m = BlackoutManager(cfg)
    m.reset(0.0)
    feed(m, np.arange(0.0, 10.0, 0.5), [None] * 20)

    accepted = [m.step(10.0 + i, 1.0, 0.0, (0.0, 0.0, 5.0)) for i in range(3)]
    assert accepted[-1], "three consistent fixes should restore aiding"
    assert m.mode is NavMode.AIDED


def test_inconsistent_fixes_do_not_restore_aided_mode():
    """Scattered fixes are the signature of multipath, not of recovery."""
    cfg = BlackoutConfig(max_fix_gap_s=2.0, reacquire_fixes=3,
                         reacquire_consistency_m=20.0)
    m = BlackoutManager(cfg)
    m.reset(0.0)
    feed(m, np.arange(0.0, 10.0, 0.5), [None] * 20)

    # stationary vehicle, so no travel can excuse the scatter
    scattered = [(0.0, 0.0, 5.0), (900.0, 0.0, 5.0), (0.0, 900.0, 5.0)]
    accepted = [m.step(10.0 + i, 1.0, 0.0, f) for i, f in enumerate(scattered)]
    assert not any(accepted)
    assert m.mode is NavMode.REACQUIRING


def test_blackout_distance_accumulates():
    m = BlackoutManager(BlackoutConfig(max_fix_gap_s=1.0))
    m.reset(0.0)
    for i in range(1, 21):
        m.step(i * 0.5, 0.5, 20.0, None)
    assert m.mode is NavMode.BLACKOUT
    # 20 m/s over the portion spent dark
    assert m.state.blackout_distance_m > 100.0
    assert m.blackout_duration(10.0) > 0.0


def test_smoother_removes_a_large_jump():
    """The display must not teleport when the estimate snaps on re-acquisition."""
    smoother = TrackSmoother(BlackoutConfig())
    smoother.update(np.array([0.0, 0.0]), 0.1)

    # estimate snaps 250 m, as it does after a two-minute outage
    steps = [smoother.update(np.array([250.0, 0.0]), 0.1) for _ in range(3)]
    first_move = float(np.linalg.norm(steps[0] - np.array([0.0, 0.0])))
    assert first_move < 40.0, f"display jumped {first_move:.0f} m in one frame"


def test_smoother_converges_well_before_recovery_is_scored():
    """Smoothness must not become a display that stays wrong indefinitely.

    Convergence is asymptotic (the rate is proportional to the remaining gap), so the
    property that matters is being settled well inside the 10 s horizon at which recovery
    accuracy is measured -- not hitting the time constant exactly.
    """
    cfg = BlackoutConfig(slew_time_constant_s=2.0)
    smoother = TrackSmoother(cfg)
    smoother.update(np.array([0.0, 0.0]), 0.1)

    target = np.array([250.0, 0.0])          # a two-minute-outage sized correction
    for _ in range(int(6.0 / 0.1)):
        position = smoother.update(target, 0.1)
    assert np.linalg.norm(position - target) < 1.0


def test_smoother_is_transparent_when_disabled():
    smoother = TrackSmoother(BlackoutConfig(slew_enabled=False))
    smoother.update(np.array([0.0, 0.0]), 0.1)
    out = smoother.update(np.array([250.0, 0.0]), 0.1)
    assert np.allclose(out, [250.0, 0.0])
