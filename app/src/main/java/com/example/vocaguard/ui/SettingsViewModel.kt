package com.example.vocaguard.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocaguard.data.CommunityScamSync
import com.example.vocaguard.data.DetectionSettings
import com.example.vocaguard.data.NetworkScamChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val detectionSettings = DetectionSettings.getInstance(context)
    private val networkChecker    = NetworkScamChecker.getInstance(context)
    private val communitySync     = CommunityScamSync.getInstance(context)

    private val _sensitivity = MutableStateFlow(detectionSettings.sensitivity)
    val sensitivity: StateFlow<Int> = _sensitivity.asStateFlow()

    private val _locale = MutableStateFlow(detectionSettings.locale)
    val locale: StateFlow<String> = _locale.asStateFlow()

    private val _apiKey = MutableStateFlow(networkChecker.getApiKey())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _apiKeySaved = MutableStateFlow(false)
    val apiKeySaved: StateFlow<Boolean> = _apiKeySaved.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatus = MutableStateFlow("")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _importResult = MutableStateFlow("")
    val importResult: StateFlow<String> = _importResult.asStateFlow()

    // ── Sensitivity ───────────────────────────────────────────────────────────

    fun setSensitivity(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _sensitivity.value = clamped
        detectionSettings.sensitivity = clamped
    }

    // ── Locale ────────────────────────────────────────────────────────────────

    fun setLocale(locale: String) {
        _locale.value = locale
        detectionSettings.locale = locale
    }

    // ── API key ───────────────────────────────────────────────────────────────

    fun updateApiKey(key: String) {
        _apiKey.value = key
        _apiKeySaved.value = false
    }

    fun saveApiKey() {
        networkChecker.setApiKey(_apiKey.value.trim())
        _apiKeySaved.value = true
    }

    // ── Community sync ────────────────────────────────────────────────────────

    fun syncNow() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncStatus.value = ""
        viewModelScope.launch {
            val count = communitySync.sync(force = true)
            _syncStatus.value = if (count >= 0) "Imported $count numbers" else "Sync failed"
            _isSyncing.value = false
        }
    }

    // ── Backup / restore ──────────────────────────────────────────────────────

    /**
     * Returns a JSON string of all user-configurable settings, suitable for
     * sharing as a backup file.
     */
    fun buildSettingsJson(): String = JSONObject().apply {
        put("sensitivity", detectionSettings.sensitivity)
        put("locale", detectionSettings.locale)
        put("apiKey", networkChecker.getApiKey())
    }.toString(2)

    /**
     * Parses a JSON string previously produced by [buildSettingsJson] and
     * applies the values. Returns true on success, false if the JSON was invalid.
     */
    fun applySettingsJson(json: String): Boolean {
        return try {
            val obj = JSONObject(json)
            if (obj.has("sensitivity")) {
                val s = obj.getInt("sensitivity").coerceIn(0, 100)
                detectionSettings.sensitivity = s
                _sensitivity.value = s
            }
            if (obj.has("locale")) {
                val l = obj.getString("locale")
                detectionSettings.locale = l
                _locale.value = l
            }
            if (obj.has("apiKey")) {
                val k = obj.getString("apiKey")
                networkChecker.setApiKey(k)
                _apiKey.value = k
            }
            _importResult.value = "Settings restored successfully"
            true
        } catch (e: Exception) {
            _importResult.value = "Invalid backup file"
            false
        }
    }

    fun clearImportResult() {
        _importResult.value = ""
    }
}
