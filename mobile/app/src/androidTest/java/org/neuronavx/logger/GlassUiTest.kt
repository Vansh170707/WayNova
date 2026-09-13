package org.neuronavx.logger

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.neuronavx.logger.ui.AvailabilityBar
import java.io.File
import kotlin.math.abs

/**
 * Covers the screens added with the liquid-glass redesign, and the two pieces of real
 * logic behind them: the preference store and the diagnostics-CSV reader that feeds the
 * analytics charts.
 */
@RunWith(AndroidJUnit4::class)
class GlassUiTest {

    private fun labels(view: View): List<String> =
        (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList())

    @Test fun tripsScreenOffersItsThreeTabs() {
        ActivityScenario.launch(TripsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val text = labels(activity.window.decorView)
                assertTrue(text.contains("Overview"))
                assertTrue(text.contains("Analytics"))
                assertTrue(text.contains("System"))
            }
        }
    }

    @Test fun diagnosticsReportsProbedSubsystems() {
        ActivityScenario.launch(DiagnosticsActivity::class.java).use { scenario ->
            // The probes run off the main thread, so let the activity settle first.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(1_500)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val text = labels(activity.window.decorView)
                assertTrue(text.contains("Accelerometer"))
                assertTrue(text.contains("Gyroscope"))
                assertTrue(text.contains("Speed TCN"))
                assertTrue(text.contains("Offline basemap"))
            }
        }
    }

    @Test fun settingsExposesOfflineMapAndPreferences() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val text = labels(activity.window.decorView)
                assertTrue(text.contains("Greater Noida"))
                assertTrue(text.contains("Imperial units"))
                assertTrue(text.contains("Keep screen on"))
                assertTrue(text.contains("Voice co-pilot"))
            }
        }
    }

    @Test fun unitPreferenceConvertsSpeedWithoutTouchingStoredValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = Prefs.useMph(context)
        try {
            Prefs.setUseMph(context, false)
            assertEquals("KM/H", Prefs.speedLabel(context))
            assertEquals(36.0, Prefs.speedInPreferredUnit(context, 10.0), 1e-6)

            Prefs.setUseMph(context, true)
            assertEquals("MPH", Prefs.speedLabel(context))
            assertEquals(22.36936, Prefs.speedInPreferredUnit(context, 10.0), 1e-4)
        } finally {
            Prefs.setUseMph(context, original)
        }
    }

    @Test fun tripTraceReadsDistanceAndBlackoutFromDiagnosticsCsv() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "trip-trace-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val sessionId = 1_700_000_000_000L

        // Ten seconds at a constant 10 m/s, the middle four of them in blackout.
        val rows = StringBuilder("timestamp_ms,mode,heading_deg,speed_ms\n")
        for (step in 0..100) {
            val timeMs = sessionId + step * 100L
            val mode = if (step in 30..70) "BLACKOUT" else "AIDED"
            rows.append("$timeMs,$mode,90.0,10.0\n")
        }
        File(directory, "nav_diagnostics_$sessionId.csv").writeText(rows.toString())

        val trace = TripTrace.load(directory, sessionId)
        assertTrue(trace.hasTrace)
        assertEquals(101, trace.samples)
        // 100 steps of 0.1 s at 10 m/s is 100 m.
        assertTrue(abs(trace.distanceKm - 0.1) < 1e-6)
        assertTrue(abs(trace.averageSpeedKph - 36.0) < 0.5)
        assertTrue(abs(trace.topSpeedKph - 36.0) < 0.01)
        assertTrue(abs(trace.blackoutSeconds - 4.0) < 0.15)
        assertTrue(trace.availability.contains(AvailabilityBar.Slice.BLACKOUT))
        assertTrue(trace.availability.contains(AvailabilityBar.Slice.AVAILABLE))

        directory.deleteRecursively()
    }

    @Test fun tripTraceIsEmptyWhenNoDiagnosticsExist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val trace = TripTrace.load(File(context.cacheDir, "absent"), 42L)
        assertFalse(trace.hasTrace)
        assertEquals(0, trace.samples)
    }
}
