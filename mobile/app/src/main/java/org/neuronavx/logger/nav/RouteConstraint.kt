package org.neuronavx.logger.nav

import kotlin.math.*

/** An explicitly conditional position hypothesis. Never corrects Navigator or its covariance. */
class RouteConstraint(private val geometry: RouteGuidance) {
    data class Result(val status: String, val point: RoutePoint? = null,
        val progressM: Double = Double.NaN, val uncertaintyM: Double = Double.NaN)
    private var along = Double.NaN
    private var lastT = Double.NaN
    private var lastSpeed = 0.0
    private var anchorT = Double.NaN
    private var acquiringSince = Double.NaN
    private var uncertainty = 0.0

    fun reset() { along=Double.NaN; lastT=Double.NaN; anchorT=Double.NaN; acquiringSince=Double.NaN }
    private fun release(reason: String): Result { reset(); return Result(reason) }

    fun update(t: Double, raw: RoutePoint, speed: Double, heading: Double, sigma: Double,
        aided: Boolean, recovering: Boolean = false): Result {
        if (!geometry.constraintSupported) return release("UNSUPPORTED_ROUTE")
        if (!listOf(t,speed,heading,sigma).all { it.isFinite() } || speed !in 0.0..70.0 || sigma < 0)
            return release("INVALID_INPUT")
        val dt = if (lastT.isFinite()) t-lastT else 0.0
        if (lastT.isFinite() && (dt <= 0 || dt > 1.0)) return release("SAMPLE_GAP")
        if (recovering) return release("GPS_RECOVERY")
        if (aided) {
            val candidates = geometry.matches(raw)
            val match = candidates.minByOrNull { it.gap } ?: return release("NO_ANCHOR")
            if (sigma > 15 || match.gap > 20) return release("NO_ANCHOR")
            if (candidates.any { it.gap <= match.gap+10 && abs(it.along-match.along)>40 }) return release("AMBIGUOUS_ROUTE")
            if (speed > 1 && cos(heading-match.heading) < cos(Math.toRadians(40.0))) return release("HEADING_CONFLICT")
            if (!acquiringSince.isFinite()) acquiringSince=t
            lastT=t; lastSpeed=speed; uncertainty=max(5.0,sigma)
            if (t-acquiringSince < 1.0) return Result("ACQUIRING_ANCHOR")
            along=match.along; anchorT=t
            return Result("ARMED", progressM=along, uncertaintyM=uncertainty)
        }
        acquiringSince=Double.NaN
        if (!along.isFinite()) return release("NO_ANCHOR")
        if (t-anchorT > 60 || sigma > 60) return release("UNCERTAINTY_LIMIT")
        val next = along + 0.5*(lastSpeed+speed)*dt
        if (next >= geometry.lengthM-2) return release("ROUTE_END_UNCONFIRMED")
        val (point, roadHeading) = geometry.pointAt(next)
        if (speed > 1 && cos(heading-roadHeading) < cos(Math.toRadians(50.0))) return release("HEADING_CONFLICT")
        val matches = geometry.matches(raw)
        // The raw estimate is a consistency check, not a measurement used to advance progress.
        val rawNearPrediction = matches.filter { abs(it.along-next) <= 40 }.minByOrNull { it.gap }
        if (rawNearPrediction == null || rawNearPrediction.gap > max(25.0,min(60.0,2*sigma)))
            return release("ROUTE_CONFLICT")
        if (geometry.matches(point).any { it.gap <= 15 && abs(it.along-next)>40 }) return release("AMBIGUOUS_ROUTE")
        uncertainty += max(1.0,0.2*speed)*dt
        if (uncertainty > 60) return release("UNCERTAINTY_LIMIT")
        along=next; lastT=t; lastSpeed=speed
        return Result("ASSISTED", point, along, max(sigma,uncertainty))
    }
}
