package org.neuronavx.logger.nav

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock

/**
 * Feeds the live sensor stream into a [Navigator].
 *
 * Deliberately parallel to `SensorLogger`: the accelerometer callback drives emission and
 * every other channel is sample-and-held, so the estimator sees exactly the row shape the
 * offline pipeline reads. Running the two off the same convention is what makes a replayed
 * drive a valid test of the live path.
 *
 * The GPS provider is used rather than the fused provider, for the same reason logging
 * does: fused output is smoothed and can already incorporate inertial estimates, so
 * feeding it to an inertial estimator would quietly close a loop.
 */
class LiveSource(
    private val context: Context,
    private val navigator: Navigator,
    private val onState: (Navigator.State) -> Unit,
) : SensorEventListener, LocationListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val accel = DoubleArray(3)
    private val gyro = DoubleArray(3)
    private val magnetic = DoubleArray(3)
    private val gravity = DoubleArray(3)

    private data class TimedFix(val t: Double, val fix: GnssFix)

    @Volatile private var pendingFix: TimedFix? = null
    @Volatile private var running = false
    private var startNanos = 0L
    private var recorder: LiveNavigationRecorder? = null
    private val fieldBlackoutTest = FieldBlackoutTest()

    var lastSummary: LiveSessionSummary? = null
        private set

    @Volatile var locationAvailable = false
        private set
    @Volatile var fixCount = 0
        private set

    fun armBlackoutTest(durationS: Double = 60.0, aidedLeadInS: Double = 15.0): Boolean =
        fieldBlackoutTest.arm(durationS, aidedLeadInS)

    fun cancelBlackoutTest(): Boolean = fieldBlackoutTest.cancel()
    fun interruptTest() = fieldBlackoutTest.interrupt()

    fun blackoutTestSnapshot(): FieldBlackoutSnapshot = fieldBlackoutTest.snapshot()

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GRAVITY).forEach {
            check(sensorManager.getDefaultSensor(it) != null) {
                "A required motion sensor is unavailable (type $it). This device cannot run the live estimator."
            }
        }
        startNanos = SystemClock.elapsedRealtimeNanos()
        recorder = LiveNavigationRecorder(context).also { it.start(startNanos) }
        lastSummary = null
        running = true
        val periodUs = 1_000_000 / SensorLoggerRate.TARGET_IMU_HZ
        listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_GRAVITY,
        )
            .forEach { type ->
                sensorManager.getDefaultSensor(type)?.let {
                    sensorManager.registerListener(this, it, periodUs)
                }
            }
        locationAvailable = try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0L, 0f, this, Looper.getMainLooper())
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false        // no GPS provider (some emulators)
        }
    }

    fun stop(): LiveSessionSummary? {
        if (!running) return lastSummary
        running = false
        sensorManager.unregisterListener(this)
        if (locationAvailable) {
            try { locationManager.removeUpdates(this) } catch (_: SecurityException) { }
        }
        lastSummary = recorder?.stop(locationAvailable, fieldBlackoutTest.snapshot())
        recorder = null
        return lastSummary
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> copy(event, gyro)
            Sensor.TYPE_MAGNETIC_FIELD -> copy(event, magnetic)
            Sensor.TYPE_GRAVITY -> copy(event, gravity)
            Sensor.TYPE_ACCELEROMETER -> {
                copy(event, accel)
                val t = (event.timestamp - startNanos) / 1e9
                fieldBlackoutTest.beforeSample(t, navigator.state?.phase, navigator.state?.mode ?: NavMode.BLACKOUT)
                recorder?.recordImu(event.timestamp, accel, gyro, magnetic, gravity)
                // hand the fix over exactly once, on the next IMU row, so a held value is
                // never fused twice
                val timedFix = pendingFix
                pendingFix = null
                val withhold = timedFix?.let {
                    fieldBlackoutTest.observeFix(it.t, it.fix, navigator)
                } ?: false
                val fix = if (withhold) null else timedFix?.fix
                navigator.addSample(t, accel, gravity, gyro, fix)?.let { state ->
                    fieldBlackoutTest.observeState(state)
                    recorder?.recordState(state, fieldBlackoutTest.snapshot(state.t))
                    onState(state)
                }
            }
        }
    }

    private fun copy(event: SensorEvent, into: DoubleArray) {
        into[0] = event.values[0].toDouble()
        into[1] = event.values[1].toDouble()
        into[2] = event.values[2].toDouble()
    }

    override fun onLocationChanged(location: Location) {
        fixCount++
        val fix = GnssFix(
            lat = location.latitude,
            lon = location.longitude,
            alt = if (location.hasAltitude()) location.altitude else Double.NaN,
            speed = if (location.hasSpeed()) location.speed.toDouble() else Double.NaN,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble()
                        else Double.NaN,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble()
                         else Double.NaN,
            bearingAccuracyDeg = if (location.hasBearingAccuracy())
                location.bearingAccuracyDegrees.toDouble() else Double.NaN,
        )
        recorder?.recordFix(fix)
        // Use the callback's monotonic time. Some injected/test Locations omit their own
        // elapsedRealtimeNanos, while this clock is the same one that timestamps sensors.
        val t = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1e9
        pendingFix = TimedFix(t, fix)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    @Deprecated("required by LocationListener on older API levels")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
}

/** Shared with the logger so both request the IMU at the same rate. */
object SensorLoggerRate {
    const val TARGET_IMU_HZ = 100
}
