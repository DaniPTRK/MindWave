package com.example.mindwave.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * User-initiated emotional journal entry (akin to feedback, but more general).
 *
 * Optionally linked to a specific StressReading so the model can later
 * use this label during fine tuning on device.
 */
@Entity(
    tableName = "emotional_journals",
    foreignKeys = [ForeignKey(
        entity = StressReading::class,
        parentColumns = ["id"],
        childColumns = ["readingId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("readingId"), Index("userEmail")]
)
data class EmotionalJournal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val readingId: Long? = null,
    val timestamp: Long,
    val userMood: Int, // 1 (very stressed / sad) – 5 (very happy / calm)
    val note: String = "",
    val tags: String = "", // comma separated, for ex "work,meeting,tired"
    val userEmail: String = "", // owner — entries are only shown to the account that created them
)
