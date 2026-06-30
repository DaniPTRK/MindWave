package com.example.mindwave.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Runs on the phone and asks the paired watch to flush its current sensor buffers.
 *
 * The primary lookup uses the declared Wear capability. If capability discovery
 * is stale or not indexed yet, the requester falls back to all connected nodes.
 */
object WatchFlushRequester {

    private const val TAG = "MWFlushRequester"
    const val FLUSH_PATH = "/mindwave/request_flush"
    private const val WEAR_CAP = "mindwave_wear_app"

    @Volatile private var scope: CoroutineScope? = null

    /**
     * Start the periodic flush-request loop.
     * Safe to call multiple times; only one loop runs at a time.
     *
     * Loop order: delay first, then flush, so no spurious flush fires at t=0
     * on app boot before the watch has collected any sensor data.
     */
    fun start(context: Context, intervalMs: Long = 60_000L) {
        if (scope != null) return
        val ctx = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { sc ->
            sc.launch {
                Log.i(TAG, "Flush requester started (interval=${intervalMs / 1000}s, first flush in ${intervalMs / 1000}s)")
                while (true) {
                    delay(intervalMs)
                    requestFlush(ctx)
                }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        Log.i(TAG, "Flush requester stopped")
    }

    suspend fun requestFlush(context: Context) {
        try {
            val ctx = context.applicationContext
            val nodes = resolveReachableWearNodes(ctx)
            if (nodes.isEmpty()) {
                Log.d(TAG, "No reachable wear nodes; skipping flush request")
                return
            }
            val msgClient = Wearable.getMessageClient(ctx)
            for (node in nodes) {
                msgClient.sendMessage(node.id, FLUSH_PATH, ByteArray(0)).await()
                Log.i(TAG, "Flush request sent to ${node.displayName} (${node.id})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Flush request failed: ${e.message}")
        }
    }

    private suspend fun resolveReachableWearNodes(context: Context): List<Node> {
        val capabilityNodes = Wearable.getCapabilityClient(context)
            .getCapability(WEAR_CAP, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes

        if (capabilityNodes.isNotEmpty()) return capabilityNodes.toList()

        val connectedNodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (connectedNodes.isNotEmpty()) {
            Log.i(TAG, "Capability '$WEAR_CAP' returned no nodes; using ${connectedNodes.size} connected node(s)")
        }
        return connectedNodes
    }
}

