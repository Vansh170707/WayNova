package org.neuronavx.logger.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Planar error-state EKF, a direct port of `src/neuronav/fusion/es_ekf.py`.
 *
 * Nominal state x = [pE, pN, psi, v, b_w, s_w]; the filter estimates small errors around
 * it and injects them after each update. Heading lives in the nominal state and only a
 * small heading ERROR is ever filtered, which is what makes the wrap-around well behaved.
 *
 * The sixth state is the yaw-gyro scale-factor error added in Phase 6:
 * `omega = (1 + s_w) * (omega_measured - b_w)`. It reaches heading only in proportion to
 * turn rate, which is what separates it from the bias and makes it observable from GNSS
 * bearing while turning. Measured on a held-out driver whose heading error was
 * scale-dominated, it cut p90 blackout drift by ~6 points; on a bias-dominated driver it
 * did nothing and cost nothing.
 *
 * Numerical choices carried over deliberately:
 *  - the innovation gate rejects measurements beyond N sigma, and a run of rejections
 *    inflates the covariance so a diverged filter can re-acquire instead of rejecting
 *    forever the very fixes that would fix it;
 *  - the covariance update uses Joseph form, which stays symmetric and positive-definite
 *    under gating.
 */
class EsEkf(val config: Config = Config()) {

    data class Config(
        // process noise
        val sigmaAccel: Double = 0.6,
        val sigmaGyro: Double = 0.02,
        val sigmaGyroBias: Double = 2e-4,
        val estimateGyroBias: Boolean = true,
        val sigmaSpeedProcess: Double = 0.4,
        // yaw scale factor
        val estimateGyroScale: Boolean = true,
        val sigmaGyroScale: Double = 0.05,
        val sigmaGyroScaleWalk: Double = 1e-5,
        val sigmaGyroRateNoise: Double = 0.01,
        // measurement noise
        val sigmaGnssPosFloor: Double = 3.0,
        val sigmaGnssSpeed: Double = 0.7,
        val sigmaGnssCourse: Double = 0.15,
        // gating
        val courseMinSpeed: Double = 2.0,
        val innovationGateSigma: Double = 5.0,
        val zuptSpeed: Double = 0.3,
        // learned speed
        val minLearnedSpeedSigma: Double = 0.8,
        val learnedSpeedUpdateHz: Double = 1.0,
        // divergence recovery
        val rejectStreakForReset: Int = 5,
        val resetPositionVar: Double = 100.0 * 100.0,
        val resetHeadingVar: Double = 0.5 * 0.5,
    )

    companion object {
        const val PE = 0
        const val PN = 1
        const val PSI = 2
        const val V = 3
        const val BW = 4
        const val SW = 5
        const val N = 6
        const val MAX_GYRO_SCALE_ERROR = 0.3
    }

    val x = DoubleArray(N)
    val p = Array(N) { DoubleArray(N) }

    var rejected = 0; private set
    var updates = 0; private set
    var resets = 0; private set
    private var consecutiveRejects = 0

    val east: Double get() = x[PE]
    val north: Double get() = x[PN]
    val heading: Double get() = x[PSI]
    val speed: Double get() = x[V]
    val gyroBias: Double get() = x[BW]
    val gyroScaleError: Double get() = x[SW]

    /** 1-sigma horizontal position uncertainty, for the confidence display. */
    val positionSigma: Double get() = sqrt(p[PE][PE] + p[PN][PN])

    fun initialize(east: Double, north: Double, heading: Double, speed: Double,
                   gyroBias: Double = 0.0, gyroScaleError: Double = 0.0) {
        x[PE] = east; x[PN] = north
        x[PSI] = Signals.wrapAngle(heading)
        x[V] = speed; x[BW] = gyroBias; x[SW] = gyroScaleError
        for (i in 0 until N) java.util.Arrays.fill(p[i], 0.0)
        p[PE][PE] = 9.0; p[PN][PN] = 9.0
        p[PSI][PSI] = 0.2 * 0.2
        p[V][V] = 1.0
        p[BW][BW] = if (config.estimateGyroBias) 1e-4 else 0.0
        p[SW][SW] = if (config.estimateGyroScale) config.sigmaGyroScale * config.sigmaGyroScale else 0.0
        rejected = 0; updates = 0; resets = 0; consecutiveRejects = 0
    }

