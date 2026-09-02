"""GNSS blackout state machine and continuous track output (blueprint section 9.2).

Five states are specified: aided, blackout entry, dead reckoning, re-acquisition, aided
recovery. Two things make this more than bookkeeping.

**GNSS loss is detected, not announced.** Evaluation can hand the estimator a mask, but a
deployed system gets no such signal -- it sees fixes stop arriving, or arrive with terrible
accuracy, or arrive wildly inconsistent with the propagated state. All three are treated as
loss here, so the same code runs in the live demo and in replay.

**Re-acquisition is validated before it is trusted.** After a long outage the first returning
fix may be multipath garbage, and accepting it would drag the solution somewhere worse than
dead reckoning. Several consecutive mutually-consistent fixes are required first.

The visible-jump problem
------------------------
When aiding resumes after a 120 s outage the filter is ~270 m from truth and the returning
fix is accurate, so the correct Bayesian move is to snap straight to it. Measured, that is a
245 m median jump (649 m at p90) completed in half a second. The estimate is right; the
*display* teleporting across the map is what the blueprint forbids.

So the estimate is left optimal and untouched, and only the rendered track is slew-limited
(`TrackSmoother`) -- catching up at a bounded speed over a second or two. This is what
production navigation does. It is deliberately NOT done by inflating measurement noise,
which would degrade the estimate itself to buy the same cosmetic smoothness.
"""
from dataclasses import dataclass, field
from enum import Enum

import numpy as np


class NavMode(Enum):
    AIDED = "aided"                # GNSS healthy, full fusion
    BLACKOUT = "blackout"          # propagating on IMU + learned speed
    REACQUIRING = "reacquiring"    # fixes returning, not yet trusted


@dataclass
class BlackoutConfig:
    # loss detection
    max_fix_gap_s: float = 3.0          # no usable fix for this long -> blackout
    max_accuracy_m: float = 30.0        # reported accuracy worse than this is not usable

    # re-acquisition
    reacquire_fixes: int = 3            # consecutive consistent fixes before trusting
    reacquire_window_s: float = 6.0     # ...which must arrive within this span
    reacquire_consistency_m: float = 40.0  # successive fixes must agree to this

    # display continuity
    #
    # After a 120 s outage the estimate can be ~270 m from where the display sits, and that
    # distance HAS to be travelled by the marker eventually. The only real choice is how
    # long it takes, and it is a genuine trade-off:
    #   fast  (<1 s)  -- indistinguishable from the teleport we set out to remove
    #   medium (~2 s) -- reads as the track visibly recovering
    #   slow  (>10 s) -- smooth, but the display stays badly wrong for a long time
    # A fixed 40 m/s rate took ~12 s to close a 290 m gap, leaving the display 43 m out
    # ten seconds after recovery. So the rate adapts to the size of the gap, floored so
    # small corrections stay gentle and capped so a huge one still cannot become a
    # single-frame jump.
    slew_rate_mps: float = 40.0         # floor: small gaps close at least this fast
    slew_max_rate_mps: float = 200.0    # cap: never move faster than this
    # Exponential time CONSTANT, not a deadline: the closing speed is proportional to the
    # remaining gap, so convergence is asymptotic and the floor above is what actually
    # finishes it. A 250 m gap closes in roughly 4 s with these values, well inside the
    # 10 s at which recovery accuracy is measured.
    slew_time_constant_s: float = 2.0
    slew_enabled: bool = True


@dataclass
class BlackoutState:
    mode: NavMode = NavMode.AIDED
    last_fix_t: float = -np.inf
    blackout_start_t: float = np.nan
    blackout_distance_m: float = 0.0
    candidates: list = field(default_factory=list)   # (t, east, north) pending validation
    n_transitions: int = 0


