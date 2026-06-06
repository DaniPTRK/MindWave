package com.example.mindwave.alert

import android.content.Context
import android.util.Log
import androidx.work.*
import androidx.work.WorkerParameters
import com.example.mindwave.data.AuthRepository
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
        private const val PREFS_NAME = "stress_alert_prefs"
        private const val KEY_LAST_ALERTED_ID = "last_alerted_reading_id"

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
        val authRepo = AuthRepository(ctx)
        val settings = SettingsRepository(ctx).current()

        val readings = db.stressDao().getLatestSync(1, userEmail = authRepo.getEmail() ?: "")
        if (readings.isEmpty()) return Result.success()

        val latest = readings.first()

        if (latest.stressProbStress < settings.alertThreshold)
            return Result.success()

        // Deduplicate: only alert once per unique reading.
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastAlertedId = prefs.getLong(KEY_LAST_ALERTED_ID, -1L)
        if (latest.id == lastAlertedId) {
            Log.d(TAG, "Already alerted for reading ${latest.id} — suppressing duplicate")
            return Result.success()
        }

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

        val xai = db.xaiDao().getByReadingId(latest.id)
        val topFeature = xai.minByOrNull { it.rank }?.featureName ?: "HRV"
        val percent = (latest.stressProbStress * 100).toInt()
        StressNotificationHelper.fireAlert(ctx, percent, topFeature)

        // Record this reading as alerted so we don't fire again for the same event.
        prefs.edit().putLong(KEY_LAST_ALERTED_ID, latest.id).apply()
        Log.i(TAG, "Stress alert fired: $percent% | top=$topFeature | readingId=${latest.id}")
        return Result.success()
    }
}