    /** Advance the nominal state and covariance by dt seconds. */
    fun propagate(dt: Double, yawRate: Double, forwardAccel: Double) {
        if (dt <= 0.0) return
        val c = config
        val psi = x[PSI]; val v = x[V]; val bw = x[BW]; val sw = x[SW]
        val wDebiased = yawRate - bw
        val w = (1.0 + sw) * wDebiased

        val sinPsi = sin(psi); val cosPsi = cos(psi)
        x[PE] += v * sinPsi * dt + 0.5 * forwardAccel * sinPsi * dt * dt
        x[PN] += v * cosPsi * dt + 0.5 * forwardAccel * cosPsi * dt * dt
        x[PSI] = Signals.wrapAngle(psi + w * dt)
        x[V] = v + forwardAccel * dt

        val f = identity()
        f[PE][PSI] = v * cosPsi * dt
        f[PE][V] = sinPsi * dt
        f[PN][PSI] = -v * sinPsi * dt
        f[PN][V] = cosPsi * dt
        if (c.estimateGyroBias) f[PSI][BW] = -(1.0 + sw) * dt
        if (c.estimateGyroScale) f[PSI][SW] = wDebiased * dt

        val rateNoise = if (c.estimateGyroScale) c.sigmaGyroRateNoise else 0.0
        val q = DoubleArray(N)
        q[PE] = (0.5 * c.sigmaAccel * dt * dt).let { it * it }
        q[PN] = q[PE]
        q[PSI] = (c.sigmaGyro * c.sigmaGyro + (rateNoise * abs(wDebiased)).let { it * it }) * dt
        q[V] = (c.sigmaAccel * dt).let { it * it } + c.sigmaSpeedProcess * c.sigmaSpeedProcess * dt
        q[BW] = if (c.estimateGyroBias) c.sigmaGyroBias * c.sigmaGyroBias * dt else 0.0
        q[SW] = if (c.estimateGyroScale) c.sigmaGyroScaleWalk * c.sigmaGyroScaleWalk * dt else 0.0

        // P = F P F^T + Q
        val fp = matMul(f, p)
        val next = matMulT(fp, f)
        for (i in 0 until N) next[i][i] += q[i]
        copyInto(next, p)
    }

    // ------------------------------------------------------------------ updates

    /**
     * Shared Kalman update for a measurement of `rows` dimensions.
     *
     * `hIdx` lists which state each measurement row observes; every measurement in this
     * filter is a direct observation of one or two states, so passing indices avoids
     * building and multiplying a dense H.
     */
    private fun apply(hIdx: IntArray, innovation: DoubleArray, r: Array<DoubleArray>): Boolean {
        val m = hIdx.size
        // S = H P H^T + R
        val s = Array(m) { i -> DoubleArray(m) { j -> p[hIdx[i]][hIdx[j]] + r[i][j] } }
        val sInv = invert(s) ?: return false

        var nis = 0.0
        for (i in 0 until m) for (j in 0 until m) nis += innovation[i] * sInv[i][j] * innovation[j]
        val gate = config.innovationGateSigma * config.innovationGateSigma * m
        if (nis > gate) { rejected++; return false }

        // K = P H^T S^-1   (P H^T is the columns of P selected by hIdx)
        val k = Array(N) { row -> DoubleArray(m) { col ->
            var acc = 0.0
            for (j in 0 until m) acc += p[row][hIdx[j]] * sInv[j][col]
            acc
        } }

        for (row in 0 until N) {
            var d = 0.0
            for (j in 0 until m) d += k[row][j] * innovation[j]
            x[row] += d
        }
        x[PSI] = Signals.wrapAngle(x[PSI])
        // An unbounded scale error can only come from a badly conditioned update, and
        // letting it run inverts the sign of every turn.
        x[SW] = x[SW].coerceIn(-MAX_GYRO_SCALE_ERROR, MAX_GYRO_SCALE_ERROR)

        // Joseph form: P = (I-KH) P (I-KH)^T + K R K^T
        val ikh = identity()
        for (row in 0 until N) for (j in 0 until m) ikh[row][hIdx[j]] -= k[row][j]
        val left = matMul(ikh, p)
        val out = matMulT(left, ikh)
        for (a in 0 until N) for (b in 0 until N) {
            var acc = 0.0
            for (i in 0 until m) for (j in 0 until m) acc += k[a][i] * r[i][j] * k[b][j]
            out[a][b] += acc
        }
        for (a in 0 until N) for (b in 0 until N) p[a][b] = 0.5 * (out[a][b] + out[b][a])
        updates++
        return true
    }

