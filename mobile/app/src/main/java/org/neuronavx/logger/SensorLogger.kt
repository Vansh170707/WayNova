package org.neuronavx.logger

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
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.neuronavx.logger.nav.GnssFix

/**
 * Records synchronised IMU + GNSS to CSV, in the exact schema the desktop pipeline reads.
 *
 * The column order and names match `neuronav.data.loader.IOVNBD_RAW_COLUMNS`, so a drive
 * recorded here loads with the same code path as the IO-VNBD sessions. That matters: the
 * field audit found that dataset's phone GNSS is often 0.1 Hz and its accelerometer is
 * frequently filtered, and own-drive collection is the fix. Data that needs a bespoke
 * loader would not actually be usable.
 *
 * Design points that follow from the blueprint's runtime-threads table:
 *  - sensor callbacks do no file I/O; they append to a buffer drained on a writer thread,
 *    so a slow flush cannot drop IMU samples.
 *  - GNSS is sample-and-hold between fixes, and `gpsFresh` marks the row carrying a
 *    genuinely new fix, so the offline loader can tell real updates from held values.
 *  - timestamps come from elapsedRealtimeNanos, which is monotonic and unaffected by
 *    wall-clock adjustments mid-drive.
 */
class SensorLogger(private val context: Context) : SensorEventListener, LocationListener {

    companion object {
        const val TARGET_IMU_HZ = 100
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var recorder: DriveCsvRecorder? = null
    private var startNanos = 0L

    @Volatile private var running = false
    /** False when GNSS could not be started (permission denied, or no provider). */
    @Volatile var locationAvailable = false
        private set
    val sampleCount = AtomicLong(0)
    val fixCount = AtomicLong(0)

    // latest value per channel; the accelerometer callback drives row emission
    private val accel = DoubleArray(3)
    private val gyro = DoubleArray(3)
    private val mag = DoubleArray(3)
    private val gravity = DoubleArray(3)

    fun outputFile(): File = File(
        context.getExternalFilesDir(null), "drive_${System.currentTimeMillis()}.csv"
    )

    @SuppressLint("MissingPermission")
    fun start(): File {
        check(!running) { "already logging" }
        val file = outputFile()
        startNanos = SystemClock.elapsedRealtimeNanos()
        sampleCount.set(0)
        fixCount.set(0)
        recorder = DriveCsvRecorder(context, file.name).also { it.start(startNanos) }
        running = true

        val periodUs = 1_000_000 / TARGET_IMU_HZ
        listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_GRAVITY,
        ).forEach { type ->
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(this, it, periodUs)
            }
        }

        // GPS provider directly rather than the fused provider: fused output is smoothed
        // and can already incorporate inertial estimates, which would contaminate the
        // reference we are trying to collect.
        //
        // The Looper is passed explicitly. The 4-argument overload delivers callbacks on
        // the CALLING thread's looper and throws outright if it has none, so start() would
        // work from the UI thread and fail anywhere else.
        //
        // Location is requested defensively: without the runtime permission this throws
        // SecurityException, and taking the whole logger down mid-drive is far worse than
        // recording IMU alone. An IMU-only log is still useful; a crash loses everything.
        locationAvailable = try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0L, 0f, this, Looper.getMainLooper())
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false   // no GPS provider on this device (some emulators)
        }
        return file
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        if (locationAvailable) {
            try { locationManager.removeUpdates(this) } catch (_: SecurityException) { }
        }
        recorder?.stop()
        recorder = null
    }

    fun isRunning(): Boolean = running

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> copy(event, gyro)
            Sensor.TYPE_MAGNETIC_FIELD -> copy(event, mag)
            Sensor.TYPE_GRAVITY -> copy(event, gravity)
            Sensor.TYPE_ACCELEROMETER -> {
                copy(event, accel)
                recorder?.recordSample(event.timestamp, accel, gyro, mag, gravity)
                sampleCount.incrementAndGet()
            }
        }
    }

    private fun copy(event: SensorEvent, into: DoubleArray) {
        into[0] = event.values[0].toDouble()
        into[1] = event.values[1].toDouble()
        into[2] = event.values[2].toDouble()
    }

    override fun onLocationChanged(location: Location) {
        recorder?.recordFix(GnssFix(
            lat = location.latitude,
            lon = location.longitude,
            alt = if (location.hasAltitude()) location.altitude else Double.NaN,
            speed = if (location.hasSpeed()) location.speed.toDouble() else Double.NaN,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN,
            // Doppler-derived bearing. Differencing 1 Hz positions is much noisier.
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else Double.NaN,
            bearingAccuracyDeg = if (location.hasBearingAccuracy())
                location.bearingAccuracyDegrees.toDouble() else Double.NaN,
        ))
        fixCount.incrementAndGet()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Deprecated("required by LocationListener on older API levels")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
