package com.example.mindwave.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
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
import com.example.mindwave.data.SensorDebugState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.sqrt

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

        val hrBuffer   = CopyOnWriteArrayList<TimestampedValue>()
        val tempBuffer = CopyOnWriteArrayList<TimestampedValue>()
        val edaBuffer  = CopyOnWriteArrayList<TimestampedValue>()
        val accXBuffer = CopyOnWriteArrayList<TimestampedValue>()
        val accYBuffer = CopyOnWriteArrayList<TimestampedValue>()
        val accZBuffer = CopyOnWriteArrayList<TimestampedValue>()

        // True only if the EDA tracker registered successfully.
        val edaAvailable = MutableStateFlow(false)
        // True only if the ACC tracker registered successfully.
        val accAvailable = MutableStateFlow(false)

        // Epoch-ms of the last HR sample received â€” used by the watchdog.
        val lastHrSampleAt = AtomicLong(0L)

        // Epoch-ms of the last phone-driven flush request. The 2-minute fallback
        // only fires when this timestamp is stale, preventing double flushes.
        val lastPhoneFlushRequestAt = AtomicLong(0L)

        /** Called from MainActivity.onResume so opening the app forces a reconnect check. */
        val reconnectRequested = AtomicBoolean(false)
    }

    data class TimestampedValue(val epochMs: Long, val value: Double)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Nullable so onDestroy is safe even if onCreate bailed out before initialising it
    private var measureClient: MeasureClient? = null

    // temp & eda
    private var healthTrackingService: HealthTrackingService? = null
    private var edaTracker: HealthTracker? = null
    private var tempTracker: HealthTracker? = null
    private var accTracker: HealthTracker? = null

    // Heart rate callbacks
    private val heartRateCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            Log.d(TAG, "HR availability: $availability")
            SensorDebugState.sensorStatus.value = "HR: $availability"
        }
        override fun onDataReceived(data: DataPointContainer) {
            val samples = data.getData(DataType.HEART_RATE_BPM)
            for (dp in samples) {
                val v = dp.value
                heartRate.value = v
                hrBuffer.add(TimestampedValue(System.currentTimeMillis(), v))
                lastHrSampleAt.set(System.currentTimeMillis())
                SensorDebugState.hrBpm.value = v
                SensorDebugState.lastKnownHrBpm.value = v
                SensorDebugState.hrBufferSize.value = hrBuffer.size
            }
        }
    }

    // Samsung EDA callbacks
    private val edaTrackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<SamsungDataPoint>) {
            for (dp in dataPoints) {
                try {
                    val eda = dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE).toDouble()
                    edaValue.value = eda
                    edaBuffer.add(TimestampedValue(System.currentTimeMillis(), eda))
                    SensorDebugState.edaMicroS.value = eda
                    SensorDebugState.lastKnownEdaMicroS.value = eda
                    SensorDebugState.edaBufSize.value = edaBuffer.size
                    Log.v(TAG, "EDA sample: ${"%.3f".format(eda)} ÂµS (buf=${edaBuffer.size})")
                } catch (e: Exception) {
                    Log.w(TAG, "EDA parse error: ${e.message}")
                }
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "EDA tracker error: ${error.name} â€” marking EDA unavailable")
            edaAvailable.value = false
            SensorDebugState.sensorStatus.value = "EDA error: ${error.name}"
        }
    }

    private val tempTrackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<SamsungDataPoint>) {
            for (dp in dataPoints) {
                try {
                    val temp = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE).toDouble()
                    if (temp == 0.0) {
                        Log.v(TAG, "TEMP value 0.0 â€” sensor not yet ready, skipping")
                        continue
                    }
                    skinTemp.value = temp
                    tempBuffer.add(TimestampedValue(System.currentTimeMillis(), temp))
                    SensorDebugState.tempC.value = temp
                    SensorDebugState.lastKnownTempC.value = temp   // persist last valid reading
                    SensorDebugState.tempBufSize.value = tempBuffer.size
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

    // Accelerometer callbacks â€” X/Y/Z are Integer ADC values
    private val accTrackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<SamsungDataPoint>) {
            val ts = System.currentTimeMillis()
            for (dp in dataPoints) {
                try {
                    val x = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X).toDouble()
                    val y = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y).toDouble()
                    val z = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z).toDouble()
                    accXBuffer.add(TimestampedValue(ts, x))
                    accYBuffer.add(TimestampedValue(ts, y))
                    accZBuffer.add(TimestampedValue(ts, z))
                    SensorDebugState.accMag.value = sqrt(x * x + y * y + z * z)
                    SensorDebugState.accBufSize.value = accXBuffer.size
                } catch (e: Exception) {
                    Log.w(TAG, "ACC parse error: ${e.message}")
                }
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "ACC error: ${error.name}")
        }
    }

    // Samsung connection listener
    private var samsungReconnectAttempt = 0
    private val MAX_RECONNECT_ATTEMPTS = 10
    private val BASE_RECONNECT_DELAY_MS = 3_000L
    // Guard: suppress onConnectionEnded during intentional disconnect (reconnect cycle)
    private var intentionalDisconnect = AtomicBoolean(false)

    private val samsungConnectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.i(TAG, "Samsung SDK connected")
            samsungReconnectAttempt = 0   // reset backoff
            SensorDebugState.sdkConnected.value = true
            SensorDebugState.sensorStatus.value = "Samsung SDK connected"
            startSamsungTrackers()
        }
        override fun onConnectionEnded() {
            // If we triggered the disconnect ourselves (inside scheduleReconnect), ignore.
            if (intentionalDisconnect.getAndSet(false)) {
                Log.d(TAG, "Samsung SDK disconnected (intentional, during reconnect cycle)")
                return
            }
            Log.w(TAG, "Samsung SDK disconnected")
            SensorDebugState.sdkConnected.value = false
            SensorDebugState.sensorStatus.value = "Samsung SDK disconnected â€” reconnectingâ€¦"
            scheduleReconnect()
        }
        override fun onConnectionFailed(error: HealthTrackerException) {
            Log.e(TAG, "Samsung SDK connection failed: ${error.message}")
            SensorDebugState.sdkConnected.value = false
            SensorDebugState.sensorStatus.value = "SDK error: ${error.message ?: error.errorCode} â€” retryingâ€¦"
            scheduleReconnect()
        }
    }

    /**
     * Schedules a reconnect attempt with exponential backoff (3 s, 6 s, 12 s, capped at 60 s).
     * Stops after MAX_RECONNECT_ATTEMPTS; the watchdog coroutine will eventually retry anyway
     * when it detects the HR stream has gone silent.
     */
    private fun scheduleReconnect() {
        if (samsungReconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Samsung SDK: max reconnect attempts reached, watchdog will retry")
            samsungReconnectAttempt = 0   // allow the watchdog to reset and try again later
            return
        }
        val delayMs = min(BASE_RECONNECT_DELAY_MS shl samsungReconnectAttempt, 60_000L)
        samsungReconnectAttempt++
        Log.i(TAG, "Samsung SDK reconnect attempt $samsungReconnectAttempt in ${delayMs}ms")
        scope.launch {
            delay(delayMs)
            try {
                // Set guard so onConnectionEnded triggered by disconnectService is ignored
                intentionalDisconnect.set(true)
                runCatching { healthTrackingService?.disconnectService() }
                delay(500)
                healthTrackingService = HealthTrackingService(samsungConnectionListener, this@SensorForegroundService)
                healthTrackingService?.connectService()
                SensorDebugState.sensorStatus.value = "Samsung SDK reconnecting (attempt $samsungReconnectAttempt)â€¦"
            } catch (e: Exception) {
                intentionalDisconnect.set(false)  // reset guard on failure
                Log.e(TAG, "Reconnect attempt $samsungReconnectAttempt failed: ${e.message}")
                SensorDebugState.sensorStatus.value = "Reconnect failed: ${e.message}"
                scheduleReconnect()   // keep trying
            }
        }
    }

    // Lifecycle
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate â€” ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
        createNotificationChannel()

        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

        val bodySensors = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
        val activityRecognition = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
        val readHR = ContextCompat.checkSelfPermission(
            this, "android.permission.health.READ_HEART_RATE"
        ) == PackageManager.PERMISSION_GRANTED

        val sufficient = bodySensors || (activityRecognition && readHR) || isSamsung
        Log.i(TAG, "Permissions: BODY_SENSORS=$bodySensors ACTIVITY_REC=$activityRecognition READ_HR=$readHR sufficient=$sufficient")

        if (!sufficient) {
            Log.w(TAG, "Insufficient permissions â€” stopping service")
            SensorDebugState.sensorStatus.value = "Missing permissions â€” reopen app"
            stopSelf()
            return
        }

        Log.i(TAG, "Calling startForeground (SDK=${Build.VERSION.SDK_INT}, isSamsung=$isSamsung)")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            Log.i(TAG, "startForeground complete â€” service is running")
            SensorDebugState.sensorStatus.value = "Service running (waiting for SDKâ€¦)"
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.javaClass.simpleName}: ${e.message}")
            SensorDebugState.sensorStatus.value = "startForeground failed: ${e.message}"
            stopSelf()
            return
        }

        measureClient = HealthServices.getClient(this).measureClient

        // On Samsung, HR comes from the Samsung SDK. On non-Samsung, use Jetpack HR.
        if (!isSamsung && readHR) {
            scope.launch {
                try {
                    measureClient?.registerMeasureCallback(DataType.HEART_RATE_BPM, heartRateCallback)
                    Log.i(TAG, "Jetpack HR measure callback registered")
                    SensorDebugState.sensorStatus.value = "HR active (Jetpack)"
                } catch (e: Exception) {
                    Log.w(TAG, "HR measure callback failed: ${e.message}")
                    SensorDebugState.sensorStatus.value = "HR failed: ${e.message}"
                }
            }
        } else if (!isSamsung) {
            Log.w(TAG, "READ_HEART_RATE not granted â€” Jetpack HR skipped")
            SensorDebugState.sensorStatus.value = "HR skipped (no READ_HEART_RATE)"
        }

        try {
            healthTrackingService = HealthTrackingService(samsungConnectionListener, this)
            healthTrackingService?.connectService()
            Log.i(TAG, "Samsung Health SDK connectingâ€¦")
        } catch (e: Exception) {
            Log.w(TAG, "Samsung Health SDK not available (${e.javaClass.simpleName})")
            SensorDebugState.sensorStatus.value = "Samsung SDK unavailable (${e.javaClass.simpleName})"
            if (isSamsung) {
                // Samsung but SDK failed â€” try Jetpack as fallback
                scope.launch {
                    try {
                        measureClient?.registerMeasureCallback(DataType.HEART_RATE_BPM, heartRateCallback)
                        Log.i(TAG, "Fallback: Jetpack HR registered after Samsung SDK failed")
                        SensorDebugState.sensorStatus.value = "HR active (Jetpack fallback)"
                    } catch (ex: Exception) {
                        Log.w(TAG, "Fallback HR also failed: ${ex.message}")
                        SensorDebugState.sensorStatus.value = "HR unavailable: ${ex.message}"
                    }
                }
            }
        }

        // Fallback flush: if the phone hasn't sent a request_flush message for 2 minutes
        // (e.g. phone app backgrounded, BT message lost), push the buffers anyway so
        // data is never stale for more than 2 windows.
        scope.launch {
            val FALLBACK_INTERVAL_MS = 120_000L   // 2 minutes, 2x the phone-driven cadence
            while (true) {
                delay(FALLBACK_INTERVAL_MS)
                try {
                    val isLoggedIn = com.example.mindwave.data.PhoneAuthState.isPhoneLoggedIn(applicationContext)
                    val lastUpdate = com.example.mindwave.data.PhoneAuthState.getLastUpdateTime(applicationContext)
                    val staleMs = if (lastUpdate > 0L) System.currentTimeMillis() - lastUpdate else -1L
                    if (!isLoggedIn) {
                        val staleDesc = if (staleMs < 0) "no update received yet" else "${staleMs / 1000}s old"
                        Log.i(TAG, "Phone not logged in â€” skipping fallback flush (auth state: $staleDesc)")
                        SensorDebugState.recordSkip("Phone not logged in")
                        continue
                    }
                    val lastPhoneFlush = lastPhoneFlushRequestAt.get()
                    val sincePhoneFlushMs = if (lastPhoneFlush > 0L) {
                        System.currentTimeMillis() - lastPhoneFlush
                    } else {
                        Long.MAX_VALUE
                    }
                    if (sincePhoneFlushMs < FALLBACK_INTERVAL_MS) {
                        Log.d(TAG, "Skipping fallback flush; phone requested one ${sincePhoneFlushMs / 1000}s ago")
                        continue
                    }

                    // Snapshot buffer sizes before sending (sendWindow clears them)
                    val hrN   = hrBuffer.size
                    val tempN = tempBuffer.size
                    val edaN  = edaBuffer.size
                    val accN  = accXBuffer.size
                    com.example.mindwave.sync.WearDataSender.sendWindow(applicationContext)
                    SensorDebugState.recordSendSuccess(hrN, tempN, edaN, accN)
                    // Reset live buffer size counters after flush
                    SensorDebugState.hrBufferSize.value  = hrBuffer.size
                    SensorDebugState.edaBufSize.value    = edaBuffer.size
                    SensorDebugState.tempBufSize.value   = tempBuffer.size
                    SensorDebugState.accBufSize.value    = accXBuffer.size
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send window: ${e.message}")
                    SensorDebugState.recordSendError(
                        hrBuffer.size, tempBuffer.size, edaBuffer.size, accXBuffer.size,
                        e.message ?: e.javaClass.simpleName
                    )
                }
            }
        }

        // Watchdog: if no HR sample has arrived for 3 minutes (or a reconnect was
        // explicitly requested from MainActivity.onResume), trigger a Samsung SDK reconnect.
        scope.launch {
            val WATCHDOG_INTERVAL_MS = 30_000L
            val HR_SILENCE_THRESHOLD_MS = 3 * 60_000L
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
                if (!isSamsung) continue

                val silenceMs = System.currentTimeMillis() - lastHrSampleAt.get()
                val forceReconnect = reconnectRequested.getAndSet(false)

                if (forceReconnect || (lastHrSampleAt.get() > 0 && silenceMs > HR_SILENCE_THRESHOLD_MS)) {
                    if (forceReconnect) {
                        Log.i(TAG, "Watchdog: reconnect requested by app foreground")
                    } else {
                        Log.w(TAG, "Watchdog: no HR data for ${silenceMs / 1000}s â€” triggering reconnect")
                    }
                    SensorDebugState.sensorStatus.value = "Watchdog reconnectingâ€¦"
                    samsungReconnectAttempt = 0   // reset so scheduleReconnect uses short delay
                    scheduleReconnect()
                }
            }
        }

        isRunning.value = true
    }

    private fun startSamsungTrackers() {
        val service = healthTrackingService ?: return
        Log.i(TAG, "startSamsungTrackers â€” registering Samsung Health SDK trackers")

        try {
            val hrTracker = service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            hrTracker.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<SamsungDataPoint>) {
                    val ts = System.currentTimeMillis()
                    for (dp in dataPoints) {
                        try {
                            val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)  // Int
                            val status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                            Log.d(TAG, "Samsung HR raw: $hr bpm (status=$status)")
                            if (hr > 0) {
                                val hrD = hr.toDouble()
                                heartRate.value = hrD
                                hrBuffer.add(TimestampedValue(ts, hrD))
                                lastHrSampleAt.set(ts)
                                SensorDebugState.hrBpm.value = hrD
                                SensorDebugState.lastKnownHrBpm.value = hrD
                                SensorDebugState.hrBufferSize.value = hrBuffer.size
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "HR parse error: ${e.message}")
                        }
                    }
                }
                override fun onFlushCompleted() {}
                override fun onError(e: HealthTracker.TrackerError) {
                    Log.e(TAG, "Samsung HR error: ${e.name}")
                    SensorDebugState.sensorStatus.value = "HR error: ${e.name}"
                }
            })
            Log.i(TAG, "Samsung HR tracker registered")
            SensorDebugState.sensorStatus.value = "HR active (Samsung SDK)"
        } catch (e: Exception) {
            Log.w(TAG, "Samsung HR tracker unavailable: ${e.message}")
            SensorDebugState.sensorStatus.value = "HR unavailable: ${e.message}"
        }

        try {
            edaTracker = service.getHealthTracker(HealthTrackerType.EDA_CONTINUOUS)
            edaTracker?.setEventListener(edaTrackerListener)
            edaAvailable.value = true
            Log.i(TAG, "EDA tracker registered (EDA_CONTINUOUS) âœ“")
            SensorDebugState.sensorStatus.value = "EDA active (EDA_CONTINUOUS)"
        } catch (e: Exception) {
            Log.w(TAG, "EDA_CONTINUOUS unavailable: ${e.message} â€” trying SWEAT_LOSS fallback")
            // Fallback: try legacy SWEAT_LOSS tracker name
            try {
                edaTracker = service.getHealthTracker(HealthTrackerType.SWEAT_LOSS)
                edaTracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                    override fun onDataReceived(dataPoints: List<SamsungDataPoint>) {
                        for (dp in dataPoints) {
                            try {
                                val eda = dp.getValue(ValueKey.SweatLossSet.SWEAT_LOSS).toDouble()
                                edaValue.value = eda
                                edaBuffer.add(TimestampedValue(System.currentTimeMillis(), eda))
                                SensorDebugState.edaMicroS.value = eda
                                SensorDebugState.lastKnownEdaMicroS.value = eda
                                SensorDebugState.edaBufSize.value = edaBuffer.size
                                Log.v(TAG, "EDA (SweatLoss) sample: ${"%.3f".format(eda)} (buf=${edaBuffer.size})")
                            } catch (ex: Exception) {
                                Log.w(TAG, "SweatLoss parse error: ${ex.message}")
                            }
                        }
                    }
                    override fun onFlushCompleted() {}
                    override fun onError(error: HealthTracker.TrackerError) {
                        Log.e(TAG, "SweatLoss tracker error: ${error.name}")
                        edaAvailable.value = false
                        SensorDebugState.sensorStatus.value = "EDA(SweatLoss) error: ${error.name}"
                    }
                })
                edaAvailable.value = true
                Log.i(TAG, "EDA tracker registered (SWEAT_LOSS fallback) âœ“")
                SensorDebugState.sensorStatus.value = "EDA active (SWEAT_LOSS)"
            } catch (ex: Exception) {
                edaAvailable.value = false
                Log.e(TAG, "EDA tracker UNAVAILABLE (tried EDA_CONTINUOUS + SWEAT_LOSS): ${e.message} / ${ex.message}")
                SensorDebugState.sensorStatus.value = "EDA unavailable â€” check READ_ADDITIONAL_HEALTH_DATA"
            }
        }

        try {
            tempTracker = service.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
            tempTracker?.setEventListener(tempTrackerListener)
            Log.i(TAG, "TEMP tracker registered")
        } catch (e: Exception) {
            Log.w(TAG, "TEMP tracker failed: ${e.message}")
        }

        // Accelerometer â€” ACCELEROMETER_CONTINUOUS, Integer ADC values
        try {
            accTracker = service.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)
            accTracker?.setEventListener(accTrackerListener)
            accAvailable.value = true
            Log.i(TAG, "ACC tracker registered")
        } catch (e: Exception) {
            accAvailable.value = false
            Log.w(TAG, "ACC tracker unavailable: ${e.message}")
        }
    }

    override fun onDestroy() {
        try {
            measureClient?.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, heartRateCallback)
                ?.addListener({ Log.d(TAG, "HR unregistered") }, Runnable::run)
        } catch (e: Exception) { Log.e(TAG, "HR unregister failed: ${e.message}") }
        try {
            edaTracker?.unsetEventListener()
            tempTracker?.unsetEventListener()
            accTracker?.unsetEventListener()
            healthTrackingService?.disconnectService()
        } catch (e: Exception) { Log.e(TAG, "Samsung cleanup error: ${e.message}") }
        edaTracker = null
        tempTracker = null
        accTracker = null
        healthTrackingService = null
        measureClient = null
        isRunning.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val bodySensors = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
        val activityRec = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
        val readHR = ContextCompat.checkSelfPermission(
            this, "android.permission.health.READ_HEART_RATE"
        ) == PackageManager.PERMISSION_GRANTED
        val sufficient = bodySensors || (activityRec && readHR) || isSamsung
        return if (sufficient) START_STICKY else START_NOT_STICKY
    }

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
            .setContentText("Monitoring HRV, EDA, TEMPâ€¦")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
}


