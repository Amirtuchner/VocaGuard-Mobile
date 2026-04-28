package io.vocaguard.data

import android.util.Log
import io.vocaguard.data.db.CallTranscriptEntity
import io.vocaguard.data.db.TranscriptDao
import io.vocaguard.data.db.TranscriptSummary
import io.vocaguard.data.db.VocaGuardDatabase
import io.vocaguard.data.db.toEntity
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TranscriptRepository(private val dao: TranscriptDao) {

    companion object {
        private const val TAG = "TranscriptRepository"
        private const val MAX_STORED = 500

        @Volatile
        private var instance: TranscriptRepository? = null

        fun getInstance(context: Context): TranscriptRepository {
            return instance ?: synchronized(this) {
                instance ?: TranscriptRepository(
                    VocaGuardDatabase.getInstance(context).transcriptDao()
                ).also { instance = it }
            }
        }

        /** Inject a repository backed by a specific DAO (used in tests). */
        fun forTesting(dao: TranscriptDao): TranscriptRepository =
            TranscriptRepository(dao).also { instance = it }
    }

    /** Live-updating stream of transcripts (newest first, max 50). */
    fun observeAll(): Flow<List<CallTranscript>> =
        dao.observeAll().map { list -> list.map(CallTranscriptEntity::toDomain) }

    suspend fun save(transcript: CallTranscript) {
        try {
            dao.insert(transcript.toEntity())
            dao.trimToLimit(MAX_STORED)
            Log.d(TAG, "Saved transcript id=${transcript.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving transcript", e)
        }
    }

    suspend fun loadAll(): List<CallTranscript> =
        dao.getAll().map(CallTranscriptEntity::toDomain)

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()

    fun countScamCallsSince(since: Long): Flow<Int> = dao.countScamCallsSince(since)

    fun countAllCallsSince(since: Long): Flow<Int> = dao.countAllCallsSince(since)

    /**
     * Emits a list of raw comma-separated scam-type strings (one per scam call) so callers
     * can compute per-type counts without loading full transcript objects.
     */
    fun scamTypeJsonsSince(since: Long): Flow<List<String>> = dao.scamTypeJsonsSince(since)

    /** Lightweight stream for the trend chart — no full transcript text loaded. */
    fun observeSummariesSince(since: Long): Flow<List<TranscriptSummary>> =
        dao.observeSummariesSince(since)
}
