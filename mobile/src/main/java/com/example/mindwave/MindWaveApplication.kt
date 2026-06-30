package com.example.mindwave

import android.app.Application
import com.example.mindwave.alert.StressAlertWorker
import com.example.mindwave.alert.StressNotificationHelper
import com.example.mindwave.data.AuthRepository
import com.example.mindwave.fl.FLTrainingWorker
import com.example.mindwave.inference.StressInferenceRepository
import com.example.mindwave.sync.WatchAuthNotifier
import com.example.mindwave.sync.WatchConnectionListener
import com.example.mindwave.sync.WatchFlushRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point which initialises the TFLite inference pipeline
 * so it's ready before any Activity or Service runs.
 */
class MindWaveApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var watchConnectionListener: WatchConnectionListener

    override fun onCreate() {
        super.onCreate()
        // Register the notification channel before any worker tries to fire an alert.
        StressNotificationHelper.createChannel(this)

        // Boot the inference repository: loads the TFLite model and wires
        // MobileDataListenerService.onWindowReceived → Room persistence.
        StressInferenceRepository.init(this)

        // Recurring stress-alert check (respects threshold + quiet hours).
        StressAlertWorker.enqueue(this)

        // Schedule the daily FL training round (2 AM, battery > 80% or charging).
        // Uses keep policy so repeated app starts don't reset the timer.
        FLTrainingWorker.enqueue(this)

        // Start watch connection listener to auto-publish auth state when watch connects
        watchConnectionListener = WatchConnectionListener(this)
        watchConnectionListener.start()

        // Start the periodic flush-request loop: phone pings watch every 60s to flush
        // its sensor buffers. The watch has a 2-min fallback timer if messages are lost.
        WatchFlushRequester.start(this, intervalMs = 60_000L)

        // Also sync current auth state with the watch immediately on app startup.
        // Both online and offline sessions are "active" — only full logout stops the watch.
        appScope.launch {
            val authRepo = AuthRepository(this@MindWaveApplication)
            val isActive = authRepo.isLoggedIn() // true for both real accounts AND offline mode
            val email = authRepo.getEmail() ?: ""
            WatchAuthNotifier.notifyWatch(this@MindWaveApplication, isActive, email)
        }
    }
}

