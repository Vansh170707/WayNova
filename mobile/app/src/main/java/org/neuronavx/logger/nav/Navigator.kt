package org.neuronavx.logger.nav

import kotlin.math.cos

/** Which sensor contract supplies heading propagation. */
enum class HeadingRateSource {
    /** IO-VNBD/logger replay compatibility: selected raw gyro channel and fitted gain. */
    CALIBRATED_CHANNEL,
    /** Live Android sensors: -dot(gyro, gravityUnit), pre-integrated at callback rate. */
    GRAVITY_PROJECTED,
}

/** One GNSS fix as the phone reports it. Position fields are NaN when there is no fix. */
data class GnssFix(
    val lat: Double, val lon: Double, val alt: Double,
    val speed: Double, val accuracyM: Double, val bearingDeg: Double,
    /**
     * Reported course accuracy in degrees, NaN when the receiver does not supply one.
     *
     * This is not a detail. Falling back to the filter's conservative default course noise
     * instead of the ~3 deg a Doppler bearing actually earns left the heading 2 deg out
     * before an outage had even begun, and 13 deg out by the end of a 120 s one -- 171 m of
     * position error, all of it from under-trusting a measurement that was fine.
     */
    val bearingAccuracyDeg: Double = Double.NaN,
)

/**
 * The live navigation loop: sensors in, a navigation state out.
 *
 * This is the on-device counterpart of `run_es_ekf`, and it is deliberately the same
 * sequence of operations in the same order -- propagate, fuse the learned speed while dark,
 * then apply whatever aiding the blackout manager will allow -- so that a replayed log can
 * be checked against the desktop answer rather than merely looking plausible.
 *
 * Three things differ from the desktop, all forced by being live rather than offline:
 *
 *  1. **No blackout mask.** Offline evaluation is handed the outage window; here the
 *     BlackoutManager has to notice GNSS stop arriving. That is the deployed behaviour and
 *     the reason the manager exists.
 *  2. **Calibration must be earned.** The desktop estimates alignment over a whole session
 *     before running anything. Here it accumulates online, and the loop refuses to navigate
 *     until it converges -- measured on the desktop, calibrating on ~300 s of aided driving
 *     failed outright half the time, so pretending is not an option.
 *  3. **Decimation.** The model was trained on 10 Hz windows while the phone's IMU runs at
 *     100 Hz, so features and propagation run on a decimated stream. Propagating at the
 *     full rate would be marginally better and would no longer match the desktop, so the
 *     rate is configurable and defaults to parity.
 */
