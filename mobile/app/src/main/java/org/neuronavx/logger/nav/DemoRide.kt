package org.neuronavx.logger.nav

import kotlin.math.*

/**
 * A simulated demonstration ride along a planned road route.
 *
 * Nothing here is a recording or an estimator output. The "true" vehicle follows the route
 * polyline with a speed profile that slows for turns; during one scripted GNSS-loss window
 * the displayed estimate drifts ahead of and beside the true position at rates of the same
 * order as the field drift reported in the README (~14% at 60 s). It exists so the map,
 * route, guidance and road lock can be shown in Greater Noida, where the offline map is;
 * the UI must label it as simulated.
 */
class DemoRide(val route: RoutePlan, private val config: Config = Config()) {

    data class Config(
        val frameDtS: Double = 0.2,
        val cruiseMps: Double = 9.0,
        val turnMps: Double = 3.5,
        val accelMps2: Double = 1.2,
        val decelMps2: Double = 1.8,
        val holdStartS: Double = 3.0,
        val holdEndS: Double = 3.0,
        /** GNSS loss starts once this fraction of the route has been driven. */
        val outageStartFraction: Double = 0.30,
        val outageMaxS: Double = 60.0,
        val recoveryS: Double = 6.0,
        /** Simulated drift per metre driven in the outage: along-track and lateral. */
        val alongDriftPerM: Double = 0.07,
        val lateralDriftPerM: Double = 0.06,
    )

    data class Frame(
        val t: Double,
        /** Simulated estimate in the local frame around [originLat], [originLon]. */
        val east: Double, val north: Double,
        /** Simulated true position on the route. */
        val trueEast: Double, val trueNorth: Double,
        val headingRad: Double, val speedMps: Double, val sigmaM: Double,
        val mode: NavMode,
        val progressM: Double,
    )

