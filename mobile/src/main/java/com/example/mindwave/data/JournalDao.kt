package com.example.mindwave.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: EmotionalJournal): Long

    @Delete
    suspend fun delete(entry: EmotionalJournal)

    @Query("DELETE FROM emotional_journals WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM emotional_journals WHERE timestamp BETWEEN :startTs AND :endTs AND userEmail = :userEmail ORDER BY timestamp DESC")
    fun getByRange(startTs: Long, endTs: Long, userEmail: String): Flow<List<EmotionalJournal>>

    // Lookup the feedback mood for a specific reading
    @Query("SELECT userMood FROM emotional_journals WHERE readingId = :readingId AND tags = 'feedback' LIMIT 1")
    suspend fun getFeedbackMood(readingId: Long): Int?
}
