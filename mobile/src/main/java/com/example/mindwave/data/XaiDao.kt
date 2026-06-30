package com.example.mindwave.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface XaiDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(explanations: List<XaiExplanation>)

    @Query("SELECT * FROM xai_explanations WHERE readingId = :readingId ORDER BY rank ASC")
    suspend fun getByReadingId(readingId: Long): List<XaiExplanation>

    /** Live-updating version for UI observation. Re-emits whenever XAI rows change. */
    @Query("SELECT * FROM xai_explanations WHERE readingId = :readingId ORDER BY rank ASC")
    fun observeByReadingId(readingId: Long): Flow<List<XaiExplanation>>
}
