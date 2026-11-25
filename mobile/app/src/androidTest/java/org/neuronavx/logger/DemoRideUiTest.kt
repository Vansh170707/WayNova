package org.neuronavx.logger

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.neuronavx.logger.nav.*

/** The demo ride plays in Greater Noida, on the offline map, with its route drawn. */
@RunWith(AndroidJUnit4::class)
class DemoRideUiTest {
    @Test fun bundledDemoRouteParsesFromXu1ToBennett() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val plan = RoutePlan.fromOsrm(context.assets.open(DemoRide.ASSET).bufferedReader().use { it.readText() },
            DemoRide.DESTINATION)
        assertEquals(3545.0, plan.distanceM, 50.0)
        val end = plan.points.last()
        assertTrue(kotlin.math.abs(end.lat - 28.4578) < 0.002 && kotlin.math.abs(end.lon - 77.5839) < 0.002)
        assertTrue(DemoRide(plan).frames().size > 1000)
    }

    @Test fun demoRideShowsOfflineMapRouteAndMovingMarker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, NavigationActivity::class.java).putExtra("start_demo", true)
        ActivityScenario.launch<NavigationActivity>(intent).use { scenario ->
            fun <T> field(a: NavigationActivity, name: String): T =
                NavigationActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a) as T
            var first: NavigationMapSnapshot? = null
            var last: NavigationMapSnapshot? = null
            var eyebrow = ""
            val deadline = System.currentTimeMillis() + 45_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
                scenario.onActivity { a ->
                    eyebrow = field<TextView>(a, "eyebrow").text.toString()
                    val snap = field<NavigationMapSnapshot?>(a, "latestMapSnapshot")
                    if (field<Boolean>(a, "mapLibreActive") && snap != null) {
                        if (first == null) first = snap else last = snap
                    }
                }
                if (last != null && kotlin.math.hypot(last!!.markerEast - first!!.markerEast,
                        last!!.markerNorth - first!!.markerNorth) > 20) break
            }
            assertTrue("Demo label missing: $eyebrow", eyebrow.contains("SIMULATED DEMO RIDE"))
            val snap = checkNotNull(last) { "Offline map never showed the demo ride" }
            assertEquals(28.4698, snap.originLat, 0.002)
            assertTrue("Marker did not move", kotlin.math.hypot(snap.markerEast - first!!.markerEast,
                snap.markerNorth - first!!.markerNorth) > 20)
            scenario.onActivity { a ->
                val renderer = field<MapLibreOfflineRenderer>(a, "mapLibreRenderer")
                val route = MapLibreOfflineRenderer::class.java.getDeclaredField("plannedRoute")
                    .apply { isAccessible = true }.get(renderer) as List<*>
                assertTrue("Demo route not drawn", route.size > 50)
                assertTrue(renderer.covers(snap))
            }
        }
    }
}
