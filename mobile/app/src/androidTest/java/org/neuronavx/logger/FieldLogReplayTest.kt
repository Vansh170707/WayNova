package org.neuronavx.logger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.neuronavx.logger.nav.*
import java.io.File
import kotlin.math.hypot

/** Optional private-log validation. Logs stay outside shipped APKs and source control. */
@RunWith(AndroidJUnit4::class)
class FieldLogReplayTest {
    @Test fun replaySavedFieldSessions() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Supply fieldReplay=true and private fixtures to run field replay",
            args.getString("fieldReplay") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Internal storage first, then the app's external files directory. `run-as` is
        // unreliable on some vendor builds and a test reinstall wipes internal storage, so
        // the external location is the one that can actually be loaded and reloaded.
        val candidates = listOfNotNull(
            File(context.filesDir, "phase16-replay"),
            context.getExternalFilesDir(null)?.let { File(it, "phase16-replay") },
        )
        val dir = candidates.firstOrNull { d ->
            d.listFiles()?.any { it.name.startsWith("nav_summary_") } == true
        }
        checkNotNull(dir) { "Private fixtures missing in ${candidates.joinToString()}" }
        val summaries = checkNotNull(dir.listFiles()) { "Private fixtures missing in $dir" }
            .filter { it.name.startsWith("nav_summary_") }.sortedBy { it.name }
        assertTrue(summaries.isNotEmpty())
        val reports = JSONArray()
        val runtime = RuntimeConfig.fromAsset(context)
        SpeedModel(context, runtime).use { model ->
            for (file in summaries) {
                val summary = JSONObject(file.readText())
                val diagnostics = File(dir, summary.getString("diagnostics_file")).readLines()
                val head = diagnostics.first().split(',')
                fun value(row: List<String>, key: String) = row[head.indexOf(key)].toDoubleOrNull() ?: Double.NaN
                val dark = diagnostics.drop(1).map { it.split(',') }
                    .filter { it[head.indexOf("test_phase")] == "WITHHOLDING" }
                if (dark.isEmpty()) continue
                val begin = value(dark.first(), "timestamp_ms") / 1000.0 - value(dark.first(), "test_elapsed_s")
                val end = begin + 60.0
                // A held reference is scored once, on the first diagnostic row containing it.
                val refs = dark.filterIndexed { i, row ->
                    i == 0 || value(row, "reference_east_m") != value(dark[i-1], "reference_east_m") ||
                        value(row, "reference_north_m") != value(dark[i-1], "reference_north_m")
                }.filter { value(it, "reference_east_m").isFinite() }
                val n = Navigator(runtime, model, inputRateHz = 100.0)
                var cursor = 0
                val errors = ArrayList<Pair<Double, Double>>()
                var recoveredAt = Double.NaN
                var states = 0
                File(dir, summary.getString("raw_file")).bufferedReader().use { reader ->
                    val columns = reader.readLine().split(',')
                    reader.forEachLine { line ->
                        val cells = line.split(',')
                        fun v(key: String) = cells[columns.indexOf(key)].toDoubleOrNull() ?: Double.NaN
                        val t = v("timestamp_ms") / 1000.0
                        val fix = if (v("gps_fresh") == 1.0 && !(t >= begin && t < end))
                            GnssFix(v("lat"), v("lon"), v("alt"), v("speed"), v("accuracy"),
                                v("bearing"), v("bearing_acc")) else null
                        val state = n.addSample(t, doubleArrayOf(v("ax"), v("ay"), v("az")),
                            doubleArrayOf(v("grav_x"), v("grav_y"), v("grav_z")),
                            doubleArrayOf(v("gx"), v("gy"), v("gz")), fix)
                        if (state != null) {
                            states++
                            if (t >= end && t <= end + 10.0 && state.phase == Navigator.Phase.NAVIGATING &&
                                state.mode == NavMode.AIDED && !recoveredAt.isFinite()) recoveredAt = t
                            while (cursor < refs.size && t >= value(refs[cursor], "timestamp_ms") / 1000.0) {
                                assertTrue("Replay missed reference time", t - value(refs[cursor], "timestamp_ms") / 1000.0 < 0.2)
                                assertTrue("Replay had not calibrated before scoring", state.phase == Navigator.Phase.NAVIGATING)
                                errors.add(Pair(state.east - value(refs[cursor], "reference_east_m"),
                                    state.north - value(refs[cursor], "reference_north_m")))
                                cursor++
                            }
                        }
                    }
                }
                assertTrue("Not all references scored", errors.size == refs.size && errors.size >= 2)
                val drift = hypot(errors.last().first - errors.first().first, errors.last().second - errors.first().second)
                val distance = refs.zipWithNext().sumOf { (a,b) ->
                    hypot(value(b,"reference_east_m")-value(a,"reference_east_m"),
                        value(b,"reference_north_m")-value(a,"reference_north_m"))
                }
                reports.put(JSONObject().apply {
                    put("session_id",summary.getLong("session_id"))
                    put("incremental_drift_m", drift)
                    put("reference_distance_m", distance)
                    put("drift_pct", 100.0 * drift / distance)
                    put("reference_samples", errors.size)
                    put("recovery_s", if (recoveredAt.isFinite()) recoveredAt - end else JSONObject.NULL)
                    put("states",states)
                    put("final_phase",n.state?.phase?.name)
                    put("final_sigma_m",n.state?.sigmaM?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                })
            }
        }
        File(dir,"replay_results.json").writeText(reports.toString(2))
        // Also write where a plain `adb shell cat` can reach it. `run-as` is unreliable on
        // some vendor builds (this vivo returns "packagelist_parse failed" intermittently),
        // and an unreadable result makes the harness useless exactly when it is needed.
        context.getExternalFilesDir(null)?.let {
            File(it, "replay_results.json").writeText(reports.toString(2))
        }
    }
}
