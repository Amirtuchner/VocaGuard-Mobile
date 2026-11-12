package io.vocaguard.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.vocaguard.billing.BillingManager
import io.vocaguard.billing.SubscriptionStatus
import io.vocaguard.data.CommunityScamSync
import io.vocaguard.data.DetectionSettings
import io.vocaguard.service.ErrorReporter
import io.vocaguard.data.FamilyContact
import io.vocaguard.data.FamilyGuardSettings
import io.vocaguard.data.NetworkScamChecker
import io.vocaguard.data.ScamType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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

    private val _showCallerNames = MutableStateFlow(detectionSettings.showCallerNames)
    val showCallerNames: StateFlow<Boolean> = _showCallerNames.asStateFlow()

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
                    if (result.contains("failed", ignoreCase = true)) {
                        ErrorReporter.report(getApplication(), "Model update failed: $result")
                    }
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

    private val _seniorPhoneNumber = MutableStateFlow(familySettings.seniorPhoneNumber)
    val seniorPhoneNumber: StateFlow<String> = _seniorPhoneNumber.asStateFlow()

    private val _callAlertEnabled = MutableStateFlow(familySettings.callAlertEnabled)
    val callAlertEnabled: StateFlow<Boolean> = _callAlertEnabled.asStateFlow()

    private val _familyContacts = MutableStateFlow(familySettings.contacts)
    val familyContacts: StateFlow<List<FamilyContact>> = _familyContacts.asStateFlow()

    private val _testAlertMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val testAlertMessage: SharedFlow<String> = _testAlertMessage.asSharedFlow()

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

    /** Opt-in to using contacts for caller names (requires READ_CONTACTS). Turning it
     *  off also stops contacts being read even if the OS permission is still granted. */
    fun setShowCallerNames(value: Boolean) {
        _showCallerNames.value = value
        detectionSettings.showCallerNames = value
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
            if (count < 0) {
                ErrorReporter.report(getApplication(), "Blocklist sync failed: ${communitySync.lastSyncError}")
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
        if (value) io.vocaguard.TenjinEvents.send(context, io.vocaguard.TenjinEvents.CALL_FORWARDING_ENABLED)
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

    fun updateSeniorPhoneNumber(phone: String) {
        _seniorPhoneNumber.value = phone
    }

    fun saveSeniorPhoneNumber() {
        familySettings.seniorPhoneNumber = _seniorPhoneNumber.value
    }

    fun setCallAlertEnabled(enabled: Boolean) {
        familySettings.callAlertEnabled = enabled
        _callAlertEnabled.value = enabled
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
            val permManager = PermissionsManager(context)
            when {
                !familySettings.isEnabled ->
                    _testAlertMessage.emit("Enable Family Guard first")
                familySettings.contacts.isEmpty() ->
                    _testAlertMessage.emit("Add at least one contact first")
                else -> {
                    val sender = io.vocaguard.alert.FamilyAlertSender(context)
                    sender.sendAlert(
                        scamType   = io.vocaguard.data.ScamType.IRS_SCAM,
                        confidence = 0.92f
                    )
                    sender.makeCallAlert(
                        scamType                 = io.vocaguard.data.ScamType.IRS_SCAM,
                        confidence               = 0.92f,
                        delayBeforeCallMs        = 0L,
                        ignoreCallAlertEnabled   = true
                    )
                    val count = familySettings.contacts.size
                    _testAlertMessage.emit("Test alert sent to $count contact${if (count > 1) "s" else ""}")
                }
            }
        }
    }

    // ── Data deletion ─────────────────────────────────────────────────────────

    private val _deleteDataStatus = MutableStateFlow("")
    val deleteDataStatus: StateFlow<String> = _deleteDataStatus.asStateFlow()

    fun deleteAllData() {
        viewModelScope.launch {
            _deleteDataStatus.value = "Deleting…"
            val db = io.vocaguard.data.db.VocaGuardDatabase.getInstance(context)
            // 1. Delete from server
            val serverOk = sdm.deleteFromServer()
            // 2. Clear local DB
            db.transcriptDao().clearAll()
            // 3. Clear all local prefs and registration
            sdm.clear()
            detectionSettings.reset()
            familySettings.reset()
            _familyGuardEnabled.value = false
            _familyContacts.value = emptyList()
            _serverRegistered.value = false
            _serverSipExtension.value = ""
            _serverActivationCode.value = ""
            // 4. Tear down SIP
            io.vocaguard.service.VocaGuardSipManager.reinitialize(getApplication())
            _deleteDataStatus.value = if (serverOk) "All data deleted" else "Local data deleted (server unreachable)"
        }
    }

    // ── Subscription status ───────────────────────────────────────────────────

    val billingStatus: StateFlow<SubscriptionStatus> =
        BillingManager.getInstance(application).status

    // ── Server Detection registration ─────────────────────────────────────────

    private val sdm = io.vocaguard.service.ServerDetectionManager

    private val _serverRegistered = MutableStateFlow(sdm.isRegistered())
    val serverRegistered: StateFlow<Boolean> = _serverRegistered.asStateFlow()

    private val _serverPhoneInput = MutableStateFlow(sdm.getPhoneNumber())
    val serverPhoneInput: StateFlow<String> = _serverPhoneInput.asStateFlow()

    private val _serverSipExtension = MutableStateFlow(sdm.getSipExtension())
    val serverSipExtension: StateFlow<String> = _serverSipExtension.asStateFlow()

    private val _serverActivationCode = MutableStateFlow(sdm.getActivationCode(getApplication()))
    val serverActivationCode: StateFlow<String> = _serverActivationCode.asStateFlow()

    private val _serverRegStatus = MutableStateFlow("")
    val serverRegStatus: StateFlow<String> = _serverRegStatus.asStateFlow()

    private val _serverIsRegistering = MutableStateFlow(false)
    val serverIsRegistering: StateFlow<Boolean> = _serverIsRegistering.asStateFlow()

    /** True while the one-tap flow is placing/awaiting the number-detection call. */
    private val _awaitingNumberVerify = MutableStateFlow(false)
    val awaitingNumberVerify: StateFlow<Boolean> = _awaitingNumberVerify.asStateFlow()

    init {
        // When the server pushes back the device's own number (CLI-capture onboarding),
        // auto-fill it and continue straight into registration + call forwarding.
        viewModelScope.launch {
            io.vocaguard.service.VocaGuardFcmService.numberVerifiedFlow.collect { num ->
                if (!num.isNullOrBlank() && _awaitingNumberVerify.value) {
                    _awaitingNumberVerify.value = false
                    io.vocaguard.service.VocaGuardFcmService.numberVerifiedFlow.value = null
                    updateServerPhoneInput(num)
                    _serverRegStatus.value = "Number detected: $num"
                    activateProtection(num)
                }
            }
        }
    }

    fun updateServerPhoneInput(phone: String) {
        _serverPhoneInput.value = phone
    }

    fun registerWithServer() {
        if (_serverIsRegistering.value) return
        _serverIsRegistering.value = true
        _serverRegStatus.value = "Registering…"
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { fcmToken ->
                viewModelScope.launch {
                    val result = sdm.register(_serverPhoneInput.value.trim(), fcmToken)
                    if (result.success) {
                        _serverRegistered.value = true
                        io.vocaguard.TenjinEvents.send(context, io.vocaguard.TenjinEvents.REGISTERED)
                        _serverSipExtension.value = result.sipExtension
                        _serverActivationCode.value = sdm.getActivationCode(getApplication())
                        _serverRegStatus.value =
                            "Registered! Dial ${sdm.getActivationCode(getApplication())} to activate call forwarding."
                        io.vocaguard.service.VocaGuardSipManager.reinitialize(getApplication())
                    } else {
                        _serverRegStatus.value = "Registration failed: ${result.error}"
                        ErrorReporter.report(getApplication(), "Registration failed: ${result.error}")
                    }
                    _serverIsRegistering.value = false
                }
            }
            .addOnFailureListener { e ->
                _serverRegStatus.value = "Could not get FCM token: ${e.message}"
                ErrorReporter.report(getApplication(), "FCM token error: ${e.message}")
                _serverIsRegistering.value = false
            }
    }

    /** Shown when auto-detection + the Hint picker both yield no number. */
    fun promptManualEntry() {
        _serverRegStatus.value = "Couldn't detect your number — enter it manually below."
    }

    /**
     * One-tap "detect my number": CLI-capture verification. Asks the server for a
     * nonce, places a brief call to the verification DID with the nonce as DTMF, and
     * waits for the server to push back the ANI (this device's real number), which the
     * [numberVerifiedFlow] observer feeds into [activateProtection]. Carrier-agnostic —
     * works where getLine1Number / the Hint API return nothing (e.g. Israeli SIMs).
     * Falls back to manual entry on any failure or a ~35s timeout.
     */
    fun verifyNumberThenActivate() {
        if (_serverIsRegistering.value || _awaitingNumberVerify.value) return
        _awaitingNumberVerify.value = true
        _serverRegStatus.value = "Detecting your number…"
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { fcmToken ->
                viewModelScope.launch {
                    val vs = sdm.startNumberVerification(fcmToken)
                    if (!vs.success) {
                        _awaitingNumberVerify.value = false
                        _serverRegStatus.value =
                            "Couldn't start number detection — enter it manually below."
                        return@launch
                    }
                    // Dial the verification DID; the commas are post-dial pauses after
                    // which the dialer emits the nonce as DTMF (same as a conference PIN).
                    val dial = "${vs.verifyNumber},,${vs.code}"
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_CALL, Uri.fromParts("tel", dial, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        Log.i("VGActivate", "placed number-detect call to $dial")
                        _serverRegStatus.value = "Placing a quick call to detect your number…"
                    } catch (e: Exception) {
                        _awaitingNumberVerify.value = false
                        Log.w("VGActivate", "number-detect ACTION_CALL failed: ${e.message}")
                        _serverRegStatus.value =
                            "Couldn't place the detection call — enter your number manually below."
                        return@launch
                    }
                    // Timeout → fall back to manual entry if nothing came back.
                    kotlinx.coroutines.delay(35_000)
                    if (_awaitingNumberVerify.value) {
                        _awaitingNumberVerify.value = false
                        if (!_serverRegistered.value) {
                            _serverRegStatus.value =
                                "Couldn't detect your number automatically — enter it manually below."
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                _awaitingNumberVerify.value = false
                _serverRegStatus.value = "Couldn't get a device token: ${e.message}"
            }
    }

    /**
     * One-tap activation for the simplified onboarding: register with the server
     * AND silently enable call forwarding (no dialer), in a single step. Falls back
     * to the manual "dial this code" flow if the silent USSD fails on the carrier.
     */
    fun activateProtection(phoneNumber: String) {
        if (_serverIsRegistering.value) return
        val phone = phoneNumber.trim()
        if (phone.isEmpty()) {
            _serverRegStatus.value = "Pick or enter your phone number first"
            return
        }
        updateServerPhoneInput(phone)
        _serverIsRegistering.value = true
        _serverRegStatus.value = "Activating protection…"
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { fcmToken ->
                viewModelScope.launch {
                    val result = sdm.register(phone, fcmToken)
                    Log.i("VGActivate", "register success=${result.success} ext=${result.sipExtension} err=${result.error}")
                    if (!result.success) {
                        _serverRegStatus.value = "Activation failed: ${result.error}"
                        ErrorReporter.report(getApplication(), "Activation failed: ${result.error}")
                        _serverIsRegistering.value = false
                        return@launch
                    }
                    _serverRegistered.value = true
                    io.vocaguard.TenjinEvents.send(context, io.vocaguard.TenjinEvents.REGISTERED)
                    _serverSipExtension.value = result.sipExtension
                    _serverActivationCode.value = sdm.getActivationCode(getApplication())
                    io.vocaguard.service.VocaGuardSipManager.reinitialize(getApplication())
                    // Enable call forwarding (silent USSD → auto-dial fallback).
                    if (enableForwardingNow()) {
                        setCallForwardingEnabled(true)
                        _serverRegStatus.value = "Protection active ✓"
                    } else {
                        _serverRegStatus.value =
                            "Registered. Tap “Enable call forwarding” to finish."
                    }
                    _serverIsRegistering.value = false
                }
            }
            .addOnFailureListener { e ->
                _serverRegStatus.value = "Could not get FCM token: ${e.message}"
                ErrorReporter.report(getApplication(), "FCM token error: ${e.message}")
                _serverIsRegistering.value = false
            }
    }

    /** Enable call forwarding. Tries a silent USSD first (works on some carriers);
     *  if that's rejected (common for CF supplementary-service codes), auto-dials the
     *  code via ACTION_CALL — the reliable path, same as the user dialing it (brief
     *  dialer flash). Returns true if either path was triggered. */
    private suspend fun enableForwardingNow(): Boolean {
        val code = sdm.getActivationCode(getApplication())
        if (code.isEmpty()) { Log.w("VGActivate", "no activation code — can't enable forwarding"); return false }
        val silentOk = kotlinx.coroutines.withTimeoutOrNull(12_000) { trySilentUssd(code) } ?: false
        Log.i("VGActivate", "silent USSD ok=$silentOk code=$code")
        if (silentOk) return true
        return try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.fromParts("tel", code, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.i("VGActivate", "dialed CF code via ACTION_CALL: $code")
            true
        } catch (e: Exception) {
            Log.w("VGActivate", "ACTION_CALL for CF failed: ${e.message}")
            false
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun trySilentUssd(code: String): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
                tm.sendUssdRequest(code, object : android.telephony.TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        t: android.telephony.TelephonyManager, request: String, response: CharSequence
                    ) { if (cont.isActive) cont.resume(true) }
                    override fun onReceiveUssdResponseFailed(
                        t: android.telephony.TelephonyManager, request: String, failureCode: Int
                    ) { if (cont.isActive) cont.resume(false) }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }

    /** Enable call forwarding on its own — inline retry from the onboarding screen. */
    fun enableCallForwarding() {
        viewModelScope.launch {
            if (enableForwardingNow()) setCallForwardingEnabled(true)
        }
    }

    fun unregisterFromServer() {
        sdm.clear()
        _serverRegistered.value = false
        _serverSipExtension.value = ""
        _serverActivationCode.value = ""
        _serverRegStatus.value = "Unregistered"
        io.vocaguard.service.VocaGuardSipManager.reinitialize(getApplication())
    }
}
