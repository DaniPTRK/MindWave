package com.example.mindwave.sensor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint as SamsungDataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Service which pedals the biometric sensor from watch to phone. Runs in the foreground to ensure
 * continuous collection.
 * Raw BVP/HR, EDA and skin temps samples are buffered for 60s and
 * streamed to the phone via the Wear Data Layer.
 * The phone is responsible for inference, XAI and Room persistence. This
 * service is used only for extracting sensor data.
 */
class SensorForegroundService : Service() {

    companion object {
        const val TAG = "MWSensor"
        private const val CHANNEL_ID = "mindwave_sensor_channel"
        private const val NOTIFICATION_ID = 1

        val heartRate = MutableStateFlow(0.0)
        val skinTemp = MutableStateFlow(0.0)
        val edaValue = MutableStateFlow(0.0)
        val isRunning = MutableStateFlow(false)

        val hrBuffer = CopyOnWriteArrayList<TimestampedValue>()
        val tempBuffer = CopyOnWriteArrayList<TimestampedValue>()
        val edaBuffer = CopyOnWriteArrayList<TimestampedValue>()

        // True only if the EDA tracker registered successfully.
        val edaAvailable = MutableStateFlow(false)
    }

    data class TimestampedValue(val epochMs: Long, val value: Double)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var measureClient: MeasureClient

    // temp & eda
    private var healthTrackingService: HealthTrackingService? = null
    private var edaTracker: HealthTracker? = null
    private var tempTracker: HealthTracker? = null

    // Heart rate callbacks
    private val heartRateCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            Log.d(TAG, "HR availability: $availability")
        }
        override fun onDataReceived(data: DataPointContainer) {
            val samples = data.getData(DataType.HEART_RATE_BPM)
            for (dp in samples) {
                val v = dp.value
                heartRate.value = v
                hrBuffer.add(TimestampedValue(System.currentTimeMillis(), v))
            }
        }
    }

    // Samsung EDA callbacks
    private val edaTrackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<SamsungDataPoint>) {
            for (dp in dataPoints) {
                try {
                    val eda = dp.getValue(ValueKey.SweatLossSet.SWEAT_LOSS).toDouble()
                    edaValue.value = eda
                    edaBuffer.add(TimestampedValue(System.currentTimeMillis(), eda))
                } catch (e: Exception) {
                    Log.w(TAG, "EDA parse error: ${e.message}")
                }
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "EDA error: ${error.name}")
        }
    }

    // Samsung skin temp callbacks
    private val tempTrackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<SamsungDataPoint>) {
            for (dp in dataPoints) {
                try {
                    val temp = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE).toDouble()
                    skinTemp.value = temp
                    tempBuffer.add(TimestampedValue(System.currentTimeMillis(), temp))
                } catch (e: Exception) {
                    Log.w(TAG, "TEMP parse error: ${e.message}")
                }
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "TEMP error: ${error.name}")
        }
    }

    // Samsung connection listener
    private val samsungConnectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.i(TAG, "Samsung SDK connected")
            startSamsungTrackers()
        }
        override fun onConnectionEnded() {
            Log.w(TAG, "Samsung SDK disconnected")
        }
        override fun onConnectionFailed(error: HealthTrackerException) {
            Log.e(TAG, "Samsung SDK connection failed: ${error.message}")
        }
    }

    // Lifecycle
    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        measureClient = HealthServices.getClient(this).measureClient

        scope.launch {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, heartRateCallback)
        }

        try {
            healthTrackingService = HealthTrackingService(samsungConnectionListener, this)
            healthTrackingService?.connectService()
        } catch (e: Exception) {
            Log.e(TAG, "Samsung SDK not available: ${e.message}")
        }

        // Flush sensor buffers to phone every 60 seconds
        scope.launch {
            while (true) {
                delay(60_000L)
                try {
                    com.example.mindwave.sync.WearDataSender.sendWindow(applicationContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send window: ${e.message}")
                }
            }
        }

        isRunning.value = true
    }

    private fun startSamsungTrackers() {
        val service = healthTrackingService ?: return
        try {
            edaTracker = service.getHealthTracker(HealthTrackerType.SWEAT_LOSS)
            edaTracker?.setEventListener(edaTrackerListener)
            edaAvailable.value = true
            Log.i(TAG, "EDA tracker registered")
        } catch (e: Exception) {
            edaAvailable.value = false
            Log.w(TAG, "EDA tracker unavailable on this device: ${e.message}")
        }
        try {
            tempTracker = service.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
            tempTracker?.setEventListener(tempTrackerListener)
        } catch (e: Exception) { Log.e(TAG, "TEMP tracker failed: ${e.message}") }
    }

    override fun onDestroy() {
        try {
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, heartRateCallback)
                .addListener({ Log.d(TAG, "HR unregistered") }, Runnable::run)
        } catch (e: Exception) { Log.e(TAG, "HR unregister failed: ${e.message}") }
        try {
            edaTracker?.unsetEventListener()
            tempTracker?.unsetEventListener()
            healthTrackingService?.disconnectService()
        } catch (e: Exception) { Log.e(TAG, "Samsung cleanup error: ${e.message}") }
        edaTracker = null
        tempTracker = null
        healthTrackingService = null
        isRunning.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MindWave Sensors", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Continuous biometric monitoring" }
        )
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MindWave")
            .setContentText("Monitoring HRV, EDA, TEMP…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
}