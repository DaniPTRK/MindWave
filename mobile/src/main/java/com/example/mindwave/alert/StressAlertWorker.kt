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
 * Periodic worker that checks the latest StressReading and fires a
 * notification if stress exceeds the threshold AND the minimum notification
 * interval (30 / 60 / 120 min, user-configurable) has elapsed since the
 * last alert.
 */
class StressAlertWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        const val TAG = "StressAlertWorker"
        private const val WORK_NAME = "mindwave_stress_alert"
        private const val PREFS_NAME = "stress_alert_prefs"
        private const val KEY_LAST_ALERTED_ID  = "last_alerted_reading_id"
        private const val KEY_LAST_ALERTED_AT  = "last_alerted_at_ms"

        /** Minimum WorkManager period floor (30 min). Worker checks the user interval internally. */
        private const val WORKER_PERIOD_MINUTES = 30L

        fun enqueue(context: Context) = schedule(context, WORKER_PERIOD_MINUTES)

        /** Call this when the user changes the notification interval so the worker re-registers. */
        fun reschedule(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            schedule(context, WORKER_PERIOD_MINUTES)
        }

        private fun schedule(context: Context, periodMinutes: Long) {
            val request = PeriodicWorkRequestBuilder<StressAlertWorker>(
                periodMinutes, TimeUnit.MINUTES,
                periodMinutes / 2, TimeUnit.MINUTES,  // flex window = half the period
            ).setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .build()
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,   // always use latest registration
                request
            )
            Log.i(TAG, "Stress alert worker scheduled (period=${periodMinutes}min)")
        }
    }

    override suspend fun doWork(): Result {
        val db = MindWaveDatabase.getInstance(ctx)
        val authRepo = AuthRepository(ctx)
        val settings = SettingsRepository(ctx).current()

        val readings = db.stressDao().getLatestSync(1, userEmail = authRepo.getEmail() ?: "")
        if (readings.isEmpty()) return Result.success()

        val latest = readings.first()
        if (latest.stressProbStress < settings.alertThreshold) return Result.success()

        // Respect user-configured minimum interval between notifications
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastAlertedId = prefs.getLong(KEY_LAST_ALERTED_ID, -1L)
        val lastAlertedAt = prefs.getLong(KEY_LAST_ALERTED_AT, 0L)
        val intervalMs = settings.notificationIntervalMinutes * 60_000L
        val now = System.currentTimeMillis()

        if (latest.id == lastAlertedId) {
            Log.d(TAG, "Already alerted for reading ${latest.id} — suppressing duplicate")
            return Result.success()
        }
        if (now - lastAlertedAt < intervalMs) {
            val waitMin = (intervalMs - (now - lastAlertedAt)) / 60_000
            Log.i(TAG, "Interval not elapsed — next alert in ${waitMin}min")
            return Result.success()
        }

        if (settings.quietHoursEnabled) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (SettingsRepository.isWithinQuietHours(
                    hour, settings.quietStartHour, settings.quietEndHour)) {
                Log.i(TAG, "Stress high but suppressed by quiet hours (hour=$hour)")
                return Result.success()
            }
        }

        val xai = db.xaiDao().getByReadingId(latest.id)
        val topFeatureName = xai.minByOrNull { it.rank }?.featureName ?: "hrv"
        // Map raw feature prefix to friendly group label for the notification
        val topFactor = when {
            topFeatureName.startsWith("hrv")  -> "Heart rhythm"
            topFeatureName.startsWith("eda")  -> "Sweat response"
            topFeatureName.startsWith("temp") -> "Skin temperature"
            topFeatureName.startsWith("acc")  -> "Movement"
            else                              -> topFeatureName
        }
        val percent = (latest.stressProbStress * 100).toInt()
        StressNotificationHelper.fireAlert(ctx, percent, topFactor)

        prefs.edit()
            .putLong(KEY_LAST_ALERTED_ID, latest.id)
            .putLong(KEY_LAST_ALERTED_AT, now)
            .apply()
        Log.i(TAG, "Stress alert fired: $percent% | top=$topFactor | readingId=${latest.id} | interval=${settings.notificationIntervalMinutes}min")
        return Result.success()
    }
}