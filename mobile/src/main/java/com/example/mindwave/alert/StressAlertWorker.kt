package com.example.mindwave.alert

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.mindwave.data.AuthRepository
import com.example.mindwave.data.MindWaveDatabase
import com.example.mindwave.data.SettingsRepository
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Periodic fallback worker for stress alerts.
 *
 * Alert triggering uses a 3-minute rolling mean. The user-configured notification interval
 * is treated as a cooldown between alerts.
 */
class StressAlertWorker(
    private val ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val TAG = "StressAlertWorker"
        private const val WORK_NAME = "mindwave_stress_alert"
        private const val PREFS_NAME = "stress_alert_prefs"
        private const val KEY_LAST_ALERTED_ID = "last_alerted_reading_id"
        private const val KEY_LAST_ALERTED_AT = "last_alerted_at_ms"

        private const val WORKER_PERIOD_MINUTES = 30L
        private const val ROLLING_ALERT_WINDOW_MS = 3 * 60_000L

        fun enqueue(context: Context) = schedule(context, WORKER_PERIOD_MINUTES)

        fun reschedule(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            schedule(context, WORKER_PERIOD_MINUTES)
        }

        private fun schedule(context: Context, periodMinutes: Long) {
            val request = PeriodicWorkRequestBuilder<StressAlertWorker>(
                periodMinutes,
                TimeUnit.MINUTES,
                periodMinutes / 2,
                TimeUnit.MINUTES,
            ).setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .build(),
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request,
            )
            Log.i(TAG, "Stress alert worker scheduled (period=${periodMinutes}min)")
        }

        suspend fun evaluateAndMaybeAlert(context: Context): Boolean {
            val db = MindWaveDatabase.getInstance(context)
            val authRepo = AuthRepository(context)
            val email = authRepo.getEmail() ?: ""
            val settings = SettingsRepository(context, email).current()
            val now = System.currentTimeMillis()

            val recent = db.stressDao().getByRangeSync(
                startTs = now - ROLLING_ALERT_WINDOW_MS,
                endTs = now,
                userEmail = email,
            )
            if (recent.isEmpty()) return false

            val rollingMean = recent.map { it.stressProbStress }.average().toFloat()
            if (rollingMean < settings.alertThreshold) return false

            val latest = recent.maxByOrNull { it.timestamp } ?: return false
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastAlertedId = prefs.getLong(KEY_LAST_ALERTED_ID, -1L)
            val lastAlertedAt = prefs.getLong(KEY_LAST_ALERTED_AT, 0L)
            val intervalMs = settings.notificationIntervalMinutes * 60_000L

            if (latest.id == lastAlertedId) {
                Log.d(TAG, "Already alerted for reading ${latest.id}; suppressing duplicate")
                return false
            }
            if (now - lastAlertedAt < intervalMs) {
                val waitMin = (intervalMs - (now - lastAlertedAt)) / 60_000
                Log.i(TAG, "Alert cooldown active; next alert in ${waitMin}min")
                return false
            }

            if (settings.quietHoursEnabled) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (SettingsRepository.isWithinQuietHours(
                        hour,
                        settings.quietStartHour,
                        settings.quietEndHour,
                    )
                ) {
                    Log.i(TAG, "Rolling stress high but suppressed by quiet hours (hour=$hour)")
                    return false
                }
            }

            val xai = db.xaiDao().getByReadingId(latest.id)
            val topFeatureName = xai.minByOrNull { it.rank }?.featureName ?: "hrv"
            val topFactor = when {
                topFeatureName.startsWith("hrv") -> "Heart rhythm"
                topFeatureName.startsWith("eda") -> "Sweat response"
                topFeatureName.startsWith("temp") -> "Skin temperature"
                topFeatureName.startsWith("acc") -> "Movement"
                else -> topFeatureName
            }

            val percent = (rollingMean * 100).toInt()
            StressNotificationHelper.fireAlert(context, percent, topFactor)

            prefs.edit()
                .putLong(KEY_LAST_ALERTED_ID, latest.id)
                .putLong(KEY_LAST_ALERTED_AT, now)
                .apply()

            Log.i(
                TAG,
                "Stress alert fired: rolling=$percent% | samples=${recent.size} | top=$topFactor | readingId=${latest.id} | cooldown=${settings.notificationIntervalMinutes}min",
            )
            return true
        }
    }

    override suspend fun doWork(): Result {
        evaluateAndMaybeAlert(ctx)
        return Result.success()
    }
}
