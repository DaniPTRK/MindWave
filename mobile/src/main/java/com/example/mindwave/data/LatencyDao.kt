package com.example.mindwave.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for latency profiling records.
 *
 * All queries exclude warm-up windows (isWarmup = 1) so statistics reflect
 * steady-state performance only.
 */
@Dao
interface LatencyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: LatencyRecord)

    // All measured records, without the warmups
    @Query("SELECT * FROM latency_records WHERE isWarmup = 0 ORDER BY windowId DESC")
    suspend fun getMeasured(): List<LatencyRecord>

    // All records + warmups.
    @Query("SELECT * FROM latency_records ORDER BY windowId DESC LIMIT :limit")
    suspend fun getAll(limit: Int = 200): List<LatencyRecord>

    // Num of warmups.
    @Query("SELECT COUNT(*) FROM latency_records WHERE isWarmup = 0")
    suspend fun getMeasuredCount(): Int

    // Delete all records.
    @Query("DELETE FROM latency_records")
    suspend fun deleteAll()
}

