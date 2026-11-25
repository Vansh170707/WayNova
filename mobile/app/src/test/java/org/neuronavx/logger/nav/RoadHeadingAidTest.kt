package org.neuronavx.logger.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos

class RoadHeadingAidTest {
    private val lat0 = 28.4578
    private val lon0 = 77.5839
    private fun p(e: Double, n: Double) = RoutePoint(lat0 + Math.toDegrees(n / 6378137.0),
        lon0 + Math.toDegrees(e / (6378137.0 * cos(Math.toRadians(lat0)))))
    private fun road(vararg en: Pair<Double, Double>, oneway: Boolean = false) =
        RoadLine(en.map { (e, n) -> p(e, n) }, oneway)
    private fun aid(vararg roads: RoadLine) = RoadHeadingAid(RoadNetwork.of(roads.toList()))
    private val deg = PI / 180

    // Road running 10 degrees east of north.
    private val straight = road(-87.0 to -500.0, 87.0 to 500.0)

    @Test fun straightRoadGivesItsDirection() {
        val r = aid(straight).evaluate(0.0, p(5.0, 0.0).lat, p(5.0, 0.0).lon, 0.0, 10.0, 0.0, 10.0)
        assertEquals(RoadHeadingAid.Status.ACCEPTED, r.status)
        assertEquals(10.0, Math.toDegrees(r.headingRad), 0.2)
    }

    @Test fun twoWayRoadIsAlignedWithTravel() {
        val r = aid(straight).evaluate(0.0, lat0, lon0, PI, 10.0, 0.0, 10.0)
        assertEquals(RoadHeadingAid.Status.ACCEPTED, r.status)
        assertEquals(-170.0, Math.toDegrees(r.headingRad), 0.2)
    }

    @Test fun parallelServiceRoadStillAgrees() {
        val service = road(-87.0 + 14.0 to -500.0, 87.0 + 14.0 to 500.0)
        val r = aid(straight, service).evaluate(0.0, lat0, lon0, 5 * deg, 10.0, 0.0, 15.0)
        assertEquals(RoadHeadingAid.Status.ACCEPTED, r.status)
        assertEquals(2, r.segments)
    }

    @Test fun junctionIsRejected() {
        val cross = road(-500.0 to 8.0, 500.0 to 8.0)
        val r = aid(straight, cross).evaluate(0.0, lat0, lon0, 10 * deg, 10.0, 0.0, 10.0)
        assertEquals(RoadHeadingAid.Status.NO_CONSENSUS, r.status)
    }

    @Test fun bendIsRejected() {
        val bend = road(0.0 to -300.0, 0.0 to 0.0, 300.0 to 0.0)
        val r = aid(bend).evaluate(0.0, p(3.0, -3.0).lat, p(3.0, -3.0).lon, 45 * deg, 8.0, 0.0, 10.0)
        assertEquals(RoadHeadingAid.Status.NO_CONSENSUS, r.status)
    }

    @Test fun turningSlowFarAndOpposedOneWayAreRejected() {
        assertEquals(RoadHeadingAid.Status.TURNING,
            aid(straight).evaluate(0.0, lat0, lon0, 0.0, 10.0, 10 * deg, 10.0).status)
        assertEquals(RoadHeadingAid.Status.SLOW,
            aid(straight).evaluate(0.0, lat0, lon0, 0.0, 2.0, 0.0, 10.0).status)
        assertEquals(RoadHeadingAid.Status.NO_ROAD,
            aid(straight).evaluate(0.0, p(200.0, 0.0).lat, p(200.0, 0.0).lon, 0.0, 10.0, 0.0, 10.0).status)
        assertEquals(RoadHeadingAid.Status.LARGE_INNOVATION,
            aid(straight).evaluate(0.0, lat0, lon0, 45 * deg, 10.0, 0.0, 10.0).status)
        val southOnly = road(87.0 to 500.0, -87.0 to -500.0, oneway = true)
        assertTrue(aid(southOnly).evaluate(0.0, lat0, lon0, 10 * deg, 10.0, 0.0, 10.0).status !=
            RoadHeadingAid.Status.ACCEPTED)
    }

    @Test fun acceptedAtMostOncePerSecond() {
        val a = aid(straight)
        assertEquals(RoadHeadingAid.Status.ACCEPTED, a.evaluate(0.0, lat0, lon0, 0.0, 10.0, 0.0, 10.0).status)
        assertEquals(RoadHeadingAid.Status.RATE_LIMITED, a.evaluate(0.5, lat0, lon0, 0.0, 10.0, 0.0, 10.0).status)
        assertEquals(RoadHeadingAid.Status.ACCEPTED, a.evaluate(1.0, lat0, lon0, 0.0, 10.0, 0.0, 10.0).status)
    }

    @Test fun bundledGreaterNoidaNetworkLoadsAndHasRoadsAtBennett() {
        val file = File("src/main/assets/${RoadNetwork.ASSET}")
        val network = file.inputStream().use(RoadNetwork::read)
        assertTrue(network.segmentCount > 100_000)
        assertTrue(network.near(lat0, lon0, 150.0).isNotEmpty())
    }
}
