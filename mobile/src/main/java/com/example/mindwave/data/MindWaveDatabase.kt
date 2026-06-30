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
        LatencyRecord::class,
    ],
    version = 7,
    exportSchema = true
)
abstract class MindWaveDatabase : RoomDatabase() {

    abstract fun stressDao(): StressDao
    abstract fun xaiDao(): XaiDao
    abstract fun journalDao(): JournalDao
    abstract fun contextDao(): ContextDao
    abstract fun latencyDao(): LatencyDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE emotional_journals ADD COLUMN userEmail TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_emotional_journals_userEmail ON emotional_journals(userEmail)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Scope stress readings (and transitively XAI + context events) to one account
                database.execSQL(
                    "ALTER TABLE stress_readings ADD COLUMN userEmail TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_stress_readings_userEmail ON stress_readings(userEmail)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS latency_records (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        windowId    INTEGER NOT NULL,
                        receivedAt  INTEGER NOT NULL DEFAULT 0,
                        featuresAt  INTEGER NOT NULL DEFAULT 0,
                        normalizedAt INTEGER NOT NULL DEFAULT 0,
                        inferenceAt INTEGER NOT NULL DEFAULT 0,
                        xaiAt       INTEGER NOT NULL DEFAULT 0,
                        roomAt      INTEGER NOT NULL DEFAULT 0,
                        alertAt     INTEGER NOT NULL DEFAULT 0,
                        isWarmup    INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_latency_records_windowId ON latency_records(windowId)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Replaces the old epoch-ms timestamp schema (receivedAt, featuresAt, …)
                // with the new µs-duration schema (featureUs, normalizeUs, … totalUs).
                database.execSQL("DROP TABLE IF EXISTS latency_records")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS latency_records (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        windowId    INTEGER NOT NULL,
                        featureUs   INTEGER NOT NULL DEFAULT 0,
                        normalizeUs INTEGER NOT NULL DEFAULT 0,
                        inferenceUs INTEGER NOT NULL DEFAULT 0,
                        xaiUs       INTEGER NOT NULL DEFAULT 0,
                        roomUs      INTEGER NOT NULL DEFAULT 0,
                        alertUs     INTEGER NOT NULL DEFAULT 0,
                        totalUs     INTEGER NOT NULL DEFAULT 0,
                        isWarmup    INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_latency_records_windowId ON latency_records(windowId)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE emotional_journals ADD COLUMN entryType TEXT NOT NULL DEFAULT 'JOURNAL'"
                )
                database.execSQL(
                    "UPDATE emotional_journals SET entryType = 'FEEDBACK' WHERE tags = 'feedback'"
                )
                database.execSQL(
                    "UPDATE emotional_journals SET entryType = 'WATCH_MOOD' WHERE tags = 'watch_quick_reply'"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_emotional_journals_entryType ON emotional_journals(entryType)"
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build().also { INSTANCE = it }
            }
    }
}
