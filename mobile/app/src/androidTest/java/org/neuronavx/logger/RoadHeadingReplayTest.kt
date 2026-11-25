package org.neuronavx.logger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.neuronavx.logger.nav.*
import java.io.File
import kotlin.math.hypot

/**
 * Paired A/B replay of private Greater Noida drives: original estimator vs road-heading aid.
 *
 * Both navigators see the identical sample stream. After calibration, GNSS is withheld
 * from both for 60 s windows separated by 60 s of aiding. The withheld phone fixes are the
 * hidden reference (phone GPS, not survey truth). Fixtures stay outside APKs and git;
 * this runs only through the emulator-only runner.
 */
@RunWith(AndroidJUnit4::class)
class RoadHeadingReplayTest {
    private class Arm(val name: String, val nav: Navigator) {
        var errStart: Pair<Double, Double>? = null
        var last: Navigator.State? = null
        var updatesAtStart = 0
        val statusCounts = HashMap<String, Int>()
        var speedErrSum = 0.0; var speedErrN = 0
        var headErrSum = 0.0; var headErrN = 0
        var lastHeadErrDeg = Double.NaN
        fun resetWindow() {
            statusCounts.clear(); speedErrSum = 0.0; speedErrN = 0
            headErrSum = 0.0; headErrN = 0; lastHeadErrDeg = Double.NaN
        }
    }

    private fun wrapDeg(a: Double): Double { var x = (a + 180.0) % 360.0; if (x < 0) x += 360.0; return x - 180.0 }

