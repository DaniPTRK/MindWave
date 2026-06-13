package com.example.mindwave.sync

import android.content.Context
import android.util.Log
import com.example.mindwave.data.AuthRepository
import com.google.android.gms.wearable.CapabilityClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Monitors watch connection state and publishes auth state when a watch connects.
 * This ensures the watch always has the latest auth state when it pairs or reconnects.
 */
class WatchConnectionListener(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val TAG = "WatchConnectionListener"
    private val authRepo = AuthRepository(context)
    
    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { capability ->
        val count = capability.nodes.size
        Log.i(TAG, "Watch capability changed: ${capability.name}, nodes: $count")
        WatchConnectionState.updateNodes(count)
        if (count > 0) {
            Log.i(TAG, "Watch connected — publishing auth state")
            scope.launch { publishCurrentAuthState() }
        }
    }

    fun start() {
        try {
            com.google.android.gms.wearable.Wearable.getCapabilityClient(context)
                .addListener(capabilityListener, "mindwave_wear_app")
            Log.i(TAG, "Started listening for watch connections")
            scope.launch {
                publishCurrentAuthState()
                // Also do an immediate capability check so UI has a value right away
                WatchConnectionState.refreshFromCapabilityClient(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start watch connection listener: ${e.message}")
        }
    }
    
    private suspend fun publishCurrentAuthState() {
        try {
            val isLoggedIn = authRepo.isLoggedIn()
            val userEmail = authRepo.getEmail() ?: "offline@local"
            
            WatchAuthNotifier.notifyWatch(
                context = context,
                isLoggedIn = isLoggedIn,
                userEmail = userEmail
            )
            Log.i(TAG, "Published auth state to watch: logged_in=$isLoggedIn, email=$userEmail")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish auth state: ${e.message}")
        }
    }
}


