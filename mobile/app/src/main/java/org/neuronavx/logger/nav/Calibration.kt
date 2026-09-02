package org.neuronavx.logger.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The phone-to-vehicle calibration the estimator needs before it can navigate. */
data class Alignment(
    val forwardAngleRad: Double,
    val forwardAccelScale: Double,
    val gyroBiasRadS: Double,
    val yawChannel: Int,
    val yawScale: Double,
    val forwardAccelCorr: Double,
    val yawCorr: Double,
    val samples: Int,
    val baselines: Int,
) {
    /** The selected gyro channel scaled into true vehicle yaw rate (rad/s). */
    fun rawYawRate(gyro: DoubleArray): Double = gyro[yawChannel] * yawScale

    fun correctedHeadingRate(gyro: DoubleArray): Double = rawYawRate(gyro) - gyroBiasRadS
}

/**
 * Online phone-to-vehicle calibration, the streaming form of
 * `src/neuronav/calibration/alignment.py`.
 *
 * The phone sits at an unknown fixed yaw angle in its cradle and its gyro's yaw channel
 * differs per device and matches no axis label, so both are estimated from data during
 * GNSS-aided driving rather than assumed.
 *
 * All three estimates are kept as **running moments**, not buffers, so memory is O(1)
 * however long the drive:
 *
 *  - *forward angle*: longitudinal acceleration appears along a fixed direction in the
 *    device frame while lateral acceleration is uncorrelated with changes in speed, so
 *    regressing the two horizontal accelerometer channels against d(speed)/dt recovers
 *    that direction in closed form. Accumulating the 2x2 normal equations is enough, and
 *    the projection's own scale can be recovered from the same moments once the angle is
 *    known -- no second pass over the data.
 *  - *yaw channel and scale*: integrated yaw over a 5 s baseline must reproduce the change
 *    in reported bearing over the same baseline. Cumulative trapezoid integrals per
 *    channel make each baseline a difference of two stored scalars.
 *  - *gyro bias*: the residual yaw rate while stopped.
 *
 * A measured constraint from the desktop side, which is why [ready] exists: calibrating on
 * only ~300 s of aided driving failed outright on 12 of 22 attempts and was worse than
 * whole-history calibration where it did succeed. The estimator must therefore refuse to
 * navigate until it has genuinely converged, rather than quietly using a bad fit.
 */
