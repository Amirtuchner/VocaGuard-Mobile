package io.vocaguard.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.vocaguard.data.CommunityScamSync
import io.vocaguard.data.DetectionSettings
import io.vocaguard.data.FamilyContact
import io.vocaguard.data.FamilyGuardSettings
import io.vocaguard.data.NetworkScamChecker
import io.vocaguard.data.ScamType
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
    private val familySettings    = FamilyGuardSettings.getInstance(context)

    private val _sensitivity = MutableStateFlow(detectionSettings.sensitivity)
    val sensitivity: StateFlow<Int> = _sensitivity.asStateFlow()

    private val _locale = MutableStateFlow(detectionSettings.locale)
    val locale: StateFlow<String> = _locale.asStateFlow()

    private val _apiKey = MutableStateFlow(networkChecker.getApiKey())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _enableTts = MutableStateFlow(detectionSettings.enableTts)
    val enableTts: StateFlow<Boolean> = _enableTts.asStateFlow()

    private val _enableSound = MutableStateFlow(detectionSettings.enableSound)
    val enableSound: StateFlow<Boolean> = _enableSound.asStateFlow()

    private val _enableVibration = MutableStateFlow(detectionSettings.enableVibration)
    val enableVibration: StateFlow<Boolean> = _enableVibration.asStateFlow()

    private val _messageScanEnabled = MutableStateFlow(detectionSettings.messageScanEnabled)
    val messageScanEnabled: StateFlow<Boolean> = _messageScanEnabled.asStateFlow()

    private val _themePreference = MutableStateFlow(detectionSettings.themePreference)
    val themePreference: StateFlow<String> = _themePreference.asStateFlow()

    private val _apiKeySaved = MutableStateFlow(false)
    val apiKeySaved: StateFlow<Boolean> = _apiKeySaved.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatus = MutableStateFlow("")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _importResult = MutableStateFlow("")
    val importResult: StateFlow<String> = _importResult.asStateFlow()

    // ── Per-scam-type alert toggles ───────────────────────────────────────────

    private val _alertTypeEnabled = MutableStateFlow(
        ScamType.entries.associateWith { detectionSettings.isAlertEnabledFor(it) }
    )
    val alertTypeEnabled: StateFlow<Map<ScamType, Boolean>> = _alertTypeEnabled.asStateFlow()

    // ── Report submission endpoint ─────────────────────────────────────────────

    private val _reportEndpointUrl = MutableStateFlow(detectionSettings.reportEndpointUrl)
    val reportEndpointUrl: StateFlow<String> = _reportEndpointUrl.asStateFlow()

    private val _reportEndpointSaved = MutableStateFlow(false)
    val reportEndpointSaved: StateFlow<Boolean> = _reportEndpointSaved.asStateFlow()

    // ── Model update ──────────────────────────────────────────────────────────

    private val _modelUpdateStatus = MutableStateFlow("")
    val modelUpdateStatus: StateFlow<String> = _modelUpdateStatus.asStateFlow()

    init {
        // Auto-check for a model update once per day in the background.
        viewModelScope.launch {
            val manager = io.vocaguard.ml.ModelUpdateManager.getInstance(getApplication())
            if (manager.isAutoCheckDue()) {
                val result = manager.checkAndDownload()
                // Only surface the status if there's actually something new — don't
                // override an empty status with "up to date" on every launch.
                if (result.contains("updated", ignoreCase = true) ||
                    result.contains("failed", ignoreCase = true)) {
                    _modelUpdateStatus.value = result
                }
            }
        }
    }

    // ── Family Guard Mode ─────────────────────────────────────────────────────

    private val _familyGuardEnabled = MutableStateFlow(familySettings.isEnabled)
    val familyGuardEnabled: StateFlow<Boolean> = _familyGuardEnabled.asStateFlow()

    private val _seniorModeEnabled = MutableStateFlow(familySettings.seniorModeEnabled)
    val seniorModeEnabled: StateFlow<Boolean> = _seniorModeEnabled.asStateFlow()

    private val _seniorName = MutableStateFlow(familySettings.seniorName)
    val seniorName: StateFlow<String> = _seniorName.asStateFlow()

    private val _callAlertEnabled = MutableStateFlow(familySettings.callAlertEnabled)
    val callAlertEnabled: StateFlow<Boolean> = _callAlertEnabled.asStateFlow()

    private val _familyWebhookUrl = MutableStateFlow(familySettings.webhookUrl)
    val familyWebhookUrl: StateFlow<String> = _familyWebhookUrl.asStateFlow()

    private val _familyContacts = MutableStateFlow(familySettings.contacts)
    val familyContacts: StateFlow<List<FamilyContact>> = _familyContacts.asStateFlow()

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

    // ── Alert toggles ─────────────────────────────────────────────────────────

    fun setEnableTts(value: Boolean) {
        _enableTts.value = value
        detectionSettings.enableTts = value
    }

    fun setEnableSound(value: Boolean) {
        _enableSound.value = value
        detectionSettings.enableSound = value
    }

    fun setEnableVibration(value: Boolean) {
        _enableVibration.value = value
        detectionSettings.enableVibration = value
    }

    fun setMessageScanEnabled(value: Boolean) {
        _messageScanEnabled.value = value
        detectionSettings.messageScanEnabled = value
    }

    fun setThemePreference(theme: String) {
        _themePreference.value = theme
        detectionSettings.themePreference = theme
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
            _syncStatus.value = when {
                count >= 0 -> "Imported $count numbers"
                communitySync.lastSyncError.isNotEmpty() -> "Sync failed: ${communitySync.lastSyncError}"
                else -> "Sync failed — check your connection"
            }
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
        put("enableTts", detectionSettings.enableTts)
        put("enableSound", detectionSettings.enableSound)
        put("enableVibration", detectionSettings.enableVibration)
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
            if (obj.has("enableTts")) {
                val v = obj.getBoolean("enableTts")
                detectionSettings.enableTts = v
                _enableTts.value = v
            }
            if (obj.has("enableSound")) {
                val v = obj.getBoolean("enableSound")
                detectionSettings.enableSound = v
                _enableSound.value = v
            }
            if (obj.has("enableVibration")) {
                val v = obj.getBoolean("enableVibration")
                detectionSettings.enableVibration = v
                _enableVibration.value = v
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

    // ── Per-scam-type alert toggles ───────────────────────────────────────────

    fun setAlertTypeEnabled(scamType: ScamType, enabled: Boolean) {
        detectionSettings.setAlertEnabled(scamType, enabled)
        _alertTypeEnabled.value = _alertTypeEnabled.value + (scamType to enabled)
    }

    // ── Call forwarding ───────────────────────────────────────────────────────

    private val _callForwardingEnabled = MutableStateFlow(detectionSettings.callForwardingEnabled)
    val callForwardingEnabled: StateFlow<Boolean> = _callForwardingEnabled.asStateFlow()

    fun setCallForwardingEnabled(value: Boolean) {
        detectionSettings.callForwardingEnabled = value
        _callForwardingEnabled.value = value
    }

    // ── Report endpoint ───────────────────────────────────────────────────────

    fun updateReportEndpointUrl(url: String) {
        _reportEndpointUrl.value = url
        _reportEndpointSaved.value = false
    }

    fun saveReportEndpointUrl() {
        detectionSettings.reportEndpointUrl = _reportEndpointUrl.value.trim()
        _reportEndpointSaved.value = true
    }

    // ── Model update ──────────────────────────────────────────────────────────

    fun checkForModelUpdate() {
        if (_modelUpdateStatus.value == "Checking…") return
        _modelUpdateStatus.value = "Checking…"
        viewModelScope.launch {
            val result = io.vocaguard.ml.ModelUpdateManager.getInstance(getApplication())
                .checkAndDownload()
            _modelUpdateStatus.value = result
        }
    }

    // ── Family Guard actions ──────────────────────────────────────────────────

    fun setFamilyGuardEnabled(enabled: Boolean) {
        familySettings.isEnabled = enabled
        _familyGuardEnabled.value = enabled
    }

    fun setSeniorModeEnabled(enabled: Boolean) {
        familySettings.seniorModeEnabled = enabled
        _seniorModeEnabled.value = enabled
    }

    fun updateSeniorName(name: String) {
        _seniorName.value = name
    }

    fun saveSeniorName() {
        familySettings.seniorName = _seniorName.value
    }

    fun setCallAlertEnabled(enabled: Boolean) {
        familySettings.callAlertEnabled = enabled
        _callAlertEnabled.value = enabled
    }

    fun updateFamilyWebhookUrl(url: String) {
        _familyWebhookUrl.value = url
    }

    fun saveFamilyWebhookUrl() {
        familySettings.webhookUrl = _familyWebhookUrl.value
    }

    fun addFamilyContact(name: String, phoneNumber: String) {
        familySettings.addContact(FamilyContact(name = name.trim(), phoneNumber = phoneNumber.trim()))
        _familyContacts.value = familySettings.contacts
    }

    fun removeFamilyContact(phoneNumber: String) {
        familySettings.removeContact(phoneNumber)
        _familyContacts.value = familySettings.contacts
    }

    fun sendTestAlert() {
        viewModelScope.launch {
            io.vocaguard.alert.FamilyAlertSender(getApplication()).sendAlert(
                scamType   = io.vocaguard.data.ScamType.IRS_SCAM,
                confidence = 0.92f
            )
        }
    }
}
