package org.neuronavx.logger.nav

import kotlin.math.*

/** One drivable road centreline taken from the offline basemap. */
data class RoadLine(val points: List<RoutePoint>, val oneway: Boolean = false, val kind: String = "")

/**
 * Presentation-only road lock.
 *
 * Detects when the displayed marker has drifted off the nearest plausible drivable road and
 * glides it back onto that road's centreline. It works in the navigator's local east/north
 * frame and never feeds anything back into Navigator, EsEkf or their covariance: Phase 3
 * showed that a confident wrong-road match corrupts the filter. Recorded diagnostics and
 * drift scoring therefore remain on the original estimate.
 *
 * Candidate choice combines three costs, a lightweight form of HMM map matching:
 *  - lateral distance from the raw estimate, scaled by the estimator's uncertainty;
 *  - agreement between the travel heading and the road direction (one-way aware);
 *  - continuity with where the previously locked point is predicted to be now, which stops
 *    the marker hopping between a highway and its parallel service road.
 */
class RoadSnapper(private val tuning: Tuning = Tuning()) {

    data class Tuning(
        /** Roads further than this from the raw estimate are never candidates. */
        val searchMinM: Double = 25.0,
        val searchMaxM: Double = 60.0,
        /** Raw estimate further than this from the centreline counts as off-road drift. */
        val onRoadToleranceM: Double = 6.0,
        /** Time constant of the glide back onto the road. */
        val convergeTauS: Double = 0.35,
        /** Upper bound on how fast the correction itself may move the marker. */
        val maxCorrectionRateMps: Double = 40.0,
        /** Moving roads whose direction differs more than this are rejected. */
        val headingGateDeg: Double = 60.0,
        val minHeadingSpeedMps: Double = 2.0,
        /**
         * Without lock history, a second road this far apart, nearly as close to the estimate
         * and nearly as cheap is ambiguous.
         */
        val ambiguityCostMargin: Double = 1.0,
        val ambiguityGapMarginM: Double = 3.0,
        val ambiguitySeparationM: Double = 12.0,
        /** Glide finishes once the marker is this close to the road; it is then pinned. */
        val convergedM: Double = 0.5,
        /** A longer pause than this (app in background, sensor gap) restarts the lock. */
        val maxGapS: Double = 2.0,
    )

    enum class Status {
        /** No road data is loaded (no offline map, or Google renderer active). */
        NO_ROADS,
        /** No drivable road near the estimate: shown unchanged (car park, unmapped lane). */
        OFF_NETWORK,
        /** Two separated roads are equally plausible and there is no history to decide. */
        AMBIGUOUS,
        /** Estimate is on the road; marker sits on the centreline. */
        ON_ROAD,
        /** Estimate had drifted beyond the road; marker is being pulled back. */
        CORRECTING,
    }

    data class Result(
        val status: Status,
        val east: Double,
        val north: Double,
        val headingRad: Double,
        /** Distance of the raw estimate from the chosen road, NaN when none was chosen. */
        val offRoadM: Double = Double.NaN,
        /** Distance the marker is currently displaced from the raw estimate. */
        val correctionM: Double = 0.0,
    ) {
        val locked: Boolean get() = status == Status.ON_ROAD || status == Status.CORRECTING
    }

    private class Segment(
        val ax: Double, val ay: Double, val bx: Double, val by: Double,
        val length: Double, val heading: Double, val oneway: Boolean,
    )

    private class Candidate(val x: Double, val y: Double, val gap: Double,
        val heading: Double, val cost: Double)

    private var segments: List<Segment> = emptyList()
    private var lastT = Double.NaN
    private var offsetE = 0.0
    private var offsetN = 0.0
    private var lockedX = Double.NaN
    private var lockedY = Double.NaN
    /** True once the marker has finished gliding on and is pinned to the road. */
    private var pinned = false

    val hasRoads: Boolean get() = segments.isNotEmpty()

    /** Replace the road set. Coordinates use the same local projection as the navigator. */
    fun setRoads(roads: List<RoadLine>, originLat: Double, originLon: Double) {
        val scale = cos(Math.toRadians(originLat)).coerceAtLeast(1e-8)
        fun east(p: RoutePoint) = Math.toRadians(p.lon - originLon) * EARTH_RADIUS_M * scale
        fun north(p: RoutePoint) = Math.toRadians(p.lat - originLat) * EARTH_RADIUS_M
        val result = ArrayList<Segment>()
        for (road in roads) {
            for (i in 0 until road.points.lastIndex) {
                val a = road.points[i]; val b = road.points[i + 1]
                if (!a.lat.isFinite() || !a.lon.isFinite() || !b.lat.isFinite() || !b.lon.isFinite()) continue
                val ax = east(a); val ay = north(a); val bx = east(b); val by = north(b)
                val length = hypot(bx - ax, by - ay)
                if (length < 0.05) continue
                result += Segment(ax, ay, bx, by, length, atan2(bx - ax, by - ay), road.oneway)
            }
        }
        segments = result
    }

    /** Forget lock history, for example when a new navigation session starts. */
    fun reset() {
        lastT = Double.NaN
        offsetE = 0.0; offsetN = 0.0
        lockedX = Double.NaN; lockedY = Double.NaN
        pinned = false
    }

