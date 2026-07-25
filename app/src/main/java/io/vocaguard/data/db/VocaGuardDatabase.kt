package io.vocaguard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CallTranscriptEntity::class, ScamNumberEntity::class],
    version = 6,
    exportSchema = false
)
abstract class VocaGuardDatabase : RoomDatabase() {

    abstract fun transcriptDao(): TranscriptDao
    abstract fun scamNumberDao(): ScamNumberDao

    companion object {
        @Volatile
        private var instance: VocaGuardDatabase? = null

        /** v1 → v2: add expiresAt column (0 = never expires for all legacy rows). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE scam_numbers ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v2 → v3: add family_alerts table (later removed in v5). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS family_alerts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        senderName TEXT NOT NULL,
                        senderNumber TEXT NOT NULL,
                        scamType TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        timestamp INTEGER NOT NULL,
                        isRead INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /** v3 → v4: add isFalsePositive column for user feedback on false-positive detections. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE call_transcripts ADD COLUMN isFalsePositive INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v4 → v5: drop family_alerts table (Family Dashboard removed from MVP). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS family_alerts")
            }
        }

        /** v5 → v6: add direction column (incoming/outgoing badge on call cards). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE call_transcripts ADD COLUMN direction TEXT NOT NULL DEFAULT 'INCOMING'"
                )
            }
        }

        fun getInstance(context: Context): VocaGuardDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VocaGuardDatabase::class.java,
                    "vocaguard.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
        }

        /** For use in tests only — injects an in-memory database. */
        fun setTestInstance(db: VocaGuardDatabase) {
            instance = db
        }
    }
}
