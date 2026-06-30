package com.example.mindwave.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mindwave.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
/**
 * ViewModel wiring Room data into the Compose dashboard.
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val db         = MindWaveDatabase.getInstance(app)
    private val stressDao  = db.stressDao()
    private val xaiDao     = db.xaiDao()
    private val journalDao = db.journalDao()
    private val contextDao = db.contextDao()
    private val authRepo   = AuthRepository(app)

    /**
     * Reactive email: polls AuthRepository every 0.5 s and re-emits whenever the value changes.
     * Keeps running indefinitely so account-switching (logout → login as different user) is detected.
     */
    private val currentEmail: StateFlow<String> = flow {
        var last = ""
        while (true) {
            val email = authRepo.getEmail() ?: ""
            if (email != last) {
                last = email
                emit(email)
            }
            kotlinx.coroutines.delay(500)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Resubcribes after email changes */
    val latestReading: StateFlow<StressReading?> =
        currentEmail
            .flatMapLatest { email -> stressDao.getLatest(1, email).map { it.firstOrNull() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Live XAI explanations for the latest reading. */
    val xaiExplanations: StateFlow<List<XaiExplanation>> =
        latestReading
            .filterNotNull()
            .flatMapLatest { xaiDao.observeByReadingId(it.id) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * All readings for the last 30 days.
     */
    val allReadings: StateFlow<List<StressReading>> =
        currentEmail
            .flatMapLatest { email ->
                val thirtyDaysAgo = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -30)
                }.timeInMillis
                stressDao.getByRange(thirtyDaysAgo, Long.MAX_VALUE, email)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All journal entries for this account. */
    val journalEntries: StateFlow<List<EmotionalJournal>> =
        currentEmail
            .flatMapLatest { email ->
                val yearAgo = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }.timeInMillis
                journalDao.getByRange(yearAgo, Long.MAX_VALUE, email)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Contextual recommendations derived from the latest reading's context events. */
    val recommendations: StateFlow<List<String>> =
        latestReading
            .filterNotNull()
            .map { reading ->
                val events = contextDao.getByReadingId(reading.id)
                buildRecommendations(reading, events)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Count of journal entries tagged "feedback" with userMood <= 2 (confirmed stress events).
     * Used by InsightsScreen to show how many stress episodes were user-confirmed.
     */
    val confirmedStressEventCount: StateFlow<Int> =
        journalEntries
            .map { entries -> entries.count { it.entryType == JournalEntryType.FEEDBACK && it.userMood <= 2 } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** XAI grouped by readingId, populated from journalEntries × allReadings. */
    var xaiByReading: Map<Long, List<XaiExplanation>> by mutableStateOf(emptyMap())
        private set
    var stressScoreByReading: Map<Long, Float> by mutableStateOf(emptyMap())
        private set

    /** Detail state (populated by deep-link or notification tap). */
    var detailReading: StressReading? by mutableStateOf(null)
        private set
    var detailXai: List<XaiExplanation> by mutableStateOf(emptyList())
        private set
    var detailContext: String? by mutableStateOf(null)
        private set

    /** Load a specific reading (e.g. from a notification deep-link) into detail state. */
    fun loadStressDetail(readingId: Long) {
        viewModelScope.launch {
            val email = currentEmail.value
            val reading = if (readingId > 0) {
                stressDao.getById(readingId)?.takeIf { it.userEmail == email }
            } else {
                stressDao.getLatestSync(1, email).firstOrNull()
            }
            detailReading = reading
            detailXai     = reading?.let { xaiDao.getByReadingId(it.id) } ?: emptyList()
            detailContext  = reading?.let { r ->
                contextDao.getByReadingId(r.id).firstOrNull()?.let { "${it.source}: ${it.title}" }
            }
        }
    }

    init {
        // Pre-load XAI and stress scores for all readings referenced by journal entries.
        viewModelScope.launch {
            combine(journalEntries, allReadings) { entries, readings -> entries to readings }
                .collect { (entries, readings) ->
                    val readingIds = entries.mapNotNull { it.readingId }.distinct()
                    val xaiMap   = mutableMapOf<Long, List<XaiExplanation>>()
                    val scoreMap = mutableMapOf<Long, Float>()
                    for (id in readingIds) {
                        xaiMap[id] = xaiDao.getByReadingId(id)
                    }
                    readings.forEach { r ->
                        if (r.id in readingIds) scoreMap[r.id] = r.stressProbStress
                    }
                    xaiByReading       = xaiMap
                    stressScoreByReading = scoreMap
                }
        }
    }

    /**
     * Save user feedback (true = "yes stressed", false = "false alarm").
     * If feedback already exists for this reading, updates it through upserting.
     */
    fun saveFeedback(isStressed: Boolean) {
        viewModelScope.launch {
            val reading = latestReading.value ?: return@launch
            val existing = journalDao.getFeedbackEntryForReading(reading.id)
            if (existing != null) {
                journalDao.insert(
                    existing.copy(
                        userMood  = if (isStressed) 1 else 5,
                        note      = if (isStressed) "Confirmed stress" else "Not stressed",
                        timestamp = System.currentTimeMillis(),
                    )
                )
            } else {
                journalDao.insert(
                    EmotionalJournal(
                        readingId = reading.id,
                        timestamp = System.currentTimeMillis(),
                        userMood  = if (isStressed) 1 else 5,
                        note      = if (isStressed) "Confirmed stress" else "Not stressed",
                        tags      = "feedback",
                        entryType = JournalEntryType.FEEDBACK,
                        userEmail = currentEmail.value,
                    )
                )
            }
        }
    }

    /**
     * Save a full journal entry from the dialog.
     * If a non-feedback entry already exists for this reading, updates it through upsertion.
     */
    fun saveJournal(mood: Int, note: String) {
        viewModelScope.launch {
            val readingId = latestReading.value?.id
            val existing  = readingId?.let { journalDao.getLatestUserEntryForReading(it) }
            if (existing != null) {
                journalDao.insert(
                    existing.copy(
                        userMood  = mood,
                        note      = note,
                        timestamp = System.currentTimeMillis(),
                    )
                )
            } else {
                journalDao.insert(
                    EmotionalJournal(
                        readingId = readingId,
                        timestamp = System.currentTimeMillis(),
                        userMood  = mood,
                        note      = note,
                        tags      = "",
                        entryType = JournalEntryType.JOURNAL,
                        userEmail = currentEmail.value,
                    )
                )
            }
        }
    }

    /** Delete a single journal entry. */
    fun deleteJournal(entry: EmotionalJournal) {
        viewModelScope.launch { journalDao.delete(entry) }
    }

    /** Update an existing journal entry's mood and note. */
    fun updateJournal(entry: EmotionalJournal, mood: Int, note: String) {
        viewModelScope.launch { journalDao.insert(entry.copy(userMood = mood, note = note)) }
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

        if (recs.isEmpty()) recs.add("Take a moment for slow, deep breathing.")
        return recs
    }
}
