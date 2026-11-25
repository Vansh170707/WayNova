package org.neuronavx.logger.nav

import kotlin.math.*

/**
 * Decides when the local road direction is safe to use as a heading measurement while
 * GNSS is unavailable.
 *
 * Phase 3 map aiding failed because a confident wrong-road POSITION match pulled the
 * solution onto the wrong road. A heading measurement avoids that: the road the car is
 * actually on and a nearby parallel road point the same way. The measurement is only
 * taken when every drivable segment around the estimate agrees on one direction. That
 * rejects junctions, bends, roundabouts and car parks automatically, along with any case
 * where the estimate is far enough off to sit near a crossing road.
 */
class RoadHeadingAid(private val roads: RoadNetwork, val config: Config = Config()) {

    data class Config(
        val minSpeedMps: Double = 5.0,
        val maxYawRateDegS: Double = 3.0,
        val minRadiusM: Double = 15.0,
        val maxRadiusM: Double = 40.0,
        val radiusPerSigma: Double = 1.5,
        /** Every nearby segment must lie within this of the consensus direction. */
        val consensusDeg: Double = 12.0,
        /** Larger disagreement with the filter is more likely a different road than a fix. */
        val maxInnovationDeg: Double = 20.0,
        val minSegmentM: Double = 3.0,
        val sigmaDeg: Double = 4.0,
        val periodS: Double = 1.0,
        /** Total nearby road length needed, so one short stray segment cannot steer heading. */
        val minTotalLengthM: Double = 0.0,
        /** Correct heading only, leaving position untouched by the heading/position correlation. */
        val headingOnly: Boolean = false,
        /** Use the ~1 s smoothed yaw rate for the turning check. */
        val smoothYawRate: Boolean = false,
    )

    enum class Status { ACCEPTED, RATE_LIMITED, SLOW, TURNING, NO_ROAD, NO_CONSENSUS, LARGE_INNOVATION, INVALID }

    data class Result(val status: Status, val headingRad: Double = Double.NaN,
        val sigmaRad: Double = Double.NaN, val segments: Int = 0)

    private var lastAcceptedT = Double.NEGATIVE_INFINITY

    fun reset() { lastAcceptedT = Double.NEGATIVE_INFINITY }

    fun evaluate(t: Double, lat: Double, lon: Double, headingRad: Double, speedMps: Double,
        yawRateRadS: Double, sigmaM: Double): Result {
        if (!listOf(t, lat, lon, headingRad, speedMps).all { it.isFinite() }) return Result(Status.INVALID)
        if (t - lastAcceptedT < config.periodS) return Result(Status.RATE_LIMITED)
        if (speedMps < config.minSpeedMps) return Result(Status.SLOW)
        if (yawRateRadS.isFinite() && abs(Math.toDegrees(yawRateRadS)) > config.maxYawRateDegS)
            return Result(Status.TURNING)
        val radius = ((if (sigmaM.isFinite()) sigmaM else config.maxRadiusM) * config.radiusPerSigma)
            .coerceIn(config.minRadiusM, config.maxRadiusM)
        val nearby = roads.near(lat, lon, radius).filter { it.lengthM >= config.minSegmentM }
        if (nearby.isEmpty() || nearby.sumOf { it.lengthM } < config.minTotalLengthM) return Result(Status.NO_ROAD)

        // Express every segment in the direction of travel. A one-way segment drawn against
        // travel stays opposed and breaks the consensus below.
        val aligned = nearby.map { s ->
            if (!s.oneway && abs(wrap(s.headingRad - headingRad)) > PI / 2) wrap(s.headingRad + PI) else s.headingRad
        }
        var sx = 0.0; var sy = 0.0
        for ((i, a) in aligned.withIndex()) { sx += nearby[i].lengthM * sin(a); sy += nearby[i].lengthM * cos(a) }
        val consensus = atan2(sx, sy)
        val spread = aligned.maxOf { abs(wrap(it - consensus)) }
        if (Math.toDegrees(spread) > config.consensusDeg) return Result(Status.NO_CONSENSUS, segments = nearby.size)
        if (Math.toDegrees(abs(wrap(consensus - headingRad))) > config.maxInnovationDeg)
            return Result(Status.LARGE_INNOVATION, consensus, segments = nearby.size)
        lastAcceptedT = t
        return Result(Status.ACCEPTED, consensus, Math.toRadians(config.sigmaDeg), nearby.size)
    }

    private fun wrap(a: Double): Double {
        var x = (a + PI) % (2 * PI)
        if (x < 0) x += 2 * PI
        return x - PI
    }
}
