package com.example.mindwave.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mindwave_settings")

/**
 * User-tunable settings persisted via DataStore.
 *
 * Holds the stress-alert threshold, FL participation, calendar integration,
 * and do not disturb hours used by the stress alert worker.
 */
class SettingsRepository(private val context: Context) {

    data class Settings(
        val alertThreshold: Float = 0.85f,
        val flEnabled: Boolean = true,
        val calendarEnabled: Boolean = false,
        val quietHoursEnabled: Boolean = true,
        val quietStartHour: Int = 22,   // 22:00
        val quietEndHour: Int = 7,      // 07:00
    )

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            alertThreshold = p[KEY_THRESHOLD] ?: 0.85f,
            flEnabled = p[KEY_FL] ?: true,
            calendarEnabled = p[KEY_CALENDAR] ?: false,
            quietHoursEnabled = p[KEY_QUIET_ON] ?: true,
            quietStartHour = p[KEY_QUIET_START] ?: 22,
            quietEndHour = p[KEY_QUIET_END] ?: 7,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setThreshold(value: Float) = edit { it[KEY_THRESHOLD] = value }
    suspend fun setFlEnabled(value: Boolean) = edit { it[KEY_FL] = value }
    suspend fun setCalendarEnabled(value: Boolean) = edit { it[KEY_CALENDAR] = value }
    suspend fun setQuietHoursEnabled(value: Boolean) = edit { it[KEY_QUIET_ON] = value }
    suspend fun setQuietWindow(startHour: Int, endHour: Int) = edit {
        it[KEY_QUIET_START] = startHour
        it[KEY_QUIET_END] = endHour
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    companion object {
        private val KEY_THRESHOLD = floatPreferencesKey("alert_threshold")
        private val KEY_FL = booleanPreferencesKey("fl_enabled")
        private val KEY_CALENDAR = booleanPreferencesKey("calendar_enabled")
        private val KEY_QUIET_ON = booleanPreferencesKey("quiet_hours_enabled")
        private val KEY_QUIET_START = intPreferencesKey("quiet_start_hour")
        private val KEY_QUIET_END = intPreferencesKey("quiet_end_hour")
        fun isWithinQuietHours(hour: Int, startHour: Int, endHour: Int): Boolean =
            if (startHour <= endHour) hour in startHour until endHour
            else hour >= startHour || hour < endHour
    }
}