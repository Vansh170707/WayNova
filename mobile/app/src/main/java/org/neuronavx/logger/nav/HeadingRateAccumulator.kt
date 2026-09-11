package org.neuronavx.logger.nav

/**
 * Pre-integrates Android-native yaw across every sensor callback.
 *
 * The estimator emits at 10 Hz while live sensors arrive at about 100 Hz. Picking one raw
 * gyro sample out of every ten aliases vibration into turn rate; this class integrates all
 * callbacks and supplies the time-weighted mean for the next estimator interval instead.
 */
class HeadingRateAccumulator(
    private val maxSampleGapS: Double = 0.5,
) {
    private var previousT = Double.NaN
    private var previousRate = Double.NaN
    private var integratedRad = 0.0
    private var latestRate = Double.NaN

    fun reset() {
        previousT = Double.NaN; previousRate = Double.NaN
        integratedRad = 0.0; latestRate = Double.NaN
    }

    fun add(t: Double, gyro: DoubleArray, gravity: DoubleArray) {
        val rate = Signals.gravityProjectedHeadingRate(gyro, gravity)
        if (!t.isFinite() || !rate.isFinite()) return

        if (previousT.isFinite() && previousRate.isFinite() && t > previousT) {
            val dt = t - previousT
            if (dt <= maxSampleGapS) {
                integratedRad += 0.5 * (previousRate + rate) * dt
            }
        }
        previousT = t
        previousRate = rate
        latestRate = rate
    }

    /**
     * Return the mean rate whose integration over [estimatorIntervalS] equals the yaw seen
     * since the previous consume. Missing-sensor gaps contribute zero rotation instead of
     * extending one stale sample across the gap.
     */
    fun consumeMean(estimatorIntervalS: Double): Double {
        val mean = when {
            estimatorIntervalS > 0.0 -> integratedRad / estimatorIntervalS
            latestRate.isFinite() -> latestRate
            else -> 0.0
        }
        integratedRad = 0.0
        return mean
    }
}
