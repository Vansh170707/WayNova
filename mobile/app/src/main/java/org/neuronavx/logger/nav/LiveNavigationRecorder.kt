package org.neuronavx.logger.nav

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import org.neuronavx.logger.AsyncCsvFile
import org.neuronavx.logger.DriveCsvRecorder
import java.io.File
import java.util.Locale
import kotlin.math.hypot

/** Files and health figures produced when one live navigation session stops. */
data class LiveSessionSummary(
    val rawFile: File,
    val diagnosticsFile: File,
    val summaryFile: File,
    val durationS: Double,
    val imuSamples: Long,
    val phoneFixes: Long,
    val fusedFixes: Int,
    val filterRejects: Int,
    val stateCount: Int,
    val maxGnssAgeS: Double,
    val maxSigmaM: Double,
    val maxBlackoutS: Double,
    val modeTransitions: Int,
    val displayDistanceM: Double,
    val blackoutTest: FieldBlackoutSnapshot,
)

/**
 * Records both sides of a live field run:
 *
 *  - a 100 Hz raw IMU/GNSS CSV that can be replayed by the desktop pipeline; and
 *  - a 10 Hz estimator CSV plus JSON summary that explains every mode transition.
 */
class LiveNavigationRecorder(private val context: Context) {
    companion object {
        const val DIAGNOSTICS_HEADER =
            "timestamp_ms,phase,mode,heading_rate_source,east_m,north_m," +
                "display_east_m,display_north_m," +
                "heading_deg,speed_ms,sigma_m,blackout_s,blackout_distance_m," +
                "learned_speed_ms,learned_sigma_ms,phone_fixes,fused_fixes," +
                "filter_rejects,gnss_age_s,cal_forward_deg,cal_accel_scale," +
                "cal_gyro_bias_rads,cal_yaw_channel,cal_yaw_scale," +
                "cal_forward_corr,cal_yaw_corr,test_phase,test_elapsed_s," +
                "test_withheld_fixes,reference_east_m,reference_north_m," +
                "reference_error_m"
    }

    private val sessionId = System.currentTimeMillis()
    private val raw = DriveCsvRecorder(context, "nav_drive_$sessionId.csv")
    private val diagnostics = AsyncCsvFile(
        context, "nav_diagnostics_$sessionId.csv", DIAGNOSTICS_HEADER
    )
    private val summaryFile = File(
        checkNotNull(context.getExternalFilesDir(null)), "nav_summary_$sessionId.json"
    )

    private var startedElapsedNanos = 0L
    private var running = false
    private var states = 0
    private var finalState: Navigator.State? = null
    private var lastStateT = Double.NaN
    private var lastMode: NavMode? = null
    private var previousDisplayEast = Double.NaN
    private var previousDisplayNorth = Double.NaN
    private var maxGnssAgeS = 0.0
    private var maxSigmaM = 0.0
    private var maxBlackoutS = 0.0
    private var modeTransitions = 0
    private var displayDistanceM = 0.0
    private var aidedS = 0.0
    private var blackoutS = 0.0
    private var reacquiringS = 0.0
    private var calibratingS = 0.0

    val rawFile: File get() = raw.file
    val diagnosticsFile: File get() = diagnostics.file

    fun start(startNanos: Long) {
        check(!running) { "live recorder already started" }
        startedElapsedNanos = startNanos
        raw.start(startNanos)
        try {
            diagnostics.start()
        } catch (e: Exception) {
            raw.stop()
            throw e
        }
        running = true
    }

    fun recordFix(fix: GnssFix) = raw.recordFix(fix)

    fun recordImu(
        eventNanos: Long,
        accel: DoubleArray,
        gyro: DoubleArray,
        magnetic: DoubleArray,
        gravity: DoubleArray,
    ) = raw.recordSample(eventNanos, accel, gyro, magnetic, gravity)

    fun recordState(
        state: Navigator.State,
        blackoutTest: FieldBlackoutSnapshot = FieldBlackoutSnapshot(),
    ) {
        if (!running) return
        val dt = if (lastStateT.isFinite()) maxOf(state.t - lastStateT, 0.0) else 0.0
        val previousMode = lastMode
        if (previousMode != null && previousMode != state.mode) modeTransitions++
        if (state.phase == Navigator.Phase.CALIBRATING) {
            calibratingS += dt
        } else when (previousMode ?: state.mode) {
            NavMode.AIDED -> aidedS += dt
            NavMode.BLACKOUT -> blackoutS += dt
            NavMode.REACQUIRING -> reacquiringS += dt
        }

        if (previousDisplayEast.isFinite() && previousDisplayNorth.isFinite() &&
            state.displayEast.isFinite() && state.displayNorth.isFinite()) {
            displayDistanceM += hypot(
                state.displayEast - previousDisplayEast,
                state.displayNorth - previousDisplayNorth,
            )
        }
        previousDisplayEast = state.displayEast
        previousDisplayNorth = state.displayNorth
        if (state.gnssFixAgeS.isFinite()) maxGnssAgeS = maxOf(maxGnssAgeS, state.gnssFixAgeS)
        if (state.sigmaM.isFinite()) maxSigmaM = maxOf(maxSigmaM, state.sigmaM)
        maxBlackoutS = maxOf(maxBlackoutS, state.blackoutS)

        val calibration = state.calibration
        diagnostics.append(listOf(
            fmt(state.t * 1000.0, 3),
            state.phase.name,
            state.mode.name,
            state.headingRateSource.name,
            fmt(state.east),
            fmt(state.north),
            fmt(state.displayEast),
            fmt(state.displayNorth),
            fmt(if (state.heading.isFinite()) Math.toDegrees(state.heading) else Double.NaN),
            fmt(state.speed),
            fmt(state.sigmaM),
            fmt(state.blackoutS),
            fmt(state.blackoutDistanceM),
            fmt(state.learnedSpeed),
            fmt(state.learnedSigma),
            state.gnssFixesReceived.toString(),
            state.gnssUpdates.toString(),
            state.rejectedFixes.toString(),
            fmt(state.gnssFixAgeS),
            fmt(calibration?.let { Math.toDegrees(it.forwardAngleRad) }),
            fmt(calibration?.forwardAccelScale),
            fmt(calibration?.gyroBiasRadS),
            calibration?.yawChannel?.toString() ?: "",
            fmt(calibration?.yawScale),
            fmt(calibration?.forwardAccelCorr),
            fmt(calibration?.yawCorr),
            blackoutTest.phase.name,
            fmt(blackoutTest.elapsedS),
            blackoutTest.withheldFixes.toString(),
            fmt(blackoutTest.referenceEastM),
            fmt(blackoutTest.referenceNorthM),
            fmt(blackoutTest.referenceErrorM),
        ).joinToString(","))

        states++
        finalState = state
        lastStateT = state.t
        lastMode = state.mode
    }

