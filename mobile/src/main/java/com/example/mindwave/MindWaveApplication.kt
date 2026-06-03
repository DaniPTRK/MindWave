package com.example.mindwave

import android.app.Application
import com.example.mindwave.alert.StressAlertWorker
import com.example.mindwave.alert.StressNotificationHelper
import com.example.mindwave.fl.FLTrainingWorker
import com.example.mindwave.inference.StressInferenceRepository

/**
 * Application entry point which initialises the TFLite inference pipeline
 * so it's ready before any Activity or Service runs.
 */
class MindWaveApplication : Application() {
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
    }
}

