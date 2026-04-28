package io.vocaguard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.vocaguard.data.TranscriptRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class HomeStats(
    val scamCallsThisMonth: Int = 0,
    val totalCallsThisMonth: Int = 0,
    /** Per scam-type label counts (e.g. "IRS_SCAM" -> 3). */
    val scamTypeBreakdown: Map<String, Int> = emptyMap()
)

/** One bar in the trend chart — one day's call summary. */
data class DailyCallCount(val label: String, val total: Int, val scams: Int)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TranscriptRepository.getInstance(application)

    private val monthAgo: Long
        get() = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000

    private val sevenDaysAgo: Long
        get() = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000

    val stats: StateFlow<HomeStats> = combine(
        repository.countScamCallsSince(monthAgo),
        repository.countAllCallsSince(monthAgo),
        repository.scamTypeJsonsSince(monthAgo)
    ) { scam, total, typeJsons ->
        // typeJsons is a list of comma-separated scam type strings, one per scam call.
        // Flatten, split, and tally per type.
        val breakdown = typeJsons
            .flatMap { it.split(",") }
            .filter { it.isNotBlank() }
            .groupingBy { it.trim() }
            .eachCount()
        HomeStats(
            scamCallsThisMonth = scam,
            totalCallsThisMonth = total,
            scamTypeBreakdown = breakdown
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeStats())

    /** Last 7 days of call counts, one entry per day (Mon … today). */
    val trendData: StateFlow<List<DailyCallCount>> = repository
        .observeSummariesSince(sevenDaysAgo)
        .map { summaries ->
            val labelFmt = SimpleDateFormat("EEE", Locale.getDefault()) // Mon, Tue…
            val keyFmt   = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            // Build ordered list of the last 7 days
            val days = (6 downTo 0).map { offset ->
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, -offset)
                keyFmt.format(cal.time) to labelFmt.format(cal.time)
            }
            val grouped = summaries.groupBy { keyFmt.format(Date(it.timestamp)) }
            days.map { (key, label) ->
                val day = grouped[key] ?: emptyList()
                DailyCallCount(
                    label  = label,
                    total  = day.size,
                    scams  = day.count { it.detectedScamTypesJson.isNotEmpty() }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
