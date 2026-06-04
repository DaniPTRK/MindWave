package com.example.mindwave.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Runs on the phone. Pushes the latest computed stress score to the watch
 * over the Wearable Data Layer so the watch can show
 * a live value. Only the aggregate score & label travel.
 */
object WatchStressSender {

    private const val TAG = "MWWatchSender"
    private const val DATA_PATH = "/mindwave/stress_score"

    suspend fun send(context: Context, percent: Int, label: String, timestamp: Long) {
        runCatching {
            val request = PutDataMapRequest.create(DATA_PATH).apply {
                dataMap.putInt("percent", percent)
                dataMap.putString("label", label)
                dataMap.putLong("timestamp", timestamp)
                dataMap.putLong("_nonce", System.nanoTime())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.i(TAG, "Pushed stress score to watch: $percent% ($label)")
        }.onFailure { Log.w(TAG, "Failed to push stress score to watch: ${it.message}") }
    }
}
