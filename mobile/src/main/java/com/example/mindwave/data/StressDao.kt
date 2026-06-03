package com.example.mindwave.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: StressReading): Long

    @Query("SELECT * FROM stress_readings WHERE timestamp BETWEEN :startTs AND :endTs ORDER BY timestamp DESC")
    fun getByRange(startTs: Long, endTs: Long): Flow<List<StressReading>>

    @Query("SELECT * FROM stress_readings ORDER BY timestamp DESC LIMIT :n")
    fun getLatest(n: Int): Flow<List<StressReading>>

    @Query("SELECT * FROM stress_readings WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): StressReading?

    @Query("SELECT * FROM stress_readings ORDER BY timestamp DESC LIMIT :n")
    suspend fun getLatestSync(n: Int): List<StressReading>

    @Query("SELECT * FROM stress_readings WHERE synced = 0")
    suspend fun getUnsynced(): List<StressReading>

    @Query("UPDATE stress_readings SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    // Reclaim BLOB storage after the FL round has consumed the tensor.
    @Query("UPDATE stress_readings SET featureTensor = NULL WHERE id IN (:ids)")
    suspend fun clearFeatureTensors(ids: List<Long>)

    /**
     * Returns unsynced readings that have confirmed user feedback and a stored feature tensor.
     * Used by the training worker to build local training batches.
     * TODO: Semisupervised approach, include all readings, accord priority to feedback presence.
     */
    @Query("""
        SELECT s.* FROM stress_readings s
        INNER JOIN emotional_journals j ON j.readingId = s.id
        WHERE j.tags = 'feedback'
          AND s.synced = 0
          AND s.featureTensor IS NOT NULL
    """)
    suspend fun getUnsyncedWithFeedback(): List<StressReading>
}