    /**
     * Fuse a GNSS position fix, with recovery if the filter has lost lock.
     *
     * A diverged filter produces huge innovations, which the gate rejects -- so the very
     * measurements that would fix it are discarded and the divergence becomes permanent.
     * After a run of rejections the covariance is inflated so it can re-acquire.
     */
    fun updateGnssPosition(east: Double, north: Double, accuracyM: Double): Boolean {
        val sigma = maxOf(accuracyM, config.sigmaGnssPosFloor)
        val r = arrayOf(doubleArrayOf(sigma * sigma, 0.0), doubleArrayOf(0.0, sigma * sigma))
        val accepted = apply(intArrayOf(PE, PN),
            doubleArrayOf(east - x[PE], north - x[PN]), r)
        if (accepted) { consecutiveRejects = 0; return true }
        consecutiveRejects++
        if (consecutiveRejects >= config.rejectStreakForReset) {
            p[PE][PE] += config.resetPositionVar
            p[PN][PN] += config.resetPositionVar
            p[PSI][PSI] += config.resetHeadingVar
            resets++
            consecutiveRejects = 0
        }
        return false
    }

    fun updateGnssSpeed(speed: Double): Boolean =
        apply(intArrayOf(V), doubleArrayOf(speed - x[V]),
            arrayOf(doubleArrayOf(config.sigmaGnssSpeed * config.sigmaGnssSpeed)))

    fun updateGnssBearing(bearingRad: Double, sigmaRad: Double = config.sigmaGnssCourse): Boolean {
        val sigma = maxOf(sigmaRad, 1e-3)
        return apply(intArrayOf(PSI),
            doubleArrayOf(Signals.wrapAngle(bearingRad - x[PSI])),
            arrayOf(doubleArrayOf(sigma * sigma)))
    }

    /**
     * Fuse the neural speed estimate as a pseudo-measurement.
     *
     * This is the learned component's only route into the navigation state: the network
     * never writes position or heading, so a bad prediction degrades the solution
     * gracefully through the filter's gating rather than teleporting it.
     */
    fun updateLearnedSpeed(speed: Double, sigma: Double): Boolean {
        if (!speed.isFinite() || !sigma.isFinite() || sigma <= 0.0) return false
        val s = maxOf(sigma, config.minLearnedSpeedSigma)
        return apply(intArrayOf(V), doubleArrayOf(speed - x[V]),
            arrayOf(doubleArrayOf(s * s)))
    }

    /** ZUPT: while stationary, speed is known to be zero to high precision. */
    fun updateZeroVelocity(): Boolean =
        apply(intArrayOf(V), doubleArrayOf(0.0 - x[V]), arrayOf(doubleArrayOf(0.05 * 0.05)))

    // -------------------------------------------------------------- small linalg

    private fun identity(): Array<DoubleArray> =
        Array(N) { i -> DoubleArray(N) { j -> if (i == j) 1.0 else 0.0 } }

    private fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(N) { i ->
            DoubleArray(N) { j ->
                var acc = 0.0
                for (k in 0 until N) acc += a[i][k] * b[k][j]
                acc
            }
        }

    /** a * b^T */
    private fun matMulT(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(N) { i ->
            DoubleArray(N) { j ->
                var acc = 0.0
                for (k in 0 until N) acc += a[i][k] * b[j][k]
                acc
            }
        }

    private fun copyInto(src: Array<DoubleArray>, dst: Array<DoubleArray>) {
        for (i in 0 until N) System.arraycopy(src[i], 0, dst[i], 0, N)
    }

    /** Inverse of a 1x1 or 2x2 innovation covariance; null when singular. */
    private fun invert(s: Array<DoubleArray>): Array<DoubleArray>? = when (s.size) {
        1 -> if (abs(s[0][0]) < 1e-15) null else arrayOf(doubleArrayOf(1.0 / s[0][0]))
        2 -> {
            val det = s[0][0] * s[1][1] - s[0][1] * s[1][0]
            if (abs(det) < 1e-15) null else arrayOf(
                doubleArrayOf(s[1][1] / det, -s[0][1] / det),
                doubleArrayOf(-s[1][0] / det, s[0][0] / det))
        }
        else -> throw IllegalArgumentException("only 1x1 and 2x2 updates are used")
    }
}