    val originLat: Double = route.points.first().lat
    val originLon: Double = route.points.first().lon
    private val scale = cos(Math.toRadians(originLat))
    private val xs = route.points.map { Math.toRadians(it.lon - originLon) * EARTH_RADIUS_M * scale }
    private val ys = route.points.map { Math.toRadians(it.lat - originLat) * EARTH_RADIUS_M }
    private val cumulative = DoubleArray(xs.size).also {
        for (i in 1 until xs.size) it[i] = it[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }
    val lengthM: Double = cumulative.last()

    init { require(route.points.size >= 2 && lengthM > 50) { "Demo route is too short" } }

    /** Point and travel heading at a distance along the route. */
    fun pointAt(s: Double): Triple<Double, Double, Double> {
        val d = s.coerceIn(0.0, lengthM)
        var i = cumulative.indexOfFirst { it >= d }.coerceAtLeast(1)
        while (i < cumulative.lastIndex && cumulative[i] - cumulative[i - 1] < 1e-6) i++
        val seg = cumulative[i] - cumulative[i - 1]
        val f = if (seg > 1e-6) (d - cumulative[i - 1]) / seg else 0.0
        val x = xs[i - 1] + (xs[i] - xs[i - 1]) * f
        val y = ys[i - 1] + (ys[i] - ys[i - 1]) * f
        return Triple(x, y, atan2(xs[i] - xs[i - 1], ys[i] - ys[i - 1]))
    }

    /** Speed limit at 1 m resolution: slow for turns, then accel/decel limited. */
    private val speedProfile: DoubleArray = run {
        val n = ceil(lengthM).toInt() + 1
        val v = DoubleArray(n) { s ->
            val here = pointAt(s.toDouble()).third
            var turn = 0.0
            var ahead = 5.0
            while (ahead <= 25.0) {
                turn = max(turn, abs(wrap(pointAt(s + ahead).third - here)))
                ahead += 5.0
            }
            val severity = ((Math.toDegrees(turn) - 15.0) / 65.0).coerceIn(0.0, 1.0)
            config.cruiseMps + (config.turnMps - config.cruiseMps) * severity
        }
        v[0] = 0.0; v[n - 1] = 0.0
        for (i in 1 until n) v[i] = min(v[i], sqrt(v[i - 1].pow(2) + 2 * config.accelMps2))
        for (i in n - 2 downTo 0) v[i] = min(v[i], sqrt(v[i + 1].pow(2) + 2 * config.decelMps2))
        v
    }

    private fun speedAt(s: Double): Double {
        val i = s.coerceIn(0.0, lengthM)
        val lo = floor(i).toInt().coerceAtMost(speedProfile.lastIndex)
        val hi = (lo + 1).coerceAtMost(speedProfile.lastIndex)
        return speedProfile[lo] + (speedProfile[hi] - speedProfile[lo]) * (i - lo)
    }

    fun frames(): List<Frame> {
        val dt = config.frameDtS
        val out = ArrayList<Frame>()
        var t = 0.0
        var s = 0.0
        var outageStartT = Double.NaN
        var outageStartS = 0.0
        var outageEndT = Double.NaN
        var endErrE = 0.0; var endErrN = 0.0; var endSigma = 0.0
        var stoppedSince = Double.NaN
        var driftE = 0.0; var driftN = 0.0; var lastS = 0.0
        while (true) {
            val (x, y, heading) = pointAt(s)
            val moving = t >= config.holdStartS && s < lengthM - 0.5
            val speed = if (moving) max(0.6, speedAt(s)) else 0.0
            if (!outageStartT.isFinite() && s >= config.outageStartFraction * lengthM) {
                outageStartT = t; outageStartS = s
            }
            val inOutage = outageStartT.isFinite() && !outageEndT.isFinite() &&
                (t - outageStartT < config.outageMaxS && s < 0.85 * lengthM)
            var errE: Double; var errN: Double; val sigma: Double; val mode: NavMode
            val right = heading + PI / 2
            if (inOutage) {
                val d = s - outageStartS
                // Accumulate drift per metre in the world frame, so a corner bends the
                // error path instead of rotating the whole error vector at once.
                val ds = s - lastS
                driftE += ds * (config.alongDriftPerM * sin(heading) + config.lateralDriftPerM * sin(right))
                driftN += ds * (config.alongDriftPerM * cos(heading) + config.lateralDriftPerM * cos(right))
                errE = driftE; errN = driftN
                sigma = min(40.0, 4.0 + 0.12 * d)
                mode = NavMode.BLACKOUT
                endErrE = errE; endErrN = errN; endSigma = sigma
            } else {
                if (outageStartT.isFinite() && !outageEndT.isFinite()) outageEndT = t
                val sinceRecovery = if (outageEndT.isFinite()) t - outageEndT else Double.POSITIVE_INFINITY
                // Small, smooth GNSS-like wander when aided.
                errE = 1.2 * sin(0.21 * t) + 0.6 * sin(0.73 * t + 1.0)
                errN = 1.0 * cos(0.17 * t) + 0.5 * sin(0.61 * t + 2.0)
                if (sinceRecovery < config.recoveryS) {
                    // Smooth ease from the outage error to aided wander: no jump at either end.
                    val k = 0.5 * (1 + cos(PI * sinceRecovery / config.recoveryS))
                    errE = errE * (1 - k) + endErrE * k
                    errN = errN * (1 - k) + endErrN * k
                    sigma = 4.0 + (endSigma - 4.0) * k
                    mode = NavMode.REACQUIRING
                } else { sigma = 4.0; mode = NavMode.AIDED }
            }
            out += Frame(t, x + errE, y + errN, x, y, heading, speed, sigma, mode, s)
            lastS = s
            if (s >= lengthM - 0.5) {
                if (!stoppedSince.isFinite()) stoppedSince = t
                if (t - stoppedSince >= config.holdEndS) break
            }
            require(out.size < 100_000) { "Demo ride generation did not finish" }
            s = min(lengthM, s + speed * dt)
            t += dt
        }
        return out
    }

    private fun wrap(a: Double): Double {
        var x = (a + PI) % (2 * PI)
        if (x < 0) x += 2 * PI
        return x - PI
    }

    companion object {
        private const val EARTH_RADIUS_M = 6378137.0
        const val ASSET = "demo_ride_xu1_bennett.json"
        const val DESTINATION = "Bennett University"
        const val START_LABEL = "Sector XU-1, Greater Noida"
    }
}
