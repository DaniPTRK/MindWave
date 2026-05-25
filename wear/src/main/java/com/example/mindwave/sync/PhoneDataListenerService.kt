package com.example.mindwave.sync

import android.content.ComponentName
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.example.mindwave.complication.MainComplicationService
import com.example.mindwave.data.WatchStressStore
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Runs on the watch. Receives the latest stress score that the phone
 * computes and pushes over the Data Layer at `/mindwave/stress_score`, caches
 * it in WatchStressStore, and refreshes the complication.
 */
class PhoneDataListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != DATA_PATH) continue

            val map = DataMapItem.fromDataItem(item).dataMap
            val percent = map.getInt("percent", 0)
            val label = map.getString("label") ?: "No data"
            val timestamp = map.getLong("timestamp", System.currentTimeMillis())

            WatchStressStore.save(this, percent, label, timestamp)
            Log.i(TAG, "Received stress score from phone: $percent% ($label)")

            runCatching {
                ComplicationDataSourceUpdateRequester.create(
                    this,
                    ComponentName(this, MainComplicationService::class.java),
                ).requestUpdateAll()
            }
        }
    }

    companion object {
        private const val TAG = "MWWatchSync"
        private const val DATA_PATH = "/mindwave/stress_score"
    }
}
