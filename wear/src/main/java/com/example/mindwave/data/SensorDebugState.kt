package com.example.mindwave.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Window that shows live debugging state observable from the UI.
 * Updated by SensorForegroundService and WearDataSender, read by DebugScreen.
 */
object SensorDebugState {

    // Sensor readings
    val hrBpm         = MutableStateFlow(0.0)
    val tempC         = MutableStateFlow(0.0)
    val edaMicroS     = MutableStateFlow(0.0)
    val accMag        = MutableStateFlow(0.0)

    // Last known non-zero
    val lastKnownTempC    = MutableStateFlow(0.0)
    val lastKnownEdaMicroS = MutableStateFlow(0.0)
    val lastKnownHrBpm    = MutableStateFlow(0.0)

    // buffer size since last flush
    val hrBufferSize  = MutableStateFlow(0)
    val edaBufSize    = MutableStateFlow(0)
    val tempBufSize   = MutableStateFlow(0)
    val accBufSize    = MutableStateFlow(0)

    // Tracker status
    val sensorStatus  = MutableStateFlow("Initialising…")
    val sdkConnected  = MutableStateFlow(false)

    // Last send results
    data class SendEvent(
        val time: String,
        val hrSamples: Int,
        val tempSamples: Int,
        val edaSamples: Int,
        val accSamples: Int,
        val success: Boolean,
        val error: String = "",
    )

    private val _lastSend = MutableStateFlow<SendEvent?>(null)
    val lastSend: StateFlow<SendEvent?> = _lastSend.asStateFlow()

    private val _sendLog = MutableStateFlow<List<SendEvent>>(emptyList())
    val sendLog: StateFlow<List<SendEvent>> = _sendLog.asStateFlow()

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun recordSendSuccess(hrN: Int, tempN: Int, edaN: Int, accN: Int) {
        val e = SendEvent(fmt.format(Date()), hrN, tempN, edaN, accN, success = true)
        _lastSend.value = e
        _sendLog.value = (_sendLog.value + e).takeLast(20)
    }

    fun recordSendError(hrN: Int, tempN: Int, edaN: Int, accN: Int, err: String) {
        val e = SendEvent(fmt.format(Date()), hrN, tempN, edaN, accN, success = false, error = err)
        _lastSend.value = e
        _sendLog.value = (_sendLog.value + e).takeLast(20)
    }

    fun recordSkip(reason: String) {
        val e = SendEvent(fmt.format(Date()), 0, 0, 0, 0, success = false, error = "SKIP: $reason")
        _lastSend.value = e
        _sendLog.value = (_sendLog.value + e).takeLast(20)
    }
}

