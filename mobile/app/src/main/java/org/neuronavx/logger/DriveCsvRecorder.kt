package org.neuronavx.logger

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.neuronavx.logger.nav.GnssFix
import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Small asynchronous line sink shared by raw-drive and navigation-diagnostics recording.
 * Sensor callbacks only format and enqueue a row; filesystem I/O stays on the writer thread.
 */
internal class AsyncCsvFile(
    context: Context,
    fileName: String,
    private val header: String,
) {
    val file = File(checkNotNull(context.getExternalFilesDir(null)), fileName)

    private var writer: BufferedWriter? = null
    private var writerThread: HandlerThread? = null
    private var writerHandler: Handler? = null
    @Volatile private var running = false
    @Volatile var lastError: String? = null
        private set

    fun start() {
        check(!running) { "CSV writer already started" }
        writer = file.bufferedWriter().also {
            it.write(header)
            it.newLine()
        }
        writerThread = HandlerThread("neuronavx-csv-writer").apply { start() }
        writerHandler = Handler(writerThread!!.looper)
        running = true
    }

    fun append(row: String) {
        if (!running) return
        writerHandler?.post {
            try {
                writer?.write(row)
                writer?.newLine()
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message ?: "write failed"}"
            }
        }
    }

    /** Close after every already-enqueued row, waiting briefly so a stopped drive is pullable. */
    fun stop() {
        if (!running) return
        running = false
        val closed = CountDownLatch(1)
        writerHandler?.post {
            try {
                writer?.flush()
                writer?.close()
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message ?: "close failed"}"
            } finally {
                writer = null
                closed.countDown()
            }
        }
        writerThread?.quitSafely()
        closed.await(2, TimeUnit.SECONDS)
        writerThread?.join(500)
        writerHandler = null
        writerThread = null
    }
}

/**
 * Writes the exact raw schema consumed by `neuronav.data.own_drive.load_own_drive`.
 * GNSS values are held between fixes while `gps_fresh` marks the one row that owns a new fix.
 */
internal class DriveCsvRecorder(
    context: Context,
    fileName: String,
) {
    companion object {
        const val HEADER =
            "timestamp_ms,ax,ay,az,gx,gy,gz,mx,my,mz,grav_x,grav_y,grav_z," +
                "lat,lon,alt,speed,accuracy,bearing,bearing_acc,gps_fresh"
    }

    private val sink = AsyncCsvFile(context, fileName, HEADER)
    private var startNanos = 0L
    private var running = false
    private var latestFix: GnssFix? = null
    private var gpsFresh = false

    val file: File get() = sink.file
    val sampleCount = AtomicLong(0)
    val fixCount = AtomicLong(0)
    val lastError: String? get() = sink.lastError

    fun start(startNanos: Long): File {
        this.startNanos = startNanos
        sampleCount.set(0)
        fixCount.set(0)
        latestFix = null
        gpsFresh = false
        sink.start()
        running = true
        return file
    }

    @Synchronized
    fun recordFix(fix: GnssFix) {
        if (!running) return
        latestFix = fix
        gpsFresh = true
        fixCount.incrementAndGet()
    }

    @Synchronized
    fun recordSample(
        eventNanos: Long,
        accel: DoubleArray,
        gyro: DoubleArray,
        magnetic: DoubleArray,
        gravity: DoubleArray,
    ) {
        if (!running) return
        val fix = latestFix
        val fresh = gpsFresh
        gpsFresh = false
        val tMillis = (eventNanos - startNanos) / 1_000_000.0
        val row = StringBuilder(220).apply {
            append(fmt(tMillis, 3))
            appendVector(accel)
            appendVector(gyro)
            appendVector(magnetic)
            appendVector(gravity)
            append(',').append(fmt(fix?.lat))
            append(',').append(fmt(fix?.lon))
            append(',').append(fmt(fix?.alt))
            append(',').append(fmt(fix?.speed, 5))
            append(',').append(fmt(fix?.accuracyM, 5))
            append(',').append(fmt(fix?.bearingDeg, 5))
            append(',').append(fmt(fix?.bearingAccuracyDeg, 5))
            append(',').append(if (fresh) 1 else 0)
        }.toString()
        sampleCount.incrementAndGet()
        sink.append(row)
    }

    private fun StringBuilder.appendVector(values: DoubleArray) {
        for (i in 0 until 3) append(',').append(fmt(values.getOrNull(i), 5))
    }

    fun stop() {
        if (!running) return
        running = false
        sink.stop()
    }

    private fun fmt(value: Double?, decimals: Int = 7): String =
        if (value == null || !value.isFinite()) ""
        else String.format(Locale.US, "%.${decimals}f", value)
}
