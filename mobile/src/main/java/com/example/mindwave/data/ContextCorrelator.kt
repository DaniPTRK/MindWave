package com.example.mindwave.data

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Correlates an external context (weather, and optionally calendar) with a
 * stress reading and persists it as a ContextEvent.
 *
 * All context is gathered and stored locally; nothing is uploaded. Call
 * correlate right after a new stress reading is inserted so the dashboard
 * and stress-detail screens can explain why stress may be elevated.
 */
class ContextCorrelator(context: Context) {

    private val appContext = context.applicationContext
    private val db = MindWaveDatabase.getInstance(appContext)
    private val weatherRepo = WeatherRepository()
    private val settingsRepo = SettingsRepository(appContext)

    /**
     * Fetch + store context for a reading.
     */
    suspend fun correlate(reading: StressReading) {
        runCatching {
            weatherRepo.current().getOrNull()?.let { weather ->
                db.contextDao().insert(
                    ContextEvent(
                        readingId = reading.id,
                        timestamp = reading.timestamp,
                        source = "weather",
                        title = "${weather.summary}, ${weather.temperatureC.toInt()}°C",
                        metadata = JSONObject()
                            .put("temperatureC", weather.temperatureC)
                            .put("weatherCode", weather.weatherCode)
                            .toString(),
                    )
                )
            }
        }.onFailure { Log.w(TAG, "Weather correlation failed: ${it.message}") }

        // Calendar correlation is opt-in and only enabled when the user grants it
        val settings = settingsRepo.current()
        if (settings.calendarEnabled) {
            // TODO: implement calendar correlation using Calendar Provider API
            Log.d(TAG, "Calendar correlation enabled but no account linked yet")
        }
    }

    companion object {
        private const val TAG = "ContextCorrelator"
    }
}
