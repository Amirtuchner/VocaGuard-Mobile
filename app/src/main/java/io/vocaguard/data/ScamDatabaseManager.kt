package io.vocaguard.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.vocaguard.data.db.ScamNumberDao
import io.vocaguard.data.db.VocaGuardDatabase
import io.vocaguard.data.db.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ScamDatabaseManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scamNumberDao: ScamNumberDao =
        VocaGuardDatabase.getInstance(appContext).scamNumberDao()
    private val networkChecker = NetworkScamChecker.getInstance(appContext)
    private val reportSubmitter = ReportSubmitter.getInstance(appContext)
    private val whitelistPrefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_WHITELIST, Context.MODE_PRIVATE)

    // Single managed scope — avoids leaking a new CoroutineScope on every operation
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-memory cache for fast call-time lookups
    private val scamDatabase = ConcurrentHashMap<String, ScamInfo>()
    private val monitoringCalls = ConcurrentHashMap<String, Boolean>()

    @Volatile
    var activeCallPhoneNumber: String = ""
        private set

    companion object {
        private const val TAG = "ScamDatabaseManager"
        private const val PREFS_WHITELIST = "vocaguard_whitelist"
        private const val KEY_WHITELIST = "whitelist"

        /** Numbers imported from the community blocklist expire after 30 days. */
        const val COMMUNITY_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** Numbers fetched via network API expire after 7 days. */
        const val NETWORK_TTL_MS = 7L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: ScamDatabaseManager? = null

        fun getInstance(context: Context): ScamDatabaseManager {
            return instance ?: synchronized(this) {
                instance ?: ScamDatabaseManager(context.applicationContext).also { instance = it }
            }
        }

        /** For use in tests only — forces the next [getInstance] call to create a fresh instance. */
        fun resetInstance() {
            instance = null
        }
    }

    init {
        loadDatabase()
    }

    private fun loadDatabase() {
        scope.launch {
            try {
                // Purge stale entries before loading into the in-memory cache.
                val nowMs = System.currentTimeMillis()
                val deleted = scamNumberDao.run {
                    val before = count()
                    deleteExpiredBefore(nowMs)
                    before - count()
                }
                if (deleted > 0) Log.i(TAG, "Purged $deleted expired scam number(s)")

                scamNumberDao.getAll().forEach { entity ->
                    scamDatabase.putIfAbsent(entity.phoneNumber, entity.toDomain())
                }
                Log.d(TAG, "Loaded ${scamDatabase.size} scam numbers from database")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading scam database", e)
            }
        }
    }

    fun checkNumber(phoneNumber: String): ScamInfo {
        val cleanNumber = cleanPhoneNumber(phoneNumber)

        if (isWhitelisted(cleanNumber)) {
            return ScamInfo(phoneNumber = cleanNumber)
        }

        scamDatabase[cleanNumber]?.let { return it }

        if (isSuspiciousPattern(cleanNumber)) {
            return ScamInfo(phoneNumber = cleanNumber, isSuspicious = true, scamType = ScamType.ROBOCALL)
        }

        // Background network check — result cached for next call, expires in 7 days
        scope.launch {
            val result = networkChecker.checkNumber(cleanNumber)
            if (result != null) {
                scamDatabase[cleanNumber] = result
                val expiresAt = System.currentTimeMillis() + NETWORK_TTL_MS
                scamNumberDao.insert(result.toEntity(expiresAt = expiresAt))
                Log.i(TAG, "Network check updated database for $cleanNumber: ${result.scamType}")
            }
        }

        return ScamInfo(phoneNumber = cleanNumber)
    }

    /**
     * Records [phoneNumber] as a known scammer.
     *
     * @param expiresAt Epoch ms after which the entry is considered stale.
     *   Defaults to 0 (never expires) for user-initiated reports.
     *   Pass [COMMUNITY_TTL_MS] or [NETWORK_TTL_MS] offsets for automated sources.
     */
    suspend fun reportScamNumber(
        phoneNumber: String,
        scamType: ScamType,
        expiresAt: Long = 0L
    ) {
        val cleanNumber = cleanPhoneNumber(phoneNumber)
        val existing = scamDatabase[cleanNumber]
        val updated = if (existing != null) {
            existing.copy(
                isKnownScammer = true,
                scamType = scamType,
                reportCount = existing.reportCount + 1,
                lastReported = System.currentTimeMillis()
            )
        } else {
            ScamInfo(
                phoneNumber = cleanNumber,
                isKnownScammer = true,
                scamType = scamType,
                reportCount = 1,
                lastReported = System.currentTimeMillis()
            )
        }
        scamDatabase[cleanNumber] = updated
        scamNumberDao.insert(updated.toEntity(expiresAt = expiresAt))
        Log.i(TAG, "Reported scam number: $cleanNumber (${scamType.name}, expiresAt=$expiresAt)")

        // Submit to community aggregation endpoint if configured (fire-and-forget, user-reports only).
        if (expiresAt == 0L) {
            reportSubmitter.submit(cleanNumber, scamType)
        }
    }

    // --- Whitelist ---

    suspend fun addToWhitelist(phoneNumber: String) {
        val cleanNumber = cleanPhoneNumber(phoneNumber)
        val current = whitelistPrefs.getStringSet(KEY_WHITELIST, emptySet()).orEmpty().toMutableSet()
        current.add(cleanNumber)
        whitelistPrefs.edit().putStringSet(KEY_WHITELIST, current).apply()
        // Remove from local scam database so the number is no longer flagged
        scamDatabase.remove(cleanNumber)
        scamNumberDao.deleteByNumber(cleanNumber)
        Log.i(TAG, "Added to whitelist and removed from scam DB: $cleanNumber")
    }

    fun removeFromWhitelist(phoneNumber: String) {
        val cleanNumber = cleanPhoneNumber(phoneNumber)
        val current = whitelistPrefs.getStringSet(KEY_WHITELIST, emptySet()).orEmpty().toMutableSet()
        current.remove(cleanNumber)
        whitelistPrefs.edit().putStringSet(KEY_WHITELIST, current).apply()
    }

    fun isWhitelisted(phoneNumber: String): Boolean {
        val cleanNumber = cleanPhoneNumber(phoneNumber)
        return whitelistPrefs.getStringSet(KEY_WHITELIST, emptySet())?.contains(cleanNumber) == true
    }

    fun getWhitelistedNumbers(): Set<String> =
        whitelistPrefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()

    // --- Monitoring ---

    fun markCallForMonitoring(phoneNumber: String, isSuspicious: Boolean) {
        val cleanNumber = cleanPhoneNumber(phoneNumber)
        monitoringCalls[cleanNumber] = isSuspicious
        activeCallPhoneNumber = cleanNumber
    }

    fun stopMonitoringCall(phoneNumber: String) {
        val cleanNumber = cleanPhoneNumber(phoneNumber)
        monitoringCalls.remove(cleanNumber)
        if (activeCallPhoneNumber == cleanNumber) activeCallPhoneNumber = ""
    }

    fun isCallBeingMonitored(phoneNumber: String) =
        monitoringCalls.containsKey(cleanPhoneNumber(phoneNumber))

    fun getAllScamNumbers(): List<ScamInfo> = scamDatabase.values.toList()

    fun clearDatabase() {
        scamDatabase.clear()
        scope.launch {
            scamNumberDao.clearAll()
        }
    }

    /** Clears all state including whitelist. For use in tests only. */
    fun resetForTesting() {
        scamDatabase.clear()
        monitoringCalls.clear()
        activeCallPhoneNumber = ""
        whitelistPrefs.edit().clear().commit() // commit() for synchronous clear in tests
        scope.launch { scamNumberDao.clearAll() }
    }

    /**
     * Normalises [phoneNumber] to a digit-only E.164 string (without the leading +) so that
     * "+1 (555) 123-4567", "15551234567", and "(555) 123-4567" all resolve to the same key.
     * Falls back to stripping non-digits if libphonenumber cannot parse the input.
     */
    private fun cleanPhoneNumber(phoneNumber: String): String {
        return try {
            val phoneUtil = PhoneNumberUtil.getInstance()
            val parsed = phoneUtil.parse(phoneNumber, "US")
            phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
                .removePrefix("+")
        } catch (_: NumberParseException) {
            phoneNumber.replace(Regex("[^0-9]"), "")
        }
    }

    private fun isSuspiciousPattern(phoneNumber: String): Boolean = when {
        phoneNumber.matches(Regex("(\\d)\\1{6,}")) -> true
        isSequential(phoneNumber) -> true
        else -> false
    }

    private fun isSequential(phoneNumber: String): Boolean {
        if (phoneNumber.length < 4) return false
        var sequential = 0
        for (i in 1 until phoneNumber.length) {
            if (phoneNumber[i].digitToInt() == phoneNumber[i - 1].digitToInt() + 1) {
                sequential++
                if (sequential >= 5) return true
            } else {
                sequential = 0
            }
        }
        return false
    }
}
