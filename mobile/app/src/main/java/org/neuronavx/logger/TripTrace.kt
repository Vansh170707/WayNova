package org.neuronavx.logger

import org.neuronavx.logger.ui.AvailabilityBar
import java.io.File

/**
 * A downsampled view of one recorded session's 10 Hz diagnostics CSV, for the analytics
 * screen. Only the few columns the charts need are parsed, and the series are bucketed
 * down to a fixed number of points so a 40-minute drive costs the same to render as a
 * two-minute one.
 *
 * Everything here is read back from what the estimator actually logged. Nothing is
 * synthesised: if a column is missing or the file is absent, the series comes back empty
 * and the screen says so.
 */
data class TripTrace(
    val speedKph: FloatArray,
    val headingDeg: FloatArray,
    val availability: List<AvailabilityBar.Slice>,
    val distanceKm: Double,
    val averageSpeedKph: Double,
    val topSpeedKph: Double,
    val blackoutSeconds: Double,
    val samples: Int,
) {
    val hasTrace: Boolean get() = samples > 0

    companion object {
        private const val BUCKETS = 120
        private const val MAX_BYTES = 24L * 1024 * 1024

        val EMPTY = TripTrace(FloatArray(0), FloatArray(0), emptyList(), 0.0, 0.0, 0.0, 0.0, 0)

        fun load(directory: File?, sessionId: Long): TripTrace {
            val file = File(directory ?: return EMPTY, "nav_diagnostics_$sessionId.csv")
            if (!file.isFile || file.length() > MAX_BYTES) return EMPTY
            return runCatching { parse(file) }.getOrDefault(EMPTY)
        }

        private fun parse(file: File): TripTrace {
            var speedIndex = -1
            var headingIndex = -1
            var modeIndex = -1
            var timeIndex = -1

            val speeds = ArrayList<Float>()
            val headings = ArrayList<Float>()
            val modes = ArrayList<String>()
            var firstTimeMs = Double.NaN
            var lastTimeMs = Double.NaN
            var distanceM = 0.0
            var previousTimeMs = Double.NaN
            var previousSpeed = Double.NaN
            var blackoutS = 0.0

            file.bufferedReader().useLines { lines ->
                lines.forEachIndexed { lineNumber, line ->
                    if (lineNumber == 0) {
                        val header = line.split(',')
                        speedIndex = header.indexOf("speed_ms")
                        headingIndex = header.indexOf("heading_deg")
                        modeIndex = header.indexOf("mode")
                        timeIndex = header.indexOf("timestamp_ms")
                        return@forEachIndexed
                    }
                    if (speedIndex < 0 || timeIndex < 0) return@forEachIndexed
                    val cells = line.split(',')
                    if (cells.size <= maxOf(speedIndex, headingIndex, modeIndex, timeIndex)) return@forEachIndexed

                    val timeMs = cells[timeIndex].toDoubleOrNull() ?: return@forEachIndexed
                    val speed = cells[speedIndex].toDoubleOrNull()
                    val heading = headingIndex.takeIf { it >= 0 }?.let { cells[it].toDoubleOrNull() }
                    val mode = modeIndex.takeIf { it >= 0 }?.let { cells[it] }.orEmpty()

                    if (firstTimeMs.isNaN()) firstTimeMs = timeMs
                    lastTimeMs = timeMs

                    if (speed != null && speed.isFinite()) {
                        // Trapezoidal integration of the estimator's own speed: this is the
                        // distance the filter believes it travelled, not a GPS odometer.
                        if (previousSpeed.isFinite() && previousTimeMs.isFinite()) {
                            val dt = (timeMs - previousTimeMs) / 1000.0
                            if (dt in 0.0..1.0) {
                                distanceM += 0.5 * (speed + previousSpeed) * dt
                                if (mode == "BLACKOUT") blackoutS += dt
                            }
                        }
                        previousSpeed = speed
                        previousTimeMs = timeMs
                        speeds.add((speed * 3.6).toFloat())
                        headings.add(((heading ?: 0.0).takeIf { it.isFinite() } ?: 0.0).toFloat())
                        modes.add(mode)
                    }
                }
            }

            if (speeds.isEmpty()) return EMPTY
            val durationS = ((lastTimeMs - firstTimeMs) / 1000.0).takeIf { it > 0 } ?: 0.0
            val average = if (durationS > 0) distanceM / durationS * 3.6 else 0.0

            return TripTrace(
                speedKph = bucketMean(speeds),
                headingDeg = bucketMean(headings),
                availability = bucketModes(modes),
                distanceKm = distanceM / 1000.0,
                averageSpeedKph = average,
                topSpeedKph = speeds.max().toDouble(),
                blackoutSeconds = blackoutS,
                samples = speeds.size,
            )
        }

        /** Averages the series into [BUCKETS] points, preserving overall shape. */
        private fun bucketMean(values: List<Float>): FloatArray {
            if (values.size <= BUCKETS) return values.toFloatArray()
            val out = FloatArray(BUCKETS)
            val width = values.size.toDouble() / BUCKETS
            for (bucket in 0 until BUCKETS) {
                val from = (bucket * width).toInt()
                val to = (((bucket + 1) * width).toInt()).coerceAtMost(values.size)
                var sum = 0.0
                var count = 0
                for (i in from until to) {
                    sum += values[i]
                    count++
                }
                out[bucket] = if (count == 0) values[from.coerceAtMost(values.lastIndex)] else (sum / count).toFloat()
            }
            return out
        }

        /**
         * Collapses the mode column into availability ticks. A bucket counts as a blackout
         * if any sample inside it was in blackout, so short outages stay visible.
         */
        private fun bucketModes(modes: List<String>): List<AvailabilityBar.Slice> {
            if (modes.isEmpty()) return emptyList()
            val width = (modes.size.toDouble() / BUCKETS).coerceAtLeast(1.0)
            val out = ArrayList<AvailabilityBar.Slice>(BUCKETS)
            var bucket = 0
            while (bucket * width < modes.size) {
                val from = (bucket * width).toInt()
                val to = (((bucket + 1) * width).toInt()).coerceAtMost(modes.size)
                var blackout = false
                var reacquiring = false
                for (i in from until to) {
                    when (modes[i]) {
                        "BLACKOUT" -> blackout = true
                        "REACQUIRING", "RECOVERY" -> reacquiring = true
                    }
                }
                out.add(
                    when {
                        blackout -> AvailabilityBar.Slice.BLACKOUT
                        reacquiring -> AvailabilityBar.Slice.REACQUIRING
                        else -> AvailabilityBar.Slice.AVAILABLE
                    },
                )
                bucket++
            }
            return out
        }
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = samples
}
