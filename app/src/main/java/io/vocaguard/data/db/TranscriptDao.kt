package io.vocaguard.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Lightweight projection for the trend chart — avoids loading full transcript text. */
data class TranscriptSummary(val timestamp: Long, val detectedScamTypesJson: String)

@Dao
interface TranscriptDao {

    @Query("SELECT * FROM call_transcripts ORDER BY timestamp DESC LIMIT 500")
    fun observeAll(): Flow<List<CallTranscriptEntity>>

    @Query("SELECT * FROM call_transcripts ORDER BY timestamp DESC LIMIT 500")
    suspend fun getAll(): List<CallTranscriptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CallTranscriptEntity)

    @Query("DELETE FROM call_transcripts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM call_transcripts WHERE id NOT IN (SELECT id FROM call_transcripts ORDER BY timestamp DESC LIMIT :limit)")
    suspend fun trimToLimit(limit: Int)

    @Query("DELETE FROM call_transcripts")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM call_transcripts WHERE detectedScamTypesJson != '' AND timestamp >= :since")
    fun countScamCallsSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM call_transcripts WHERE timestamp >= :since")
    fun countAllCallsSince(since: Long): Flow<Int>

    /** Returns all scam transcripts since [since] so the ViewModel can compute per-type counts. */
    @Query("SELECT detectedScamTypesJson FROM call_transcripts WHERE detectedScamTypesJson != '' AND timestamp >= :since")
    fun scamTypeJsonsSince(since: Long): Flow<List<String>>

    /** Lightweight projection for trend chart — timestamps + scam flags, no full text. */
    @Query("SELECT timestamp, detectedScamTypesJson FROM call_transcripts WHERE timestamp >= :since ORDER BY timestamp ASC")
    fun observeSummariesSince(since: Long): Flow<List<TranscriptSummary>>
}
