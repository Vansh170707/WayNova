package org.neuronavx.logger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.rule.GrantPermissionRule
import android.Manifest
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import org.neuronavx.logger.nav.Alignment
import org.neuronavx.logger.nav.LiveSource
import org.neuronavx.logger.nav.Navigator
import org.neuronavx.logger.nav.RuntimeConfig

/**
 * On-device checks that can run without a driver, a car, or GPS reception.
 *
 * These are the parts of the deployment path that are worth verifying automatically:
 * that the shipped model actually loads and runs from the APK's assets, that its output
 * is sane, and that the logger writes the schema the desktop pipeline parses. Latency
 * measured on an emulator is meaningless for the blueprint's edge acceptance gate -- that
 * needs real hardware -- so nothing here asserts a timing bound.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceTest {

    @get:Rule
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun modelLoadsFromApkAssetsAndRuns() {
        val bench = SpeedModelBenchmark(context)
        try {
            val result = bench.run(iterations = 30, warmup = 5)
            // a speed model that returns nothing, or takes a whole second, is broken
            assertTrue("no timing recorded", result.medianMs > 0.0)
            assertTrue("implausibly slow: ${result.medianMs} ms", result.medianMs < 1000.0)
        } finally {
            bench.close()
        }
    }

    @Test
    fun modelOutputIsAPlausibleSpeed() {
        val bench = SpeedModelBenchmark(context)
        try {
            val speed = bench.inferOnce(FloatArray(bench.features * bench.windowSamples))
            assertTrue("speed must be finite", speed.isFinite())
            // the head is softplus, so it cannot go negative; an all-zero window should
            // land somewhere in ordinary driving range rather than at an extreme
            assertTrue("negative speed: $speed", speed >= 0f)
            assertTrue("absurd speed: $speed", speed < 100f)
        } finally {
            bench.close()
        }
    }

    @Test
    fun loggerWritesTheSchemaTheDesktopPipelineReads() {
        val logger = SensorLogger(context)
        val file = logger.start()
        Thread.sleep(2500)          // let real sensor callbacks accumulate rows
        logger.stop()
        Thread.sleep(500)           // writer thread drains

        assertTrue("no file written", file.exists())
        val lines = file.readLines()
        assertTrue("expected a header and some rows, got ${lines.size}", lines.size > 1)

        // `bearing_acc` was added in Phase 7. It is how far the filter is willing to move
        // on a reported course; without it the estimator falls back to a conservative
        // constant, under-trusts a good Doppler bearing, and accumulates heading error.
        // `own_drive.py` reads it as optional so logs recorded before it still load.
        val expected = ("timestamp_ms,ax,ay,az,gx,gy,gz,mx,my,mz,grav_x,grav_y,grav_z," +
            "lat,lon,alt,speed,accuracy,bearing,bearing_acc,gps_fresh")
        assertEquals("header drifted from the desktop loader", expected, lines[0])

        // every row must have one field per header column, or pandas will misalign them
        val columns = expected.split(",").size
        lines.drop(1).take(50).forEach { row ->
            assertEquals("wrong field count in: $row", columns, row.split(",").size)
        }

        // the emulator supplies synthetic sensors, so values should still be parseable
        val first = lines[1].split(",")
        assertTrue("timestamp not numeric", first[0].toDoubleOrNull() != null)
        // permission was granted by the rule, so GNSS must have started
        assertTrue("location did not start despite permission", logger.locationAvailable)
        file.delete()
    }

    @Test
    fun liveNavigationAutomaticallyWritesFieldEvidence() {
        val runtime = RuntimeConfig.fromAsset(context)
        val navigator = Navigator(runtime, model = null, inputRateHz = 100.0).apply {
            overrideCalibration(Alignment(
                forwardAngleRad = 0.0,
                forwardAccelScale = 1.0,
                gyroBiasRadS = 0.0,
                yawChannel = 2,
                yawScale = 1.0,
                forwardAccelCorr = 1.0,
                yawCorr = 1.0,
                samples = 1_000,
                baselines = 1_000,
            ))
        }
        val source = LiveSource(context, navigator, onState = {})
        var stopped = false
        try {
            source.start()
            source.onLocationChanged(Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 28.45
                longitude = 77.50
                altitude = 200.0
                speed = 8.0f
                accuracy = 3.0f
                bearing = 0.0f
                bearingAccuracyDegrees = 2.0f
            })
            Thread.sleep(1_500)
            val summary = source.stop() ?: error("live recorder produced no summary")
            stopped = true

            assertTrue("raw field log missing", summary.rawFile.exists())
            assertTrue("diagnostics log missing", summary.diagnosticsFile.exists())
            assertTrue("session summary missing", summary.summaryFile.exists())
            assertTrue("too few raw sensor rows: ${summary.imuSamples}", summary.imuSamples > 20)
            assertTrue("injected phone fix was not recorded", summary.phoneFixes >= 1)
            assertTrue("injected phone fix was not fused", summary.fusedFixes >= 1)
            assertTrue("no estimator states recorded", summary.stateCount > 2)

            summary.rawFile.bufferedReader().use {
                assertEquals("raw schema drifted", DriveCsvRecorder.HEADER, it.readLine())
                assertTrue("raw field log has no data", it.readLine() != null)
            }
            summary.diagnosticsFile.bufferedReader().use {
                assertEquals(
                    "diagnostics schema drifted",
                    org.neuronavx.logger.nav.LiveNavigationRecorder.DIAGNOSTICS_HEADER,
                    it.readLine(),
                )
                assertTrue("diagnostics log has no data", it.readLine() != null)
            }
            val json = JSONObject(summary.summaryFile.readText())
            assertEquals(summary.rawFile.name, json.getString("raw_file"))
            assertEquals(summary.diagnosticsFile.name, json.getString("diagnostics_file"))
            assertTrue(json.getLong("imu_samples") > 20)
            assertTrue(json.getLong("phone_fixes") >= 1)

            summary.rawFile.delete()
            summary.diagnosticsFile.delete()
            summary.summaryFile.delete()
        } finally {
            if (!stopped) source.stop()?.let {
                it.rawFile.delete()
                it.diagnosticsFile.delete()
                it.summaryFile.delete()
            }
        }
    }

    @Test
    fun bundledGreaterNoidaOfflineMapIsComplete() {
        val archive = "offline_map/greater_noida.pmtiles"
        context.assets.openFd(archive).use { descriptor ->
            assertTrue("city archive is unexpectedly tiny", descriptor.length > 5 * 1024 * 1024)
        }
        context.assets.open(archive).use { input ->
            val header = ByteArray(8)
            assertEquals("short PMTiles header", 8, input.read(header))
            assertEquals("wrong PMTiles magic", "PMTiles", header.copyOfRange(0, 7).decodeToString())
            assertEquals("archive must use PMTiles v3", 3, header[7].toInt())
        }
        val requiredAssets = listOf(
            "offline_map/style.json",
            "offline_map/sprites/dark.png",
            "offline_map/sprites/dark@2x.png",
            "offline_map/glyphs/Noto Sans Regular/0-255.pbf",
            "offline_map/glyphs/Noto Sans Devanagari Regular v1/2304-2559.pbf",
        )
        requiredAssets.forEach { asset ->
            context.assets.open(asset).use { input ->
                assertTrue("missing/empty offline asset: $asset", input.read() >= 0)
            }
        }
    }
}
