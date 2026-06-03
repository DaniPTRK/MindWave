package com.example.mindwave.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Central Room database
 * All biometric data, predictions, explanations, journal entries and
 * context events are stored on the device. Nothing in this database
 * is ever uploaded to the server. Only model weight tensors leave the
 * device (via the Flower FL client).
 */
@Database(
    entities = [
        StressReading::class,
        XaiExplanation::class,
        EmotionalJournal::class,
        ContextEvent::class,
    ],
    version = 2,
    exportSchema = true
)
abstract class MindWaveDatabase : RoomDatabase() {

    abstract fun stressDao(): StressDao
    abstract fun xaiDao(): XaiDao
    abstract fun journalDao(): JournalDao
    abstract fun contextDao(): ContextDao

    companion object {
        @Volatile
        private var INSTANCE: MindWaveDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE stress_readings ADD COLUMN featureTensor BLOB"
                )
            }
        }

        fun getInstance(context: Context): MindWaveDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MindWaveDatabase::class.java,
                    "mindwave.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
    }
}