class OnlineCalibration(
    private val minSpeedForAngle: Double = 5.0,
    private val minSpeedForYaw: Double = 8.0,
    /** Native Android gravity/gyro share axes; replay datasets do not. */
    private val useGravityProjectedYaw: Boolean = false,
    private val baselineS: Double = 5.0,
    private val maxBaselineS: Double = 10.0,
    private val stationarySpeed: Double = 0.5,
    private val smoothS: Double = 1.0,
    private val minAngleSamples: Int = 200,
    private val minBaselines: Int = 100,
    // Forward acceleration is noisy and vehicle-dependent (the audited replay is only
    // ~0.13), so it gets a degeneracy floor. Yaw is the blackout-critical observable:
    // the known-good replay is 0.79 at first lock / 0.92 at full drive, while the Greater
    // Noida field lock that selected the wrong axis was only 0.30 and decayed to 0.24.
    private val minForwardCorrelation: Double = 0.10,
    private val minYawCorrelation: Double = 0.65,
    private val minYawWinnerMargin: Double = 0.20,
) {
    companion object {
        val MAX_BASELINE_TURN_RAD = Math.toRadians(150.0)
        private const val MAX_ABS_ACCEL_TARGET = 5.0
        private const val PROJECTED_YAW = 3
        private const val YAW_CANDIDATES = 4
    }

    private class Fix(val t: Double, val bearing: Double, val speed: Double,
                      val cum: DoubleArray)

    // --- forward-angle normal equations
    private var n = 0
    private var sH1 = 0.0; private var sH2 = 0.0; private var sY = 0.0
    private var sH1H1 = 0.0; private var sH1H2 = 0.0; private var sH2H2 = 0.0
    private var sH1Y = 0.0; private var sH2Y = 0.0; private var sYY = 0.0

    // --- yaw accumulators: three raw channels plus native gravity projection
    private val yn = IntArray(YAW_CANDIDATES)
    private val yx = DoubleArray(YAW_CANDIDATES); private val yy = DoubleArray(YAW_CANDIDATES)
    private val yxx = DoubleArray(YAW_CANDIDATES); private val yxy = DoubleArray(YAW_CANDIDATES)
    private val yyy = DoubleArray(YAW_CANDIDATES)
    // the same baselines also measure the gyro BIAS: over a baseline of length T,
    //   d_bearing = scale * integral(omega) - bias * T
    // so carrying the T moments turns one regression into a joint fit for both
    private val yt = DoubleArray(YAW_CANDIDATES); private val yxt = DoubleArray(YAW_CANDIDATES)
    private val ytt = DoubleArray(YAW_CANDIDATES); private val ytd = DoubleArray(YAW_CANDIDATES)

    // --- gyro bias while stationary
    private val stillSum = DoubleArray(3)
    private var stillCount = 0

    // --- streaming state
    private val cum = DoubleArray(YAW_CANDIDATES)
    private val prevYaw = DoubleArray(YAW_CANDIDATES)
    private var havePrevGyro = false
    private var lastT = Double.NaN
    private val fixes = ArrayDeque<Fix>()

    // causal moving average of the horizontal acceleration, standing in for the desktop's
    // centred rolling mean; a centred window is not available in a live stream
    private val h1Buf = ArrayDeque<Double>()
    private val h2Buf = ArrayDeque<Double>()
    private var h1Sum = 0.0; private var h2Sum = 0.0
    private var smoothLen = 10

    // aided-speed derivative, held between fixes
    private var prevFixT = Double.NaN
    private var prevFixSpeed = Double.NaN
    private var dvdt = Double.NaN
    private var heldSpeed = Double.NaN

    val angleSamples: Int get() = n
    val yawBaselines: Int get() = if (useGravityProjectedYaw) yn[PROJECTED_YAW]
        else maxOf(yn[0], yn[1], yn[2])

    /** Why the most recent count-ready candidate was held back, for field diagnostics. */
    var lastRejectionReason: String? = null
        private set

    /** True once both regressions have enough evidence to be worth trusting. */
    val ready: Boolean get() = n >= minAngleSamples && yawBaselines >= minBaselines

    /** Evidence progress stops at 99% until the correlation/axis-quality gate passes. */
    val progress: Double get() {
        val evidence = minOf(
            n / minAngleSamples.toDouble(),
            yawBaselines / minBaselines.toDouble(),
        ).coerceIn(0.0, 1.0)
        return if (ready) 0.99 else evidence
    }

    fun setRate(hz: Double) {
        smoothLen = maxOf((smoothS * hz).toInt(), 1)
    }

    /** Feed one decimated IMU sample. `gyro` is the raw device-frame angular rate. */
    fun addImu(t: Double, accel: DoubleArray, gravity: DoubleArray, gyro: DoubleArray) {
        val dt = if (lastT.isNaN()) 0.0 else t - lastT
        lastT = t

        val yaw = doubleArrayOf(
            gyro[0], gyro[1], gyro[2],
            Signals.gravityProjectedHeadingRate(gyro, gravity),
        )

        if (havePrevGyro && dt > 0.0) {
            for (c in 0 until YAW_CANDIDATES) {
                if (yaw[c].isFinite() && prevYaw[c].isFinite()) {
                    cum[c] += 0.5 * (prevYaw[c] + yaw[c]) * dt
                }
            }
        }
        yaw.copyInto(prevYaw)
        havePrevGyro = true

        if (heldSpeed.isFinite() && heldSpeed < stationarySpeed) {
            for (c in 0 until 3) stillSum[c] += gyro[c]
            stillCount++
        }

        val h = DoubleArray(2)
        Signals.horizontalAcceleration(accel, gravity, h)
        h1Sum += h[0]; h2Sum += h[1]
        h1Buf.addLast(h[0]); h2Buf.addLast(h[1])
        while (h1Buf.size > smoothLen) { h1Sum -= h1Buf.removeFirst(); h2Sum -= h2Buf.removeFirst() }
        val h1 = h1Sum / h1Buf.size
        val h2 = h2Sum / h2Buf.size

        val target = dvdt
        if (target.isFinite() && heldSpeed.isFinite() && heldSpeed > minSpeedForAngle &&
            abs(target) < MAX_ABS_ACCEL_TARGET && h1.isFinite() && h2.isFinite()) {
            n++
            sH1 += h1; sH2 += h2; sY += target
            sH1H1 += h1 * h1; sH1H2 += h1 * h2; sH2H2 += h2 * h2
            sH1Y += h1 * target; sH2Y += h2 * target; sYY += target * target
        }
    }

    /**
     * Feed one GNSS fix. `bearingRad` is NaN when the receiver withholds it, which it does
     * whenever the vehicle is too slow for a Doppler course to mean anything.
     */
    fun addFix(t: Double, speed: Double, bearingRad: Double) {
        if (prevFixT.isFinite() && t > prevFixT) {
            dvdt = (speed - prevFixSpeed) / (t - prevFixT)
        }
        prevFixT = t; prevFixSpeed = speed
        heldSpeed = speed

        if (!bearingRad.isFinite() || !speed.isFinite()) return
        fixes.addLast(Fix(t, bearingRad, speed, cum.copyOf()))
        drainBaselines()
        // bounded: a fix that never found a partner within maxBaselineS never will
        while (fixes.isNotEmpty() && t - fixes.first().t > maxBaselineS) fixes.removeFirst()
    }

    /** Form every baseline whose far end has now arrived. */
    private fun drainBaselines() {
        while (fixes.size >= 2) {
            val i = fixes.first()
            // the FIRST fix at or after i + baselineS, matching the desktop's searchsorted;
            // taking the last would silently lengthen the baseline and bias the scale
            val partner = fixes.firstOrNull { it.t >= i.t + baselineS } ?: return
            fixes.removeFirst()
            if (partner.t - i.t > maxBaselineS) continue
            if (minOf(i.speed, partner.speed) < minSpeedForYaw) continue
            val delta = Signals.wrapAngle(partner.bearing - i.bearing)
            if (abs(delta) > MAX_BASELINE_TURN_RAD) continue
            val span = partner.t - i.t
            for (c in 0 until YAW_CANDIDATES) {
                val integrated = partner.cum[c] - i.cum[c]
                // A baseline whose integrated rotation exceeds half a turn cannot be
                // matched against a wrapped bearing difference: both sides are ambiguous.
                if (abs(integrated) > MAX_BASELINE_TURN_RAD) continue
                yn[c]++
                yx[c] += integrated; yy[c] += delta
                yxx[c] += integrated * integrated
                yxy[c] += integrated * delta
                yyy[c] += delta * delta
                yt[c] += span
                yxt[c] += integrated * span
                ytt[c] += span * span
                ytd[c] += span * delta
            }
        }
    }

    /** Solve for the calibration, or null if there is not yet enough evidence. */
    fun solve(): Alignment? {
        if (!ready) {
            lastRejectionReason = null
            return null
        }

        // forward angle from the 2x2 normal equations  [sH1H1 sH1H2; sH1H2 sH2H2] c = [sH1Y; sH2Y]
        val det = sH1H1 * sH2H2 - sH1H2 * sH1H2
        if (abs(det) < 1e-12) {
            lastRejectionReason = "forward acceleration geometry is degenerate"
            return null
        }
        val c1 = (sH2H2 * sH1Y - sH1H2 * sH2Y) / det
        val c2 = (sH1H1 * sH2Y - sH1H2 * sH1Y) / det
        val angle = atan2(c2, c1)

        // the projected series p = cos(a)*h1 + sin(a)*h2 never has to be rebuilt: every
        // moment it needs is a combination of moments already accumulated
        val ca = cos(angle); val sa = sin(angle)
        val sP = ca * sH1 + sa * sH2
        val sPP = ca * ca * sH1H1 + 2.0 * ca * sa * sH1H2 + sa * sa * sH2H2
        val sPY = ca * sH1Y + sa * sH2Y
        val scale = slope(n.toDouble(), sP, sY, sPP, sPY)
        val corr = correlation(n.toDouble(), sP, sY, sPP, sPY, sYY)
        if (!scale.isFinite() || !corr.isFinite() ||
            abs(corr) < minForwardCorrelation || scale <= 0.05 || scale > 3.0) {
            lastRejectionReason = "weak forward fit (corr=$corr, scale=$scale)"
            return null
        }

        var bestCh = -1
        var bestCorr = 0.0
        var secondBestAbsCorr = 0.0
        var bestScale = 1.0
        var bestBias = 0.0
        for (c in 0 until 3) {
            if (yn[c] < minBaselines) continue
            val nn = yn[c].toDouble()
            val r = correlation(nn, yx[c], yy[c], yxx[c], yxy[c], yyy[c])
            if (!r.isFinite()) continue
            if (bestCh < 0 || abs(r) > abs(bestCorr)) {
                if (bestCh >= 0) secondBestAbsCorr = maxOf(secondBestAbsCorr, abs(bestCorr))
                bestCh = c; bestCorr = r
                // joint least squares for [scale, -bias] against [integral, T]
                val det = yxx[c] * ytt[c] - yxt[c] * yxt[c]
                if (abs(det) > 1e-12) {
                    bestScale = (ytt[c] * yxy[c] - yxt[c] * ytd[c]) / det
                    bestBias = -(yxx[c] * ytd[c] - yxt[c] * yxy[c]) / det
                } else {
                    bestScale = slope(nn, yx[c], yy[c], yxx[c], yxy[c])
                    bestBias = 0.0
                }
            } else secondBestAbsCorr = maxOf(secondBestAbsCorr, abs(r))
        }
        // Averaging yaw rate while stopped is the obvious estimator and a bad one: it
        // depends on a few hundred samples picked by a noisy speed threshold, and on one
        // segment returned 0.0198 rad/s against a true 0.0007 -- 136 deg of heading over a
        // two-minute outage. The baseline fit above uses thousands of samples of ordinary
        // driving instead, and is kept unless there were no baselines at all.
        var reportedYawCorr = bestCorr
        val bias = if (useGravityProjectedYaw) {
            val nn = yn[PROJECTED_YAW].toDouble()
            val projectedCorr = correlation(
                nn,
                yx[PROJECTED_YAW], yy[PROJECTED_YAW],
                yxx[PROJECTED_YAW], yxy[PROJECTED_YAW], yyy[PROJECTED_YAW],
            )
            // Projection already has the physically correct unit gain. Estimate only the
            // additive bias from delta = integral - bias*T; fitting an unconstrained gain
            // to mostly-straight overlapping windows caused the field drive's -0.608 lock.
            val projectedBias = if (ytt[PROJECTED_YAW] > 1e-12) {
                (yxt[PROJECTED_YAW] - ytd[PROJECTED_YAW]) / ytt[PROJECTED_YAW]
            } else Double.NaN
            if (!projectedCorr.isFinite() || abs(projectedCorr) < minYawCorrelation ||
                !projectedBias.isFinite() || abs(projectedBias) > 0.02) {
                lastRejectionReason = "weak gravity-projected yaw fit " +
                    "(corr=$projectedCorr, bias=$projectedBias)"
                return null
            }
            // Android gyro and gravity share a frame, so projection is the actual live
            // heading channel. Two raw axes often tie when a phone is tilted: rejecting
            // that harmless tie kept a field session at 99% despite projected corr=0.976.
            // Keep the best raw channel only as a legacy diagnostic/model fallback.
            reportedYawCorr = projectedCorr
            projectedBias
        } else {
            if (bestCh < 0) {
                lastRejectionReason = "no usable yaw channel"
                return null
            }
            val yawMargin = abs(bestCorr) - secondBestAbsCorr
            if (!bestScale.isFinite() || !bestBias.isFinite() ||
                abs(bestCorr) < minYawCorrelation || yawMargin < minYawWinnerMargin ||
                abs(bestScale) < 0.05 || abs(bestScale) > 5.0 || abs(bestBias) > 0.02) {
                lastRejectionReason = "ambiguous yaw fit (corr=$bestCorr, margin=$yawMargin, " +
                    "scale=$bestScale, bias=$bestBias)"
                return null
            }
            bestBias
        }

        // A projected live solve no longer depends on any single raw axis. The chosen raw
        // values remain finite for old feature/debug consumers, while Navigator supplies
        // the projected rate directly to the live model window.
        val reportedYawChannel = if (bestCh >= 0) bestCh else 0
        val reportedYawScale = if (bestScale.isFinite()) bestScale else 1.0

        lastRejectionReason = null
        return Alignment(
            forwardAngleRad = angle,
            forwardAccelScale = scale,
            gyroBiasRadS = bias,
            yawChannel = reportedYawChannel,
            yawScale = reportedYawScale,
            forwardAccelCorr = corr,
            yawCorr = reportedYawCorr,
            samples = n,
            baselines = if (useGravityProjectedYaw) yn[PROJECTED_YAW]
                else yn[reportedYawChannel],
        )
    }

    private fun slope(nn: Double, sx: Double, sy: Double, sxx: Double, sxy: Double): Double {
        val d = nn * sxx - sx * sx
        return if (abs(d) < 1e-12) Double.NaN else (nn * sxy - sx * sy) / d
    }

    private fun correlation(nn: Double, sx: Double, sy: Double, sxx: Double,
                            sxy: Double, syy: Double): Double {
        val cov = nn * sxy - sx * sy
        val vx = nn * sxx - sx * sx
        val vy = nn * syy - sy * sy
        if (vx <= 0.0 || vy <= 0.0) return Double.NaN
        return cov / sqrt(vx * vy)
    }
}
