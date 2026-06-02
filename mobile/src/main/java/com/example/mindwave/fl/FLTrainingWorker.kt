package com.example.mindwave.fl

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.*
import com.example.mindwave.data.AuthRepository
import com.example.mindwave.data.MindWaveDatabase
import com.example.mindwave.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Runs a local Federated Learning round once per day at ~2 AM.
 *
 * Constraints: NetworkType.CONNECTED + BatteryNotLow (WorkManager) plus
 * a custom battery > 80% or charging.
 *
 * Each round:
 *   1. Checks the server for a newer global model; downloads it if available.
 *   2. Loads feedback-labelled stress readings from Room.
 *   3. Fine-tunes the local TFLite model using the `train` signature.
 *   4. Marks the used readings as synced so they aren't retrained on next round.
 *
 * The fine-tuned weights stay on-device.
 */
class FLTrainingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "FLTrainingWorker"
        private const val PREFS_NAME = "fl_prefs"
        private const val KEY_LOCAL_VERSION = "local_model_version"

        /** Filename used for the downloaded / fine-tuned model in filesDir. */
        const val LOCAL_MODEL_FILENAME = "mindwave_fl_model.tflite"

        /** Original trainable model bundled in assets (fallback if no download yet). */
        private const val BASE_TRAINABLE_ASSET = "mindwave_stress_trainable.tflite"

        /** Minimum feedback samples required before running a training round. */
        private const val MIN_FEEDBACK_SAMPLES = 3

        /** On-device fine-tuning epochs per FL round. */
        private const val LOCAL_EPOCHS = 2

        /** Mini-batch size for the TFLite `train` signature call. */
        private const val BATCH_SIZE = 8

        private const val WORK_NAME = "fl_daily_training"

        /**
         * Enqueues a unique periodic work request that fires daily at ~2 AM.
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<FLTrainingWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setInitialDelay(delayUntilNext2AM(), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Log.i(TAG, "FL training worker enqueued (initial delay ${delayUntilNext2AM() / 60_000} min)")
        }

        private fun delayUntilNext2AM(): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 2)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "FL round starting")

        // Gate on battery, require min 80%
        if (!isBatteryAbove80()) {
            Log.i(TAG, "Battery below 80% and not charging — deferring FL round")
            return@withContext Result.success()
        }

        // Respect pref
        val settings = SettingsRepository(applicationContext).current()
        if (!settings.flEnabled) {
            Log.i(TAG, "FL disabled by user — skipping round")
            return@withContext Result.success()
        }

        val db = MindWaveDatabase.getInstance(applicationContext)
        val authRepo = AuthRepository(applicationContext)

        // refresh jwt
        authRepo.ensureValidToken()

        val token = authRepo.getToken() ?: run {
            Log.w(TAG, "No JWT token after refresh, user not logged in")
            return@withContext Result.success()
        }

        // Check for a newer model on the server and download it
        try {
            checkAndDownloadModel(token)
        } catch (e: Exception) {
            Log.w(TAG, "Model update check failed: ${e.message}")
        }

        // Load feedback-labelled readings
        val readings = db.stressDao().getUnsyncedWithFeedback()
        if (readings.size < MIN_FEEDBACK_SAMPLES) {
            Log.i(TAG, "Only ${readings.size} feedback sample(s), need $MIN_FEEDBACK_SAMPLES, skipping training")
            return@withContext Result.success()
        }
        Log.i(TAG, "Training on ${readings.size} feedback sample(s)")

        // Pair each reading with its binary feedback label
        val labelledSamples = readings.mapNotNull { reading ->
            val tensor = reading.featureTensor ?: return@mapNotNull null
            val mood = db.journalDao().getFeedbackMood(reading.id) ?: return@mapNotNull null
            val label = if (mood >= 4) 1L else 0L
            tensor to label
        }

        if (labelledSamples.isEmpty()) {
            Log.w(TAG, "No labelled samples with stored tensors — skipping training")
            return@withContext Result.success()
        }

        // Fine-tune the local model on feedback data.
        val trained = runLocalTraining(labelledSamples, token)
        if (!trained) Log.w(TAG, "Local training step failed or model unavailable")

        // Mark readings as synced and free the BLOB storage.
        val ids = readings.map { it.id }
        db.stressDao().markSynced(ids)
        db.stressDao().clearFeatureTensors(ids)
        Log.i(TAG, "FL round complete — ${readings.size} reading(s) synced, tensors freed")
        Result.success()
    }

    // Battery helpers
    private fun isBatteryAbove80(): Boolean {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = applicationContext.registerReceiver(null, filter) ?: return true
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL
        return isCharging || (level >= 0 && (level.toFloat() / scale) >= 0.80f)
    }

    // Model update
    private fun checkAndDownloadModel(token: String) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val localVersion = prefs.getInt(KEY_LOCAL_VERSION, 0)

        val body = httpGet("${AuthRepository.BASE_URL}/model/latest", token) ?: return
        val json = JSONObject(body)
        val serverVersion = json.optInt("version", 0)
        val weightsPath = json.optString("weights_path", "")

        if (serverVersion <= localVersion) {
            Log.d(TAG, "Model is up to date (v$localVersion)")
            return
        }

        Log.i(TAG, "Downloading global model v$serverVersion (was v$localVersion)")
        val modelBytes = httpGetBytes(
            "${AuthRepository.BASE_URL}/model/$serverVersion/download", token
        ) ?: return

        if (weightsPath.endsWith(".npz")) {
            // Server sent aggregated numpy weights, so we write to a separate file and
            // apply via the `restore` signature at the start of the next training round
            val npzFile = File(applicationContext.filesDir, "fl_global_weights.npz")
            npzFile.writeBytes(modelBytes)
            Log.i(TAG, "Global weights v$serverVersion saved as .npz (${modelBytes.size} bytes)")
        } else {
            // Server sent a full .tflite, we just replace vals
            val dest = File(applicationContext.filesDir, LOCAL_MODEL_FILENAME)
            dest.writeBytes(modelBytes)
            Log.i(TAG, "Global model v$serverVersion saved as .tflite (${modelBytes.size} bytes)")
        }

        prefs.edit().putInt(KEY_LOCAL_VERSION, serverVersion).apply()
    }

    // Local fine-tuning via TFLite `train` signature
    private fun runLocalTraining(samples: List<Pair<ByteArray, Long>>, token: String): Boolean {
        val localFile = File(applicationContext.filesDir, LOCAL_MODEL_FILENAME)
        val interp = try {
            if (localFile.exists()) {
                Log.d(TAG, "Loading fine-tune model from filesDir")
                Interpreter(localFile, Interpreter.Options().apply { numThreads = 2 })
            } else {
                Log.d(TAG, "Loading fine-tune model from assets ($BASE_TRAINABLE_ASSET)")
                val fd = applicationContext.assets.openFd(BASE_TRAINABLE_ASSET)
                val buf = java.io.FileInputStream(fd.fileDescriptor).channel
                    .map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                Interpreter(buf, Interpreter.Options().apply { numThreads = 2 })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot load trainable model: ${e.message}")
            return false
        }

        return try {
            val nSteps = 12
            val nFeatures = 23

            // Restore previously saved weights so fine-tuning is cumulative across rounds.
            // Priority: global weights from server (.npz) > local fine-tuned weights (.bin)
            val globalNpz = File(applicationContext.filesDir, "fl_global_weights.npz")
            val weightsFile = File(applicationContext.filesDir, "fl_weights.bin")
            when {
                globalNpz.exists() -> {
                    restoreWeightsNpz(interp, globalNpz)
                    globalNpz.delete() // consumed
                    Log.i(TAG, "Restored global aggregated weights from server")
                }
                weightsFile.exists() -> restoreWeights(interp, weightsFile)
            }

            repeat(LOCAL_EPOCHS) { epoch ->
                var batchLoss = 0.0
                var batchCount = 0

                samples.chunked(BATCH_SIZE).forEach { batch ->
                    val bsz = batch.size
                    val xBuf = ByteBuffer
                        .allocateDirect(bsz * nSteps * nFeatures * Float.SIZE_BYTES)
                        .order(ByteOrder.nativeOrder())
                    val yBuf = ByteBuffer
                        .allocateDirect(bsz * Long.SIZE_BYTES)
                        .order(ByteOrder.nativeOrder())

                    batch.forEach { (tensorBytes, label) ->
                        ByteBuffer.wrap(tensorBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .let { src -> repeat(nSteps * nFeatures) { xBuf.putFloat(src.float) } }
                        yBuf.putLong(label)
                    }
                    xBuf.rewind(); yBuf.rewind()

                    val lossOut = FloatArray(1)
                    val inputs = mapOf("x" to xBuf, "y" to yBuf)
                    val outputs = mutableMapOf<String, Any>("loss" to lossOut)
                    interp.runSignature(inputs, outputs, "train")
                    batchLoss += lossOut[0]
                    batchCount++
                }

                Log.d(TAG, "Epoch ${epoch + 1}/$LOCAL_EPOCHS — avg loss %.4f"
                    .format(if (batchCount > 0) batchLoss / batchCount else 0.0))
            }

            // Persist updated weights via `parameters` signature so the next round
            // continues from here rather than restarting from the base model
            saveWeights(interp, weightsFile)

            // Upload weight delta to server so it can be included in FedAvg aggregation
            val uploadOk = httpPostBytes(
                "${AuthRepository.BASE_URL}/model/fl/submit-weights",
                token,
                weightsFile.readBytes(),
            )
            if (uploadOk) {
                Log.i(TAG, "Weights uploaded for aggregation")
            } else {
                Log.w(TAG, "Weight upload failed — will retry next FL round")
            }

            // Reload the inference interpreter so predictions use the updated weights
            com.example.mindwave.inference.StressInferenceRepository.reloadModel()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Training step failed: ${e.message}")
            false
        } finally {
            interp.close()
        }
    }

    /**
     * Extracts weight tensors via the TFLite `parameters` signature and writes
     * them as packed little-endian floats.
     */
    private fun saveWeights(interp: Interpreter, dest: File) {
        try {
            val out = mutableMapOf<String, Any>()
            interp.runSignature(emptyMap(), out, "parameters")
            java.io.DataOutputStream(dest.outputStream().buffered()).use { dos ->
                dos.writeInt(out.size)
                out.values.forEach { v ->
                    val arr = when (v) {
                        is FloatArray -> v
                        is Array<*>   -> (v as? Array<FloatArray>)?.flatMap { it.toList() }?.toFloatArray() ?: FloatArray(0)
                        else          -> FloatArray(0)
                    }
                    dos.writeInt(arr.size)
                    arr.forEach { dos.writeFloat(it) }
                }
            }
            Log.d(TAG, "Saved ${out.size} weight tensor(s) to ${dest.name}")
        } catch (e: Exception) {
            Log.w(TAG, "saveWeights failed: ${e.message}")
        }
    }

    /** Reads weight tensors saved by saveWeights and loads them via the `restore` signature. */
    private fun restoreWeights(interp: Interpreter, src: File) {
        try {
            java.io.DataInputStream(src.inputStream().buffered()).use { dis ->
                val count = dis.readInt()
                val inputs = (0 until count).associate { i ->
                    val size = dis.readInt()
                    "var_$i" to FloatArray(size) { dis.readFloat() }
                }
                interp.runSignature(inputs, mutableMapOf(), "restore")
            }
            Log.d(TAG, "Restored weights from ${src.name}")
        } catch (e: Exception) {
            Log.w(TAG, "restoreWeights failed: ${e.message}")
        }
    }

    /**
     * Reads a server-produced .npz file and loads them into the interpreter via the `restore`
     * signature.
     */
    private fun restoreWeightsNpz(interp: Interpreter, src: File) {
        try {
            val inputs = mutableMapOf<String, FloatArray>()
            java.util.zip.ZipFile(src).use { zip ->
                zip.entries().asSequence().sortedBy { it.name }.forEachIndexed { i, entry ->
                    zip.getInputStream(entry).use { stream ->
                        val bytes = stream.readBytes()
                        // Skip NPY header: magic(6) + major(1) + minor(1) + headerLen(2) + header
                        val headerLen = (bytes[9].toInt() and 0xFF shl 8) or (bytes[8].toInt() and 0xFF)
                        val dataOffset = 10 + headerLen
                        val buf = ByteBuffer.wrap(bytes, dataOffset, bytes.size - dataOffset)
                            .order(ByteOrder.LITTLE_ENDIAN)
                        val floats = FloatArray((bytes.size - dataOffset) / 4) { buf.float }
                        inputs["var_$i"] = floats
                    }
                }
            }
            interp.runSignature(inputs as Map<String, Any>, mutableMapOf(), "restore")
            Log.d(TAG, "Restored ${inputs.size} weight tensor(s) from npz")
        } catch (e: Exception) {
            Log.w(TAG, "restoreWeightsNpz failed: ${e.message}")
        }
    }

    // HTTP helpers
    private fun httpGet(urlStr: String, token: String): String? = try {
        (URL(urlStr).openConnection() as HttpURLConnection).run {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 10_000
            readTimeout = 15_000
            connect()
            if (responseCode == HttpURLConnection.HTTP_OK) {
                inputStream.bufferedReader().readText()
            } else {
                Log.w(TAG, "GET $urlStr → HTTP $responseCode")
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "GET $urlStr: ${e.message}")
        null
    }

    private fun httpGetBytes(urlStr: String, token: String): ByteArray? = try {
        (URL(urlStr).openConnection() as HttpURLConnection).run {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 10_000
            readTimeout = 120_000
            connect()
            if (responseCode == HttpURLConnection.HTTP_OK) {
                inputStream.readBytes()
            } else {
                Log.w(TAG, "GET bytes $urlStr → HTTP $responseCode")
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "GET bytes $urlStr: ${e.message}")
        null
    }

    /**
     * Returns true on HTTP 202 (Accepted), false on any failure.
     */
    private fun httpPostBytes(urlStr: String, token: String, body: ByteArray): Boolean = try {
        val boundary = "----MindWaveFL${System.currentTimeMillis()}"
        (URL(urlStr).openConnection() as HttpURLConnection).run {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true

            outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                // multipart field header
                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"weights_file\"; filename=\"fl_weights.bin\"\r\n")
                writer.write("Content-Type: application/octet-stream\r\n\r\n")
                writer.flush()
                // binary body
                outputStream.write(body)
                outputStream.flush()
                // closing boundary
                writer.write("\r\n--$boundary--\r\n")
                writer.flush()
            }

            val code = responseCode
            Log.d(TAG, "POST weights $urlStr → HTTP $code")
            code == HttpURLConnection.HTTP_ACCEPTED   // 202
        }
    } catch (e: Exception) {
        Log.w(TAG, "POST bytes $urlStr: ${e.message}")
        false
    }
}
