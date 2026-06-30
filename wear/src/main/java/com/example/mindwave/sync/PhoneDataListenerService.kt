package com.example.mindwave.sync

import android.content.ComponentName
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.example.mindwave.complication.MainComplicationService
import com.example.mindwave.data.WatchStressStore
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs on the watch. Two responsibilities:
 *
 * 1. DATA_PATH (/mindwave/stress_score) receives the latest stress score
 *    from the phone, caches it in WatchStressStore, refreshes the complication.
 *
 * 2. FLUSH_PATH (/mindwave/request_flush) phone-initiated flush request.
 *    On receipt, immediately flushes the current sensor buffers to the phone
 *    via WearDataSender.sendWindow(). This gives the phone control over the
 *    60-second inference cadence rather than relying solely on the watch timer.
 */
class PhoneDataListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != DATA_PATH) continue

            val map = DataMapItem.fromDataItem(item).dataMap
            val percent = map.getInt("percent", 0)
            val label = map.getString("label") ?: "No data"
            val timestamp = map.getLong("timestamp", System.currentTimeMillis())
            val readingId = map.getLong("reading_id", 0L)

            WatchStressStore.save(this, percent, label, timestamp, readingId)
            Log.i(TAG, "Received stress score from phone: $percent% ($label)")

            runCatching {
                ComplicationDataSourceUpdateRequester.create(
                    this,
                    ComponentName(this, MainComplicationService::class.java),
                ).requestUpdateAll()
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != FLUSH_PATH) return
        Log.i(TAG, "Flush request received from phone (node=${event.sourceNodeId}); flushing buffers")
        scope.launch {
            try {
                // Only flush if the phone is still logged in (same guard as the watch timer).
                if (!com.example.mindwave.data.PhoneAuthState.isPhoneLoggedIn(applicationContext)) {
                    Log.i(TAG, "Flush request ignored; phone not logged in")
                    com.example.mindwave.data.SensorDebugState.recordSkip("Phone not logged in")
                    return@launch
                }
                // Log the flush request time so the watch timer can skip its next flush if it was about to fire.
                com.example.mindwave.sensor.SensorForegroundService.lastPhoneFlushRequestAt
                    .set(System.currentTimeMillis())
                val hrN   = com.example.mindwave.sensor.SensorForegroundService.hrBuffer.size
                val tempN = com.example.mindwave.sensor.SensorForegroundService.tempBuffer.size
                val edaN  = com.example.mindwave.sensor.SensorForegroundService.edaBuffer.size
                val accN  = com.example.mindwave.sensor.SensorForegroundService.accXBuffer.size
                WearDataSender.sendWindow(applicationContext)
                com.example.mindwave.data.SensorDebugState.recordSendSuccess(hrN, tempN, edaN, accN)
                // Reset live buffer-size counters so the debug screen reflects the empty buffers.
                com.example.mindwave.data.SensorDebugState.hrBufferSize.value  =
                    com.example.mindwave.sensor.SensorForegroundService.hrBuffer.size
                com.example.mindwave.data.SensorDebugState.edaBufSize.value    =
                    com.example.mindwave.sensor.SensorForegroundService.edaBuffer.size
                com.example.mindwave.data.SensorDebugState.tempBufSize.value   =
                    com.example.mindwave.sensor.SensorForegroundService.tempBuffer.size
                com.example.mindwave.data.SensorDebugState.accBufSize.value    =
                    com.example.mindwave.sensor.SensorForegroundService.accXBuffer.size
                Log.i(TAG, "Flush triggered by phone request; HR=$hrN TEMP=$tempN EDA=$edaN ACC=$accN")
            } catch (e: Exception) {
                Log.e(TAG, "Flush on phone request failed: ${e.message}")
                com.example.mindwave.data.SensorDebugState.recordSendError(
                    com.example.mindwave.sensor.SensorForegroundService.hrBuffer.size,
                    com.example.mindwave.sensor.SensorForegroundService.tempBuffer.size,
                    com.example.mindwave.sensor.SensorForegroundService.edaBuffer.size,
                    com.example.mindwave.sensor.SensorForegroundService.accXBuffer.size,
                    e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    companion object {
        private const val TAG       = "MWWatchSync"
        private const val DATA_PATH = "/mindwave/stress_score"
        const val FLUSH_PATH        = "/mindwave/request_flush"
    }
}