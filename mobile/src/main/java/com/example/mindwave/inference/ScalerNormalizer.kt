package com.example.mindwave.inference

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Applies the exact standard scaling that was fit on the WESAD training
 * subjects (scikit-learn StandardScaler) so on-device features match the
 * distribution the LSTM was trained on.
 *
 * The parameters are exported from `ml/models/scaler.pkl` into
 * `assets/scaler_params.json` via `export_scaler_params()` in
 * `ml/src/tflite_training_export.py`.
 *
 */
class ScalerNormalizer private constructor(
    private val mean: FloatArray,
    private val scale: FloatArray,
) {
    /**
     * Standardise a (N_SUBWINDOWS x N_FEATURES) row-major feature tensor in place
     * of a copy.
     */
    fun normalize(features: FloatArray): FloatArray {
        val f = mean.size
        if (f == 0 || features.size % f != 0) {
            Log.w(TAG, "Scaler dim ($f) incompatible with features (${features.size}); skipping")
            return features
        }
        val out = FloatArray(features.size)
        for (i in features.indices) {
            val j = i % f
            val s = if (scale[j] == 0f) 1f else scale[j]
            out[i] = (features[i] - mean[j]) / s
        }
        return out
    }

    companion object {
        private const val TAG = "MWScaler"
        private const val ASSET = "scaler_params.json"

        /** Loads scaler params from assets, or null if absent/invalid. */
        fun fromAssets(context: Context): ScalerNormalizer? = try {
            val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val meanArr = obj.getJSONArray("mean")
            val scaleArr = obj.getJSONArray("scale")
            val mean = FloatArray(meanArr.length()) { meanArr.getDouble(it).toFloat() }
            val scale = FloatArray(scaleArr.length()) { scaleArr.getDouble(it).toFloat() }
            Log.i(TAG, "Loaded scaler params (${mean.size} features)")
            ScalerNormalizer(mean, scale)
        } catch (e: Exception) {
            Log.w(TAG, "scaler_params.json unavailable: ${e.message}")
            null
        }
    }
}
