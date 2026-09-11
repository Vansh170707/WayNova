package org.neuronavx.logger.nav

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

/** Result of applying the current phone/drive calibration to one model prediction. */
data class AdaptedSpeedEstimate(val speed: Double, val sigma: Double)

/**
 * Learns and removes the live phone's speed-model bias while GNSS is healthy.
 *
 * The September 3 field drives isolated a cross-device domain shift: the TCN was about
 * 4.5--5 m/s high both before and during the controlled outage. A multiplicative fit is
 * poorly identifiable during a short, almost constant-speed lead-in, while an additive
 * bias is observable from aided model/GNSS pairs. Only recent pairs at comparable GNSS
 * speeds contribute, so a slowdown does not inherit an old cruising offset.
 *
 * With fewer than two pairs, navigation carries the last trusted GNSS speed. With two
 * pairs it can track model changes relative to the latest aided anchor, with broad noise.
 * Three comparable pairs establish the correction. Predictions are bias
 * corrected and slew limited to physically plausible acceleration/deceleration. Model
 * uncertainty is never allowed below the live calibration residual, and grows when a
 * prediction had to be limited.
 */
class LiveSpeedAdapter(
    private val minSamples: Int = 3,
    private val maxSamples: Int = 60,
    private val maxGnssAccuracyM: Double = 30.0,
    private val maxVehicleSpeedMs: Double = 70.0,
    private val maxAccelerationMs2: Double = 3.0,
    private val maxDecelerationMs2: Double = 5.0,
    private val uncalibratedSigmaMs: Double = 6.0,
    private val residualSigmaFloorMs: Double = 1.0,
) {
    private data class Pair(val error: Double, val speed: Double, val t: Double)
    private val pairs = ArrayDeque<Pair>()
    private var lastAidedRaw = Double.NaN
    // Preserve all recent evidence in steady motion. Only select a speed neighbourhood
    // when moving; stops retain their complete recent bias history rather than following
    // individual vibration peaks. Five predictions alone proved too noisy in field replay.
    private val errors: List<Double>
        get() = pairs.filter { lastTrustedT - it.t <= 15.0 &&
            (lastTrustedSpeed < 0.5 || abs(it.speed - lastTrustedSpeed) <= 2.0) }
            .map { it.error }
    private var lastTrustedSpeed = Double.NaN
    private var lastTrustedT = Double.NaN
    private var lastAdaptedSpeed = Double.NaN
    private var lastAdaptedT = Double.NaN
    /** Set when the learned offset exceeded the model output for a still-moving vehicle. */
    private var degenerateCorrection = false

    fun reset() {
        pairs.clear()
        lastAidedRaw = Double.NaN
        lastTrustedSpeed = Double.NaN
        lastTrustedT = Double.NaN
        lastAdaptedSpeed = Double.NaN
        lastAdaptedT = Double.NaN
        degenerateCorrection = false
    }

    val calibrationSamples: Int get() = errors.size
    val isReady: Boolean get() = calibrationSamples >= minSamples

    /** Raw-model minus GNSS speed, in m/s. */
    val biasMs: Double
        get() = if (errors.isEmpty()) Double.NaN else median(errors)

    /** Robust 1-sigma scatter of the aided residuals after bias removal. */
    val residualSigmaMs: Double
        get() {
            if (errors.size < 2) return Double.NaN
            val centre = biasMs
            val deviations = errors.map { abs(it - centre) }
            return max(1.4826 * median(deviations), residualSigmaFloorMs)
        }

    fun observeTrustedSpeed(t: Double, speed: Double, accuracyM: Double) {
        if (!trusted(speed, accuracyM) || !t.isFinite()) return
        lastTrustedSpeed = speed
        lastTrustedT = t
        // A healthy aided fix closes any previous outage and becomes the next fallback.
        lastAdaptedSpeed = speed
        lastAdaptedT = t
    }

    /** Pair one aided prediction with the same instant's trusted GNSS speed. */
    fun observeAidedPrediction(
        prediction: SpeedEstimate,
        gnssSpeed: Double,
        gnssAccuracyM: Double,
    ): Boolean {
        if (!trusted(gnssSpeed, gnssAccuracyM) ||
            !prediction.speed.isFinite() || prediction.speed !in 0.0..maxVehicleSpeedMs) {
            return false
        }
        val error = prediction.speed - gnssSpeed
        if (abs(error) > MAX_ABSOLUTE_BIAS_MS) return false

        // Once a centre exists, reject a single implausible pair without freezing normal
        // changes in road speed or model output. The generous 8 m/s floor retained every
        // valid pair in the two field drives while excluding gross sensor/model glitches.
        if (isReady) {
            val scatter = residualSigmaMs.takeIf { it.isFinite() } ?: residualSigmaFloorMs
            if (abs(error - biasMs) > max(OUTLIER_FLOOR_MS, 4.0 * scatter)) return false
        }

        lastAidedRaw = prediction.speed
        pairs.addLast(Pair(error, gnssSpeed, lastTrustedT))
        while (pairs.size > maxSamples) pairs.removeFirst()
        return true
    }

    /** Bias-corrected diagnostic preview while aided; this does not alter outage state. */
    fun preview(prediction: SpeedEstimate): AdaptedSpeedEstimate {
        val desired = correctedOrFallback(prediction)
        return AdaptedSpeedEstimate(desired, correctedSigma(prediction, desired, desired))
    }

    /** Bias-correct and physically constrain a prediction that will be fused in blackout. */
    fun adapt(t: Double, prediction: SpeedEstimate): AdaptedSpeedEstimate {
        val desired = correctedOrFallback(prediction)
        val previous = lastAdaptedSpeed.takeIf { it.isFinite() }
            ?: lastTrustedSpeed.takeIf { it.isFinite() }
            ?: desired
        val previousT = lastAdaptedT.takeIf { it.isFinite() }
            ?: lastTrustedT.takeIf { it.isFinite() }
            ?: t
        val dt = (t - previousT).coerceIn(0.0, MAX_SLEW_INTERVAL_S)
        val limited = desired.coerceIn(
            (previous - maxDecelerationMs2 * dt).coerceAtLeast(0.0),
            (previous + maxAccelerationMs2 * dt).coerceAtMost(maxVehicleSpeedMs),
        )
        lastAdaptedSpeed = limited
        lastAdaptedT = t
        return AdaptedSpeedEstimate(
            limited,
            correctedSigma(prediction, desired, limited),
        )
    }

    private fun correctedOrFallback(prediction: SpeedEstimate): Double {
        if (!isReady) {
            degenerateCorrection = false
            return deltaAnchored(prediction, requirePairs = true)
        }

        val corrected = prediction.speed - biasMs
        // An additive offset only describes the speed regime it was learned in. On the
        // 7 September 18:09 drive the bias was fixed at 9.46 m/s from cruising pairs; once
        // the vehicle slowed, the model's own output fell below that offset and the
        // correction clipped to zero on 57% of the outage while GNSS showed 3.42 m/s.
        // Asserting a stopped vehicle is far worse than admitting the offset does not
        // apply here, so fall back to tracking the model's RELATIVE change from the last
        // aided anchor. The vehicle must actually have been moving for this to be the
        // better answer: a genuine stop still has to be allowed to reach zero.
        if (corrected <= DEGENERATE_MARGIN_MS && lastTrustedSpeed.isFinite() &&
            lastTrustedSpeed >= MOVING_ANCHOR_MS) {
            degenerateCorrection = true
            return deltaAnchored(prediction, requirePairs = false)
        }
        degenerateCorrection = false
        return corrected.coerceIn(0.0, maxVehicleSpeedMs)
    }

    /**
     * Track the model's change since the last aided pair, anchored on trusted GNSS speed.
     *
     * A constant-speed fallback cannot respond to a departure, so the relative movement the
     * model still reports is kept even when its absolute level is not trustworthy.
     */
    private fun deltaAnchored(prediction: SpeedEstimate, requirePairs: Boolean): Double {
        val haveAnchor = lastAidedRaw.isFinite() && prediction.speed.isFinite() &&
            (!requirePairs || pairs.size >= 2)
        val delta = if (haveAnchor) prediction.speed - lastAidedRaw else 0.0
        return lastTrustedSpeed.takeIf { it.isFinite() }
            ?.let { (it + delta).coerceIn(0.0, maxVehicleSpeedMs) }
            ?: prediction.speed.takeIf { it.isFinite() }
                ?.coerceIn(0.0, maxVehicleSpeedMs)
            ?: 0.0
    }

    private fun correctedSigma(
        prediction: SpeedEstimate,
        desired: Double,
        limited: Double,
    ): Double {
        val modelSigma = prediction.sigma.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        // A fallen-back estimate is no better calibrated than an uncalibrated one, and the
        // filter must weight it that way rather than inheriting the tight residual scatter
        // of a bias that was just shown not to apply.
        val calibrationSigma =
            if (isReady && !degenerateCorrection) residualSigmaMs else uncalibratedSigmaMs
        // If the physical guard clips a jump, its rejected magnitude is uncertainty rather
        // than silently discarded information.
        return max(max(modelSigma, calibrationSigma), abs(desired - limited))
    }

    private fun trusted(speed: Double, accuracyM: Double): Boolean =
        speed.isFinite() && speed in 0.0..maxVehicleSpeedMs &&
            accuracyM.isFinite() && accuracyM > 0.0 && accuracyM <= maxGnssAccuracyM

    private fun median(values: Collection<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else 0.5 * (sorted[middle - 1] + sorted[middle])
    }

    private companion object {
        const val MAX_ABSOLUTE_BIAS_MS = 30.0
        const val OUTLIER_FLOOR_MS = 8.0
        const val MAX_SLEW_INTERVAL_S = 5.0
        const val DEGENERATE_MARGIN_MS = 0.5
        const val MOVING_ANCHOR_MS = 1.0
    }
}