    /**
     * @param t estimator time in seconds
     * @param east, north raw marker position in the navigator's local frame
     * @param headingRad travel heading, clockwise from north
     * @param speedMps estimated speed
     * @param sigmaM estimator position uncertainty
     */
    fun update(t: Double, east: Double, north: Double, headingRad: Double,
        speedMps: Double, sigmaM: Double): Result {
        if (!t.isFinite() || !east.isFinite() || !north.isFinite()) {
            reset(); return Result(Status.OFF_NETWORK, east, north, headingRad)
        }
        val rawDt = if (lastT.isFinite()) t - lastT else Double.NaN
        val gap = !rawDt.isFinite() || rawDt < 0 || rawDt > tuning.maxGapS
        if (gap) { offsetE = 0.0; offsetN = 0.0; lockedX = Double.NaN; lockedY = Double.NaN; pinned = false }
        val dt = if (gap) 0.0 else rawDt
        lastT = t
        if (segments.isEmpty()) return release(Status.NO_ROADS, east, north, headingRad, dt)

        val speed = if (speedMps.isFinite()) speedMps.coerceAtLeast(0.0) else 0.0
        val sigma = if (sigmaM.isFinite()) sigmaM else tuning.searchMaxM
        val sigmaD = sigma.coerceIn(5.0, 20.0)
        val radius = (2.5 * sigma).coerceIn(tuning.searchMinM, tuning.searchMaxM)
        val useHeading = speed >= tuning.minHeadingSpeedMps && headingRad.isFinite()
        val gate = Math.toRadians(tuning.headingGateDeg)

        val hasHistory = lockedX.isFinite()
        val predictedX: Double
        val predictedY: Double
        if (hasHistory) {
            val step = if (useHeading) speed * dt else 0.0
            predictedX = lockedX + step * sin(headingRad)
            predictedY = lockedY + step * cos(headingRad)
        } else { predictedX = Double.NaN; predictedY = Double.NaN }

        val candidates = ArrayList<Candidate>()
        for (s in segments) {
            val dx = s.bx - s.ax; val dy = s.by - s.ay
            val f = (((east - s.ax) * dx + (north - s.ay) * dy) / (s.length * s.length)).coerceIn(0.0, 1.0)
            val px = s.ax + f * dx; val py = s.ay + f * dy
            val lateral = hypot(east - px, north - py)
            if (lateral > radius) continue
            var roadHeading = s.heading
            var cost = (lateral / sigmaD).pow(2)
            if (useHeading) {
                var delta = angleBetween(headingRad, s.heading)
                if (!s.oneway && delta > PI / 2) {
                    // Two-way road: travel the opposite direction of the drawn segment.
                    delta = PI - delta
                    roadHeading = wrap(s.heading + PI)
                }
                if (delta > gate) continue
                cost += 0.5 * (delta / Math.toRadians(20.0)).pow(2)
            }
            if (hasHistory) cost += (hypot(px - predictedX, py - predictedY) / 10.0).pow(2)
            candidates += Candidate(px, py, lateral, roadHeading, cost)
        }
        val best = candidates.minByOrNull { it.cost }
            ?: return release(Status.OFF_NETWORK, east, north, headingRad, dt)
        if (!hasHistory && candidates.any {
                it.cost <= best.cost + tuning.ambiguityCostMargin &&
                    it.gap <= best.gap + tuning.ambiguityGapMarginM &&
                    hypot(it.x - best.x, it.y - best.y) > tuning.ambiguitySeparationM
            }) return release(Status.AMBIGUOUS, east, north, headingRad, dt)

        val targetE = best.x - east; val targetN = best.y - north
        if (pinned || (!hasHistory && dt == 0.0)) {
            // Locked (or very first fix): the marker stays exactly on the road, even if the
            // raw estimate jumps sideways.
            offsetE = targetE; offsetN = targetN; pinned = true
        } else {
            // Acquiring a road after none: glide on quickly rather than teleport.
            glideTowards(targetE, targetN, dt)
            pinned = hypot(targetE - offsetE, targetN - offsetN) <= tuning.convergedM
        }
        lockedX = best.x; lockedY = best.y
        val status = if (best.gap > tuning.onRoadToleranceM) Status.CORRECTING else Status.ON_ROAD
        val heading = if (useHeading) best.heading else headingRad
        return Result(status, east + offsetE, north + offsetN, heading, best.gap, hypot(offsetE, offsetN))
    }

    /** Ease the applied correction back to zero so the marker never jumps on release. */
    private fun release(status: Status, east: Double, north: Double, heading: Double, dt: Double): Result {
        lockedX = Double.NaN; lockedY = Double.NaN; pinned = false
        glideTowards(0.0, 0.0, dt)
        return Result(status, east + offsetE, north + offsetN, heading, correctionM = hypot(offsetE, offsetN))
    }

    private fun glideTowards(targetE: Double, targetN: Double, dt: Double) {
        if (dt <= 0.0) return
        val alpha = 1.0 - exp(-dt / tuning.convergeTauS)
        var stepE = (targetE - offsetE) * alpha
        var stepN = (targetN - offsetN) * alpha
        val step = hypot(stepE, stepN)
        val maxStep = tuning.maxCorrectionRateMps * dt
        if (step > maxStep) { stepE *= maxStep / step; stepN *= maxStep / step }
        offsetE += stepE; offsetN += stepN
    }

    private fun angleBetween(a: Double, b: Double): Double = abs(wrap(a - b))

    private fun wrap(a: Double): Double {
        var x = (a + PI) % (2 * PI)
        if (x < 0) x += 2 * PI
        return x - PI
    }

    private companion object {
        const val EARTH_RADIUS_M = 6378137.0
    }
}
