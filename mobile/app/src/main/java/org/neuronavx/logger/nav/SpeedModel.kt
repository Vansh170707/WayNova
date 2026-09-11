package org.neuronavx.logger.nav

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Everything the deployed speed model needs that is not inside the .onnx graph.
 *
 * Shipping the graph alone gives a model that runs and returns plausible-looking numbers
 * while being quietly wrong -- the inputs would be unstandardised and the uncertainty
 * uncalibrated. `scripts/export_model.py --install-assets` writes this file beside the
 * graph from the same checkpoint so the two cannot drift apart.
 */
data class RuntimeConfig(
    val windowSamples: Int,
    val rateHz: Double,
    val features: List<String>,
    val scalerMean: DoubleArray,
    val scalerStd: DoubleArray,
    val sigmaScale: Double,
    val speedSlope: Double,
    val speedOffset: Double,
    val heldOutDriver: String?,
) {
    companion object {
        fun fromAsset(context: Context, name: String = "speed_tcn_runtime.json"): RuntimeConfig {
            val text = context.assets.open(name).bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            val features = o.getJSONArray("features").let { a ->
                List(a.length()) { a.getString(it) }
            }
            fun doubles(key: String): DoubleArray {
                val a = o.getJSONArray(key)
                return DoubleArray(a.length()) { a.getDouble(it) }
            }
            val affine = o.optJSONObject("speed_affine")
            val cfg = RuntimeConfig(
                windowSamples = o.getInt("window_samples"),
                rateHz = o.getDouble("rate_hz"),
                features = features,
                scalerMean = doubles("scaler_mean"),
                scalerStd = doubles("scaler_std"),
                sigmaScale = o.optDouble("sigma_scale", 1.0),
                speedSlope = affine?.optDouble("slope", 1.0) ?: 1.0,
                speedOffset = affine?.optDouble("offset", 0.0) ?: 0.0,
                heldOutDriver = o.optString("held_out_driver", null),
            )
            require(cfg.features.size == FeatureWindow.N_FEATURES) {
                "runtime config has ${cfg.features.size} features, estimator expects " +
                    "${FeatureWindow.N_FEATURES}"
            }
            require(cfg.scalerMean.size == cfg.features.size &&
                cfg.scalerStd.size == cfg.features.size) {
                "scaler length does not match the feature list"
            }
            return cfg
        }
    }
}

/** One speed prediction: the mean the filter fuses, and the sigma that decides how far. */
data class SpeedEstimate(val speed: Double, val sigma: Double)

/** Small boundary that lets the navigation loop test speed-fusion policy independently. */
fun interface SpeedPredictionModel {
    fun predict(window: FeatureWindow): SpeedEstimate
}

/**
 * The exported speed TCN, wrapped with its standardisation and uncertainty calibration.
 *
 * ONNX Runtime is used because it was both the fastest desktop path and the least
 * troublesome Android dependency; the ExecuTorch `.pte` is exported alongside for the
 * blueprint's preferred path. Both are checked against eager output at export time, so the
 * numbers are comparable either way.
 */
class SpeedModel(
    context: Context,
    val config: RuntimeConfig,
    assetName: String = "speed_tcn.onnx",
) : AutoCloseable, SpeedPredictionModel {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val buffer = FloatArray(FeatureWindow.N_FEATURES * config.windowSamples)
    private val shape = longArrayOf(1, FeatureWindow.N_FEATURES.toLong(),
        config.windowSamples.toLong())

    init {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            // one thread mirrors deployment: inference shares the device with sensor
            // ingestion and the UI, so grabbing every core would flatter the measurement
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        session = env.createSession(bytes, options)
        inputName = session.inputNames.iterator().next()
    }

    /** Standardise the window, run the graph, and calibrate the result. */
    override fun predict(window: FeatureWindow): SpeedEstimate {
        window.writeStandardised(config.scalerMean, config.scalerStd, buffer)
        return predictStandardised(buffer)
    }

    fun predictStandardised(standardised: FloatArray): SpeedEstimate {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(standardised), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                val mean = scalarOf(results[0].value)
                val logVar = scalarOf(results[1].value)
                // Undo any affine shrinkage the checkpoint recorded, and propagate that
                // same factor into sigma -- dividing the prediction without dividing its
                // error bar would make the filter trust a widened estimate too much.
                val slope = if (config.speedSlope != 0.0) config.speedSlope else 1.0
                val speed = ((mean - config.speedOffset) / slope).coerceAtLeast(0.0)
                val sigma = sqrt(exp(logVar)) * config.sigmaScale / kotlin.math.abs(slope)
                return SpeedEstimate(speed, sigma)
            }
        }
    }

    /**
     * ORT hands back a PRIMITIVE float[] for a rank-1 output, not Float[]. Casting to the
     * boxed array compiles and then throws ClassCastException at runtime, which is exactly
     * the class of bug the on-device test run exists to catch.
     */
    private fun scalarOf(value: Any?): Double = when (value) {
        is FloatArray -> value[0].toDouble()
        is Array<*> -> ((value[0]) as FloatArray)[0].toDouble()
        else -> error("unexpected ONNX output type ${value?.javaClass}")
    }

    override fun close() = session.close()
}
