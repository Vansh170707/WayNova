package org.neuronavx.logger.nav

import kotlin.math.hypot

enum class NavMode { AIDED, BLACKOUT, REACQUIRING }

/**
 * GNSS blackout state machine and continuous track output, ported from
 * `src/neuronav/fusion/blackout.py` (blueprint section 9.2).
 *
 * Two things make this more than bookkeeping.
 *
 * **GNSS loss is detected, not announced.** A deployed system gets no mask -- it sees fixes
 * stop arriving, or arrive with terrible accuracy. Both are treated as loss, so the same
 * code runs live and in replay.
 *
 * **Re-acquisition is validated before it is trusted.** After a long outage the first
 * returning fix may be multipath garbage, and accepting it would drag the solution
 * somewhere worse than dead reckoning.
 */
data class BlackoutConfig(
    val maxFixGapS: Double = 3.0,
    val maxAccuracyM: Double = 30.0,
    val reacquireFixes: Int = 3,
    val reacquireWindowS: Double = 6.0,
    val reacquireConsistencyM: Double = 40.0,
    val slewRateMps: Double = 40.0,
    val slewMaxRateMps: Double = 200.0,
    val slewTimeConstantS: Double = 2.0,
    val slewEnabled: Boolean = true,
)

class BlackoutManager(private val cfg: BlackoutConfig = BlackoutConfig()) {

    private class Candidate(val t: Double, val east: Double, val north: Double)

    var mode: NavMode = NavMode.AIDED; private set
    var transitions = 0; private set
    var blackoutDistanceM = 0.0; private set
    private var lastFixT = Double.NEGATIVE_INFINITY
    private var blackoutStartT = Double.NaN
    private val candidates = ArrayList<Candidate>()

    fun reset(t: Double = 0.0) {
        mode = NavMode.AIDED
        lastFixT = t
        blackoutStartT = Double.NaN
        blackoutDistanceM = 0.0
        candidates.clear()
        transitions = 0
    }

    fun blackoutDuration(t: Double): Double =
        if (blackoutStartT.isNaN()) 0.0 else maxOf(t - blackoutStartT, 0.0)

    private fun enterBlackout(t: Double) {
        if (mode == NavMode.BLACKOUT) return
        mode = NavMode.BLACKOUT
        blackoutStartT = t
        blackoutDistanceM = 0.0
        candidates.clear()
        transitions++
    }

    private fun enterAided() {
        mode = NavMode.AIDED
        blackoutStartT = Double.NaN
        candidates.clear()
        transitions++
    }

    /**
     * Advance the state machine. Returns true if the supplied fix should be fused.
     *
     * `east`/`north` are NaN when no fix arrived this sample. Distance travelled during an
     * outage is accumulated for the drift display.
     */
    fun step(t: Double, dt: Double, speed: Double,
             east: Double, north: Double, accuracyM: Double): Boolean {
        if (mode != NavMode.AIDED) {
            blackoutDistanceM += maxOf(speed, 0.0) * maxOf(dt, 0.0)
        }

        val usable = east.isFinite() && north.isFinite() &&
            (!accuracyM.isFinite() || accuracyM <= cfg.maxAccuracyM)

        if (!usable) {
            if (t - lastFixT > cfg.maxFixGapS) enterBlackout(t)
            return false
        }

        lastFixT = t
        if (mode == NavMode.AIDED) return true

        if (mode == NavMode.BLACKOUT) {
            mode = NavMode.REACQUIRING
            candidates.clear()
        }

        if (candidates.isNotEmpty()) {
            val first = candidates.first()
            if (t - first.t > cfg.reacquireWindowS) {
                candidates.clear()                       // stale, start over
            } else {
                val last = candidates.last()
                val gap = hypot(east - last.east, north - last.north)
                // allow for genuine travel between fixes before calling them inconsistent
                val allowed = cfg.reacquireConsistencyM + speed * maxOf(t - last.t, 0.0)
                if (gap > allowed) candidates.clear()
            }
        }
        candidates.add(Candidate(t, east, north))

        if (candidates.size >= cfg.reacquireFixes) {
            enterAided()
            return true
        }
        return false
    }
}

/**
 * Slew-limits the DISPLAYED track so it never teleports.
 *
 * The filter's estimate is the truth of record and is not modified; this governs only what
 * is drawn. Measured on the desktop, a 120 s outage ends with the estimate ~270 m from the
 * display, and snapping is the correct Bayesian move for the estimate but the worst
 * possible thing to render. The rate adapts to the size of the gap -- floored so small
 * corrections stay gentle, capped so a huge one still cannot become a single-frame jump.
 */
class TrackSmoother(private val cfg: BlackoutConfig = BlackoutConfig()) {

    var east = Double.NaN; private set
    var north = Double.NaN; private set
    var maxLagM = 0.0; private set

    fun reset() {
        east = Double.NaN; north = Double.NaN; maxLagM = 0.0
    }

    fun update(estEast: Double, estNorth: Double, dt: Double) {
        if (east.isNaN() || !cfg.slewEnabled) {
            east = estEast; north = estNorth
            return
        }
        val dE = estEast - east
        val dN = estNorth - north
        val distance = hypot(dE, dN)
        if (distance > maxLagM) maxLagM = distance

        val rate = (distance / maxOf(cfg.slewTimeConstantS, 1e-3))
            .coerceIn(cfg.slewRateMps, cfg.slewMaxRateMps)
        val budget = rate * maxOf(dt, 0.0)
        if (distance <= budget || distance == 0.0) {
            east = estEast; north = estNorth
        } else {
            val f = budget / distance
            east += dE * f
            north += dN * f
        }
    }
}
