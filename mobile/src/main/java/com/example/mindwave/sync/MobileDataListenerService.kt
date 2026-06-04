package com.example.mindwave.sync

import android.content.Context
import android.util.Log
import com.example.mindwave.data.EmotionalJournal
import com.example.mindwave.data.MindWaveDatabase
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs on the phone. Listens for DataItems sent by the watch
 * (sensor windows), deserializes them and publishes via a callback so the
 * repository layer can run TFLite inference & persist to Room.
 */
class MobileDataListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "MWMobileSync"
        private const val DATA_PATH = "/mindwave/sensor_window"
        var onWindowReceived: ((SensorWindow) -> Unit)? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class SensorWindow(
        val timestamp: Long,
        val hrTimes: LongArray,
        val hrValues: FloatArray,
        val tempTimes: LongArray,
        val tempValues: FloatArray,
        val edaTimes: LongArray,
        val edaValues: FloatArray,
        val accXValues: FloatArray,
        val accYValues: FloatArray,
        val accZValues: FloatArray,
        val mood: Int,
    )

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != DATA_PATH) continue

            val map = DataMapItem.fromDataItem(item).dataMap

            // Quick-reply mood log from the watch, store as a journal entry
            if (map.getBoolean("mood_only", false)) {
                val mood = map.getInt("mood", 0)
                val ts = map.getLong("timestamp", System.currentTimeMillis())
                if (mood in 1..5) {
                    scope.launch {
                        MindWaveDatabase.getInstance(applicationContext).journalDao().insert(
                            EmotionalJournal(
                                readingId = null,
                                timestamp = ts,
                                userMood = mood,
                                note = "",
                                tags = "watch_quick_reply",
                            )
                        )
                        Log.i(TAG, "Stored watch mood quick-reply: $mood")
                    }
                }
                continue
            }

            val window = SensorWindow(
                timestamp  = map.getLong("timestamp"),
                hrTimes    = map.getLongArray("hr_times") ?: longArrayOf(),
                hrValues   = map.getFloatArray("hr_values") ?: floatArrayOf(),
                tempTimes  = map.getLongArray("temp_times") ?: longArrayOf(),
                tempValues = map.getFloatArray("temp_values") ?: floatArrayOf(),
                edaTimes   = map.getLongArray("eda_times") ?: longArrayOf(),
                edaValues  = map.getFloatArray("eda_values") ?: floatArrayOf(),
                accXValues = map.getFloatArray("acc_x_values") ?: floatArrayOf(),
                accYValues = map.getFloatArray("acc_y_values") ?: floatArrayOf(),
                accZValues = map.getFloatArray("acc_z_values") ?: floatArrayOf(),
                mood       = map.getInt("mood", 0),
            )
            Log.i(TAG, "Received sensor window: HR=${window.hrValues.size} samples")
            onWindowReceived?.invoke(window)
        }
    }
}