package com.example.mindwave.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Store for the latest stress score pushed from the phone.=
 */
object WatchStressStore {

    private const val PREFS = "mindwave_watch_stress"
    private const val KEY_PERCENT = "percent"
    private const val KEY_LABEL = "label"
    private const val KEY_TIMESTAMP = "timestamp"

    data class Snapshot(
        val percent: Int,
        val label: String,
        val timestamp: Long,
    ) {
        val hasData: Boolean get() = timestamp > 0L
    }

    private val _flow = MutableStateFlow(Snapshot(0, "No data", 0L))

    val flow: StateFlow<Snapshot> = _flow.asStateFlow()

    fun save(context: Context, percent: Int, label: String, timestamp: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PERCENT, percent)
            .putString(KEY_LABEL, label)
            .putLong(KEY_TIMESTAMP, timestamp)
            .apply()
        // Emit to the flow so any active Compose UI recomposes immediately.
        _flow.value = Snapshot(percent, label, timestamp)
    }

    fun load(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            percent   = p.getInt(KEY_PERCENT, 0),
            label     = p.getString(KEY_LABEL, "No data") ?: "No data",
            timestamp = p.getLong(KEY_TIMESTAMP, 0L),
        ).also { _flow.value = it }
    }
}
