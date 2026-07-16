package io.vocaguard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.vocaguard.data.DetectionSettings
import io.vocaguard.data.ScamDatabaseManager
import io.vocaguard.data.TranscriptRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
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
    val scamTypeBreakdown: Map<String, Int> = emptyMap(),
    // Lifetime stats
    val totalCallsLifetime: Int = 0,
    val scamCallsLifetime: Int = 0,
    val callsScreenedClean: Int = 0,
    // Message stats
    val messagesScanned: Long = 0,
    val messagesFlagged: Long = 0,
    // Blocklist
    val blocklistSize: Int = 0,
    // Protection uptime
    val protectionDays: Int = 0,
    val installTimestamp: Long = 0L,
    // Estimated money saved (FTC avg $1,480 per scam)
    val estimatedMoneySaved: Int = 0,
    // Protection score (0-100)
    val protectionScore: Int = 0
)

/** One bar in the trend chart — one day's call summary. */
data class DailyCallCount(val label: String, val total: Int, val scams: Int)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TranscriptRepository.getInstance(application)
    private val settings = DetectionSettings.getInstance(application)

    private val monthAgo: Long
        get() = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000

    private val sevenDaysAgo: Long
        get() = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000

    private val blocklistFlow = flow {
        val size = try {
            ScamDatabaseManager.getInstance(application).getAllScamNumbers().size
        } catch (_: Exception) { 0 }
        emit(size)
    }

    /** Emits every 60s so time-based values (protectionDays) stay fresh. */
    private val minuteTicker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000L)
        }
    }

    val stats: StateFlow<HomeStats> = combine(
        repository.countScamCallsSince(monthAgo),
        repository.countAllCallsSince(monthAgo),
        repository.scamTypeJsonsSince(monthAgo),
        repository.countAllCallsLifetime(),
        repository.countScamCallsLifetime(),
        repository.countCallsScreenedClean(),
        blocklistFlow,
        minuteTicker
    ) { values ->
        val scam = values[0] as Int
        val total = values[1] as Int
        @Suppress("UNCHECKED_CAST")
        val typeJsons = values[2] as List<String>
        val lifetimeTotal = values[3] as Int
        val lifetimeScam = values[4] as Int
        val screenedClean = values[5] as Int
        val blocklist = values[6] as Int
        // values[7] is the ticker timestamp — used only to trigger recalculation

        val breakdown = typeJsons
            .flatMap { it.split(",") }
            .filter { it.isNotBlank() }
            .groupingBy { it.trim() }
            .eachCount()

        // If installTimestamp was never set, backfill it now for existing users
        var installTs = settings.installTimestamp
        if (installTs == 0L && settings.onboardingComplete) {
            installTs = System.currentTimeMillis()
            settings.installTimestamp = installTs
        }
        val protectionDays = if (installTs > 0)
            ((System.currentTimeMillis() - installTs) / (24 * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
        else 0

        val estimatedSaved = lifetimeScam * 1480

        // Protection score: 100 = fully configured
        var score = 0
        if (settings.onboardingComplete) score += 30
        if (settings.callForwardingEnabled) score += 25
        if (settings.messageScanEnabled) score += 15
        if (settings.enableTts) score += 10
        if (settings.enableSound) score += 10
        if (settings.enableVibration) score += 10

        HomeStats(
            scamCallsThisMonth = scam,
            totalCallsThisMonth = total,
            scamTypeBreakdown = breakdown,
            totalCallsLifetime = lifetimeTotal,
            scamCallsLifetime = lifetimeScam,
            callsScreenedClean = screenedClean,
            messagesScanned = settings.messagesScannedTotal,
            messagesFlagged = settings.messagesFlaggedTotal,
            blocklistSize = blocklist,
            protectionDays = protectionDays,
            installTimestamp = installTs,
            estimatedMoneySaved = estimatedSaved,
            protectionScore = score.coerceAtMost(100)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeStats())

    /** Last 7 days of call counts, one entry per day (Sun … Sat). */
    val trendData: StateFlow<List<DailyCallCount>> = repository
        .observeSummariesSince(sevenDaysAgo)
        .map { summaries ->
            val labelFmt = SimpleDateFormat("EEE", Locale.ENGLISH) // Mon, Tue…
            val keyFmt   = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH)
            // Build Sun–Sat week containing today (Sunday first)
            val cal = Calendar.getInstance()
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // SUNDAY=1
            cal.add(Calendar.DAY_OF_YEAR, -(dayOfWeek - Calendar.SUNDAY))
            val days = (0..6).map { offset ->
                val dayCal = cal.clone() as Calendar
                dayCal.add(Calendar.DAY_OF_YEAR, offset)
                keyFmt.format(dayCal.time) to labelFmt.format(dayCal.time)
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
