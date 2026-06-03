package com.example.mindwave.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ContextDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ContextEvent): Long

    @Query("SELECT * FROM context_events WHERE readingId = :readingId ORDER BY timestamp ASC")
    suspend fun getByReadingId(readingId: Long): List<ContextEvent>
}

