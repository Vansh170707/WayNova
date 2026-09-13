package org.neuronavx.logger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.neuronavx.logger.nav.SavedRide
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class SavedRideTest {
    @Test fun recordedRideLaunchIsNotALiveRecording() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = 9223372036854775000L
        val raw = File(context.getExternalFilesDir(null),"nav_drive_$id.csv")
        val diagnostic = File(context.getExternalFilesDir(null),"nav_diagnostics_$id.csv")
        require(!raw.exists() && !diagnostic.exists()) { "Synthetic test fixture already exists" }
        try {
            raw.writeText("lat,lon,gps_fresh\n28.45,77.5,1\n")
            diagnostic.writeText("timestamp_ms,phase,mode,display_east_m,display_north_m,heading_deg,speed_ms,sigma_m\n" +
                "1000,NAVIGATING,AIDED,12,34,90,4,5\n1200,NAVIGATING,BLACKOUT,13,35,90,4,6\n")
            val intent = android.content.Intent(context, NavigationActivity::class.java).putExtra("saved_ride_id",id)
            androidx.test.core.app.ActivityScenario.launch<NavigationActivity>(intent).use { scenario ->
                Thread.sleep(2000)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    fun field(name: String) = NavigationActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
                    assertNull(field("live"))
                    val snapshot = field("latestMapSnapshot") as org.neuronavx.logger.nav.NavigationMapSnapshot
                    assertEquals(13.0,snapshot.markerEast,0.0)
                    assertTrue(snapshot.inOutage)
                    assertFalse(File(context.getExternalFilesDir(null),"nav_summary_$id.json").exists())
                }
            }
        } finally { raw.delete(); diagnostic.delete() }
    }

    @Test fun replaysOriginalDisplayCoordinatesAndPreservesGaps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(),"saved-ride-test-").toFile()
        try {
            File(directory,"nav_drive_123.csv").writeText("lat,lon,gps_fresh\n0,0,0\n28.45,77.5,1\n28.46,77.6,1\n")
            File(directory,"nav_diagnostics_123.csv").writeText(
                "timestamp_ms,phase,mode,display_east_m,display_north_m,heading_deg,speed_ms,sigma_m\n" +
                "0,CALIBRATING,AIDED,NaN,NaN,NaN,0,0\n" +
                "1000,NAVIGATING,AIDED,12,34,90,4,5\n" +
                "9000,NAVIGATING,BLACKOUT,56,78,180,3,40\n" +
                "8000,NAVIGATING,AIDED,999,999,0,3,5\n")
            val ride = SavedRide.load(directory,123)
            assertEquals(28.45,ride.origin.lat,0.0)
            assertEquals(2,ride.frames.size)
            assertEquals(12.0,ride.frames.first().east,0.0)
            assertEquals(Math.PI/2,ride.frames.first().heading,1e-9)
            assertEquals("BLACKOUT",ride.frames.last().mode)
            assertEquals(8000.0,ride.frames.last().timeMs-ride.frames.first().timeMs,0.0)
            File(directory,"nav_drive_123.csv").writeText("lat,lon,gps_fresh\n28.45,77.5,0\n")
            assertTrue(runCatching { SavedRide.load(directory,123) }.isFailure)
        } finally { directory.deleteRecursively() }
    }
}
