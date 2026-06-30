package com.example.mindwave.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mindwave.alert.StressAlertWorker
import com.example.mindwave.alert.StressNotificationHelper
import com.example.mindwave.data.AuthRepository
import com.example.mindwave.data.MindWaveDatabase
import com.example.mindwave.data.OFFLINE_EMAIL
import com.example.mindwave.data.SettingsRepository
import com.example.mindwave.data.StressReading
import com.example.mindwave.demo.DemoDataSeeder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Backs ProfileScreen by exposing persisted settings + account info and
 * forwards updates to [SettingsRepository] / [AuthRepository].
 */
class ProfileViewModel(app: Application) : AndroidViewModel(app) {

    private val authRepo = AuthRepository(app)
    private val settingsRepo = SettingsRepository(app, authRepo.getEmail() ?: "")
    private val db = MindWaveDatabase.getInstance(app)

    val email: String = authRepo.getEmail()?.let {
        if (it == OFFLINE_EMAIL) "Offline user" else it
    } ?: "Signed in"

    val isOffline: Boolean = authRepo.isOfflineUser()

    val settings: StateFlow<SettingsRepository.Settings> =
        settingsRepo.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.Settings(),
        )

    fun setThreshold(value: Float) = viewModelScope.launch { settingsRepo.setThreshold(value) }
    fun setFlEnabled(value: Boolean) = viewModelScope.launch { settingsRepo.setFlEnabled(value) }
    fun setCalendarEnabled(value: Boolean) = viewModelScope.launch { settingsRepo.setCalendarEnabled(value) }
    fun setQuietHoursEnabled(value: Boolean) = viewModelScope.launch { settingsRepo.setQuietHoursEnabled(value) }
    fun setQuietWindow(startHour: Int, endHour: Int) =
        viewModelScope.launch { settingsRepo.setQuietWindow(startHour, endHour) }
    fun setNotificationInterval(minutes: Int) = viewModelScope.launch {
        settingsRepo.setNotificationInterval(minutes)
        StressAlertWorker.reschedule(getApplication())
    }

    /** Wipes all on-device data AND calls DELETE /users/me on the server, then logs out. */
    fun deleteLocalData(onDone: () -> Unit) {
        viewModelScope.launch {
            // Server side deletion — skip for offline users
            if (!authRepo.isOfflineUser()) {
                val token = authRepo.getToken()
                if (token != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            (URL("${AuthRepository.BASE_URL}/users/me").openConnection() as HttpURLConnection).apply {
                                requestMethod = "DELETE"
                                setRequestProperty("Authorization", "Bearer $token")
                                connectTimeout = 10_000
                                readTimeout = 10_000
                                connect()
                                if (responseCode !in 200..299) {
                                    android.util.Log.w("ProfileViewModel",
                                        "Server account deletion returned HTTP $responseCode")
                                }
                            }
                        }
                    }
                }
            }
            // Wipe local data and tokens regardless
            withContext(Dispatchers.IO) {
                db.clearAllTables()
            }
            authRepo.logout()
            onDone()
        }
    }

    /**
     * Populates the local database with demo readings, XAI and journal entries
     * for presentations. Replaces any existing local data.
     */
    fun seedDemoData(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                DemoDataSeeder.seed(db, userEmail = authRepo.getEmail() ?: "", replaceExisting = true)
            }
            onDone()
        }
    }

    /**
     * Inserts a high-stress reading with a synthetic explanation, then
     * immediately fires a stress-alert notification. This bypasses the
     * WorkManager interval check and verifies the detail route end-to-end.
     */
    fun triggerStressSpike(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val reading = StressReading(
                    timestamp           = System.currentTimeMillis(),
                    userEmail           = authRepo.getEmail() ?: "",
                    hrvMean             = 650f,
                    hrvSdnn             = 22f,
                    hrvRmssd            = 18f,
                    edaScl              = 7.2f,
                    edaScr              = 2.1f,
                    tempMean            = 34.8f,
                    accMag              = 1.2f,
                    stressScore         = 1,
                    stressProbBaseline  = 0.05f,
                    stressProbStress    = 0.95f,
                    stressProbAmusement = 0f,
                    synced              = false,
                    featureTensor       = null,
                )
                val readingId = db.stressDao().insert(reading)
                db.xaiDao().insertAll(
                    DemoDataSeeder.buildXaiForReading(
                        readingId = readingId,
                        isStress = true,
                        seed = 95,
                    )
                )
                StressNotificationHelper.fireAlert(
                    context       = getApplication(),
                    stressPercent = 95,
                    topFactor     = "Heart rhythm",
                )
            }
            onDone()
        }
    }
}
