package org.neuronavx.logger.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

class RoadSnapperTest {
    private val lat0 = 28.5
    private val lon0 = 77.5

    /** Road through local east/north points (metres) around the test origin. */
    private fun road(vararg en: Pair<Double, Double>, oneway: Boolean = false) = RoadLine(
        en.map { (e, n) ->
            RoutePoint(lat0 + Math.toDegrees(n / 6378137.0),
                lon0 + Math.toDegrees(e / (6378137.0 * cos(Math.toRadians(lat0)))))
        }, oneway, "major_road")

    private fun snapper(vararg roads: RoadLine) = RoadSnapper().apply { setRoads(roads.toList(), lat0, lon0) }

    private val northRoad = road(0.0 to -2000.0, 0.0 to 2000.0)

    @Test fun onRoadEstimateSitsOnCentreline() {
        val r = snapper(northRoad).update(0.0, 3.0, 0.0, 0.0, 10.0, 5.0)
        assertEquals(RoadSnapper.Status.ON_ROAD, r.status)
        assertEquals(0.0, r.east, 0.01)
    }

    @Test fun sidewaysDriftIsDetectedAndMarkerStaysOnRoad() {
        val s = snapper(northRoad)
        var t = 0.0; var north = 0.0
        s.update(t, 1.0, north, 0.0, 10.0, 8.0)
        // Estimate suddenly drifts 20 m east of the road.
        t += 0.05; north += 0.5
        val r = s.update(t, 20.0, north, 0.0, 10.0, 8.0)
        assertEquals(RoadSnapper.Status.CORRECTING, r.status)
        assertEquals(20.0, r.offRoadM, 0.01)
        assertEquals(0.0, r.east, 0.01)
        assertEquals(north, r.north, 0.01)
    }

    @Test fun reacquiringRoadGlidesOnWithoutTeleport() {
        val s = snapper(northRoad)
        var t = 0.0; var north = 0.0
        // Far from any road for a while: shown unchanged.
        repeat(20) { t += 0.05; north += 0.5; s.update(t, 70.0, north, 0.0, 10.0, 8.0) }
        t += 0.05; north += 0.5
        val first = s.update(t, 20.0, north, 0.0, 10.0, 8.0)
        assertEquals(RoadSnapper.Status.CORRECTING, first.status)
        // Correction moves at most 40 m/s * dt per update.
        assertTrue("jumped to ${first.east}", first.east >= 20.0 - 40.0 * 0.05 - 0.01)
        var last = first
        repeat(30) { t += 0.05; north += 0.5; last = s.update(t, 20.0, north, 0.0, 10.0, 8.0) }
        assertTrue("still ${last.east} m off road", abs(last.east) < 0.01)
        assertEquals(north, last.north, 0.5)
    }

    @Test fun estimateFarFromAnyRoadIsLeftAlone() {
        val r = snapper(northRoad).update(0.0, 200.0, 0.0, 0.0, 10.0, 5.0)
        assertEquals(RoadSnapper.Status.OFF_NETWORK, r.status)
        assertEquals(200.0, r.east, 1e-9)
    }

    @Test fun noRoadDataLeavesMarkerUnchanged() {
        val r = RoadSnapper().update(0.0, 12.0, 34.0, 0.0, 10.0, 5.0)
        assertEquals(RoadSnapper.Status.NO_ROADS, r.status)
        assertEquals(12.0, r.east, 1e-9); assertEquals(34.0, r.north, 1e-9)
    }

    @Test fun headingPrefersRoadInTravelDirection() {
        // Cross street 5 m north is closer, but the car is travelling north on a road 12 m west.
        val cross = road(-500.0 to 5.0, 500.0 to 5.0)
        val main = road(-12.0 to -2000.0, -12.0 to 2000.0)
        val r = snapper(cross, main).update(0.0, 0.0, 0.0, 0.0, 12.0, 15.0)
        assertTrue(r.locked)
        assertEquals(-12.0, r.east, 0.01)
    }

    @Test fun oneWayRoadAgainstTravelIsRejected() {
        val southOnly = road(0.0 to 2000.0, 0.0 to -2000.0, oneway = true)
        val r = snapper(southOnly).update(0.0, 3.0, 0.0, 0.0, 10.0, 5.0)
        assertEquals(RoadSnapper.Status.OFF_NETWORK, r.status)
    }

    @Test fun twoWayRoadReportsTravelHeading() {
        val r = snapper(northRoad).update(0.0, 2.0, 0.0, PI, 10.0, 5.0)
        assertTrue(r.locked)
        assertEquals(PI, abs(r.headingRad), 1e-6)
    }

    @Test fun equidistantParallelRoadsWithoutHistoryAreAmbiguous() {
        val west = road(-8.0 to -2000.0, -8.0 to 2000.0)
        val east = road(8.0 to -2000.0, 8.0 to 2000.0)
        val r = snapper(west, east).update(0.0, 0.0, 0.0, 0.0, 10.0, 10.0)
        assertEquals(RoadSnapper.Status.AMBIGUOUS, r.status)
        assertEquals(0.0, r.east, 1e-9)
    }

    @Test fun continuityKeepsLockOnMainRoadNextToServiceRoad() {
        val main = road(0.0 to -2000.0, 0.0 to 2000.0)
        val service = road(15.0 to -2000.0, 15.0 to 2000.0)
        val s = snapper(main, service)
        var t = 0.0; var north = 0.0
        s.update(t, 1.0, north, 0.0, 15.0, 20.0)
        var r = s.update(t, 1.0, north, 0.0, 15.0, 20.0)
        // Blackout drift slides the estimate towards the service road.
        for (i in 1..40) {
            t += 0.05; north += 0.75
            r = s.update(t, 1.0 + i * 0.3, north, 0.0, 15.0, 20.0)
        }
        assertTrue(r.locked)
        assertTrue("switched to service road at ${r.east}", abs(r.east) < 1.0)
    }

    @Test fun releaseEasesOutInsteadOfJumping() {
        val s = snapper(northRoad)
        s.update(0.0, 10.0, 0.0, 0.0, 10.0, 8.0)
        // Estimate leaves the network: the 10 m correction decays rather than vanishing.
        val r = s.update(0.05, 200.0, 0.0, 0.0, 10.0, 8.0)
        assertEquals(RoadSnapper.Status.OFF_NETWORK, r.status)
        assertTrue(r.correctionM in 0.1..10.0)
        assertTrue(hypot(r.east - 200.0, r.north) < 10.0)
    }
}
