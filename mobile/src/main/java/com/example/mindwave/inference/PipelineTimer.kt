package com.example.mindwave.inference

import android.os.SystemClock

/**
 * Mutable bag of timestamps collected at each stage of the inference pipeline.
 *
 * Uses [SystemClock.elapsedRealtimeNanos], a clock that provides better accuracy
 * than wall clock time and is guaranteed to be monotonic.
 */
class PipelineTimer(val windowId: Long) {
    var receivedNs: Long = 0L
    var featuresNs: Long = 0L
    var normalizedNs: Long = 0L
    var inferenceNs: Long = 0L
    var xaiNs: Long = 0L
    var roomNs: Long = 0L
    var alertNs: Long = 0L

    /** Snapshot the monotonic clock. Call at each stage boundary. */
    fun now(): Long = SystemClock.elapsedRealtimeNanos()

    // microsecs

    val featureUs:   Long get() = if (featuresNs   > 0 && receivedNs   > 0) (featuresNs   - receivedNs)   / 1_000L else 0L
    val normalizeUs: Long get() = if (normalizedNs  > 0 && featuresNs   > 0) (normalizedNs  - featuresNs)   / 1_000L else 0L
    val inferenceUs: Long get() = if (inferenceNs   > 0 && normalizedNs  > 0) (inferenceNs   - normalizedNs)  / 1_000L else 0L
    val xaiUs:       Long get() = if (xaiNs         > 0 && inferenceNs   > 0) (xaiNs         - inferenceNs)   / 1_000L else 0L
    val roomUs:      Long get() = if (roomNs        > 0 && (if (xaiNs > 0) xaiNs else inferenceNs) > 0)
                                      (roomNs - (if (xaiNs > 0) xaiNs else inferenceNs)) / 1_000L else 0L
    val alertUs:     Long get() = if (alertNs       > 0 && roomNs        > 0) (alertNs       - roomNs)        / 1_000L else 0L
    val totalUs:     Long get() = if (alertNs       > 0 && receivedNs   > 0) (alertNs       - receivedNs)   / 1_000L else 0L

    // ms for logs
    val totalMs:     Long get() = totalUs / 1_000L
}
