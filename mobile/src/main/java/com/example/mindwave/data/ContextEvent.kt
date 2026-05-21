package com.example.mindwave.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * External context event linked to a stress reading.
 *
 * Sources: Google Calendar API or OpenWeather API (todo: add more in the future)
 * The ContextCorrelator service fetches these after each prediction and
 * stores them here so the XAI & recommendation layer can answer:
 * "Your HRV dropped 5 min before /event/ during /weather/."
 */
@Entity(
    tableName = "context_events",
    foreignKeys = [ForeignKey(
        entity = StressReading::class,
        parentColumns = ["id"],
        childColumns = ["readingId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("readingId")]
)
data class ContextEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val readingId: Long? = null,
    val timestamp: Long,
    val source: String, // "calendar" or "weather"
    val title: String, // event title
    val metadata: String = ""
)
