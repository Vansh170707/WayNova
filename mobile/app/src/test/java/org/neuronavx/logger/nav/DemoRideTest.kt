package org.neuronavx.logger.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class DemoRideTest {
    // Plain L-shaped route around Greater Noida: 600 m north, then 400 m east.
    private val start = RoutePoint(28.4698, 77.5681)
    private fun offset(e: Double, n: Double) = RoutePoint(
        start.lat + Math.toDegrees(n / 6378137.0),
        start.lon + Math.toDegrees(e / (6378137.0 * Math.cos(Math.toRadians(start.lat)))))
    private val plan = RoutePlan("Test", listOf(start, offset(0.0, 600.0), offset(400.0, 600.0)),
        listOf(RouteStep("Start driving", 600.0, start)), 1000.0, 120.0)

    @Test fun rideStartsAndEndsOnRouteAndTimeIncreases() {
        val ride = DemoRide(plan)
        val frames = ride.frames()
        assertEquals(1000.0, ride.lengthM, 1.0)
        assertEquals(0.0, frames.first().trueEast, 1e-6)
        assertEquals(400.0, frames.last().trueEast, 1.0)
        assertEquals(600.0, frames.last().trueNorth, 1.0)
        assertTrue(frames.zipWithNext().all { (a, b) -> b.t > a.t && b.progressM >= a.progressM })
        assertTrue(frames.all { it.speedMps in 0.0..9.0 })
    }

    @Test fun ridesSlowerAtTheCornerThanOnTheStraight() {
        val frames = DemoRide(plan).frames()
        val corner = frames.filter { it.progressM in 595.0..605.0 }.maxOf { it.speedMps }
        val straight = frames.filter { it.progressM in 250.0..350.0 }.maxOf { it.speedMps }
        assertTrue("corner $corner straight $straight", corner < straight - 2.0)
    }

    @Test fun simulatedGpsLossDriftsAndRecoversWithoutJump() {
        val frames = DemoRide(plan).frames()
        val outage = frames.filter { it.mode == NavMode.BLACKOUT }
        assertTrue(outage.isNotEmpty())
        val maxErr = outage.maxOf { hypot(it.east - it.trueEast, it.north - it.trueNorth) }
        assertTrue("drift $maxErr", maxErr > 10.0)
        assertTrue(frames.any { it.mode == NavMode.REACQUIRING })
        val last = frames.last()
        assertEquals(NavMode.AIDED, last.mode)
        assertTrue(hypot(last.east - last.trueEast, last.north - last.trueNorth) < 3.0)
        // No frame-to-frame jump in the displayed estimate bigger than speed allows plus noise.
        val worst = frames.zipWithNext().maxByOrNull { (a, b) -> hypot(b.east - a.east, b.north - a.north) }!!
        val jump = hypot(worst.second.east - worst.first.east, worst.second.north - worst.first.north)
        assertTrue("jump $jump m at ${worst.first.mode}->${worst.second.mode} s=${worst.first.progressM}", jump < 9.0 * 0.2 + 3.0)
    }
}
