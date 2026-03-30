package com.example.vocaguard.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.vocaguard.data.db.ScamNumberDao
import com.example.vocaguard.data.db.VocaGuardDatabase
import com.example.vocaguard.data.db.toEntity
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
                scamNumberDao.getAll().forEach { entity ->
                    scamDatabase[entity.phoneNumber] = entity.toDomain()
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

        // Background network check — result cached for next call
        scope.launch {
            val result = networkChecker.checkNumber(cleanNumber)
            if (result != null) {
                scamDatabase[cleanNumber] = result
                scamNumberDao.insert(result.toEntity())
                Log.i(TAG, "Network check updated database for $cleanNumber: ${result.scamType}")
            }
        }

        return ScamInfo(phoneNumber = cleanNumber)
    }

    suspend fun reportScamNumber(phoneNumber: String, scamType: ScamType) {
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
        scamNumberDao.insert(updated.toEntity())
        Log.i(TAG, "Reported scam number: $cleanNumber (${scamType.name})")
    }

    // --- Whitelist ---

    fun addToWhitelist(phoneNumber: String) {
        val cleanNumber = cleanPhoneNumber(phoneNumber)
        val current = whitelistPrefs.getStringSet(KEY_WHITELIST, emptySet())!!.toMutableSet()
        current.add(cleanNumber)
        whitelistPrefs.edit().putStringSet(KEY_WHITELIST, current).apply()
        Log.i(TAG, "Added to whitelist: $cleanNumber")
    }

    fun removeFromWhitelist(phoneNumber: String) {
        val cleanNumber = cleanPhoneNumber(phoneNumber)
        val current = whitelistPrefs.getStringSet(KEY_WHITELIST, emptySet())!!.toMutableSet()
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

    private fun cleanPhoneNumber(phoneNumber: String): String =
        phoneNumber.replace(Regex("[^0-9]"), "")

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