class BlackoutManager:
    """Tracks GNSS health and decides whether a fix may be fused."""

    def __init__(self, config: BlackoutConfig = None):
        self.cfg = config or BlackoutConfig()
        self.state = BlackoutState()

    def reset(self, t: float = 0.0):
        self.state = BlackoutState(last_fix_t=t)

    @property
    def mode(self) -> NavMode:
        return self.state.mode

    def blackout_duration(self, t: float) -> float:
        if np.isnan(self.state.blackout_start_t):
            return 0.0
        return max(t - self.state.blackout_start_t, 0.0)

    def _enter_blackout(self, t: float):
        if self.state.mode is NavMode.BLACKOUT:
            return
        self.state.mode = NavMode.BLACKOUT
        self.state.blackout_start_t = t
        self.state.blackout_distance_m = 0.0
        self.state.candidates.clear()
        self.state.n_transitions += 1

    def _enter_aided(self):
        self.state.mode = NavMode.AIDED
        self.state.blackout_start_t = np.nan
        self.state.candidates.clear()
        self.state.n_transitions += 1

    def step(self, t: float, dt: float, speed: float, fix=None) -> bool:
        """Advance the state machine. Returns True if `fix` should be fused.

        `fix` is (east, north, accuracy_m) or None when no fix arrived this sample.
        Distance travelled during the outage is accumulated for the drift display.
        """
        cfg = self.cfg
        if self.state.mode is not NavMode.AIDED:
            self.state.blackout_distance_m += max(speed, 0.0) * max(dt, 0.0)

        usable = fix is not None and np.isfinite(fix[0]) and np.isfinite(fix[1]) and (
            not np.isfinite(fix[2]) or fix[2] <= cfg.max_accuracy_m)

        if not usable:
            # no usable fix: declare blackout once the gap exceeds tolerance
            if t - self.state.last_fix_t > cfg.max_fix_gap_s:
                self._enter_blackout(t)
            return False

        self.state.last_fix_t = t

        if self.state.mode is NavMode.AIDED:
            return True

        # A fix arrived while we were dark. Hold it as a candidate and require several
        # mutually consistent ones -- a lone multipath fix after a long outage would
        # otherwise drag the solution somewhere worse than dead reckoning.
        if self.state.mode is NavMode.BLACKOUT:
            self.state.mode = NavMode.REACQUIRING
            self.state.candidates = []

        east, north = float(fix[0]), float(fix[1])
        candidates = self.state.candidates
        if candidates:
            t0, e0, n0 = candidates[0]
            if t - t0 > cfg.reacquire_window_s:
                candidates.clear()                       # stale, start over
            else:
                te, ee, ne = candidates[-1]
                gap = np.hypot(east - ee, north - ne)
                # allow for genuine travel between fixes before calling them inconsistent
                allowed = cfg.reacquire_consistency_m + speed * max(t - te, 0.0)
                if gap > allowed:
                    candidates.clear()
        candidates.append((t, east, north))

        if len(candidates) >= cfg.reacquire_fixes:
            self._enter_aided()
            return True
        return False


class TrackSmoother:
    """Slew-limits the DISPLAYED track so it never teleports.

    The filter's estimate is the truth of record and is not modified; this only governs
    what is drawn. When the estimate jumps, the rendered position closes the gap at a
    bounded speed, so a 245 m correction becomes a visible slew of a second or two rather
    than a single-frame teleport.
    """

    def __init__(self, config: BlackoutConfig = None):
        self.cfg = config or BlackoutConfig()
        self.position = None
        self.max_lag_m = 0.0

    def reset(self, position=None):
        self.position = None if position is None else np.asarray(position, dtype=float)
        self.max_lag_m = 0.0

    def update(self, estimate, dt: float) -> np.ndarray:
        estimate = np.asarray(estimate, dtype=float)
        if self.position is None or not self.cfg.slew_enabled:
            self.position = estimate.copy()
            return self.position.copy()

        delta = estimate - self.position
        distance = float(np.linalg.norm(delta))
        self.max_lag_m = max(self.max_lag_m, distance)

        rate = np.clip(distance / max(self.cfg.slew_time_constant_s, 1e-3),
                       self.cfg.slew_rate_mps, self.cfg.slew_max_rate_mps)
        budget = rate * max(dt, 0.0)
        if distance <= budget or distance == 0.0:
            self.position = estimate.copy()
        else:
            self.position = self.position + delta * (budget / distance)
        return self.position.copy()
