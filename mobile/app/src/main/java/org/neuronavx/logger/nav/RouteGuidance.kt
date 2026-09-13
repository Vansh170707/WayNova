package org.neuronavx.logger.nav

import kotlin.math.*

/** Presentation-only route matching. Never snaps or feeds corrections to Navigator. */
class RouteGuidance(private val route: RoutePlan) {
    enum class Status { READY, UNCERTAIN, OFF_ROUTE, AMBIGUOUS, WRONG_WAY, NO_DETAIL, NEAR_DESTINATION }
    data class Result(val status: Status, val stepIndex: Int = -1,
        val instruction: String = "", val distanceM: Double = 0.0, val remainingM: Double = 0.0)
    private data class XY(val x: Double, val y: Double)
    internal data class Match(val along: Double, val gap: Double, val heading: Double)
    private val origin = route.points.first()
    private fun xy(p: RoutePoint) = XY(
        Math.toRadians(p.lon - origin.lon) * 6371000 * cos(Math.toRadians(origin.lat)),
        Math.toRadians(p.lat - origin.lat) * 6371000)
    private val points = route.points.map(::xy)
    private val offsets = DoubleArray(points.size).apply {
        for (i in 1 until size) this[i] = this[i - 1] + hypot(points[i].x - points[i-1].x, points[i].y - points[i-1].y)
    }
    internal fun matches(point: RoutePoint): List<Match> {
        val p = xy(point)
        return (0 until points.lastIndex).mapNotNull { i ->
            val a = points[i]; val b = points[i+1]
            val dx = b.x-a.x; val dy = b.y-a.y; val length = hypot(dx, dy)
            if (length < 0.01) null else {
                val fraction = (((p.x-a.x)*dx + (p.y-a.y)*dy)/(length*length)).coerceIn(0.0, 1.0)
                Match(offsets[i] + fraction*length, hypot(p.x-a.x-fraction*dx, p.y-a.y-fraction*dy), atan2(dx, dy))
            }
        }
    }
    internal val constraintSupported: Boolean get() = points.size in 2..5000 && offsets.last() > 1 &&
        abs(origin.lat) < 80 && points.all { abs(it.x) < 100000 && abs(it.y) < 100000 }
    internal val lengthM: Double get() = offsets.last()
    internal fun pointAt(along: Double): Pair<RoutePoint, Double> {
        val distance = along.coerceIn(0.0, lengthM)
        val i = (0 until points.lastIndex).firstOrNull { offsets[it+1] > distance }
            ?: (0 until points.lastIndex).last { offsets[it+1] > offsets[it] }
        val fraction = ((distance-offsets[i])/(offsets[i+1]-offsets[i])).coerceIn(0.0,1.0)
        val a = points[i]; val b = points[i+1]
        val x = a.x + (b.x-a.x)*fraction; val y = a.y + (b.y-a.y)*fraction
        return RoutePoint(origin.lat+Math.toDegrees(y/6371000),
            origin.lon+Math.toDegrees(x/(6371000*cos(Math.toRadians(origin.lat))))) to atan2(b.x-a.x,b.y-a.y)
    }
    private val stepOffsets: List<Double>? = run {
        // Bound presentation work; larger plans remain usable as static previews.
        if (points.size > 5000 || route.steps.size > 200) return@run null
        var distance = 0.0
        val result = ArrayList<Double>()
        for (step in route.steps) {
            val point = step.maneuverPoint ?: return@run null
            val expected = if (route.distanceM > 0) distance / route.distanceM * offsets.last() else 0.0
            val match = matches(point).filter { it.gap <= 25.0 }
                .minByOrNull { it.gap + abs(it.along-expected)*0.01 } ?: return@run null
            if (result.isNotEmpty() && match.along < result.last()-5) return@run null
            result += match.along
            distance += step.distanceM
        }
        result
    }

    fun update(point: RoutePoint, reliable: Boolean, sigmaM: Double, fixAgeS: Double,
        headingRad: Double, speedMps: Double): Result {
        if (!reliable || !speedMps.isFinite() || speedMps < 0 || !sigmaM.isFinite() || sigmaM !in 0.0..20.0 ||
            !fixAgeS.isFinite() || fixAgeS !in 0.0..2.5) return Result(Status.UNCERTAIN)
        return evaluate(point, sigmaM, headingRad, speedMps)
    }

    /**
     * Guidance from RouteConstraint's explicitly labelled road hypothesis.
     * The tighter 30 m voice limit is independent of the constraint's 60 m display limit.
     */
    fun updateAssisted(point: RoutePoint, sigmaM: Double, headingRad: Double, speedMps: Double): Result {
        if (!speedMps.isFinite() || speedMps < 0 || !sigmaM.isFinite() || sigmaM !in 0.0..30.0)
            return Result(Status.UNCERTAIN)
        return evaluate(point, sigmaM, headingRad, speedMps)
    }

    private fun evaluate(point: RoutePoint, sigmaM: Double, headingRad: Double, speedMps: Double): Result {
        val steps = stepOffsets ?: return Result(Status.NO_DETAIL)
        val candidates = matches(point)
        val best = candidates.minByOrNull { it.gap } ?: return Result(Status.NO_DETAIL)
        if (best.gap > 35.0) return Result(Status.OFF_ROUTE)
        if (candidates.any { it.gap <= best.gap + 10 && abs(it.along-best.along) > 60 })
            return Result(Status.AMBIGUOUS)
        if (speedMps > 2 && (!headingRad.isFinite() || cos(headingRad-best.heading) < -0.17))
            return Result(Status.WRONG_WAY)
        val remaining = (offsets.last()-best.along).coerceAtLeast(0.0)
        if (remaining <= 25 && hypot(xy(point).x-points.last().x, xy(point).y-points.last().y) <= 20 && speedMps in 0.0..2.0)
            return Result(Status.NEAR_DESTINATION, remainingM = remaining)
        val next = steps.indices.firstOrNull { it > 0 && steps[it] >= best.along - 12 }
            ?: return Result(Status.NO_DETAIL)
        return Result(Status.READY, next, route.steps[next].instruction,
            (steps[next]-best.along).coerceAtLeast(0.0), remaining)
    }
}
