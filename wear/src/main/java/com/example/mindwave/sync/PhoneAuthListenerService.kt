package com.example.mindwave.sync

import android.util.Log
import com.example.mindwave.data.PhoneAuthState
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Runs on the watch. Receives auth state updates from the phone via
 * the Data Layer at `/mindwave/phone_auth`.
 *
 * When the phone logs out or switches to offline mode, the watch
 * will stop sending sensor data until the phone is logged in again.
 */
class PhoneAuthListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != AUTH_PATH) continue

            val map = DataMapItem.fromDataItem(item).dataMap
            val isLoggedIn = map.getBoolean("is_logged_in", true)
            val userEmail = map.getString("user_email") ?: ""
            val timestamp = map.getLong("timestamp", System.currentTimeMillis())

            PhoneAuthState.update(this, isLoggedIn, userEmail)
            Log.i(TAG, "Phone auth state updated: logged_in=$isLoggedIn, email=$userEmail, ts=$timestamp")

            if (!isLoggedIn) {
                Log.i(TAG, "Phone logged out — watch will stop sending sensor data")
            }
        }
    }

    companion object {
        private const val TAG = "PhoneAuthListener"
        private const val AUTH_PATH = "/mindwave/phone_auth"
    }
}

