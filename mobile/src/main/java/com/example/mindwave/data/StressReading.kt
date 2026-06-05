package com.example.mindwave.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One stress prediction window.
 *
 * Stores the key sensor features extracted from the 60s wrist window,
 * the predicted stress class and its per-class probabilities, plus a
 * sync flag for the FedLearning cycle.
 *
 * userEmail scopes readings to the logged-in account on this device.
 * All biometric data stays on-device; this field is never uploaded.
 */
@Entity(
    tableName = "stress_readings",
    indices = [Index("userEmail")],
)
data class StressReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long, // epoch millis
    val userEmail: String = "", // owner — scopes this reading to one account on device

    // Model input, aggregated features from the 60 s window
    val hrvMean: Float,
    val hrvSdnn: Float,
    val hrvRmssd: Float,
    val edaScl: Float,
    val edaScr: Float,
    val tempMean: Float,
    val accMag: Float,

    // Prediction output
    val stressScore: Int, // 0 normal, 1 stress
    val stressProbBaseline: Float,
    val stressProbStress: Float,
    val stressProbAmusement: Float, // TODO: rename to 'other', as amusement has been dropped

    // FL sync state: true once this window's weights have been included in a federated round
    val synced: Boolean = false,

    // Raw feature tensor
    // Shape: (N_SUBWINDOWS=12, N_FEATURES=23)
    val featureTensor: ByteArray? = null,
)
