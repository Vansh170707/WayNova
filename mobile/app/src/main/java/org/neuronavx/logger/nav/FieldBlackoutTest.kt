package org.neuronavx.logger.nav

import kotlin.math.hypot

/** Lifecycle of one controlled field outage. Only one test is allowed per live session. */
enum class FieldBlackoutPhase { OFF, ARMED, WITHHOLDING, RECOVERING, COMPLETE, INTERRUPTED }

/** Immutable test evidence shown by the UI and written beside every estimator state. */
data class FieldBlackoutSnapshot(
    val phase: FieldBlackoutPhase = FieldBlackoutPhase.OFF,
    val requestedDurationS: Double = 0.0,
    val leadInS: Double = 0.0,
    val startsInS: Double = Double.NaN,
    val elapsedS: Double = 0.0,
    val remainingS: Double = 0.0,
    val withheldFixes: Int = 0,
    val referenceSamples: Int = 0,
    val referenceEastM: Double = Double.NaN,
    val referenceNorthM: Double = Double.NaN,
    val referenceErrorM: Double = Double.NaN,
    val maxReferenceErrorM: Double = Double.NaN,
    val endReferenceErrorM: Double = Double.NaN,
    val actualDurationS: Double = 0.0,
    val recovered: Boolean = false,
    val reacquireS: Double = Double.NaN,
)

/**
 * Withholds otherwise healthy phone fixes from [Navigator] while retaining them as a
 * hidden reference.
 *
 * Turning Android Location off would exercise the outage detector, but it would also
 * destroy the only field reference available for measuring drift. This controller keeps
 * the raw recorder untouched, drops fixes only at the estimator boundary, and scores the
 * unaided state against the latest real satellite position.
 */
class FieldBlackoutTest {
    private var phase = FieldBlackoutPhase.OFF
    private var requestedDurationS = 0.0
    private var leadInS = 0.0
    private var navigatingSinceT = Double.NaN
    private var startT = Double.NaN
    private var endT = Double.NaN
    private var lastT = 0.0
    private var lastSampleT = Double.NaN
    private var withheldFixes = 0
    private var referenceSamples = 0
    private var referenceEastM = Double.NaN
    private var referenceNorthM = Double.NaN
    private var referenceErrorM = Double.NaN
    private var maxReferenceErrorM = Double.NaN
    private var endReferenceErrorM = Double.NaN
    private var recovered = false
    private var reacquireS = Double.NaN

    @Synchronized
    fun arm(durationS: Double = 60.0, aidedLeadInS: Double = 15.0): Boolean {
        if (phase != FieldBlackoutPhase.OFF) return false
        require(durationS >= 5.0) { "controlled outage must last at least 5 s" }
        require(aidedLeadInS >= 0.0) { "aided lead-in cannot be negative" }
        requestedDurationS = durationS
        leadInS = aidedLeadInS
        phase = FieldBlackoutPhase.ARMED
        return true
    }

    @Synchronized
    fun interrupt() {
        if (phase in setOf(FieldBlackoutPhase.ARMED, FieldBlackoutPhase.WITHHOLDING,
                FieldBlackoutPhase.RECOVERING)) {
            if (startT.isFinite() && !endT.isFinite()) endT = lastSampleT
            phase = FieldBlackoutPhase.INTERRUPTED
        }
    }

    @Synchronized
    fun cancel(): Boolean {
        if (phase != FieldBlackoutPhase.ARMED) return false
        phase = FieldBlackoutPhase.OFF
        navigatingSinceT = Double.NaN
        requestedDurationS = 0.0
        leadInS = 0.0
        return true
    }

