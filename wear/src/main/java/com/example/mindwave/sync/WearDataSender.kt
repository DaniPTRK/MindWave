package com.example.mindwave.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.example.mindwave.sensor.SensorForegroundService
import com.example.mindwave.sensor.SensorForegroundService.TimestampedValue
import kotlinx.coroutines.tasks.await

/**
 * Sends buffered sensor windows from the watch to the paired phone via the
 * Wearable Data Layer API.
 *
 * The phone-side DataClient listener picks up the DataItem,
 * deserializes it, feeds the feature extractor, runs TFLite inference,
 * and persists the StressReading & XAI in Room.
 *
 * Data travels over the link between watch and
 * phone using Google's encrypted transport.
 *
 * DataMap format:
 *   - "timestamp"    : Long
 *   - "hr_times"     : LongArray
 *   - "hr_values"    : DoubleArray
 *   - "temp_times"   : LongArray
 *   - "temp_values"  : DoubleArray
 *   - "eda_times"    : LongArray
 *   - "eda_values"   : DoubleArray
 *   - "acc_x_values" : FloatArray  (ACCELEROMETER_X, ~25 Hz)
 *   - "acc_y_values" : FloatArray
 *   - "acc_z_values" : FloatArray
 *   - "mood"         : Int (0 = no feedback, 1–5 from quick journal)
 */
object WearDataSender {

    private const val TAG = "MWSync"
    private const val DATA_PATH = "/mindwave/sensor_window"

    /**
     * Flush the 60s buffers and send them to the phone - called from a coroutine in
     * SensorForegroundService.
     */
    suspend fun sendWindow(context: Context, userMood: Int = 0) {
        val dataClient: DataClient = Wearable.getDataClient(context)

        val hr = ArrayList(SensorForegroundService.hrBuffer).also {
            SensorForegroundService.hrBuffer.clear()
        }
        var temp = ArrayList(SensorForegroundService.tempBuffer).also {
            SensorForegroundService.tempBuffer.clear()
        }
        // If the temp buffer is empty (sensor didn't fire this window) inject the
        // last known temperature so the feature extractor doesn't fall back to 36.0°C.
        if (temp.isEmpty()) {
            val lastTemp = com.example.mindwave.data.SensorDebugState.lastKnownTempC.value
            if (lastTemp > 0.0) {
                temp = arrayListOf(TimestampedValue(System.currentTimeMillis(), lastTemp))
                Log.d(TAG, "Temp buffer empty — injecting last known temp: ${"%.2f".format(lastTemp)}°C")
            }
        }
        val eda = ArrayList(SensorForegroundService.edaBuffer).also {
            SensorForegroundService.edaBuffer.clear()
        }
        val accX = ArrayList(SensorForegroundService.accXBuffer).also {
            SensorForegroundService.accXBuffer.clear()
        }
        val accY = ArrayList(SensorForegroundService.accYBuffer).also {
            SensorForegroundService.accYBuffer.clear()
        }
        val accZ = ArrayList(SensorForegroundService.accZBuffer).also {
            SensorForegroundService.accZBuffer.clear()
        }

        if (hr.isEmpty() && temp.isEmpty() && eda.isEmpty()) {
            Log.d(TAG, "No sensor data to send")
            return
        }

        // Samsung Galaxy Watch ACC values are in mg (milligravity) from the SDK.
        // 1000 mg = 1 g. Convert to g so features match WESAD training scale (WESAD ACC is in g).
        val ACC_SCALE = 1000f
        val accXG = accX.map { it.value.toFloat() / ACC_SCALE }.toFloatArray()
        val accYG = accY.map { it.value.toFloat() / ACC_SCALE }.toFloatArray()
        val accZG = accZ.map { it.value.toFloat() / ACC_SCALE }.toFloatArray()

        Log.i(TAG, "Sending window: HR=${hr.size} TEMP=${temp.size} EDA=${eda.size} ACC=${accX.size}" +
            if (temp.isNotEmpty()) " tempMean=${"%.2f".format(temp.map{it.value}.average())}°C" else " (no temp)")

        val request = PutDataMapRequest.create(DATA_PATH).apply {
            dataMap.putLong("timestamp", System.currentTimeMillis())
            dataMap.putLongArray("hr_times", hr.map { it.epochMs }.toLongArray())
            dataMap.putFloatArray("hr_values", hr.map { it.value.toFloat() }.toFloatArray())
            dataMap.putLongArray("temp_times", temp.map { it.epochMs }.toLongArray())
            dataMap.putFloatArray("temp_values", temp.map { it.value.toFloat() }.toFloatArray())
            dataMap.putLongArray("eda_times", eda.map { it.epochMs }.toLongArray())
            dataMap.putFloatArray("eda_values", eda.map { it.value.toFloat() }.toFloatArray())
            dataMap.putFloatArray("acc_x_values", accXG)
            dataMap.putFloatArray("acc_y_values", accYG)
            dataMap.putFloatArray("acc_z_values", accZG)
            dataMap.putBoolean("eda_available", SensorForegroundService.edaAvailable.value)
            dataMap.putBoolean("acc_available", SensorForegroundService.accAvailable.value)
            dataMap.putInt("mood", userMood)
            dataMap.putLong("_nonce", System.nanoTime())
        }
        request.setUrgent()

        sendWithRetry(dataClient, request, "window HR=${hr.size} TEMP=${temp.size} EDA=${eda.size} ACC=${accX.size}")
    }

    /**
     * Put a DataMap with bounded exponential backoff  so a transient Bluetooth drop
     * does not silently end the monitoring session.
     */
    private suspend fun sendWithRetry(
        dataClient: DataClient,
        request: PutDataMapRequest,
        label: String,
        maxRetries: Int = 5,
    ) {
        var attempt = 0
        while (true) {
            try {
                dataClient.putDataItem(request.asPutDataRequest()).await()
                Log.i(TAG, "Sent $label")
                return
            } catch (e: Exception) {
                if (attempt >= maxRetries) {
                    Log.e(TAG, "Giving up sending $label after $attempt retries", e)
                    return
                }
                val backoffMs = minOf(1000L shl attempt, 30_000L)
                Log.w(TAG, "Send failed ($label) attempt $attempt, retrying in ${backoffMs}ms")
                kotlinx.coroutines.delay(backoffMs)
                attempt++
            }
        }
    }

    /**
     * Quick-reply mood log from the watch.
     * @param mood 1/2 = stressed, 3 = not sure, 4/5 = not stressed
     */
    suspend fun sendMood(context: Context, mood: Int, readingId: Long = 0L) {
        val dataClient: DataClient = Wearable.getDataClient(context)
        val request = PutDataMapRequest.create(DATA_PATH).apply {
            dataMap.putLong("timestamp", System.currentTimeMillis())
            dataMap.putInt("mood", mood)
            dataMap.putBoolean("mood_only", true)
            dataMap.putLong("reading_id", readingId)
            dataMap.putLong("_nonce", System.nanoTime())
        }
        request.setUrgent()
        try {
            dataClient.putDataItem(request.asPutDataRequest()).await()
            Log.i(TAG, "Sent mood quick-reply: $mood")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send mood to phone", e)
        }
    }
}

