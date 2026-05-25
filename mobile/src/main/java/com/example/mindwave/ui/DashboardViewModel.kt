package com.example.mindwave.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mindwave.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * ViewModel wiring Room data into the Compose dashboard
 * TODO: User repositories to abstract away the DAOs and allow for easier testing/mocking
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val db = MindWaveDatabase.getInstance(app)
    private val stressDao = db.stressDao()
    private val xaiDao = db.xaiDao()
    private val journalDao = db.journalDao()
    private val contextDao = db.contextDao()

    /** Latest reading */
    val latestReading: StateFlow<StressReading?> =
        stressDao.getLatest(1)
            .map { it.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** XAI for the latest reading */
    val xaiExplanations: StateFlow<List<XaiExplanation>> =
        latestReading
            .filterNotNull()
            .map { xaiDao.getByReadingId(it.id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Contextual recommendations, from ContextEvents linked to latest reading */
    val recommendations: StateFlow<List<String>> =
        latestReading
            .filterNotNull()
            .map { reading ->
                val events = contextDao.getByReadingId(reading.id)
                buildRecommendations(reading, events)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All readings for the last 30 days (for history screen). */
    val allReadings: StateFlow<List<StressReading>> = run {
        val thirtyDaysAgo = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -30)
        }.timeInMillis
        stressDao.getByRange(thirtyDaysAgo, System.currentTimeMillis())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /** All journal entries (for journal list screen). */
    val journalEntries: StateFlow<List<EmotionalJournal>> = run {
        val yearAgo = Calendar.getInstance().apply {
            add(Calendar.YEAR, -1)
        }.timeInMillis
        journalDao.getByRange(yearAgo, System.currentTimeMillis())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /** XAI grouped by readingId */
    var xaiByReading: Map<Long, List<XaiExplanation>> by mutableStateOf(emptyMap())
        private set

    /** Stress score by readingId */
    var stressScoreByReading: Map<Long, Float> by mutableStateOf(emptyMap())
        private set

    /** Currently-loaded stress detail (reading + its XAI) for the detail screen. */
    var detailReading: StressReading? by mutableStateOf(null)
        private set
    var detailXai: List<XaiExplanation> by mutableStateOf(emptyList())
        private set
    var detailContext: String? by mutableStateOf(null)
        private set

    /** Load a specific reading (ex. from a notification deep-link) into detail state */
    fun loadStressDetail(readingId: Long) {
        viewModelScope.launch {
            val reading = if (readingId > 0) stressDao.getById(readingId)
                          else stressDao.getLatestSync(1).firstOrNull()
            detailReading = reading
            detailXai = reading?.let { xaiDao.getByReadingId(it.id) } ?: emptyList()
            detailContext = reading?.let { r ->
                contextDao.getByReadingId(r.id).firstOrNull()?.let { "${it.source}: ${it.title}" }
            }
        }
    }

    init {
        // Populate maps when journal entries change
        viewModelScope.launch {
            journalEntries.collect { entries ->
                val readingIds = entries.mapNotNull { it.readingId }.distinct()
                val xaiMap = mutableMapOf<Long, List<XaiExplanation>>()
                val scoreMap = mutableMapOf<Long, Float>()
                for (id in readingIds) {
                    xaiMap[id] = xaiDao.getByReadingId(id)
                }
                allReadings.value.forEach { r ->
                    if (r.id in readingIds) scoreMap[r.id] = r.stressProbStress
                }
                xaiByReading = xaiMap
                stressScoreByReading = scoreMap
            }
        }
    }

    /**
     * Save user feedback (true = "yes stressed", false = "false alarm").
     */
    fun saveFeedback(isStressed: Boolean) {
        viewModelScope.launch {
            val reading = latestReading.value ?: return@launch
            journalDao.insert(
                EmotionalJournal(
                    readingId = reading.id,
                    timestamp = System.currentTimeMillis(),
                    userMood = if (isStressed) 5 else 1,
                    note = if (isStressed) "Confirmed stress" else "False alarm",
                    tags = "feedback",
                )
            )
        }
    }

    /** Save a full journal entry from the dialog. */
    fun saveJournal(mood: Int, note: String) {
        viewModelScope.launch {
            journalDao.insert(
                EmotionalJournal(
                    readingId = latestReading.value?.id,
                    timestamp = System.currentTimeMillis(),
                    userMood = mood,
                    note = note,
                    tags = "",
                )
            )
        }
    }

    /** Delete a single journal entry. */
    fun deleteJournal(entry: EmotionalJournal) {
        viewModelScope.launch {
            journalDao.delete(entry)
        }
    }

    /** Update an existing journal entry's mood and note */
    fun updateJournal(entry: EmotionalJournal, mood: Int, note: String) {
        viewModelScope.launch {
            journalDao.insert(
                entry.copy(userMood = mood, note = note)
            )
        }
    }

    private fun buildRecommendations(
        reading: StressReading,
        contextEvents: List<ContextEvent>,
    ): List<String> {
        val recs = mutableListOf<String>()
        if (reading.stressScore != 1) return recs

        val upcoming = contextEvents.filter {
            it.source == "calendar" && it.timestamp > reading.timestamp
        }
        if (upcoming.isNotEmpty()) {
            val event = upcoming.first()
            recs.add("Try a 2-min breathing exercise before '${event.title}'.")
        }

        val weather = contextEvents.firstOrNull { it.source == "weather" }
        if (weather != null && weather.metadata.contains("\"temp\":")) {
            val tempMatch = Regex("\"temp\":(\\d+)").find(weather.metadata)
            val temp = tempMatch?.groupValues?.get(1)?.toIntOrNull() ?: 20
            if (temp < 10) {
                recs.add("Cold weather may amplify stress \u2014 warm up & hydrate.")
            }
        }

        if (recs.isEmpty()) {
            recs.add("Take a moment for slow, deep breathing.")
        }
        return recs
    }
}
