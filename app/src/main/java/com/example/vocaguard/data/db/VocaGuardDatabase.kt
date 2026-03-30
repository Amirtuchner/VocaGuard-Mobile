package com.example.vocaguard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CallTranscriptEntity::class, ScamNumberEntity::class],
    version = 1,
    exportSchema = false
)
abstract class VocaGuardDatabase : RoomDatabase() {

    abstract fun transcriptDao(): TranscriptDao
    abstract fun scamNumberDao(): ScamNumberDao

    companion object {
        @Volatile
        private var instance: VocaGuardDatabase? = null

        fun getInstance(context: Context): VocaGuardDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VocaGuardDatabase::class.java,
                    "vocaguard.db"
                )
                    // If the schema version is ever bumped without a Migration object,
                    // Room will wipe and recreate the database rather than crash.
                    // Call history and cached scam numbers are non-critical local data,
                    // so destructive migration is acceptable as a last resort.
                    // For any planned schema change, provide an explicit Migration first.
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