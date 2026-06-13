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

// Single DataStore file; keys are namespaced per account via a prefix.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mindwave_settings")

/**
 * User-tunable settings persisted via DataStore, namespaced per account.
 *
 * Pass the current user's email so each account gets its own key prefix,
 * preventing settings from leaking between accounts on the same device.
 */
class SettingsRepository(private val context: Context, private val userEmail: String = "") {

    // Sanitize the email to a safe key prefix: lowercase, non-alphanumeric → "_"
    private val prefix = userEmail.lowercase().replace(Regex("[^a-z0-9]"), "_").take(40)
        .let { if (it.isBlank()) "default" else it }

    data class Settings(
        val alertThreshold: Float = 0.85f,
        val flEnabled: Boolean = true,
        val calendarEnabled: Boolean = false,
        val quietHoursEnabled: Boolean = true,
        val quietStartHour: Int = 22,
        val quietEndHour: Int = 7,
    )

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            alertThreshold = p[keyThreshold] ?: 0.85f,
            flEnabled = p[keyFl] ?: true,
            calendarEnabled = p[keyCalendar] ?: false,
            quietHoursEnabled = p[keyQuietOn] ?: true,
            quietStartHour = p[keyQuietStart] ?: 22,
            quietEndHour = p[keyQuietEnd] ?: 7,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setThreshold(value: Float) = edit { it[keyThreshold] = value }
    suspend fun setFlEnabled(value: Boolean) = edit { it[keyFl] = value }
    suspend fun setCalendarEnabled(value: Boolean) = edit { it[keyCalendar] = value }
    suspend fun setQuietHoursEnabled(value: Boolean) = edit { it[keyQuietOn] = value }
    suspend fun setQuietWindow(startHour: Int, endHour: Int) = edit {
        it[keyQuietStart] = startHour
        it[keyQuietEnd] = endHour
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    // Per-user preference keys, prefixed with the sanitized email slug
    private val keyThreshold  = floatPreferencesKey("${prefix}_alert_threshold")
    private val keyFl         = booleanPreferencesKey("${prefix}_fl_enabled")
    private val keyCalendar   = booleanPreferencesKey("${prefix}_calendar_enabled")
    private val keyQuietOn    = booleanPreferencesKey("${prefix}_quiet_hours_enabled")
    private val keyQuietStart = intPreferencesKey("${prefix}_quiet_start_hour")
    private val keyQuietEnd   = intPreferencesKey("${prefix}_quiet_end_hour")

    companion object {
        fun isWithinQuietHours(hour: Int, startHour: Int, endHour: Int): Boolean =
            if (startHour <= endHour) hour in startHour until endHour
            else hour >= startHour || hour < endHour
    }
}