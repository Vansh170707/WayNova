package org.neuronavx.logger

import android.content.Context
import android.os.Build
import org.neuronavx.logger.nav.NavMode
import org.neuronavx.logger.nav.Alignment
import org.neuronavx.logger.nav.Navigator
import org.neuronavx.logger.nav.ReplaySource
import org.neuronavx.logger.nav.RuntimeConfig
import org.neuronavx.logger.nav.SpeedModel
import kotlin.math.roundToInt

/**
 * Repeatable Gate 5 benchmark for the deployed loop, suitable for running on a real phone.
 *
 * The bundled replay is 10 Hz, while live sensor callbacks arrive at 100 Hz. Each replay
 * row is therefore expanded into ten calls: one complete navigation update and nine calls
 * through the real integer decimator. The measured 100 ms cycle includes all ten calls,
 * feature construction, the speed model at its configured fusion cadence, ES-EKF updates,
 * blackout management and display smoothing. CSV parsing and drawing are deliberately out
 * of scope because they are replay/UI concerns rather than estimator work.
 */
class FullNavigationLoopBenchmark(
    private val context: Context,
    private val replayAsset: String = "replay_M_seg00.csv",
    /**
     * Pin the signature replay's audited whole-drive calibration. Calibration quality has
     * its own parity test; a timing benchmark must exercise the navigation/model loop even
     * when production quality gates intentionally hold a weak causal fit at 99%.
     */
    private val benchmarkAlignment: Alignment = Alignment(
        forwardAngleRad = -0.903331,
        forwardAccelScale = 0.108145,
        gyroBiasRadS = 0.00071078,
        yawChannel = 1,
        yawScale = -1.003535,
        forwardAccelCorr = 1.0,
        yawCorr = 1.0,
        samples = 0,
        baselines = 0,
    ),
) : AutoCloseable {

    data class Result(
        val medianMs: Double,
        val p95Ms: Double,
        val maxMs: Double,
        val meanMs: Double,
        val budgetMs: Double,
        val repeats: Int,
        val navigationCycles: Int,
        val calibrationCycles: Int,
        val unaidedCycles: Int,
        val rawSensorCallbacks: Int,
        val sensorRateHz: Double,
        val navigationRateHz: Double,
        val elapsedMs: Double,
        val device: String,
    ) {
        val p95BudgetUsedPct: Double
            get() = 100.0 * p95Ms / budgetMs

        override fun toString(): String = buildString {
            append("$device\n")
            append("%.0f Hz callbacks -> %.0f Hz navigation\n\n"
                .format(sensorRateHz, navigationRateHz))
            append("median %.3f ms | p95 %.3f ms | max %.3f ms\n"
                .format(medianMs, p95Ms, maxMs))
            append("mean %.3f ms | p95 uses %.3f%% of %.1f ms budget\n\n"
                .format(meanMs, p95BudgetUsedPct, budgetMs))
            append("%,d navigation cycles, %,d unaided\n"
                .format(navigationCycles, unaidedCycles))
            append("%,d calibration cycles, %,d raw sensor callbacks\n"
                .format(calibrationCycles, rawSensorCallbacks))
            append("$repeats replay passes in %.2f s".format(elapsedMs / 1000.0))
        }
    }

    private val runtime = RuntimeConfig.fromAsset(context)
    private val model = SpeedModel(context, runtime)

    fun run(repeats: Int = 5, sensorRateHz: Double = 100.0): Result {
        require(repeats > 0) { "repeats must be positive" }
        val callbacksPerCycle = (sensorRateHz / runtime.rateHz).roundToInt()
        require(callbacksPerCycle >= 1) { "sensor rate must be at least the navigation rate" }
        require(kotlin.math.abs(callbacksPerCycle * runtime.rateHz - sensorRateHz) < 1e-6) {
            "sensor rate must be an integer multiple of ${runtime.rateHz} Hz"
        }

        val navigationSamples = ArrayList<Double>(repeats * 4500)
        var calibrationCycles = 0
        var unaidedCycles = 0
        var rawCallbacks = 0
        val wallStart = System.nanoTime()

        repeat(repeats) {
            val navigator = Navigator(runtime, model, inputRateHz = sensorRateHz).apply {
                overrideCalibration(benchmarkAlignment)
            }
            ReplaySource(context.assets.open(replayAsset)).use { source ->
                while (true) {
                    val sample = source.next() ?: break
                    val cycleStart = System.nanoTime()
                    var emitted: Navigator.State? = null

                    // sub=0 is the real replay row and owns its fresh GNSS fix. The other
                    // callbacks model the 100 Hz sensor traffic that the live decimator
                    // receives between consecutive 10 Hz estimator updates.
                    for (sub in 0 until callbacksPerCycle) {
                        val state = navigator.addSample(
                            sample.t + sub / sensorRateHz,
                            sample.accel,
                            sample.gravity,
                            sample.gyro,
                            if (sub == 0) sample.fix else null,
                        )
                        if (state != null) emitted = state
                    }
                    rawCallbacks += callbacksPerCycle
                    val cycleMs = (System.nanoTime() - cycleStart) / 1e6
                    val state = emitted ?: continue
                    if (state.phase == Navigator.Phase.NAVIGATING) {
                        navigationSamples.add(cycleMs)
                        if (state.mode != NavMode.AIDED) unaidedCycles++
                    } else {
                        calibrationCycles++
                    }
                }
            }
        }

        require(navigationSamples.size > 1000) {
            "replay produced only ${navigationSamples.size} navigating cycles"
        }
        require(unaidedCycles > 0) { "replay never exercised the unaided loop" }
        navigationSamples.sort()
        val p95Index = ((navigationSamples.size - 1) * 0.95).roundToInt()
        val elapsedMs = (System.nanoTime() - wallStart) / 1e6
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown ABI"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}, $abi)"

        return Result(
            medianMs = navigationSamples[navigationSamples.size / 2],
            p95Ms = navigationSamples[p95Index],
            maxMs = navigationSamples.last(),
            meanMs = navigationSamples.average(),
            budgetMs = 1000.0 / runtime.rateHz,
            repeats = repeats,
            navigationCycles = navigationSamples.size,
            calibrationCycles = calibrationCycles,
            unaidedCycles = unaidedCycles,
            rawSensorCallbacks = rawCallbacks,
            sensorRateHz = sensorRateHz,
            navigationRateHz = runtime.rateHz,
            elapsedMs = elapsedMs,
            device = device,
        )
    }

    override fun close() = model.close()
}
