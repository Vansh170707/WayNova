package org.neuronavx.logger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.neuronavx.logger.nav.*

@RunWith(AndroidJUnit4::class)
class RoundTwoTest {
    @Test fun optionalPlaceSearchSmokeTest() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("routingOnline") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Android geocoder is not installed", android.location.Geocoder.isPresent())
        @Suppress("DEPRECATION")
        val results = android.location.Geocoder(context).getFromLocationName("Bennett University, Greater Noida, India", 5)
        assertTrue("Place lookup returned no valid address", results.orEmpty().any { it.hasLatitude() && it.hasLongitude() })
    }

    @Test fun optionalOnlineRoutingSmokeTest() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("routingOnline") == "true")
        // Public, synthetic Greater Noida endpoints, never copied from a private drive.
        val route = RouteClient.fetch(RoutePoint(28.47, 77.51), RoutePoint(28.50, 77.53), "Greater Noida test route")
        assertTrue(route.points.size > 2)
        assertTrue(route.distanceM > 100)
        assertTrue(route.steps.isNotEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.filesDir, "routing_smoke_result.json").writeText(route.toJson().toString())
    }

    @Test fun historyDoesNotPromotePartialOrCorruptRecords() {
        assertNull(SessionRecord.parse("{broken"))
        assertNull(SessionRecord.parse("{}"))
        val record = JSONObject().put("session_id", 1788784767348)
            .put("controlled_blackout", JSONObject().put("phase", "WITHHOLDING")
                .put("end_reference_error_m", 1.2).put("recovered", false))
        assertFalse(SessionRecord.parse(record.toString())!!.complete)
        record.getJSONObject("controlled_blackout").put("phase", "COMPLETE").put("recovered", true)
        assertTrue(SessionRecord.parse(record.toString())!!.complete)
        record.put("raw_writer_error", "disk full")
        assertFalse(SessionRecord.parse(record.toString())!!.complete)
    }

    @Test fun backgroundInterruptionCannotLaterBecomeComplete() {
        val test = FieldBlackoutTest()
        test.arm(60.0, 0.0)
        test.beforeSample(1.0, Navigator.Phase.NAVIGATING)
        assertEquals(FieldBlackoutPhase.WITHHOLDING, test.snapshot().phase)
        test.interrupt()
        test.beforeSample(62.0, Navigator.Phase.NAVIGATING)
        assertEquals(FieldBlackoutPhase.INTERRUPTED, test.snapshot().phase)
    }

    @Test fun testRequiresContinuousAidedLeadIn() {
        val test = FieldBlackoutTest()
        test.arm(60.0, 2.0)
        for (i in 0..50) test.beforeSample(i / 10.0, Navigator.Phase.NAVIGATING, NavMode.BLACKOUT)
        assertEquals(FieldBlackoutPhase.ARMED, test.snapshot().phase)
        for (i in 51..65) test.beforeSample(i / 10.0, Navigator.Phase.NAVIGATING, NavMode.AIDED)
        assertEquals(FieldBlackoutPhase.ARMED, test.snapshot().phase)
        for (i in 66..76) test.beforeSample(i / 10.0, Navigator.Phase.NAVIGATING, NavMode.AIDED)
        assertEquals(FieldBlackoutPhase.WITHHOLDING, test.snapshot().phase)
    }

    @Test fun coordinatesAreValidatedAndKeepLatitudeLongitudeOrder() {
        assertNull(RoutePoint.coordinateInput("91,77"))
        assertNull(RoutePoint.coordinateInput("NaN,77"))
        assertNull(RoutePoint.coordinateInput("city name"))
        val p = RoutePoint.coordinateInput("28.45, 77.5")!!
        assertEquals(28.45, p.lat, 1e-9); assertEquals(77.5, p.lon, 1e-9)
    }

    @Test fun routeStepsAndCoordinatesSurviveOfflineCacheRoundTrip() {
        val body = """{"code":"Ok","routes":[{"distance":1234.5,"duration":120,
            "geometry":{"coordinates":[[77.5,28.45],[77.51,28.46]]},
            "legs":[{"steps":[{"distance":1234.5,"name":"Test Road","maneuver":{"type":"turn","modifier":"left"}},
            {"distance":0,"name":"","maneuver":{"type":"arrive"}}]}]}]}"""
        val route = RoutePlan.fromOsrm(body, "Example destination")
        assertEquals(28.45, route.points.first().lat, 1e-9)
        assertEquals("Turn left · Test Road", route.steps.first().instruction)
        assertEquals(route, RoutePlan.fromCache(route.toJson()))
    }

    @Test fun missingDrivingRouteFailsExplicitly() {
        assertTrue(runCatching { RoutePlan.fromOsrm("""{"code":"NoRoute"}""", "Destination") }.isFailure)
    }

    @Test fun dashboardLaunchesWithNavigationAndDemoActions() {
        ActivityScenario.launch(DashboardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun labels(view: View): List<String> =
                    (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
                        (if (view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList())
                val labels = labels(activity.window.decorView)
                assertTrue(labels.contains("WAYNOVA"))
                assertTrue(labels.contains("Open live navigation  →"))
                assertTrue(labels.contains("Watch the recorded demo"))
            }
        }
    }

    @Test fun navigationStartsIdleWithoutRecordingOrAutoTest() {
        ActivityScenario.launch(NavigationActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun texts(v: View): List<String> =
                    (if (v is TextView) listOf(v.text.toString()) else emptyList()) +
                        (if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList())
                val labels = texts(activity.window.decorView)
                assertTrue(labels.contains("Start live navigation"))
                assertFalse(labels.contains("Stop navigation"))
                assertTrue(labels.any { it.contains("Find destination") || it.contains("Directions") })
            }
        }
    }
}
