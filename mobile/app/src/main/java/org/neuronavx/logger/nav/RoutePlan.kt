package org.neuronavx.logger.nav

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class RoutePoint(val lat: Double, val lon: Double) {
    init { require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) }
    companion object {
        fun coordinateInput(text: String): RoutePoint? = runCatching {
            val parts = text.split(',')
            require(parts.size == 2)
            RoutePoint(parts[0].trim().toDouble(), parts[1].trim().toDouble())
        }.getOrNull()
    }
}

data class RouteStep(val instruction: String, val distanceM: Double)

/** A planned road route is never fed into the estimator or used as its reference truth. */
data class RoutePlan(val destination: String, val points: List<RoutePoint>, val steps: List<RouteStep>,
    val distanceM: Double, val durationS: Double, val savedAt: Long = System.currentTimeMillis()) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("destination", destination); put("distance_m", distanceM); put("duration_s", durationS)
        put("saved_at", savedAt)
        put("points", JSONArray().apply { points.forEach { put(JSONArray().put(it.lon).put(it.lat)) } })
        put("steps", JSONArray().apply { steps.forEach {
            put(JSONObject().put("instruction", it.instruction).put("distance_m", it.distanceM))
        } })
    }
    companion object {
        private fun coordinates(array: JSONArray): List<RoutePoint> {
            require(array.length() in 2..50000) { "Route geometry is unavailable or too large" }
            return (0 until array.length()).map { i -> array.getJSONArray(i).let {
                RoutePoint(it.getDouble(1), it.getDouble(0))
            } }
        }
        private fun validNumber(value: Double): Double {
            require(value.isFinite() && value >= 0) { "Invalid route measurement" }
            return value
        }
        fun fromCache(json: JSONObject): RoutePlan {
            val steps = json.getJSONArray("steps")
            require(steps.length() in 1..2000)
            return RoutePlan(json.getString("destination"), coordinates(json.getJSONArray("points")),
                (0 until steps.length()).map { steps.getJSONObject(it).let { step ->
                    RouteStep(step.getString("instruction"), validNumber(step.getDouble("distance_m")))
                } }, validNumber(json.getDouble("distance_m")), validNumber(json.getDouble("duration_s")),
                json.getLong("saved_at"))
        }
        fun fromOsrm(body: String, destination: String): RoutePlan {
            val json = JSONObject(body)
            require(json.optString("code") == "Ok") { "No driving route found. Try a nearby road entrance." }
            val route = json.getJSONArray("routes").getJSONObject(0)
            val steps = ArrayList<RouteStep>()
            val legs = route.getJSONArray("legs")
            for (i in 0 until legs.length()) {
                val list = legs.getJSONObject(i).getJSONArray("steps")
                for (j in 0 until list.length()) {
                    val step = list.getJSONObject(j)
                    val maneuver = step.getJSONObject("maneuver")
                    val type = maneuver.getString("type")
                    val modifier = maneuver.optString("modifier").replace('_', ' ')
                    val instruction = when (type) {
                        "depart" -> "Start driving"
                        "arrive" -> "Arrive at destination"
                        "roundabout", "rotary" -> "At the roundabout" +
                            if (maneuver.has("exit")) ", take exit ${maneuver.getInt("exit")}" else ", continue"
                        "turn" -> "Turn $modifier"
                        "new name", "continue", "notification" -> "Continue $modifier"
                        "merge" -> "Merge $modifier"
                        "fork" -> "Keep $modifier at the fork"
                        "on ramp" -> "Take the ramp $modifier"
                        "off ramp" -> "Take the exit $modifier"
                        else -> type.replaceFirstChar { it.uppercase() } + " " + modifier
                    }
                    val road = step.optString("name").take(160)
                    steps += RouteStep(instruction.trim() + if (road.isNotBlank()) " · $road" else "",
                        validNumber(step.getDouble("distance")))
                }
            }
            require(steps.size in 1..2000)
            return RoutePlan(destination.take(200), coordinates(route.getJSONObject("geometry")
                .getJSONArray("coordinates")), steps, validNumber(route.getDouble("distance")),
                validNumber(route.getDouble("duration")))
        }
    }
}

/** Low-volume, user-initiated prototype routing; no automatic reroute or background upload. */
object RouteClient {
    private var lastRequestNanos = 0L
    @Synchronized fun fetch(start: RoutePoint, end: RoutePoint, name: String): RoutePlan {
        val now = System.nanoTime()
        require(now - lastRequestNanos >= 1_500_000_000L) { "Please wait a moment before requesting another route." }
        lastRequestNanos = now
        val coords = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", start.lon, start.lat, end.lon, end.lat)
        val connection = URL("https://router.project-osrm.org/route/v1/driving/$coords?steps=true&overview=full&geometries=geojson")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "Waynova-SIH-Prototype/0.2 (Android; user-requested driving routes)")
            require(connection.responseCode == 200) { "Routing service unavailable (${connection.responseCode}). Try again later." }
            val body = connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(result.length + count <= 2_000_000) { "Route is too large for this prototype." }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            return RoutePlan.fromOsrm(body, name)
        } finally { connection.disconnect() }
    }
}
