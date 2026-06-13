package com.example.mindwave.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Publishes the phone's auth state to the paired watch via the Data Layer.
 * The watch uses this to determine whether it should continue collecting
 * and sending sensor data.
 *
 * When the phone logs out or switches to offline mode, the watch will
 * stop sending data until the phone is logged in again.
 */
object WatchAuthNotifier {

    private const val TAG = "WatchAuthNotifier"
    private const val AUTH_STATE_PATH = "/mindwave/phone_auth"

    /**
     * Publishes the current auth state to the watch.
     * Call this whenever login/logout/offline mode changes.
     *
     * @param isLoggedIn true if a real account is logged in (not offline mode)
     * @param userEmail the current account email (or "offline@local")
     */
    suspend fun notifyWatch(context: Context, isLoggedIn: Boolean, userEmail: String) {
        try {
            val dataClient = Wearable.getDataClient(context)
            val request = PutDataMapRequest.create(AUTH_STATE_PATH).apply {
                dataMap.putBoolean("is_logged_in", isLoggedIn)
                dataMap.putString("user_email", userEmail)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }
            request.setUrgent() // Ensure immediate delivery

            dataClient.putDataItem(request.asPutDataRequest()).await()
            Log.i(TAG, "Notified watch: logged_in=$isLoggedIn, email=$userEmail")
        } catch (e: Exception) {
            // Watch might not be connected, which is fine
            Log.w(TAG, "Failed to notify watch (watch may be disconnected): ${e.message}")
        }
    }
}

