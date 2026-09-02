"""Phone-to-vehicle alignment and gyro bias estimation (blueprint Section 6.2).

The phone sits at an unknown fixed yaw angle in its cradle, so the vehicle's forward
direction is an unknown direction within the device horizontal plane. Rather than
assuming a mounting, both the alignment angle and the yaw-gyro bias are estimated
from the data itself during GNSS-aided driving.
"""
from dataclasses import dataclass

import numpy as np
import pandas as pd

from neuronav.calibration.signals import angular_rate, heading_rate, horizontal_acceleration
from neuronav.utils.geo import latlon_to_enu


@dataclass
class Alignment:
    """Estimated phone-to-vehicle calibration for one segment."""
    forward_angle_rad: float   # direction of vehicle forward within the (e1,e2) basis
    gyro_bias_rad_s: float     # yaw-rate bias, subtracted before propagation
    n_samples: int
    forward_accel_corr: float  # diagnostic: quality of the alignment fit
    forward_accel_scale: float = 1.0  # gain from measured to true longitudinal accel
    yaw_channel: int = 2       # index into (gx, gy, gz) carrying vehicle yaw rate
    yaw_scale: float = 1.0     # signed gain from that channel to true yaw rate
    yaw_corr: float = 0.0      # diagnostic: quality of the yaw-channel fit

    def forward_acceleration(self, df: pd.DataFrame, apply_scale: bool = True) -> np.ndarray:
        """Project horizontal acceleration onto the estimated forward axis."""
        h = horizontal_acceleration(df)
        c, s = np.cos(self.forward_angle_rad), np.sin(self.forward_angle_rad)
        fwd = h[:, 0] * c + h[:, 1] * s
        return fwd * self.forward_accel_scale if apply_scale else fwd

    def raw_yaw_rate(self, df: pd.DataFrame) -> np.ndarray:
        """The selected gyro channel, scaled into true vehicle yaw rate (rad/s)."""
        return angular_rate(df)[:, self.yaw_channel] * self.yaw_scale

    def corrected_heading_rate(self, df: pd.DataFrame) -> np.ndarray:
        return self.raw_yaw_rate(df) - self.gyro_bias_rad_s


MAX_BASELINE_TURN_RAD = np.radians(150.0)


def robust_line(x: np.ndarray, y: np.ndarray, n_iter: int = 2,
                keep_sigma: float = 2.5) -> tuple[float, float, float]:
    """Least squares, then refit on the inner residuals. Returns (slope, intercept, corr).

    Plain least squares is the wrong estimator for these calibration fits because the
    outliers are not noise, they are *aliased* samples: a bearing baseline spanning a turn
    of more than 180 deg wraps the short way round and reports a change of the wrong sign
    and size. A handful of those is enough to halve the fitted gyro scale, which then
    integrates into heading for the whole outage. Trimming on residual spread removes them
    without needing to know which mechanism produced them.

    The scale (MAD) is used rather than the standard deviation so the threshold itself is
    not inflated by the outliers being removed.
    """
    keep = np.ones(len(x), dtype=bool)
    slope, intercept = np.polyfit(x, y, 1)
    for _ in range(n_iter):
        resid = y - (slope * x + intercept)
        mad = np.median(np.abs(resid[keep] - np.median(resid[keep])))
        scale = 1.4826 * mad
        if not np.isfinite(scale) or scale <= 0:
            break
        proposed = np.abs(resid) <= keep_sigma * scale
        if proposed.sum() < max(30, 0.3 * len(x)):
            break
        keep = proposed
        slope, intercept = np.polyfit(x[keep], y[keep], 1)
    corr = float(np.corrcoef(x[keep], y[keep])[0, 1]) if keep.sum() > 2 else float("nan")
    return float(slope), float(intercept), corr


