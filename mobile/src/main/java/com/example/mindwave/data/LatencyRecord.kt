package com.example.mindwave.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One pipeline latency measurement for a single sensor window.
 *
 * All durations are stored in microseconds using the monotonic clock, divided by 1000.
 * A value of 0 means the stage was not reached (e.g. XAI skipped).
 *
 * Stage order:
 *   featureUs, normalizeUs, inferenceUs, xaiUs, roomUs, alertUs, totalUs
 */
@Entity(
    tableName = "latency_records",
    indices = [Index("windowId", unique = true)],
)
data class LatencyRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // ID for the sensor window (e.g. 1 per 5s) that produced this record.
    val windowId: Long,

    // µs for FeatureExtractor.extract()
    val featureUs: Long = 0L,

    // µs for ScalerNormalizer.normalize()
    val normalizeUs: Long = 0L,

    // µs for TFLite runInference()
    val inferenceUs: Long = 0L,

    //µs for runExplain() (0 if XAI skipped).
    val xaiUs: Long = 0L,

    // µs for Room insert()
    val roomUs: Long = 0L,

    // µs for evaluateAndMaybeAlert()
    val alertUs: Long = 0L,

    // µs for the complete pipeline
    val totalUs: Long = 0L,

    /**
     * True for the first warmup_count windows processed after app start.
     * Warm-up records are excluded from latency statistics.
     */
    val isWarmup: Boolean = false,
) {
    companion object {
        const val WARMUP_COUNT = 10
    }

    // Convenience ms accessors for log messages and UI display
    val featureMs:   Long get() = featureUs   / 1_000L
    val normalizeMs: Long get() = normalizeUs / 1_000L
    val inferenceMs: Long get() = inferenceUs / 1_000L
    val xaiMs:       Long get() = xaiUs       / 1_000L
    val roomMs:      Long get() = roomUs       / 1_000L
    val alertMs:     Long get() = alertUs      / 1_000L
    val totalMs:     Long get() = totalUs      / 1_000L
}

