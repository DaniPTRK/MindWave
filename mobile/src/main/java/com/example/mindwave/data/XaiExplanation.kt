package com.example.mindwave.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One feature-importance entry produced by the XAI signature.
 *
 * Each StressReading produces N_FEATURES rows (one per feature), ranked
 * from most to least important. The UI displays the top-K to the user.
 */
@Entity(
    tableName = "xai_explanations",
    foreignKeys = [ForeignKey(
        entity = StressReading::class,
        parentColumns = ["id"],
        childColumns = ["readingId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("readingId")]
)

data class XaiExplanation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val readingId: Long,
    val featureName: String,
    val importance: Float,
    val rank: Int
)
