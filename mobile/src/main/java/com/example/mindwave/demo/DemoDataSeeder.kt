package com.example.mindwave.demo

import com.example.mindwave.data.EmotionalJournal
import com.example.mindwave.data.MindWaveDatabase
import com.example.mindwave.data.StressReading
import com.example.mindwave.data.XaiExplanation
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Populates the local Room database with realistic-looking demo data so the
 * dashboard, history, journal and XAI screens can be shown without a paired
 * watch or a live sensor feed.
 */
object DemoDataSeeder {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val HOUR_MS = 60L * 60 * 1000

    private val FEATURE_NAMES = listOf(
        "hrv_meanNN", "hrv_SDNN", "hrv_RMSSD", "hrv_pNN50", "hrv_LF", "hrv_HF", "hrv_LFHF",
        "eda_scl_mean", "eda_scl_slope", "eda_scr_mean", "eda_scr_std", "eda_scr_auc",
        "eda_scr_peaks", "eda_scr_amp_mean",
        "temp_mean", "temp_std", "temp_slope", "temp_min", "temp_max",
        "acc_mag_mean", "acc_mag_std", "acc_mag_energy", "acc_mag_zcr",
    )

    /** Short, contextual journal notes used to make the timeline feel real. */
    private val STRESS_NOTES = listOf(
        "Back-to-back meetings all morning." to "work,meetings",
        "Sprint demo went long, felt the pressure." to "work,deadline",
        "Tough commute, running late." to "commute",
        "Argument before a 1:1." to "work,people",
        "Exam revision, couldn't focus." to "study,exam",
    )
    private val CALM_NOTES = listOf(
        "Quiet morning with coffee." to "morning",
        "Went for a walk after lunch." to "exercise,break",
        "Finished a task early, relieved." to "work,win",
        "Relaxed evening, no screens." to "evening,rest",
        "Good sleep last night." to "sleep,rest",
    )

    /**
     * Inserts 7 days of readings, their XAI breakdowns and a handful of
     * journal entries.
     */
    suspend fun seed(db: MindWaveDatabase, userEmail: String = "", replaceExisting: Boolean = true) {
        val stressDao = db.stressDao()
        val xaiDao = db.xaiDao()
        val journalDao = db.journalDao()

        if (replaceExisting) {
            db.clearAllTables()
        }

        val rng = Random(42)
        val now = System.currentTimeMillis()
        var journalsCreated = 0

        for (dayOffset in 6 downTo 0) {
            val dayStart = now - dayOffset * DAY_MS
            val hours = listOf(9, 12, 15, 18)
            for (hour in hours) {
                val ts = dayStart - (now % DAY_MS) + hour * HOUR_MS +
                        rng.nextLong(-20 * 60_000, 20 * 60_000)
                if (ts > now) continue

                val baseStress = when (hour) {
                    12, 15 -> 0.55f
                    9 -> 0.40f
                    else -> 0.30f
                }
                val prob = (baseStress + rng.nextFloat() * 0.4f - 0.15f).coerceIn(0.05f, 0.97f)
                val isStress = prob > 0.5f

                val reading = buildReading(ts, prob, isStress, rng, userEmail)
                val readingId = stressDao.insert(reading)
                xaiDao.insertAll(buildXai(readingId, isStress, rng))

                // Occasionally attach a journal entry to a reading.
                if (rng.nextFloat() < 0.35f) {
                    val (note, tags) = if (isStress) STRESS_NOTES.random(rng) else CALM_NOTES.random(rng)
                    journalDao.insert(
                        EmotionalJournal(
                            readingId = readingId,
                            timestamp = ts + 5 * 60_000,
                            userMood = if (isStress) (1 + rng.nextInt(2)) else (4 + rng.nextInt(2)),
                            note = note,
                            tags = tags,
                            userEmail = userEmail,
                        )
                    )
                    journalsCreated++
                }
            }
        }

        // Guarantee at least a few standalone journal entries.
        if (journalsCreated < 3) {
            repeat(3) { i ->
                val (note, tags) = CALM_NOTES.random(rng)
                journalDao.insert(
                    EmotionalJournal(
                        readingId = null,
                        timestamp = now - i.toLong() * DAY_MS - HOUR_MS,
                        userMood = 3 + rng.nextInt(3),
                        note = note,
                        tags = tags,
                        userEmail = userEmail,
                    )
                )
            }
        }
    }

    private fun buildReading(
        ts: Long,
        probStress: Float,
        isStress: Boolean,
        rng: Random,
        userEmail: String = "",
    ): StressReading {
        val hrvMean = lerp(820f, 680f, probStress) + rng.nextFloat() * 30
        val hrvSdnn = lerp(60f, 28f, probStress) + rng.nextFloat() * 8
        val hrvRmssd = lerp(55f, 22f, probStress) + rng.nextFloat() * 8
        val edaScl = lerp(2.0f, 6.5f, probStress) + rng.nextFloat()
        val edaScr = lerp(0.2f, 1.8f, probStress) + rng.nextFloat() * 0.3f
        val tempMean = lerp(33.5f, 34.6f, probStress) + rng.nextFloat() * 0.2f
        val accMag = 0.9f + rng.nextFloat() * 0.6f

        return StressReading(
            timestamp = ts,
            userEmail = userEmail,
            hrvMean = hrvMean,
            hrvSdnn = hrvSdnn,
            hrvRmssd = hrvRmssd,
            edaScl = edaScl,
            edaScr = edaScr,
            tempMean = tempMean,
            accMag = accMag,
            stressScore = if (isStress) 1 else 0,
            stressProbBaseline = 1f - probStress,
            stressProbStress = probStress,
            stressProbAmusement = 0f,
            synced = true,
            featureTensor = null,
        )
    }

    private fun buildXai(
        readingId: Long,
        isStress: Boolean,
        rng: Random,
    ): List<XaiExplanation> {
        // Weight the dominant sensor group depending on stress vs calm.
        val groupWeight = if (isStress)
            mapOf("hrv" to 1.6f, "eda" to 1.4f, "temp" to 0.8f, "acc" to 0.5f)
        else
            mapOf("hrv" to 1.0f, "eda" to 0.7f, "temp" to 0.9f, "acc" to 1.1f)

        val raw = FEATURE_NAMES.map { name ->
            val prefix = name.substringBefore("_")
            val w = groupWeight[prefix] ?: 1f
            name to (w * (0.3f + rng.nextFloat()))
        }
        val total = raw.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1e-6f)

        return raw
            .map { (name, v) -> name to v / total }
            .sortedByDescending { it.second }
            .mapIndexed { idx, (name, importance) ->
                XaiExplanation(
                    readingId = readingId,
                    featureName = name,
                    importance = importance,
                    rank = idx + 1,
                )
            }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}

@Suppress("unused")
private fun Float.toPercent(): Int = (this * 100).roundToInt()
