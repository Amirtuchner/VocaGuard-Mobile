package io.vocaguard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CallTranscriptEntity::class, ScamNumberEntity::class, FamilyAlertEntity::class],
    version = 3,
    exportSchema = false
)
abstract class VocaGuardDatabase : RoomDatabase() {

    abstract fun transcriptDao(): TranscriptDao
    abstract fun scamNumberDao(): ScamNumberDao
    abstract fun familyAlertDao(): FamilyAlertDao

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

        /** v2 → v3: add family_alerts table for the caregiver dashboard. */
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

        fun getInstance(context: Context): VocaGuardDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VocaGuardDatabase::class.java,
                    "vocaguard.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
