package org.neuronavx.logger.nav

import java.io.File

/** Plays the original diagnostic estimates, not a new model run or a GPS ground-truth trace. */
data class SavedRide(val origin: RoutePoint, val frames: List<Frame>) {
    data class Frame(val timeMs: Double, val east: Double, val north: Double,
        val heading: Double, val speed: Double, val sigma: Double, val mode: String)
    companion object {
        fun load(directory: File, id: Long): SavedRide {
            require(id > 0)
            val raw = File(directory, "nav_drive_$id.csv")
            val diagnostics = File(directory, "nav_diagnostics_$id.csv")
            require(raw.isFile && raw.length() <= 200L*1024*1024 && diagnostics.isFile && diagnostics.length() <= 24L*1024*1024) {
                "Recorded raw/diagnostic files are missing or exceed the replay limit."
            }
            val origin = raw.bufferedReader().use { reader ->
                val header = reader.readLine()?.split(',') ?: error("Empty raw recording")
                val lat = header.indexOf("lat"); val lon = header.indexOf("lon"); val fresh = header.indexOf("gps_fresh")
                require(minOf(lat,lon,fresh) >= 0) { "Raw recording lacks origin columns" }
                var found: RoutePoint? = null
                while (found == null) {
                    val row = reader.readLine()?.split(',') ?: break
                    if ((row.getOrNull(fresh)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0) < 0.5) continue
                    found = runCatching { RoutePoint(row[lat].toDouble(), row[lon].toDouble()) }.getOrNull()
                }
                found ?: error("No recorded GPS origin; replay cannot be placed on a map")
            }
            val frames = diagnostics.bufferedReader().use { reader ->
                val header = reader.readLine()?.split(',') ?: error("Empty diagnostics")
                val required = listOf("timestamp_ms","phase","mode","display_east_m","display_north_m","heading_deg","speed_ms","sigma_m")
                require(header.containsAll(required)) { "This recording does not contain replay coordinates" }
                val index = header.withIndex().associate { it.value to it.index }
                val result = ArrayList<Frame>()
                reader.forEachLine { line ->
                    val row = line.split(',')
                    fun cell(name: String) = row.getOrNull(index.getValue(name)).orEmpty()
                    fun number(name: String) = cell(name).toDoubleOrNull() ?: Double.NaN
                    if (cell("phase") == "NAVIGATING") {
                        val frame = Frame(number("timestamp_ms"),number("display_east_m"),number("display_north_m"),
                            Math.toRadians(number("heading_deg")),number("speed_ms"),number("sigma_m"),cell("mode"))
                        if (listOf(frame.timeMs,frame.east,frame.north,frame.heading,frame.speed,frame.sigma).all { it.isFinite() } &&
                            frame.speed >= 0 && frame.sigma >= 0 &&
                            (result.isEmpty() || frame.timeMs > result.last().timeMs)) {
                            require(result.size < 100000) { "Recording is too long for in-app replay" }
                            result += frame
                        }
                    }
                }
                result
            }
            require(frames.isNotEmpty()) { "No valid navigation estimates were recorded" }
            return SavedRide(origin,frames)
        }
    }
}
