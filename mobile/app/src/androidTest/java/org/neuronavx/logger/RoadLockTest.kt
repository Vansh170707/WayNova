package org.neuronavx.logger

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.neuronavx.logger.nav.*
import kotlin.math.cos

/** Road lock reads real road geometry from the bundled Greater Noida offline map. */
@RunWith(AndroidJUnit4::class)
class RoadLockTest {
    private val lat = 28.4744
    private val lon = 77.5040

    @Test fun offlineMapSuppliesDrivableRoadsAndMarkerSnapsOntoThem() {
        ActivityScenario.launch(NavigationActivity::class.java).use { scenario ->
            fun <T> field(activity: NavigationActivity, name: String): T =
                NavigationActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .get(activity) as T
            var renderer: MapLibreOfflineRenderer? = null
            val deadline = System.currentTimeMillis() + 30_000
            while (renderer == null && System.currentTimeMillis() < deadline) {
                scenario.onActivity { a ->
                    if (field<Boolean>(a, "mapLibreReady")) renderer = field(a, "mapLibreRenderer")
                }
                if (renderer == null) Thread.sleep(250)
            }
            val map = checkNotNull(renderer) { "MapLibre offline renderer never became ready" }
            // Put the marker in Greater Noida so the camera follows it and loads street tiles.
            val snapshot = NavigationMapSnapshot(lat, lon, doubleArrayOf(0.0), doubleArrayOf(0.0),
                doubleArrayOf(0.0), doubleArrayOf(0.0), booleanArrayOf(false), 0.0, 0.0, 0.0, 5.0, false)
            scenario.onActivity { map.submit(snapshot) }
            var roads: List<RoadLine> = emptyList()
            val roadDeadline = System.currentTimeMillis() + 30_000
            while (roads.isEmpty() && System.currentTimeMillis() < roadDeadline) {
                Thread.sleep(500)
                scenario.onActivity { roads = map.drivableRoadsNear(lat, lon).orEmpty() }
            }
            assertTrue("No drivable roads read from the offline map", roads.isNotEmpty())
            assertTrue(roads.all { it.kind in setOf("highway", "major_road", "minor_road") })

            // Place the raw estimate 15 m east of a real road vertex: it must be pulled onto a road.
            val vertex = roads.flatMap { it.points }.minByOrNull {
                (it.lat - lat) * (it.lat - lat) + (it.lon - lon) * (it.lon - lon)
            }!!
            val scale = cos(Math.toRadians(lat))
            val vEast = Math.toRadians(vertex.lon - lon) * 6378137.0 * scale
            val vNorth = Math.toRadians(vertex.lat - lat) * 6378137.0
            val snapper = RoadSnapper().apply { setRoads(roads, lat, lon) }
            val result = snapper.update(0.0, vEast + 15.0, vNorth, Double.NaN, 0.0, 10.0)
            assertTrue("Expected a lock, got ${result.status}", result.locked)
            assertTrue(result.offRoadM <= 15.0 + 1e-6)
            assertEquals(result.offRoadM, kotlin.math.hypot(result.east - vEast - 15.0, result.north - vNorth), 0.01)
        }
    }
}
