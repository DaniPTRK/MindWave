package com.example.mindwave.inference

import android.content.Context
import android.util.Log
import com.example.mindwave.data.MindWaveDatabase
import com.example.mindwave.data.StressReading
import com.example.mindwave.data.XaiExplanation
import com.example.mindwave.sync.MobileDataListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Wires the MobileDataListenerService received windows to TFLite inference and Room persistence.
 *
 * Pipeline:
 *   SensorWindow -> FeatureExtractor -> TFLite `infer` signature -> StressReading (Room) +
 *   XaiExplanation rows (Room) -> DashboardViewModel observes via Flow -> UI updates
 */
class StressInferenceRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MWInference"
        private const val MODEL_FILE = "mindwave_stress.tflite"

        @Volatile
        private var instance: StressInferenceRepository? = null

        fun init(context: Context): StressInferenceRepository {
            return instance ?: synchronized(this) {
                instance ?: StressInferenceRepository(context.applicationContext)
                    .also {
                        instance = it
                        it.register()
                    }
            }
        }

        /** Call after FLTrainingWorker saves a new global model to filesDir. */
        fun reloadModel() {
            instance?.register()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = MindWaveDatabase.getInstance(context)
    private val contextCorrelator = com.example.mindwave.data.ContextCorrelator(context)
    private val scaler = ScalerNormalizer.fromAssets(context)
    @Volatile private var interpreter: Interpreter? = null

    /** Loads the TFLite model and subscribes to sensor windows from the watch. */
    private fun register() {
        // Prefer a model updated by FLTrainingWorker over the bundled asset.
        val localModel = java.io.File(context.filesDir, com.example.mindwave.fl.FLTrainingWorker.LOCAL_MODEL_FILENAME)
        val newInterp = try {
            if (localModel.exists()) {
                Log.i(TAG, "Loading updated model from filesDir: ${localModel.name}")
                Interpreter(localModel, Interpreter.Options().apply { numThreads = 2 })
            } else {
                Log.i(TAG, "Loading bundled model from assets: $MODEL_FILE")
                val fd = context.assets.openFd(MODEL_FILE)
                val buf = FileInputStream(fd.fileDescriptor).channel
                    .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                Interpreter(buf, Interpreter.Options().apply { numThreads = 2 })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model: ${e.message}")
            null
        }
        val old = interpreter
        interpreter = newInterp
        old?.close()

        // Subscribe to sensor windows from the watch
        MobileDataListenerService.onWindowReceived = { window ->
            scope.launch { processWindow(window) }
        }
        Log.i(TAG, "StressInferenceRepository registered")
    }

    /** Called for each new sensor window received from the watch. Runs the full pipeline from
     * feature extraction to inference to Room persistence. */
    private suspend fun processWindow(window: MobileDataListenerService.SensorWindow) {
        // Extract features
        val rawFeatures = FeatureExtractor.extract(
            hrValues   = window.hrValues,
            tempValues = window.tempValues,
            edaValues  = window.edaValues,
            accX       = window.accXValues,
            accY       = window.accYValues,
            accZ       = window.accZValues,
        )
        if (rawFeatures == null) {
            Log.w(TAG, "Not enough sensor data to run inference (HR=${window.hrValues.size} samples)")
            return
        }

        // Apply the training-time standard scaler.
        val features = scaler?.normalize(rawFeatures) ?: rawFeatures

        // Run TFLite inference
        val interp = interpreter
        if (interp == null) {
            Log.w(TAG, "TFLite interpreter not ready — skipping inference")
            return
        }

        val probs = runInference(interp, features) ?: return
        // probs: [normal, stress]
        val stressScore = probs.indices.maxByOrNull { probs[it] } ?: 0
        Log.i(TAG, "Inference: normal=%.2f stress=%.2f → class=$stressScore"
            .format(probs[0], probs[1]))

        // Run vanilla-gradient XAI (explain signature)
        val saliency = runExplain(interp, features)

        // Persist StressReading to Room
        val reading = StressReading(
            timestamp           = window.timestamp,
            hrvMean             = rawFeatures.extractFeatureAt(0),  // raw, human-readable
            hrvSdnn             = rawFeatures.extractFeatureAt(1),
            hrvRmssd            = rawFeatures.extractFeatureAt(2),
            edaScl              = rawFeatures.extractFeatureAt(7),
            edaScr              = rawFeatures.extractFeatureAt(9),
            tempMean            = rawFeatures.extractFeatureAt(14),
            accMag              = rawFeatures.extractFeatureAt(19), // acc_mag_mean from first sub-window
            stressScore         = stressScore,
            stressProbBaseline  = probs[0],   // P(non_stress)
            stressProbStress    = probs[1],   // P(stress)
            stressProbAmusement = 0f,         // unused bcs binary model has no amusement class
            featureTensor       = floatArrayToBytes(rawFeatures),
        )
        val readingId = db.stressDao().insert(reading)

        // Persist XAI explanation rows
        if (saliency != null) {
            val featureNames = buildFeatureNames()
            val xaiRows = featureNames.mapIndexed { idx, name ->
                XaiExplanation(
                    readingId   = readingId,
                    featureName = name,
                    importance  = saliency.getOrElse(idx) { 0f },
                    rank        = idx,
                )
            }.sortedByDescending { it.importance }
                .mapIndexed { rank, xai -> xai.copy(rank = rank) }
            db.xaiDao().insertAll(xaiRows)
        }

        // Best-effort context enrichment (weather; calendar if enabled).
        contextCorrelator.correlate(reading.copy(id = readingId))

        // Push the score to the paired watch (tile / complication live value).
        val percent = (probs[1] * 100).toInt()
        com.example.mindwave.sync.WatchStressSender.send(
            context = context,
            percent = percent,
            label = com.example.mindwave.ui.theme.stressLabel(percent),
            timestamp = window.timestamp,
        )
    }

    // helpers

    private fun runInference(interp: Interpreter, features: FloatArray): FloatArray? {
        return try {
            // Binary model
            val inputBuf = featuresToBuffer(features)
            val outputBuf = ByteBuffer.allocateDirect(2 * 4).order(ByteOrder.nativeOrder())

            // `infer` signature: arg "x" -> returns "logits"
            try {
                val inputs  = mapOf("x" to inputBuf)
                val outputs = mutableMapOf<String, Any>("logits" to outputBuf)
                interp.runSignature(inputs, outputs, "infer")
            } catch (e: Exception) {
                inputBuf.rewind()
                outputBuf.rewind()
                interp.run(inputBuf, outputBuf)
            }

            outputBuf.rewind()
            FloatArray(2) { outputBuf.float }
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")
            null
        }
    }

    private fun runExplain(interp: Interpreter, features: FloatArray): FloatArray? {
        return try {
            val inputBuf = featuresToBuffer(features)
            // The `explain` signature averages over timesteps internally.
            // Output shape is [1, N_FEATURES] = 23 floats.
            val outputBuf = ByteBuffer.allocateDirect(FeatureExtractor.N_FEATURES * 4)
                .order(ByteOrder.nativeOrder())
            // Arg name "x", return key "importances" (matches tflite_training_export.py)
            val inputs  = mapOf("x" to inputBuf)
            val outputs = mutableMapOf<String, Any>("importances" to outputBuf)
            interp.runSignature(inputs, outputs, "explain")
            outputBuf.rewind()
            FloatArray(FeatureExtractor.N_FEATURES) { outputBuf.float }
        } catch (e: Exception) {
            Log.d(TAG, "Explain signature not available: ${e.message}")
            null
        }
    }

    private fun featuresToBuffer(features: FloatArray): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(
            FeatureExtractor.N_SUBWINDOWS * FeatureExtractor.N_FEATURES * 4
        ).order(ByteOrder.nativeOrder())
        features.forEach { buf.putFloat(it) }
        buf.rewind()
        return buf
    }

    /** Extract the value for a given feature index from sub-window 0. */
    private fun FloatArray.extractFeatureAt(featureIdx: Int): Float =
        getOrElse(featureIdx) { 0f }

    private fun buildFeatureNames(): List<String> = listOf(
        "hrv_meanNN", "hrv_SDNN", "hrv_RMSSD", "hrv_pNN50",
        "hrv_LF", "hrv_HF", "hrv_LFHF",
        "eda_scl_mean", "eda_scl_slope", "eda_scr_mean", "eda_scr_std",
        "eda_scr_auc", "eda_scr_peaks", "eda_scr_amp_mean",
        "temp_mean", "temp_std", "temp_slope", "temp_min", "temp_max",
        "acc_mag_mean", "acc_mag_std", "acc_mag_energy", "acc_mag_zcr",
    )

    /** Serialise FloatArray to little-endian bytes for Room BLOB storage. */
    private fun floatArrayToBytes(fa: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(fa.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        fa.forEach { buf.putFloat(it) }
        return buf.array()
    }
}