def select_yaw_channel(df: pd.DataFrame, baseline_s: float = 5.0,
                       min_speed: float = 8.0,
                       robust: bool = False) -> tuple[int, float, float, float | None]:
    """Work out which gyro channel carries vehicle yaw rate, and its signed scale.

    IO-VNBD's gyro columns are labelled Yaw/Pitch/Roll, but the labels do not hold:
    measured against a vehicle yaw-rate reference, the true yaw channel is `gy` on the
    S-series and `gz` on the A-series, and neither matches the header. Projecting the
    angular-rate vector onto gravity does not disambiguate them either, because the
    gravity and gyro columns are not expressed in a common axis order.

    So the channel is chosen from data: integrated yaw over a `baseline_s` window must
    reproduce the change in reported bearing over the same window. This uses only
    smartphone-available signals, so it remains valid as a deployment-time calibration.

    Returns (channel, scale, correlation, bias). The bias is None unless `robust`, which
    also fits it from the same baselines rather than from stationary samples.
    """
    t = df["timestamp"].to_numpy()
    omega = angular_rate(df)

    valid = df["aid_bearing_valid"].to_numpy() & np.isfinite(df["aid_bearing"].to_numpy())
    bt = t[valid]
    bearing = df["aid_bearing"].to_numpy()[valid]
    speed = df["aid_speed"].to_numpy()[valid]
    if len(bt) < 200:
        raise ValueError("not enough bearing fixes to select a yaw channel")

    starts, d_bearing, spans = [], [], []
    for i in range(len(bt) - 1):
        j = np.searchsorted(bt, bt[i] + baseline_s)
        if j >= len(bt):
            break
        if min(speed[i], speed[j]) < min_speed:
            continue
        if bt[j] - bt[i] > baseline_s * 2:
            continue
        delta = (bearing[j] - bearing[i] + np.pi) % (2 * np.pi) - np.pi
        if abs(delta) > np.radians(150):
            continue
        starts.append(bt[i])
        spans.append(bt[j])
        d_bearing.append(delta)

    if len(d_bearing) < 100:
        raise ValueError(f"only {len(d_bearing)} usable bearing baselines")

    d_bearing = np.asarray(d_bearing)
    a_idx = np.searchsorted(t, np.asarray(starts))
    b_idx = np.searchsorted(t, np.asarray(spans))

    span_s = np.asarray(spans) - np.asarray(starts)

    best = None
    for ch in range(3):
        integrated = np.array([
            np.trapezoid(omega[a:b, ch], t[a:b]) if b > a + 1 else np.nan
            for a, b in zip(a_idx, b_idx)])
        ok = np.isfinite(integrated)
        if robust:
            # A baseline whose integrated rotation exceeds half a turn cannot be matched
            # against a wrapped bearing difference: both sides are ambiguous. Rejecting it
            # from the gyro side as well as the bearing side is what makes the fit survive
            # low-speed manoeuvring, where such baselines are common.
            ok &= np.abs(integrated) <= MAX_BASELINE_TURN_RAD
        if ok.sum() < 100:
            continue
        if robust:
            scale, _, corr = robust_line(integrated[ok], d_bearing[ok])
            # The same baselines also measure the BIAS, and measure it far better than
            # standing still does. Over a baseline of length T,
            #     d_bearing = scale * integral(omega) - bias * T
            # so regressing on both regressors recovers the bias from thousands of samples
            # of ordinary driving. The alternative -- averaging yaw rate while stopped --
            # depends on a handful of samples selected by a noisy speed threshold, and on
            # one segment it returned 0.0198 rad/s against a true 0.0007: an error worth
            # 136 deg of heading over a two-minute outage.
            design = np.column_stack([integrated[ok], span_s[ok]])
            coef, *_ = np.linalg.lstsq(design, d_bearing[ok], rcond=None)
            bias = float(-coef[1])
        else:
            corr = float(np.corrcoef(integrated[ok], d_bearing[ok])[0, 1])
            scale = float(np.polyfit(integrated[ok], d_bearing[ok], 1)[0])
            bias = None
        if best is None or abs(corr) > abs(best[1]):
            best = (ch, corr, scale, bias)

    if best is None:
        raise ValueError("could not identify a yaw channel")
    return best[0], best[2], best[1], best[3]


def estimate_gyro_bias(df: pd.DataFrame, yaw_channel: int, yaw_scale: float,
                       stationary_speed: float = 0.5) -> float:
    """Mean yaw rate while the vehicle is stationary -- that residual is bias.

    Stationarity is judged from the aided speed when available. Falls back to the
    whole-segment median, which is serviceable over a long mixed drive because left
    and right turns roughly cancel.
    """
    w = angular_rate(df)[:, yaw_channel] * yaw_scale
    if "aid_speed" in df.columns:
        speed = pd.Series(df["aid_speed"]).ffill().bfill().to_numpy()
    else:
        speed = df["speed"].to_numpy()
    still = np.isfinite(speed) & (speed < stationary_speed)
    if still.sum() >= 200:
        return float(np.mean(w[still]))
    return float(np.median(w))