    /** Called immediately before each IMU sample reaches the estimator. */
    @Synchronized
    fun beforeSample(t: Double, navigationPhase: Navigator.Phase?, mode: NavMode = NavMode.AIDED) {
        if (!t.isFinite() || (lastSampleT.isFinite() && t <= lastSampleT)) return
        if (lastSampleT.isFinite() && t - lastSampleT > 1.0 &&
            phase in setOf(FieldBlackoutPhase.ARMED, FieldBlackoutPhase.WITHHOLDING,
                FieldBlackoutPhase.RECOVERING)) {
            if (startT.isFinite() && !endT.isFinite()) endT = lastSampleT
            phase = FieldBlackoutPhase.INTERRUPTED
        }
        lastSampleT = t
        lastT = t
        when (phase) {
            FieldBlackoutPhase.ARMED -> {
                if (navigationPhase != Navigator.Phase.NAVIGATING || mode != NavMode.AIDED) {
                    navigatingSinceT = Double.NaN
                    return
                }
                if (!navigatingSinceT.isFinite()) navigatingSinceT = t
                if (t - navigatingSinceT >= leadInS) {
                    startT = t
                    phase = FieldBlackoutPhase.WITHHOLDING
                }
            }
            FieldBlackoutPhase.WITHHOLDING -> {
                if (t - startT >= requestedDurationS) {
                    endT = t
                    phase = FieldBlackoutPhase.RECOVERING
                }
            }
            else -> Unit
        }
    }

    /**
     * Observe one real phone fix. Returns true when it must be hidden from [Navigator].
     * The raw drive recorder receives the same fix before this method is called.
     */
    @Synchronized
    fun observeFix(t: Double, fix: GnssFix, navigator: Navigator): Boolean {
        lastT = maxOf(lastT, t)
        val withhold = phase == FieldBlackoutPhase.WITHHOLDING
        if (withhold) withheldFixes++

        if ((withhold || phase == FieldBlackoutPhase.RECOVERING) &&
            navigator.originLat.isFinite() && navigator.originLon.isFinite()) {
            val state = navigator.state
            if (state != null && state.phase == Navigator.Phase.NAVIGATING &&
                state.east.isFinite() && state.north.isFinite() &&
                fix.lat.isFinite() && fix.lon.isFinite()) {
                val reference = navigator.toEnu(fix.lat, fix.lon)
                referenceEastM = reference.first
                referenceNorthM = reference.second
                referenceErrorM = hypot(
                    state.east - referenceEastM,
                    state.north - referenceNorthM,
                )
                referenceSamples++
                maxReferenceErrorM = if (maxReferenceErrorM.isFinite())
                    maxOf(maxReferenceErrorM, referenceErrorM) else referenceErrorM
                if (withhold) endReferenceErrorM = referenceErrorM
            }
        }
        return withhold
    }

    /** Called after an emitted estimator state so recovery is closed on actual AIDED mode. */
    @Synchronized
    fun observeState(state: Navigator.State) {
        lastT = state.t
        if (phase == FieldBlackoutPhase.RECOVERING && state.mode == NavMode.AIDED) {
            phase = FieldBlackoutPhase.COMPLETE
            recovered = true
            reacquireS = maxOf(state.t - endT, 0.0)
        }
    }

    @Synchronized
    fun snapshot(t: Double = lastT): FieldBlackoutSnapshot {
        val startsIn = if (phase == FieldBlackoutPhase.ARMED && navigatingSinceT.isFinite())
            maxOf(leadInS - (t - navigatingSinceT), 0.0) else Double.NaN
        val actualDuration = if (startT.isFinite()) {
            val stop = if (endT.isFinite()) endT else t
            maxOf(stop - startT, 0.0)
        } else 0.0
        val elapsed = if (phase == FieldBlackoutPhase.WITHHOLDING && startT.isFinite())
            maxOf(t - startT, 0.0) else actualDuration
        val remaining = if (phase == FieldBlackoutPhase.WITHHOLDING)
            maxOf(requestedDurationS - elapsed, 0.0) else 0.0
        return FieldBlackoutSnapshot(
            phase = phase,
            requestedDurationS = requestedDurationS,
            leadInS = leadInS,
            startsInS = startsIn,
            elapsedS = elapsed,
            remainingS = remaining,
            withheldFixes = withheldFixes,
            referenceSamples = referenceSamples,
            referenceEastM = referenceEastM,
            referenceNorthM = referenceNorthM,
            referenceErrorM = referenceErrorM,
            maxReferenceErrorM = maxReferenceErrorM,
            endReferenceErrorM = endReferenceErrorM,
            actualDurationS = actualDuration,
            recovered = recovered,
            reacquireS = reacquireS,
        )
    }
}
