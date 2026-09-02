package org.neuronavx.logger

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * On-device latency benchmark for the speed TCN -- the measurement Gate 5 actually needs.
 *
 * The blueprint's edge acceptance gate is explicit that desktop inference speed does not
 * count: the model is finale-ready only when it sustains the target update rate on real
 * hardware with the full sensor/fusion loop live. Desktop profiling put the whole loop at
 * 0.74 ms against a 100 ms budget, so the question here is not whether it fits but by how
 * much, and whether the p95 stays flat under thermal load.
 *
 * ONNX Runtime is used because it was both the fastest desktop path (0.107 ms/window) and
 * the least troublesome Android dependency. The ExecuTorch/XNNPACK `.pte` is exported
 * alongside and is the blueprint's preferred path once its AAR is wired in; the numbers
 * from either are comparable because both are checked against eager output at export time.
 */
class SpeedModelBenchmark(context: Context, assetName: String = "speed_tcn.onnx") {

    data class Result(
        val medianMs: Double,
        val p95Ms: Double,
        val meanMs: Double,
        val iterations: Int,
        val windowSamples: Int,
        val budgetUsedPct: Double,
    ) {
        override fun toString(): String = buildString {
            append("median %.3f ms | p95 %.3f ms | mean %.3f ms\n".format(medianMs, p95Ms, meanMs))
            append("%.1f%% of the 100 ms budget at 10 Hz".format(budgetUsedPct))
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    /** Feature count and window length must match the exported model. */
    var features: Int = 6
    var windowSamples: Int = 60

    init {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            // one thread mirrors the deployment case: inference shares the device with
            // sensor ingestion, map work and the UI, so grabbing every core would flatter
            // the benchmark relative to how the app actually runs.
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        session = env.createSession(bytes, options)
        inputName = session.inputNames.iterator().next()
    }

    /**
     * Run [iterations] single-window inferences after [warmup] discarded ones.
     *
     * Batch size is fixed at 1 deliberately: the live loop has exactly one new window per
     * update, so a batched figure would not describe the deployed path.
     */
    fun run(iterations: Int = 300, warmup: Int = 30): Result {
        val data = FloatArray(features * windowSamples) { (it % 17) * 0.05f - 0.4f }
        val shape = longArrayOf(1, features.toLong(), windowSamples.toLong())

        repeat(warmup) { infer(data, shape) }

        val samples = DoubleArray(iterations)
        for (i in 0 until iterations) {
            val t0 = System.nanoTime()
            infer(data, shape)
            samples[i] = (System.nanoTime() - t0) / 1_000_000.0
        }
        samples.sort()

        val median = samples[iterations / 2]
        val p95 = samples[(iterations * 95) / 100]
        return Result(
            medianMs = median,
            p95Ms = p95,
            meanMs = samples.average(),
            iterations = iterations,
            windowSamples = windowSamples,
            budgetUsedPct = 100.0 * median / 100.0,
        )
    }

    private fun infer(data: FloatArray, shape: LongArray) {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { /* discard outputs */ }
        }
    }

    /** Single inference returning the predicted forward speed, for tests and the live loop. */
    fun inferOnce(window: FloatArray): Float {
        require(window.size == features * windowSamples) {
            "expected ${features * windowSamples} values, got ${window.size}"
        }
        val shape = longArrayOf(1, features.toLong(), windowSamples.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                // "speed" is a rank-1 tensor, and ORT hands back a PRIMITIVE float[] for
                // it -- not Float[]. Casting to the boxed array compiles and then throws
                // ClassCastException at runtime.
                return when (val value = results[0].value) {
                    is FloatArray -> value[0]
                    is Array<*> -> (value[0] as FloatArray)[0]
                    else -> error("unexpected output type ${value?.javaClass}")
                }
            }
        }
    }

    fun close() {
        session.close()
    }
}