def estimate_alignment(df: pd.DataFrame, min_speed: float = 5.0,
                       smooth_s: float = 1.0, use_reference: bool = False,
                       robust: bool = False) -> Alignment:
    """Estimate the vehicle's forward direction within the device horizontal plane.

    Longitudinal vehicle acceleration appears along a fixed direction in the device
    frame while lateral acceleration is, for ordinary driving, uncorrelated with
    changes in speed. Regressing the two horizontal accelerometer channels jointly
    against d(speed)/dt therefore recovers that direction in closed form.

    Inputs are smartphone-only by default, driven by the simulated ~1 Hz aiding.
    `use_reference=True` substitutes the vehicle's own longitudinal-acceleration
    channel and exists purely as a labelled upper bound for validation.
    """
    t = df["timestamp"].to_numpy()
    rate = len(df) / max(t[-1] - t[0], 1e-6)
    win = max(int(round(smooth_s * rate)), 1)
    smooth = lambda x: pd.Series(x).rolling(win, center=True, min_periods=1).mean().to_numpy()

    h = horizontal_acceleration(df)
    h1, h2 = smooth(h[:, 0]), smooth(h[:, 1])

    if use_reference:
        target = smooth(df["ref_a_long"].to_numpy())
        speed = df["ref_speed"].to_numpy()
        ok = np.isfinite(target) & np.isfinite(h1) & np.isfinite(h2) & (speed > min_speed)
    else:
        if "aid_valid" not in df.columns:
            raise ValueError("expected simulated aiding columns; call add_simulated_gnss first")
        aid = df["aid_valid"].to_numpy()
        at = t[aid]
        av = df["aid_speed"].to_numpy()[aid]
        if len(at) < 100:
            raise ValueError("not enough aiding fixes to estimate alignment")
        # differentiate the aided speed, then map back onto the IMU time base
        dv = np.gradient(av, at)
        target = np.interp(t, at, dv)
        speed = np.interp(t, at, av)
        ok = (np.isfinite(target) & np.isfinite(h1) & np.isfinite(h2)
              & (speed > min_speed) & (np.abs(target) < 5.0))

    if ok.sum() < 200:
        raise ValueError(f"only {ok.sum()} usable samples for alignment")

    A = np.column_stack([h1[ok], h2[ok]])
    coef, *_ = np.linalg.lstsq(A, target[ok], rcond=None)
    angle = float(np.arctan2(coef[1], coef[0]))

    projected = A @ np.array([np.cos(angle), np.sin(angle)])
    if robust:
        scale, _, corr = robust_line(projected, target[ok])
    else:
        corr = float(np.corrcoef(projected, target[ok])[0, 1])
        scale = float(np.polyfit(projected, target[ok], 1)[0])

    yaw_channel, yaw_scale, yaw_corr, yaw_bias = select_yaw_channel(df, robust=robust)

    return Alignment(
        forward_angle_rad=angle,
        gyro_bias_rad_s=(yaw_bias if yaw_bias is not None
                         else estimate_gyro_bias(df, yaw_channel, yaw_scale)),
        n_samples=int(ok.sum()),
        forward_accel_corr=corr,
        forward_accel_scale=scale,
        yaw_channel=yaw_channel,
        yaw_scale=yaw_scale,
        yaw_corr=yaw_corr,
    )


def gnss_enu(df: pd.DataFrame, origin: tuple[float, float, float] = None):
    """ENU coordinates of the GNSS fixes in a segment, plus their timestamps."""
    fixes = df[df["gnss_new"]]
    if origin is None:
        origin = (fixes["lat"].iloc[0], fixes["lon"].iloc[0], fixes["alt"].iloc[0])
    e, n, u = latlon_to_enu(fixes["lat"], fixes["lon"], fixes["alt"], *origin)
    return np.asarray(e), np.asarray(n), fixes["timestamp"].to_numpy(), origin
