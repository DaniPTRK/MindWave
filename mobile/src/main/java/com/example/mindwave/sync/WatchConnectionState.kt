package com.example.mindwave.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks watch connection state and the most recent data window received from the watch.
 * Read by the phone UI to show "Watch connected / last data received" status.
 */
object WatchConnectionState {

    enum class ConnectionStatus { UNKNOWN, CONNECTED, DISCONNECTED }

    data class LastWindow(
        val time: String,
        val hrSamples: Int,
        val tempSamples: Int,
        val edaSamples: Int,
        val accSamples: Int,
    )

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.UNKNOWN)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _lastWindow = MutableStateFlow<LastWindow?>(null)
    val lastWindow: StateFlow<LastWindow?> = _lastWindow.asStateFlow()

    private val _nodeCount = MutableStateFlow(0)
    val nodeCount: StateFlow<Int> = _nodeCount.asStateFlow()

    /** Epoch ms of the most recent sensor window received from the watch. 0 = none yet. */
    private val _lastWindowReceivedAt = MutableStateFlow(0L)
    val lastWindowReceivedAt: StateFlow<Long> = _lastWindowReceivedAt.asStateFlow()

    fun recordWindowReceived(hrN: Int, tempN: Int, edaN: Int, accN: Int) {
        _lastWindow.value = LastWindow(fmt.format(Date()), hrN, tempN, edaN, accN)
        _lastWindowReceivedAt.value = System.currentTimeMillis()
        _connectionStatus.value = ConnectionStatus.CONNECTED
    }

    fun updateNodes(count: Int) {
        _nodeCount.value = count
        _connectionStatus.value =
            if (count > 0) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED
    }

    /** One-shot check of currently connected nodes. Call from app startup. */
    fun refreshFromCapabilityClient(context: Context) {
        try {
            Wearable.getCapabilityClient(context)
                .getCapability("mindwave_wear_app", CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { info ->
                    val count = info.nodes.size
                    Log.i("WatchConnectionState", "Connected wear nodes: $count")
                    updateNodes(count)
                }
                .addOnFailureListener {
                    Log.w("WatchConnectionState", "Capability check failed: ${it.message}")
                }
        } catch (e: Exception) {
            Log.w("WatchConnectionState", "refreshFromCapabilityClient error: ${e.message}")
        }
    }
}