    fun stop(
        locationAvailable: Boolean,
        blackoutTest: FieldBlackoutSnapshot = FieldBlackoutSnapshot(),
    ): LiveSessionSummary? {
        if (!running) return null
        running = false
        raw.stop()
        diagnostics.stop()

        val durationS = maxOf(
            (SystemClock.elapsedRealtimeNanos() - startedElapsedNanos) / 1e9,
            0.0,
        )
        val end = finalState
        val summary = LiveSessionSummary(
            rawFile = raw.file,
            diagnosticsFile = diagnostics.file,
            summaryFile = summaryFile,
            durationS = durationS,
            imuSamples = raw.sampleCount.get(),
            phoneFixes = raw.fixCount.get(),
            fusedFixes = end?.gnssUpdates ?: 0,
            filterRejects = end?.rejectedFixes ?: 0,
            stateCount = states,
            maxGnssAgeS = maxGnssAgeS,
            maxSigmaM = maxSigmaM,
            maxBlackoutS = maxBlackoutS,
            modeTransitions = modeTransitions,
            displayDistanceM = displayDistanceM,
            blackoutTest = blackoutTest,
        )
        writeSummary(summary, locationAvailable, end)
        return summary
    }

    private fun writeSummary(
        summary: LiveSessionSummary,
        locationAvailable: Boolean,
        end: Navigator.State?,
    ) {
        val calibration = end?.calibration
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("raw_file", summary.rawFile.name)
            put("diagnostics_file", summary.diagnosticsFile.name)
            put("duration_s", summary.durationS)
            put("imu_samples", summary.imuSamples)
            put("phone_fixes", summary.phoneFixes)
            put("fused_fixes", summary.fusedFixes)
            put("filter_rejects", summary.filterRejects)
            put("estimator_states", summary.stateCount)
            put("location_provider_available", locationAvailable)
            put("max_gnss_age_s", summary.maxGnssAgeS)
            put("max_sigma_m", summary.maxSigmaM)
            put("max_blackout_s", summary.maxBlackoutS)
            put("mode_transitions", summary.modeTransitions)
            put("display_distance_m", summary.displayDistanceM)
            put("calibrating_s", calibratingS)
            put("aided_s", aidedS)
            put("blackout_s", blackoutS)
            put("reacquiring_s", reacquiringS)
            put("controlled_blackout", JSONObject().apply {
                put("phase", summary.blackoutTest.phase.name)
                put("requested_duration_s", summary.blackoutTest.requestedDurationS)
                put("actual_duration_s", summary.blackoutTest.actualDurationS)
                put("lead_in_s", summary.blackoutTest.leadInS)
                put("withheld_fixes", summary.blackoutTest.withheldFixes)
                put("reference_samples", summary.blackoutTest.referenceSamples)
                putFinite("max_reference_error_m", summary.blackoutTest.maxReferenceErrorM)
                putFinite("end_reference_error_m", summary.blackoutTest.endReferenceErrorM)
                put("recovered", summary.blackoutTest.recovered)
                putFinite("reacquire_s", summary.blackoutTest.reacquireS)
            })
            put("raw_writer_error", raw.lastError ?: JSONObject.NULL)
            put("diagnostics_writer_error", diagnostics.lastError ?: JSONObject.NULL)
            put("final_phase", end?.phase?.name ?: JSONObject.NULL)
            put("final_mode", end?.mode?.name ?: JSONObject.NULL)
            put("heading_rate_source", end?.headingRateSource?.name ?: JSONObject.NULL)
            put("calibration", if (calibration == null) JSONObject.NULL else JSONObject().apply {
                put("forward_angle_deg", Math.toDegrees(calibration.forwardAngleRad))
                put("forward_accel_scale", calibration.forwardAccelScale)
                put("gyro_bias_rad_s", calibration.gyroBiasRadS)
                put("yaw_channel", calibration.yawChannel)
                put("yaw_scale", calibration.yawScale)
                put("forward_accel_corr", calibration.forwardAccelCorr)
                put("yaw_corr", calibration.yawCorr)
                put("samples", calibration.samples)
                put("baselines", calibration.baselines)
            })
        }
        summaryFile.writeText(json.toString(2))
    }

    private fun fmt(value: Double?, decimals: Int = 6): String =
        if (value == null || !value.isFinite()) ""
        else String.format(Locale.US, "%.${decimals}f", value)

    private fun JSONObject.putFinite(name: String, value: Double) {
        put(name, if (value.isFinite()) value else JSONObject.NULL)
    }
}