class Navigator(
    private val runtime: RuntimeConfig,
    private val model: SpeedModel?,
    ekfConfig: EsEkf.Config = EsEkf.Config(),
    private val blackoutConfig: BlackoutConfig = BlackoutConfig(),
    private val inputRateHz: Double = runtime.rateHz,
) {

    enum class Phase { CALIBRATING, NAVIGATING }

    data class State(
        val t: Double,
        val phase: Phase,
        val mode: NavMode,
        val east: Double, val north: Double,
        val displayEast: Double, val displayNorth: Double,
        val heading: Double, val speed: Double,
        val sigmaM: Double,
        val blackoutS: Double,
        val blackoutDistanceM: Double,
        val learnedSpeed: Double, val learnedSigma: Double,
        val calibration: Alignment?,
        val calibrationProgress: Double,
        val gnssUpdates: Int, val rejectedFixes: Int,
        val gnssFixesReceived: Int,
        val gnssFixAgeS: Double,
        val gyroBiasRadS: Double,
        val gyroScaleError: Double,
        val headingRateSource: HeadingRateSource,
    )

    private val nativeHeadingContract = inputRateHz > runtime.rateHz * 1.5
    /**
     * Gravity projection already has unit gain and online calibration has estimated its
     * additive bias. Letting a few aided course updates rewrite those parameters turned a
     * good field heading into 145 degrees of error during the next 60-second outage.
     */
    private val effectiveEkfConfig = if (nativeHeadingContract) ekfConfig.copy(
        estimateGyroBias = false,
        estimateGyroScale = false,
        courseMinSpeed = maxOf(ekfConfig.courseMinSpeed, LIVE_COURSE_MIN_SPEED_MS),
    ) else ekfConfig
    private val ekf = EsEkf(effectiveEkfConfig)
    private val calibration = OnlineCalibration(
        minSpeedForYaw = if (nativeHeadingContract) 2.0 else 8.0,
        useGravityProjectedYaw = nativeHeadingContract,
    )
    private val blackout = BlackoutManager(blackoutConfig)
    private val smoother = TrackSmoother(blackoutConfig)
    private val window = FeatureWindow(runtime.windowSamples)
    private val nativeHeadingRate = HeadingRateAccumulator()

    /**
     * Integer-factor decimation to the rate the model was trained at.
     *
     * Deliberately a sample COUNT rather than a time threshold. The obvious rule -- emit
     * when at least one period has elapsed -- looks equivalent and is not: recorded IMU
     * streams are nominally 10 Hz but jitter between 0.091 s and 0.109 s per sample, so a
     * threshold silently dropped 1301 of 7796 samples (17% of a drive) and the estimator
     * quietly integrated over the holes. An integer factor cannot do that, and on a phone
     * running the accelerometer at 100 Hz it is exactly every tenth sample.
     */
    private val decimationFactor =
        Math.round(inputRateHz / runtime.rateHz).toInt().coerceAtLeast(1)
    // Production live input is 100 Hz while the replay contract is already 10 Hz. The
    // latter comes from IO-VNBD, whose gravity/gyro columns are not in a common axis order.
    private var headingRateSource = if (nativeHeadingContract)
        HeadingRateSource.GRAVITY_PROJECTED else HeadingRateSource.CALIBRATED_CHANNEL
    private var sampleIndex = -1
    private var lastEmitT = Double.NaN
    private var lastT = Double.NaN

    /**
     * Latest fresh fix waiting for the next decimated navigation cycle.
     *
     * Live GNSS callbacks are asynchronous to the 100 Hz accelerometer stream. Previously
     * a fix was carried by exactly one raw callback, then lost whenever that callback was
     * one of the nine samples skipped by the 10 Hz decimator. Keeping it here until an
     * emitted cycle consumes it prevents real ~1 Hz GNSS from looking like a blackout.
     */
    private var pendingNavigationFix: GnssFix? = null
    private var gnssFixesReceived = 0
    private var fusedGnssFixes = 0
    private var lastGnssFixT = Double.NEGATIVE_INFINITY

    private var align: Alignment? = null
    private var phase = Phase.CALIBRATING
    private var lastLearnedT = Double.NEGATIVE_INFINITY
    private var justInitialized = false
    private var learned = SpeedEstimate(Double.NaN, Double.NaN)

    /** ENU origin, taken from the first usable fix. */
    var originLat = Double.NaN; private set
    var originLon = Double.NaN; private set
    var originAlt = Double.NaN; private set

    private var heldSpeed = 0.0
    private var heldBearing = Double.NaN

    val trackEast = ArrayList<Double>()
    val trackNorth = ArrayList<Double>()
    val displayEastTrack = ArrayList<Double>()
    val displayNorthTrack = ArrayList<Double>()
    /** Per point: was the estimator unaided here? Drives the map's colouring. */
    val trackDark = ArrayList<Boolean>()

    var state: State? = null; private set

    init {
        calibration.setRate(inputRateHz)
    }

    /** Force a calibration instead of estimating one -- used to verify the port itself. */
    fun overrideCalibration(
        a: Alignment,
        source: HeadingRateSource = HeadingRateSource.CALIBRATED_CHANNEL,
    ) {
        align = a
        headingRateSource = source
    }

    fun setOrigin(lat: Double, lon: Double, alt: Double) {
        originLat = lat; originLon = lon; originAlt = alt
    }

    /**
     * Feed one raw sample. `fix` is non-null only on the sample carrying a fresh GNSS fix.
     * Returns the navigation state when this sample produced one.
     */
    fun addSample(t: Double, accel: DoubleArray, gravity: DoubleArray, gyro: DoubleArray,
                  fix: GnssFix?): State? {
        if (fix != null && fix.lat.isFinite() && originLat.isNaN()) {
            setOrigin(fix.lat, fix.lon, if (fix.alt.isFinite()) fix.alt else 0.0)
        }
        if (fix != null) {
            // Calibration sees each physical fix exactly once, while navigation retains
            // the latest one until the next emitted cycle. More than one fix inside a
            // 100 ms navigation period is not useful to this 10 Hz filter; the newest is.
            pendingNavigationFix = fix
            gnssFixesReceived++
            lastGnssFixT = t
            if (fix.speed.isFinite()) heldSpeed = fix.speed
            if (fix.bearingDeg.isFinite()) heldBearing = Math.toRadians(fix.bearingDeg)
            calibration.addFix(t, fix.speed,
                if (fix.bearingDeg.isFinite()) Math.toRadians(fix.bearingDeg) else Double.NaN)
        }

        // Calibration and yaw pre-integration must see every physical callback. Keeping
        // these below the decimation return silently sampled 100 Hz at 10 Hz and produced
        // the field drive's false -0.608 yaw gain.
        calibration.addImu(t, accel, gravity, gyro)
        nativeHeadingRate.add(t, gyro, gravity)

        sampleIndex++
        if (sampleIndex % decimationFactor != 0) return null
        val dt = if (lastEmitT.isNaN()) 0.0 else t - lastEmitT
        lastEmitT = t
        val projectedHeadingRate = nativeHeadingRate.consumeMean(dt)
        val navigationFix = pendingNavigationFix
        pendingNavigationFix = null

        if (align == null) {
            align = calibration.solve()
            if (align == null) return emitCalibrating(t)
        }
        val a = align!!

        if (phase == Phase.CALIBRATING) {
            if (originLat.isNaN() || !fixAvailable(navigationFix)) return emitCalibrating(t)
            val (e, n) = toEnu(navigationFix!!.lat, navigationFix.lon)
            ekf.initialize(e, n, if (heldBearing.isFinite()) heldBearing else 0.0,
                if (navigationFix.speed.isFinite()) navigationFix.speed else 0.0, a.gyroBiasRadS)
            blackout.reset(t)
            smoother.reset()
            window.reset()
            phase = Phase.NAVIGATING
            // Diagnostics below describe the navigation period, not the potentially long
            // calibration drive. Count the fix that initialized this period as its first.
            gnssFixesReceived = 1
            fusedGnssFixes = 0
            justInitialized = true
        }

        window.push(
            accel, gravity, gyro, a,
            nativeYawRate = if (headingRateSource == HeadingRateSource.GRAVITY_PROJECTED)
                projectedHeadingRate else Double.NaN,
        )

        val inOutage = blackout.mode != NavMode.AIDED
        // While dark, speed is carried by the learned estimate rather than by integrating
        // an accelerometer the field audit showed to be unreliable.
        val forwardAccel = if (inOutage) 0.0 else forwardAcceleration(accel, gravity, a)
        // The sample that initialised the state has already been consumed by doing so;
        // propagating it again would advance the filter by one step the desktop does not
        // take, and every later comparison would carry that offset.
        if (dt > 0.0 && !justInitialized) {
            val yawRate = when (headingRateSource) {
                // Legacy parity uses the desktop pipeline's already bias-corrected input.
                HeadingRateSource.CALIBRATED_CHANNEL -> a.correctedHeadingRate(gyro)
                // Native projection is raw; EsEkf subtracts its initialized bias exactly once.
                HeadingRateSource.GRAVITY_PROJECTED -> projectedHeadingRate
            }
            ekf.propagate(dt, yawRate, forwardAccel)
        }
        justInitialized = false

        if (inOutage && model != null && window.isFull &&
            t - lastLearnedT >= 1.0 / ekf.config.learnedSpeedUpdateHz) {
            learned = model.predict(window)
            if (ekf.updateLearnedSpeed(learned.speed, learned.sigma)) lastLearnedT = t
        }

        var east = Double.NaN; var north = Double.NaN
        var accuracy = Double.NaN
        if (navigationFix != null && navigationFix.lat.isFinite() && navigationFix.lon.isFinite()) {
            val enu = toEnu(navigationFix.lat, navigationFix.lon)
            east = enu.first; north = enu.second
            accuracy = navigationFix.accuracyM
        }
        val fuse = blackout.step(t, dt, ekf.speed, east, north, accuracy)
        if (fuse) {
            if (ekf.updateGnssPosition(east, north, accuracy)) fusedGnssFixes++
            if (navigationFix != null && navigationFix.speed.isFinite()) {
                ekf.updateGnssSpeed(navigationFix.speed)
                if (navigationFix.speed < ekf.config.zuptSpeed) ekf.updateZeroVelocity()
            }
            val courseUsable = navigationFix != null && navigationFix.bearingDeg.isFinite() &&
                (!nativeHeadingContract || navigationFix.speed.isFinite() &&
                    navigationFix.speed >= ekf.config.courseMinSpeed)
            if (courseUsable) {
                val sigma = if (navigationFix.bearingAccuracyDeg.isFinite())
                    Math.toRadians(navigationFix.bearingAccuracyDeg) else ekf.config.sigmaGnssCourse
                ekf.updateGnssBearing(Math.toRadians(navigationFix.bearingDeg), sigma)
            }
        }

        smoother.update(ekf.east, ekf.north, dt)
        trackEast.add(ekf.east); trackNorth.add(ekf.north)
        displayEastTrack.add(smoother.east); displayNorthTrack.add(smoother.north)
        trackDark.add(blackout.mode != NavMode.AIDED)

        val s = State(
            t = t, phase = phase, mode = blackout.mode,
            east = ekf.east, north = ekf.north,
            displayEast = smoother.east, displayNorth = smoother.north,
            heading = ekf.heading, speed = ekf.speed, sigmaM = ekf.positionSigma,
            blackoutS = blackout.blackoutDuration(t),
            blackoutDistanceM = blackout.blackoutDistanceM,
            learnedSpeed = learned.speed, learnedSigma = learned.sigma,
            calibration = a, calibrationProgress = 1.0,
            gnssUpdates = fusedGnssFixes, rejectedFixes = ekf.rejected,
            gnssFixesReceived = gnssFixesReceived,
            gnssFixAgeS = maxOf(t - lastGnssFixT, 0.0),
            gyroBiasRadS = ekf.gyroBias,
            gyroScaleError = ekf.gyroScaleError,
            headingRateSource = headingRateSource,
        )
        state = s
        lastT = t
        return s
    }

    private fun fixAvailable(fix: GnssFix?): Boolean =
        fix != null && fix.lat.isFinite() && fix.lon.isFinite()

    private fun emitCalibrating(t: Double): State {
        val progress = calibration.progress
        val s = State(
            t = t, phase = Phase.CALIBRATING, mode = NavMode.AIDED,
            east = Double.NaN, north = Double.NaN,
            displayEast = Double.NaN, displayNorth = Double.NaN,
            heading = Double.NaN, speed = heldSpeed, sigmaM = Double.NaN,
            blackoutS = 0.0, blackoutDistanceM = 0.0,
            learnedSpeed = Double.NaN, learnedSigma = Double.NaN,
            calibration = null, calibrationProgress = progress,
            gnssUpdates = 0, rejectedFixes = 0,
            gnssFixesReceived = gnssFixesReceived,
            gnssFixAgeS = if (lastGnssFixT.isFinite()) maxOf(t - lastGnssFixT, 0.0)
                else Double.POSITIVE_INFINITY,
            gyroBiasRadS = Double.NaN,
            gyroScaleError = Double.NaN,
            headingRateSource = headingRateSource,
        )
        state = s
        return s
    }

    private fun forwardAcceleration(accel: DoubleArray, gravity: DoubleArray,
                                    a: Alignment): Double {
        val h = DoubleArray(2)
        Signals.horizontalAcceleration(accel, gravity, h)
        return (h[0] * cos(a.forwardAngleRad) + h[1] * kotlin.math.sin(a.forwardAngleRad)) *
            a.forwardAccelScale
    }

    /** Local-tangent-plane projection, identical to `neuronav.utils.geo.latlon_to_enu`. */
    fun toEnu(lat: Double, lon: Double): Pair<Double, Double> {
        val a = 6378137.0
        val east = Math.toRadians(lon - originLon) * a * cos(Math.toRadians(originLat))
        val north = Math.toRadians(lat - originLat) * a
        return Pair(east, north)
    }

    private companion object {
        const val LIVE_COURSE_MIN_SPEED_MS = 5.0
    }
}
