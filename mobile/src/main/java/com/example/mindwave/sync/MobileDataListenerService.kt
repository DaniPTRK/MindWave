package com.example.mindwave.sync

import android.content.Context
import android.util.Log
import com.example.mindwave.data.AuthRepository
import com.example.mindwave.data.EmotionalJournal
import com.example.mindwave.data.JournalEntryType
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
                val readingId = map.getLong("reading_id", 0L)
                if (mood in 1..5) {
                    scope.launch {
                        val email = AuthRepository(applicationContext).getEmail() ?: ""
                        val isBinaryFeedback = readingId > 0L && mood != 3
                        MindWaveDatabase.getInstance(applicationContext).journalDao().insert(
                            EmotionalJournal(
                                readingId = readingId.takeIf { it > 0L },
                                timestamp = ts,
                                userMood = mood,
                                note = when {
                                    isBinaryFeedback && mood <= 2 -> "Confirmed stress"
                                    isBinaryFeedback -> "Not stressed"
                                    else -> "Not sure"
                                },
                                tags = if (isBinaryFeedback) "feedback" else "watch_quick_reply",
                                entryType = if (isBinaryFeedback) JournalEntryType.FEEDBACK else JournalEntryType.WATCH_MOOD,
                                userEmail = email,
                            )
                        )
                        Log.i(TAG, "Stored watch quick-reply: mood=$mood readingId=$readingId feedback=$isBinaryFeedback")
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

            // Single consolidated log per window
            val hrMean   = if (window.hrValues.isNotEmpty()) window.hrValues.average() else 0.0
            val tempMean = if (window.tempValues.isNotEmpty()) window.tempValues.average() else 0.0
            val edaMean  = if (window.edaValues.isNotEmpty()) window.edaValues.average() else 0.0
            Log.i(TAG, "â–¶ Window received | " +
                "HR=${window.hrValues.size}smp (avg=${"%.0f".format(hrMean)} bpm) | " +
                "TEMP=${window.tempValues.size}smp (avg=${"%.1f".format(tempMean)}Â°C) | " +
                "EDA=${window.edaValues.size}smp (avg=${"%.2f".format(edaMean)} ÂµS) | " +
                "ACC=${window.accXValues.size}smp | mood=${window.mood}")

            // Update phone-side connection state so UI can show watch is connected + last data
            WatchConnectionState.recordWindowReceived(
                window.hrValues.size,
                window.tempValues.size,
                window.edaValues.size,
                window.accXValues.size,
            )

            onWindowReceived?.invoke(window)
        }
    }
}


