package org.neuronavx.logger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.neuronavx.logger.nav.*

@RunWith(AndroidJUnit4::class)
class LiveRobustnessTest {
    private val gravity = doubleArrayOf(0.0, 0.0, 9.80665)
    private val zero = DoubleArray(3)

    private fun navigator() = Navigator(
        RuntimeConfig.fromAsset(InstrumentationRegistry.getInstrumentation().targetContext),
        SpeedPredictionModel { SpeedEstimate(10.0, 2.0) }, inputRateHz = 100.0,
    ).apply {
        overrideCalibration(Alignment(0.0, 1.0, 0.0, 2, -1.0, 1.0, 1.0, 1000, 1000),
            HeadingRateSource.GRAVITY_PROJECTED)
    }

    @Test fun correctionFollowsChangedDrivingSpeed() {
        val a = LiveSpeedAdapter()
        repeat(50) { i ->
            a.observeTrustedSpeed(i.toDouble(), 10.0, 3.0)
            a.observeAidedPrediction(SpeedEstimate(20.0, 1.0), 10.0, 3.0)
        }
        repeat(5) { i ->
            a.observeTrustedSpeed(50.0 + i, 3.0, 3.0)
            a.observeAidedPrediction(SpeedEstimate(6.0, 1.0), 3.0, 3.0)
        }
        assertTrue(a.isReady)
        assertEquals(3.0, a.biasMs, 1e-9)
        assertEquals(5.0, a.adapt(56.0, SpeedEstimate(8.0, 1.0)).speed, 1e-9)
        assertFalse(a.observeAidedPrediction(SpeedEstimate(8.0, 1.0), 5.0, Double.NaN))
    }

    @Test fun quietCruisingCannotBecomeStationaryAndMovementReleasesStop() {
        val gate = StationaryHold()
        for (i in 0..500) gate.add(i / 100.0, gravity, gravity, zero)
        assertFalse(gate.active)
        gate.observeFix(5.0, 5.0, 3.0)
        assertFalse(gate.active)
        for (i in 6..8) gate.observeFix(i.toDouble(), 0.0, 3.0)
        assertTrue(gate.active)
        for (i in 501..600) gate.add(i / 100.0, doubleArrayOf(1.0, 0.0, 9.80665), gravity, zero)
        assertFalse("a departure remained clamped to zero", gate.active)
    }

    @Test fun stationaryOutageIgnoresBiasedModel() {
        val n = navigator()
        var stop: Navigator.State? = null
        for (i in 0..7500) {
            val fix = if (i <= 1500 && i % 100 == 0)
                GnssFix(28.45, 77.5, 0.0, 0.0, 3.0, 0.0) else null
            n.addSample(i / 100.0, gravity, gravity, zero, fix)?.let { stop = it }
        }
        assertEquals(NavMode.BLACKOUT, stop!!.mode)
        assertEquals(0.0, stop!!.speed, 0.01)
        assertTrue(kotlin.math.hypot(stop!!.east, stop!!.north) < 0.5)
    }

    @Test fun longGapRequiresFreshCalibrationAndInvalidatesTest() {
        val n = navigator()
        val test = FieldBlackoutTest()
        test.arm(60.0, 0.0)
        for (i in 0..1000) {
            val t = i / 100.0
            test.beforeSample(t, n.state?.phase)
            val fix = if (i % 100 == 0) GnssFix(28.45, 77.5, 0.0, 5.0, 3.0, 0.0) else null
            n.addSample(t, gravity, gravity, zero, fix)?.let { test.observeState(it) }
        }
        test.beforeSample(1490.0, n.state?.phase)
        val resumed = n.addSample(1490.0, gravity, gravity, zero, null)!!
        test.observeState(resumed)
        assertEquals(Navigator.Phase.CALIBRATING, resumed.phase)
        assertTrue(resumed.sigmaM.isNaN())
        assertTrue(n.trackEast.isEmpty())
        assertEquals(0, resumed.speedModelCalibrationSamples)
        assertEquals(FieldBlackoutPhase.INTERRUPTED, test.snapshot().phase)
        assertFalse(test.snapshot().recovered)
        assertTrue(test.snapshot().actualDurationS < 11.0)
        assertNull(n.addSample(1480.0, gravity, gravity, zero, null))
    }
}
