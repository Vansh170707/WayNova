package org.neuronavx.logger.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The six calibrated, vehicle-frame channels the speed TCN consumes, in the exact order
 * and definition of `src/neuronav/models/dataset.py:FEATURE_NAMES`.
 *
 * The network sees vehicle-frame quantities rather than raw device axes for a concrete
 * reason found in the field audit: the gyro yaw channel and the accelerometer gain both
 * vary per session, so a network fed raw axes would have to memorise per-phone conventions
 * instead of learning motion. Everything here is derived from smartphone channels plus the
 * online calibration, so the same computation is available on-device -- which is the whole
 * point of this class existing.
 *
 * The window is held as a ring buffer in the (features, time) layout the exported graph
 * expects, so producing an input tensor is a copy rather than a transpose.
 */
class FeatureWindow(private val windowSamples: Int) {

    companion object {
        const val N_FEATURES = 6
        const val A_FORWARD = 0
        const val A_LATERAL = 1
        const val A_VERTICAL = 2
        const val YAW_RATE = 3
        const val A_HORIZ_MAG = 4
        const val JERK = 5
    }

    private val ring = Array(N_FEATURES) { DoubleArray(windowSamples) }
    private var head = 0
    private var filled = 0
    private var prevAccelNorm = Double.NaN

    val isFull: Boolean get() = filled >= windowSamples

    fun reset() {
        head = 0; filled = 0; prevAccelNorm = Double.NaN
    }

    /** Compute one feature vector and append it to the window. */
    fun push(
        accel: DoubleArray,
        gravity: DoubleArray,
        gyro: DoubleArray,
        align: Alignment,
        nativeYawRate: Double = Double.NaN,
    ) {
        val h = DoubleArray(2)
        Signals.horizontalAcceleration(accel, gravity, h)
        val c = cos(align.forwardAngleRad)
        val s = sin(align.forwardAngleRad)

        val aForward = (h[0] * c + h[1] * s) * align.forwardAccelScale
        val aLateral = (-h[0] * s + h[1] * c) * align.forwardAccelScale
        val aVertical = Signals.verticalAcceleration(accel, gravity)
        // the FEATURE is the scaled raw yaw rate, not the bias-corrected one the filter
        // propagates -- the network was trained on the former
        // Live Android already has the physically meaningful gravity-projected yaw rate.
        // Requiring one raw axis here would reintroduce the phone-orientation ambiguity
        // that projection removed. Replay keeps the trained selected-axis contract.
        val yaw = if (nativeYawRate.isFinite()) nativeYawRate else align.rawYawRate(gyro)
        val horizMag = hypot(h[0], h[1])

        val norm = Signals.magnitude(accel)
        // first sample: the desktop prepends the first value, making the first jerk zero
        val jerk = if (prevAccelNorm.isNaN()) 0.0 else abs(norm - prevAccelNorm)
        prevAccelNorm = norm

        ring[A_FORWARD][head] = clean(aForward)
        ring[A_LATERAL][head] = clean(aLateral)
        ring[A_VERTICAL][head] = clean(aVertical)
        ring[YAW_RATE][head] = clean(yaw)
        ring[A_HORIZ_MAG][head] = clean(horizMag)
        ring[JERK][head] = clean(jerk)

        head = (head + 1) % windowSamples
        if (filled < windowSamples) filled++
    }

    /**
     * Copy the window into [out] as standardised float32, oldest sample first.
     *
     * Standardisation happens here rather than in the model wrapper so the buffer is only
     * walked once; the statistics are the ones fitted during training and shipped beside
     * the graph.
     */
    fun writeStandardised(mean: DoubleArray, std: DoubleArray, out: FloatArray) {
        require(out.size == N_FEATURES * windowSamples) {
            "expected ${N_FEATURES * windowSamples} floats, got ${out.size}"
        }
        for (f in 0 until N_FEATURES) {
            val m = mean[f]
            val sd = if (std[f] != 0.0) std[f] else 1.0
            val base = f * windowSamples
            for (k in 0 until windowSamples) {
                val idx = (head + k) % windowSamples     // oldest first
                out[base + k] = ((ring[f][idx] - m) / sd).toFloat()
            }
        }
    }

    /** Latest value of one channel, for the UI and for tests. */
    fun latest(feature: Int): Double {
        if (filled == 0) return Double.NaN
        return ring[feature][(head - 1 + windowSamples) % windowSamples]
    }

    private fun clean(v: Double): Double = if (v.isFinite()) v else 0.0
}
