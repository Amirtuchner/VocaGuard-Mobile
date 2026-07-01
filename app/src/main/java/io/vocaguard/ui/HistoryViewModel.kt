package io.vocaguard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.vocaguard.data.CallTranscript
import io.vocaguard.data.ScamDatabaseManager
import io.vocaguard.data.ScamType
import io.vocaguard.data.TranscriptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PAGE_SIZE = 30

/** Active search/filter criteria for the History tab. */
data class HistoryFilter(
    val searchQuery: String = "",
    val scamTypeFilter: String? = null, // null = all types
    val showScamOnly: Boolean = false
)

sealed class SnackbarEvent {
    data class Success(val message: String) : SnackbarEvent()
    data class Error(val message: String, val throwable: Throwable?) : SnackbarEvent()
}

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TranscriptRepository.getInstance(application)
    private val scamDb = ScamDatabaseManager.getInstance(application)

    private val _filter = MutableStateFlow(HistoryFilter())
    val filter: StateFlow<HistoryFilter> = _filter.asStateFlow()

    private val _currentPage = MutableStateFlow(1)

    private val _snackbarEvent = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<SnackbarEvent> = _snackbarEvent.asSharedFlow()

    /** All transcripts from DB (newest first, up to MAX_STORED). */
    private val allTranscripts: StateFlow<List<CallTranscript>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Transcripts matching the current filter. */
    val filteredTranscripts: StateFlow<List<CallTranscript>> =
        combine(allTranscripts, _filter) { all, f ->
            all.filter { transcript ->
                val q = f.searchQuery.trim()
                val matchesQuery = q.isEmpty() ||
                    transcript.text.contains(q, ignoreCase = true) ||
                    transcript.phoneNumber.contains(q, ignoreCase = true)
                val matchesType = f.scamTypeFilter == null ||
                    transcript.detectedScamTypes.any { it == f.scamTypeFilter }
                val matchesScamOnly = !f.showScamOnly || (transcript.detectedScamTypes.isNotEmpty() && !transcript.isFalsePositive)
                matchesQuery && matchesType && matchesScamOnly
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Current page of filtered transcripts shown in the list (page × PAGE_SIZE items). */
    val displayedTranscripts: StateFlow<List<CallTranscript>> =
        combine(filteredTranscripts, _currentPage) { filtered, page ->
            filtered.take(page * PAGE_SIZE)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True when there are more filtered items beyond the currently displayed page. */
    val hasMore: StateFlow<Boolean> =
        combine(filteredTranscripts, _currentPage) { filtered, page ->
            filtered.size > page * PAGE_SIZE
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ── Filter controls ──────────────────────────────────────────────────────

    fun setSearchQuery(query: String) {
        _filter.value = _filter.value.copy(searchQuery = query)
        _currentPage.value = 1
    }

    fun setScamTypeFilter(type: String?) {
        _filter.value = _filter.value.copy(scamTypeFilter = type)
        _currentPage.value = 1
    }

    fun setShowScamOnly(show: Boolean) {
        _filter.value = _filter.value.copy(showScamOnly = show)
        _currentPage.value = 1
    }

    fun clearFilters() {
        _filter.value = HistoryFilter()
        _currentPage.value = 1
    }

    fun loadMore() {
        _currentPage.value++
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    fun delete(id: Long) {
        viewModelScope.launch {
            try {
                repository.delete(id)
                _snackbarEvent.emit(SnackbarEvent.Success("Transcript deleted"))
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                _snackbarEvent.emit(SnackbarEvent.Error("Failed to delete transcript: ${e.message}", e))
            }
        }
    }

    fun reportScamNumber(phoneNumber: String, scamType: ScamType) {
        viewModelScope.launch {
            try {
                scamDb.reportScamNumber(phoneNumber, scamType)
                _snackbarEvent.emit(SnackbarEvent.Success(
                    "Reported as ${scamType.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}"
                ))
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                _snackbarEvent.emit(SnackbarEvent.Error("Failed to report number: ${e.message}", e))
            }
        }
    }

    fun addToWhitelist(phoneNumber: String) {
        viewModelScope.launch {
            try {
                scamDb.addToWhitelist(phoneNumber)
                _snackbarEvent.emit(SnackbarEvent.Success("$phoneNumber added to safe list"))
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                _snackbarEvent.emit(SnackbarEvent.Error("Failed to add to safe list: ${e.message}", e))
            }
        }
    }

    fun markAsFalsePositive(id: Long) {
        viewModelScope.launch {
            try {
                repository.markAsFalsePositive(id)
                _snackbarEvent.emit(SnackbarEvent.Success("Marked as not a scam"))
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                _snackbarEvent.emit(SnackbarEvent.Error("Failed to update: ${e.message}", e))
            }
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Formats currently-filtered transcripts as plain text for sharing.
     */
    fun exportAsText(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder("VocaGuard Call History Export\n")
        sb.append("Generated: ${fmt.format(Date())}\n")
        sb.append("=".repeat(50)).append("\n\n")
        filteredTranscripts.value.forEach { t ->
            sb.append("Date: ${fmt.format(Date(t.timestamp))}\n")
            if (t.phoneNumber.isNotEmpty()) sb.append("Number: ${t.phoneNumber}\n")
            if (t.detectedScamTypes.isNotEmpty()) {
                sb.append("Scam types: ${t.detectedScamTypes.joinToString()}\n")
            }
            sb.append("Transcript:\n${t.text}\n")
            sb.append("-".repeat(40)).append("\n\n")
        }
        return sb.toString()
    }

    /**
     * Formats currently-filtered transcripts as CSV (RFC 4180 quoted fields).
     */
    fun exportAsCsv(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder("\"Date\",\"Phone Number\",\"Scam Types\",\"Transcript\"\n")
        filteredTranscripts.value.forEach { t ->
            val date  = fmt.format(Date(t.timestamp))
            val phone = t.phoneNumber
            val types = t.detectedScamTypes.joinToString(";")
            val text  = t.text.replace("\"", "\"\"")
            sb.append("\"$date\",\"$phone\",\"$types\",\"$text\"\n")
        }
        return sb.toString()
    }
}
