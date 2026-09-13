package org.neuronavx.logger

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.neuronavx.logger.nav.*
import kotlin.math.cos

@RunWith(AndroidJUnit4::class)
class RouteConstraintTest {
    @Test fun diagnosticsKeepOriginalCoordinatesAndAssistanceSeparate() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val n = Navigator(RuntimeConfig.fromAsset(context),null,inputRateHz=100.0).apply {
            overrideCalibration(Alignment(0.0,1.0,0.0,2,-1.0,1.0,1.0,1000,1000),HeadingRateSource.GRAVITY_PROJECTED)
        }
        repeat(101) { i -> n.addSample(i/100.0,doubleArrayOf(0.0,0.0,9.80665),
            doubleArrayOf(0.0,0.0,9.80665),DoubleArray(3),
            if (i%100==0) GnssFix(28.45,77.5,0.0,5.0,3.0,0.0) else null) }
        val state = n.state!!.copy(east=12.0,north=34.0,displayEast=13.0,displayNorth=35.0)
        val recorder=LiveNavigationRecorder(context)
        recorder.start(android.os.SystemClock.elapsedRealtimeNanos())
        recorder.recordState(state,routeAssistance=RouteConstraint.Result("ASSISTED",p(0.0,40.0),40.0,20.0))
        val summary=recorder.stop(true)!!
        try {
            val rows=summary.diagnosticsFile.readLines().map { it.split(',') }
            assertEquals(rows[0].size,rows[1].size)
            fun value(key: String)=rows[1][rows[0].indexOf(key)]
            assertEquals("12.000000",value("east_m"))
            assertEquals("13.000000",value("display_east_m"))
            assertEquals("ASSISTED",value("route_assist_status"))
            assertEquals(77.5,value("route_assist_lon").toDouble(),1e-6)
            assertEquals(40.0,value("route_progress_m").toDouble(),0.0)
        } finally {
            summary.rawFile.delete(); summary.diagnosticsFile.delete(); summary.summaryFile.delete()
        }
    }
    private fun p(e: Double, n: Double) = RoutePoint(28.45+Math.toDegrees(n/6371000),
        77.5+Math.toDegrees(e/(6371000*cos(Math.toRadians(28.45)))))
    private fun engine(points: List<RoutePoint> = listOf(p(0.0,0.0),p(0.0,1000.0))) =
        RouteConstraint(RouteGuidance(RoutePlan("Synthetic test",points, emptyList(),1000.0,100.0)))
    private fun anchor(c: RouteConstraint, north: Double = 20.0, speed: Double = 5.0) {
        assertEquals("ACQUIRING_ANCHOR",c.update(0.0,p(0.0,north),speed,0.0,5.0,true).status)
        assertEquals("ARMED",c.update(1.0,p(0.0,north),speed,0.0,5.0,true).status)
    }
    @Test fun integratesSpeedOnRoadWithoutRewritingRawPosition() {
        val c = engine(); anchor(c)
        val raw = p(10.0,25.0)
        val r = c.update(2.0,raw,5.0,0.0,12.0,false)
        assertEquals("ASSISTED",r.status)
        assertEquals(25.0,r.progressM,0.05)
        assertEquals(77.5,r.point!!.lon,1e-8)
        assertNotEquals(raw.lon,r.point.lon,1e-8)
        assertTrue(r.uncertaintyM >= 12.0)
    }
    @Test fun requiresGPSAnchorAndReleasesOnWrongTurnUntilGPSReturns() {
        val c=engine()
        assertEquals("NO_ANCHOR",c.update(0.0,p(0.0,0.0),5.0,0.0,5.0,false).status)
        anchor(c)
        assertEquals("HEADING_CONFLICT",c.update(2.0,p(0.0,25.0),5.0,Math.PI/2,5.0,false).status)
        assertEquals("NO_ANCHOR",c.update(3.0,p(0.0,30.0),5.0,0.0,5.0,false).status)
        assertEquals("ACQUIRING_ANCHOR",c.update(4.0,p(0.0,35.0),5.0,0.0,5.0,true).status)
        assertEquals("ARMED",c.update(5.0,p(0.0,40.0),5.0,0.0,5.0,true).status)
    }
    @Test fun followsBendOnlyWithCompatibleHeading() {
        val points=listOf(p(0.0,0.0),p(0.0,100.0),p(100.0,100.0))
        val c=engine(points); anchor(c,95.0)
        val r=c.update(2.0,p(5.0,100.0),5.0,Math.PI/2,5.0,false)
        assertEquals("ASSISTED",r.status)
        assertEquals(100.0,r.progressM,0.05)
        val wrong=engine(points); anchor(wrong,96.0)
        assertEquals("HEADING_CONFLICT",wrong.update(2.0,p(0.0,101.0),5.0,0.0,5.0,false).status)
    }
    @Test fun rejectsOverlapsGapsRecoveryAndLargeUncertainty() {
        val loop=engine(listOf(p(0.0,0.0),p(0.0,100.0),p(0.0,0.0)))
        assertEquals("AMBIGUOUS_ROUTE",loop.update(0.0,p(0.0,20.0),5.0,0.0,5.0,true).status)
        val gap=engine(); anchor(gap)
        assertEquals("SAMPLE_GAP",gap.update(5.0,p(0.0,40.0),5.0,0.0,5.0,false).status)
        val recovery=engine(); anchor(recovery)
        assertEquals("GPS_RECOVERY",recovery.update(2.0,p(0.0,25.0),5.0,0.0,5.0,false,true).status)
        val uncertain=engine(); anchor(uncertain)
        assertEquals("UNCERTAINTY_LIMIT",uncertain.update(2.0,p(0.0,25.0),5.0,0.0,61.0,false).status)
    }
    @Test fun stopsWithoutInventingProgressAndDoesNotClaimArrival() {
        val stopped=engine(); anchor(stopped,speed=0.0)
        assertEquals(20.0,stopped.update(2.0,p(0.0,20.0),0.0,0.0,5.0,false).progressM,0.05)
        val end=engine(listOf(p(0.0,0.0),p(0.0,30.0))); anchor(end,26.0)
        assertEquals("ROUTE_END_UNCONFIRMED",end.update(2.0,p(0.0,31.0),5.0,0.0,5.0,false).status)
    }
}