    @Test fun pairedBlackoutWindowsOnOwnDrives() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Supply roadHeadingReplay=true and private fixtures", args.getString("roadHeadingReplay") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "road-heading-replay")
        val drives = dir.listFiles { f -> f.name.matches(Regex("nav_drive_[0-9]+\\.csv")) }?.sortedBy { it.name }.orEmpty()
        assertTrue("No fixtures in $dir", drives.isNotEmpty())
        val roads = context.assets.open(RoadNetwork.ASSET).use(RoadNetwork::read)
        val runtime = RuntimeConfig.fromAsset(context)
        val windows = JSONArray()
        // traceOnly=true: baseline arm only, plus a 10 Hz speed trace inside every window.
        val traceOnly = args.getString("traceOnly") == "true"
        val trace = StringBuilder("drive,window_start_s,t_rel,mode,est_speed,learned,adapted,hold,gps_speed,gps_age_s\n")
        SpeedModel(context, runtime).use { model ->
            for (drive in drives) {
                val arms = listOf(
                    Arm("baseline", Navigator(runtime, model, inputRateHz = 100.0)),
                    // Run 1 configuration, kept as the reference arm.
                    Arm("road_heading", Navigator(runtime, model, inputRateHz = 100.0,
                        roadHeadingAid = RoadHeadingAid(roads))),
                    // Run 3: smoothed yaw rate for the turning gate, 20 m minimum road evidence.
                    Arm("road_v2", Navigator(runtime, model, inputRateHz = 100.0,
                        roadHeadingAid = RoadHeadingAid(roads, RoadHeadingAid.Config(
                            smoothYawRate = true, minTotalLengthM = 20.0)))),
                    // As v2, but the update corrects heading only, never position directly.
                    Arm("road_v2_heading_only", Navigator(runtime, model, inputRateHz = 100.0,
                        roadHeadingAid = RoadHeadingAid(roads, RoadHeadingAid.Config(
                            smoothYawRate = true, minTotalLengthM = 20.0, headingOnly = true)))),
                ).let { if (traceOnly) it.take(1) else it }
                var navT = Double.NaN
                var windowStart = Double.NaN
                var distance = 0.0
                var prevRef: Pair<Double, Double>? = null
                var refHeading = Double.NaN
                var gpsSpeed = Double.NaN; var gpsT = Double.NEGATIVE_INFINITY
                drive.bufferedReader().use { reader ->
                    val columns = reader.readLine().split(',')
                    val idx = columns.withIndex().associate { it.value to it.index }
                    reader.forEachLine { line ->
                        val c = line.split(',')
                        fun v(k: String) = c.getOrNull(idx.getValue(k))?.toDoubleOrNull() ?: Double.NaN
                        val t = v("timestamp_ms") / 1000.0
                        if (!t.isFinite()) return@forEachLine
                        val hasFix = v("gps_fresh") == 1.0 && v("lat").isFinite() && v("lon").isFinite()
                        val origin = arms[0].nav
                        val ref = if (hasFix && origin.originLat.isFinite()) origin.toEnu(v("lat"), v("lon")) else null

                        if (windowStart.isFinite() && ref != null) {
                            prevRef?.let {
                                val step = hypot(ref.first - it.first, ref.second - it.second)
                                distance += step
                                if (step > 1.0) refHeading = kotlin.math.atan2(ref.first - it.first, ref.second - it.second)
                            }
                            prevRef = ref
                            // Close on the first hidden fix at or after 60 s, scored before it is fused.
                            if (t >= windowStart + 60.0) {
                                val result = JSONObject().put("drive", drive.name)
                                    .put("start_s", windowStart - navT).put("distance_m", distance)
                                for (arm in arms) {
                                    val s = arm.last!!
                                    val e0 = arm.errStart!!
                                    val drift = hypot(s.east - ref.first - e0.first, s.north - ref.second - e0.second)
                                    result.put("${arm.name}_drift_m", drift)
                                    result.put("${arm.name}_drift_pct", if (distance > 0) 100 * drift / distance else JSONObject.NULL)
                                    result.put("${arm.name}_mode", s.mode.name)
                                    result.put("${arm.name}_road_updates", arm.nav.roadHeadingUpdates - arm.updatesAtStart)
                                    val ex = s.east - ref.first - e0.first; val ey = s.north - ref.second - e0.second
                                    if (refHeading.isFinite()) {
                                        // Along = signed error in the direction of travel (+ ahead); cross = right of travel.
                                        result.put("${arm.name}_along_m", ex * kotlin.math.sin(refHeading) + ey * kotlin.math.cos(refHeading))
                                        result.put("${arm.name}_cross_m", ex * kotlin.math.cos(refHeading) - ey * kotlin.math.sin(refHeading))
                                    }
                                    result.put("${arm.name}_mean_speed_err_ms", if (arm.speedErrN > 0) arm.speedErrSum / arm.speedErrN else JSONObject.NULL)
                                    result.put("${arm.name}_mean_abs_heading_err_deg", if (arm.headErrN > 0) arm.headErrSum / arm.headErrN else JSONObject.NULL)
                                    result.put("${arm.name}_end_heading_err_deg", if (arm.lastHeadErrDeg.isFinite()) arm.lastHeadErrDeg else JSONObject.NULL)
                                    result.put("${arm.name}_status", JSONObject(arm.statusCounts as Map<*, *>))
                                }
                                windows.put(result)
                                windowStart = Double.NaN
                            }
                        }

                        val dark = windowStart.isFinite()
                        if (hasFix && v("speed").isFinite()) { gpsSpeed = v("speed"); gpsT = t }
                        val fix = if (hasFix && !dark) GnssFix(v("lat"), v("lon"), v("alt"), v("speed"),
                            v("accuracy"), v("bearing"), v("bearing_acc")) else null
                        val accel = doubleArrayOf(v("ax"), v("ay"), v("az"))
                        val grav = doubleArrayOf(v("grav_x"), v("grav_y"), v("grav_z"))
                        val gyro = doubleArrayOf(v("gx"), v("gy"), v("gz"))
                        for (arm in arms) arm.nav.addSample(t, accel, grav, gyro, fix)?.let { st ->
                            arm.last = st
                            if (dark && arm === arms[0] && traceOnly) trace.append(drive.name).append(',')
                                .append("%.1f".format(windowStart - navT)).append(',').append("%.2f".format(t - windowStart)).append(',')
                                .append(st.mode.name).append(',').append("%.3f".format(st.speed)).append(',')
                                .append("%.3f".format(st.learnedSpeed)).append(',').append("%.3f".format(st.adaptedSpeed)).append(',')
                                .append(if (st.stationaryHoldActive) 1 else 0).append(',')
                                .append(if (t - gpsT <= 1.1) "%.3f".format(gpsSpeed) else "").append(',')
                                .append("%.2f".format(t - gpsT)).append('\n')
                            if (dark) {
                                val key = arm.nav.lastRoadHeadingStatus?.name ?: "NONE"
                                arm.statusCounts[key] = (arm.statusCounts[key] ?: 0) + 1
                            }
                        }
                        // Hidden fixes inside a window measure speed and heading error (phone GPS as reference).
                        if (dark && hasFix && v("speed").isFinite() && v("speed") > 3.0) for (arm in arms) {
                            val st = arm.last ?: continue
                            arm.speedErrSum += st.speed - v("speed"); arm.speedErrN++
                            if (v("bearing").isFinite()) {
                                val he = wrapDeg(Math.toDegrees(st.heading) - v("bearing"))
                                arm.headErrSum += kotlin.math.abs(he); arm.headErrN++; arm.lastHeadErrDeg = he
                            }
                        }
                        if (!navT.isFinite() && arms.all { it.last?.phase == Navigator.Phase.NAVIGATING }) navT = t

                        // Open a window on a fresh fused fix so the start error is measured.
                        if (navT.isFinite() && !windowStart.isFinite() && ref != null &&
                            t >= nextWindowAt(navT, drive.name, windows) &&
                            arms.all { it.last?.mode == NavMode.AIDED }) {
                            windowStart = t; prevRef = ref; distance = 0.0
                            for (arm in arms) {
                                val s = arm.last!!
                                arm.errStart = Pair(s.east - ref.first, s.north - ref.second)
                                arm.updatesAtStart = arm.nav.roadHeadingUpdates
                                arm.resetWindow()
                            }
                            refHeading = Double.NaN
                        }
                    }
                }
            }
        }
        val out = JSONObject().put("windows", windows)
        File(dir, "road_heading_results.json").writeText(out.toString(2))
        if (traceOnly) File(dir, "speed_trace.csv").writeText(trace.toString())
        context.getExternalFilesDir(null)?.let { File(it, "road_heading_results.json").writeText(out.toString(2)) }
        assertTrue("No windows scored", windows.length() > 0)
    }

    /** Window k of a drive starts 20 s + 120 s * k after both arms began navigating. */
    private fun nextWindowAt(navT: Double, drive: String, all: JSONArray): Double {
        var k = 0
        for (i in 0 until all.length()) if (all.getJSONObject(i).getString("drive") == drive) k++
        return navT + 20.0 + 120.0 * k
    }
}
