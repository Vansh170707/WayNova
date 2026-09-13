package org.neuronavx.logger

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.neuronavx.logger.nav.*

@RunWith(AndroidJUnit4::class)
class RouteGuidanceTest {
    private val a = RoutePoint(28.45, 77.50)
    private val b = RoutePoint(28.451, 77.50)
    private val c = RoutePoint(28.451, 77.501)
    private fun plan() = RoutePlan("Test", listOf(a,b,c), listOf(
        RouteStep("Start", 111.2, a), RouteStep("Turn right", 97.8, b), RouteStep("Arrive", 0.0, c)
    ), 209.0, 30.0)
    private fun at(p: RoutePoint, reliable: Boolean = true, sigma: Double = 5.0,
        age: Double = 0.1, heading: Double = 0.0, speed: Double = 5.0) =
        RouteGuidance(plan()).update(p, reliable, sigma, age, heading, speed)

    @Test fun nextTurnAdvancesOnlyAfterPassingTheManeuver() {
        val before = at(RoutePoint(28.4505,77.50))
        assertEquals(RouteGuidance.Status.READY, before.status)
        assertEquals("Turn right", before.instruction)
        assertEquals(55.6, before.distanceM, 2.0)
        val after = at(RoutePoint(28.451,77.5005), heading = Math.PI/2)
        assertEquals("Arrive", after.instruction)
        assertTrue(after.remainingM < before.remainingM)
    }
    @Test fun poorPositionOrBlackoutSuppressesInstructions() {
        assertEquals(RouteGuidance.Status.UNCERTAIN, at(a, reliable=false).status)
        assertEquals(RouteGuidance.Status.UNCERTAIN, at(a, sigma=21.0).status)
        assertEquals(RouteGuidance.Status.UNCERTAIN, at(a, age=3.0).status)
        assertEquals(RouteGuidance.Status.UNCERTAIN, at(a, sigma=Double.NaN).status)
    }
    @Test fun routeAssistedGuidanceHasAnIndependentConservativeUncertaintyGate() {
        val guidance = RouteGuidance(plan())
        val point = RoutePoint(28.4505,77.50)
        assertEquals(RouteGuidance.Status.READY,
            guidance.updateAssisted(point, 30.0, 0.0, 5.0).status)
        assertEquals(RouteGuidance.Status.UNCERTAIN,
            guidance.updateAssisted(point, 30.1, 0.0, 5.0).status)
        assertEquals(RouteGuidance.Status.WRONG_WAY,
            guidance.updateAssisted(point, 10.0, Math.PI, 5.0).status)
    }
    @Test fun offRouteAndOppositeHeadingDoNotInventATurn() {
        assertEquals(RouteGuidance.Status.OFF_ROUTE, at(RoutePoint(28.449,77.499)).status)
        assertEquals(RouteGuidance.Status.WRONG_WAY, at(RoutePoint(28.4505,77.50), heading=Math.PI).status)
    }
    @Test fun destinationRequiresProximityAndLowSpeed() {
        assertEquals(RouteGuidance.Status.NEAR_DESTINATION, at(c, speed=0.0).status)
        assertNotEquals(RouteGuidance.Status.NEAR_DESTINATION, at(c, speed=6.0, heading=Math.PI/2).status)
    }
    @Test fun legacyCacheRemainsReadableButPreviewOnly() {
        val legacy = plan().copy(steps=plan().steps.map { it.copy(maneuverPoint=null) })
        val restored = RoutePlan.fromCache(legacy.toJson())
        assertEquals(legacy, restored)
        assertEquals(RouteGuidance.Status.NO_DETAIL, RouteGuidance(restored).update(a,true,5.0,0.1,0.0,4.0).status)
        val current = plan()
        assertEquals(current, RoutePlan.fromCache(current.toJson()))
    }
    @Test fun overlappingOutAndReturnGeometryIsAmbiguous() {
        val loop = plan().copy(points=listOf(a,b,a), steps=listOf(RouteStep("Start",111.2,a),RouteStep("Return",111.2,b),RouteStep("Arrive",0.0,a)),distanceM=222.4)
        assertEquals(RouteGuidance.Status.AMBIGUOUS, RouteGuidance(loop).update(
            RoutePoint(28.4503,77.50),true,5.0,0.1,0.0,4.0).status)
    }

    @Test fun turnCuePlannerAddsDistanceAndLabelsRouteEstimates() {
        val approach = RouteGuidance.Result(
            RouteGuidance.Status.READY, 2, "Turn left · Main Road", 244.0, 800.0)
        assertEquals("In 240 meters, Turn left onto Main Road.",
            TurnCuePlanner.cue(approach, routeAssisted=false)?.text)
        assertEquals("Route estimate. In 240 meters, Turn left onto Main Road.",
            TurnCuePlanner.cue(approach, routeAssisted=true)?.text)
        val now = approach.copy(distanceM=20.0)
        assertEquals("Route estimate. Turn left onto Main Road now.",
            TurnCuePlanner.cue(now, routeAssisted=true)?.text)
        assertNull(TurnCuePlanner.cue(approach.copy(distanceM=251.0), false))
    }
}
