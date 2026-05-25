package com.example.mindwave.alert

import android.content.Context
import android.util.Log
import androidx.work.*
import androidx.work.WorkerParameters
import com.example.mindwave.data.MindWaveDatabase
import com.example.mindwave.data.SettingsRepository
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Periodic worker which runs every 60s, checking the latest StressReading
 * and fires a notification if the stress probability exceeds a threshold.
 */
class StressAlertWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        const val TAG = "StressAlertWorker"
        private const val WORK_NAME = "mindwave_stress_alert"

        /**
         * Enqueue a recurring check.
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<StressAlertWorker>(
                1, TimeUnit.MINUTES,
                30, TimeUnit.SECONDS
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }

    override suspend fun doWork(): Result {
        val db = MindWaveDatabase.getInstance(ctx)
        val settings = SettingsRepository(ctx).current()

        // Get the most recent reading
        val readings = db.stressDao().getLatestSync(1)
        if (readings.isEmpty())
            return Result.success()

        val latest = readings.first()

        // Respect the user-configured alert threshold
        if (latest.stressProbStress < settings.alertThreshold)
            return Result.success()

        if (settings.quietHoursEnabled) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (SettingsRepository.isWithinQuietHours(
                    hour, settings.quietStartHour, settings.quietEndHour
                )
            ) {
                Log.i(TAG, "Stress high but suppressed by quiet hours (hour=$hour)")
                return Result.success()
            }
        }

        // fire alert
        val xai = db.xaiDao().getByReadingId(latest.id)
        val topFeature = xai.minByOrNull { it.rank }?.featureName ?: "HRV"
        val percent = (latest.stressProbStress * 100).toInt()
        StressNotificationHelper.fireAlert(ctx, percent, topFeature)
        Log.i(TAG, "Stress alert fired: $percent% | top=$topFeature")
        return Result.success()
    }
}