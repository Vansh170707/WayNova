package org.neuronavx.logger.nav

import java.io.BufferedReader
import java.io.InputStream

/**
 * Replays a log recorded in the Android logger's CSV schema through the live [Navigator].
 *
 * Replay is not a convenience here, it is the only way to exercise a GNSS outage on
 * demand: driving through a real one is not something that can be scheduled, and a
 * scripted outage in a recorded log runs the identical code path the live app runs -- the
 * blackout state machine still has to *notice* that fixes stopped arriving, because
 * nothing in the file tells it.
 *
 * `scripts/make_replay_log.py` writes these files from an IO-VNBD session together with
 * the desktop estimator's answer on the same file, so a replay can be scored rather than
 * merely watched.
 */
class ReplaySource(private val reader: BufferedReader) : AutoCloseable {

    constructor(stream: InputStream) : this(stream.bufferedReader())

    data class Sample(
        val t: Double,
        val accel: DoubleArray, val gravity: DoubleArray, val gyro: DoubleArray,
        val fix: GnssFix?,
    )

    private val index = HashMap<String, Int>()
    private var pending: String? = null

    /** Sample rate observed in the file, so the navigator can pick a decimation factor. */
    var observedRateHz: Double = Double.NaN
        private set
    private var firstT = Double.NaN
    private var lastT = Double.NaN
    private var seen = 0

    init {
        val header = reader.readLine() ?: error("empty replay log")
        header.split(",").forEachIndexed { i, name -> index[name.trim()] = i }
        for (required in listOf("timestamp_ms", "ax", "ay", "az", "gx", "gy", "gz",
                "grav_x", "grav_y", "grav_z", "lat", "lon", "speed", "accuracy",
                "bearing", "gps_fresh")) {
            require(index.containsKey(required)) { "replay log is missing column $required" }
        }
        pending = reader.readLine()
    }

    fun hasNext(): Boolean = pending != null

    fun next(): Sample? {
        val line = pending ?: return null
        pending = reader.readLine()
        val f = line.split(",")
        if (f.size < index.size) return next()

        fun num(name: String): Double {
            val i = index[name] ?: return Double.NaN
            if (i >= f.size) return Double.NaN
            val raw = f[i]
            return if (raw.isEmpty()) Double.NaN else raw.toDoubleOrNull() ?: Double.NaN
        }

        val t = num("timestamp_ms") / 1000.0
        if (!t.isFinite()) return next()
        if (firstT.isNaN()) firstT = t
        lastT = t
        seen++
        if (seen > 1 && lastT > firstT) observedRateHz = (seen - 1) / (lastT - firstT)

        // gps_fresh marks the row carrying a genuinely NEW fix. Without it the held values
        // would be re-fused every sample, which treats one measurement as ten.
        val fresh = num("gps_fresh") >= 0.5
        val lat = num("lat")
        val fix = if (fresh && lat.isFinite()) GnssFix(
            lat = lat, lon = num("lon"), alt = num("alt"),
            speed = num("speed"), accuracyM = num("accuracy"),
            bearingDeg = num("bearing"),
            bearingAccuracyDeg = num("bearing_acc"),
        ) else null

        return Sample(
            t = t,
            accel = doubleArrayOf(num("ax"), num("ay"), num("az")),
            gravity = doubleArrayOf(num("grav_x"), num("grav_y"), num("grav_z")),
            gyro = doubleArrayOf(num("gx"), num("gy"), num("gz")),
            fix = fix,
        )
    }

    /** Drive [navigator] to the end of the log, invoking [onState] for each update. */
    fun runAll(navigator: Navigator, onState: ((Navigator.State) -> Unit)? = null): Int {
        var emitted = 0
        while (true) {
            val s = next() ?: break
            val state = navigator.addSample(s.t, s.accel, s.gravity, s.gyro, s.fix)
            if (state != null) { emitted++; onState?.invoke(state) }
        }
        return emitted
    }

    override fun close() = reader.close()
}
