package io.vocaguard.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import io.vocaguard.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.linphone.core.*

/**
 * Manages VocaGuard's embedded SIP client (Linphone Core).
 * Registers as PJSIP endpoint "vocaguard" on the Asterisk server.
 * When Asterisk originates a call here (after AMI Originate), the SDK
 * auto-answers if [pendingAccept] is set, and provides audio directly in-app.
 */
object VocaGuardSipManager {

    private const val TAG           = "VocaGuardSIP"
    private const val SIP_REALM     = "asterisk"
    private const val FALLBACK_USER = "vocaguard"

    enum class CallState { IDLE, INCOMING, ACTIVE, ENDED }

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState

    private val _registrationState = MutableStateFlow(RegistrationState.None)
    /** Observed by UI to show SIP connectivity status. */
    val registrationState: StateFlow<RegistrationState> = _registrationState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var retryJob: Job? = null
    private var retryCount = 0

    private var core: Core? = null
    private var appContext: Context? = null
    private var iterateJob: Job? = null

    /** Set to true when user taps Accept — SDK will auto-answer the next incoming INVITE. */
    @Volatile var pendingAccept = false

    fun initialize(context: Context) {
        if (core != null) return
        appContext = context.applicationContext
        try {
            val factory = Factory.instance()
            factory.setDebugMode(false, "VocaGuard")

            val c = factory.createCore(null, null, context)

            // Audio-only — disable video
            c.isVideoCaptureEnabled = false
            c.isVideoDisplayEnabled = false

            // Enable Opus codec for better audio quality (lower latency, better compression)
            for (pt in c.audioPayloadTypes) {
                when (pt.mimeType.lowercase()) {
                    "opus" -> pt.enable(true)
                    "pcmu", "pcma" -> pt.enable(true)    // keep G.711 as fallback
                    else -> pt.enable(false)
                }
            }

            // Increase jitter buffer to absorb network variance — reduces clicks/pops
            c.audioJittcomp = 80   // ms (default 60)

            // Enable adaptive rate control for smoother audio
            c.isAdaptiveRateControlEnabled = true

            // Keep NAT pinhole open with UDP CRLF keep-alives
            c.isKeepAliveEnabled = true

            // Use per-user SIP credentials if registered, otherwise fall back to legacy
            val serverIp = BuildConfig.TOKEN_SERVER_HOST
            val sipUser  = if (ServerDetectionManager.isRegistered()) ServerDetectionManager.getSipExtension() else FALLBACK_USER
            val sipPass  = if (ServerDetectionManager.isRegistered()) ServerDetectionManager.getSipPassword() else BuildConfig.SIP_PASSWORD

            // SIP auth credentials
            val authInfo = factory.createAuthInfo(
                sipUser, null, sipPass, null, SIP_REALM, serverIp
            )
            c.addAuthInfo(authInfo)

            // SIP account
            val params = c.createAccountParams()
            params.identityAddress = factory.createAddress("sip:$sipUser@$serverIp")
            val serverAddr = factory.createAddress("sip:$serverIp")
            serverAddr?.transport = TransportType.Udp
            params.serverAddress = serverAddr
            params.isRegisterEnabled = true
            params.expires = 300  // re-register every 5min — sufficient for NAT keep-alive

            val account = c.createAccount(params)
            c.addAccount(account)
            c.defaultAccount = account

            c.addListener(object : CoreListenerStub() {
                override fun onCallStateChanged(
                    core: Core, call: Call, state: Call.State, message: String
                ) {
                    Log.i(TAG, "Call state: $state — $message")
                    when (state) {
                        Call.State.IncomingReceived -> {
                            _callState.value = CallState.INCOMING
                            if (pendingAccept) {
                                pendingAccept = false
                                answerCall(core, call)
                            }
                        }
                        Call.State.StreamsRunning, Call.State.Connected -> {
                            _callState.value = CallState.ACTIVE
                        }
                        Call.State.End, Call.State.Released, Call.State.Error -> {
                            _callState.value = CallState.ENDED
                            pendingAccept = false
                            currentRecordFile = null
                        }
                        else -> {}
                    }
                }

                override fun onAccountRegistrationStateChanged(
                    core: Core, account: Account,
                    state: RegistrationState, message: String
                ) {
                    Log.i(TAG, "Registration: $state — $message")
                    _registrationState.value = state
                    when (state) {
                        RegistrationState.Ok -> {
                            retryJob?.cancel()
                            retryCount = 0
                        }
                        RegistrationState.Failed -> {
                            scheduleReregister(account)
                        }
                        else -> {}
                    }
                }
            })

            c.start()
            core = c

            // Linphone requires periodic iterate() calls to process SIP messages.
            // Adaptive interval: 20ms during active/incoming calls for real-time
            // audio; 500ms when idle. The idle loop only needs to keep the
            // registration alive and notice an inbound INVITE — 500ms instead of
            // 100ms cuts idle CPU wake-ups 5× (2/sec vs 10/sec), letting the phone
            // sleep more between them. Incoming calls are announced by the FCM
            // "incoming_call" push first, and the state flips to INCOMING as soon
            // as the INVITE is seen, so answer latency stays sub-second.
            iterateJob = scope.launch(Dispatchers.Main) {
                while (isActive) {
                    core?.iterate()
                    val interval = if (_callState.value == CallState.ACTIVE ||
                                       _callState.value == CallState.INCOMING) 20L else 500L
                    delay(interval)
                }
            }

            Log.i(TAG, "SIP core initialised and registered")
        } catch (e: Exception) {
            Log.e(TAG, "SIP init failed", e)
        }
    }

    private fun scheduleReregister(account: Account) {
        retryJob?.cancel()
        val delayMs = when (retryCount) {
            0    -> 5_000L
            1    -> 15_000L
            2    -> 30_000L
            else -> 60_000L
        }
        retryCount++
        retryJob = scope.launch {
            delay(delayMs)
            Log.w(TAG, "Re-registration attempt $retryCount after ${delayMs}ms")
            account.refreshRegister()
        }
    }

    /**
     * Tear down and re-initialize the SIP core with fresh credentials.
     * Call after a successful server registration to switch to the per-user extension.
     */
    fun reinitialize(context: Context) {
        iterateJob?.cancel()
        core?.stop()
        core = null
        retryJob?.cancel()
        retryCount = 0
        _callState.value = CallState.IDLE
        _registrationState.value = RegistrationState.None
        initialize(context)
    }

    /**
     * Force a registration refresh. Call from MainActivity.onResume() so the
     * app re-registers promptly after returning to the foreground (e.g. after
     * Asterisk was restarted while the app was in the background).
     */
    fun ensureRegistered() {
        val account = core?.defaultAccount ?: return
        if (_registrationState.value != RegistrationState.Ok) {
            Log.i(TAG, "ensureRegistered: state=${_registrationState.value}, refreshing")
            account.refreshRegister()
        }
    }

    /** Called when user taps Accept — accepts an already-incoming call or waits for the next. */
    fun acceptCall() {
        val incomingCall = core?.calls?.firstOrNull { it.state == Call.State.IncomingReceived }
        if (incomingCall != null) {
            pendingAccept = false
            answerCall(core!!, incomingCall)
        } else {
            pendingAccept = true   // AMI Originate hasn't arrived yet — answer when it does
        }
    }

    /** Path of the current call's recording file (pre-set at answer time). */
    private var currentRecordFile: String? = null

    private fun answerCall(core: Core, call: Call) {
        val callParams = core.createCallParams(call)
        callParams?.isAudioEnabled = true
        callParams?.isVideoEnabled = false
        // Pre-set record file so startRecording() works mid-call
        appContext?.let { ctx ->
            val dir = java.io.File(ctx.getExternalFilesDir(null), "recordings")
            dir.mkdirs()
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = java.io.File(dir, "call_$ts.wav")
            callParams?.recordFile = file.absolutePath
            currentRecordFile = file.absolutePath
            Log.i(TAG, "Record file pre-set: ${file.absolutePath}")
        }
        try {
            call.acceptWithParams(callParams)
            // Route audio to earpiece by default (user holds phone to ear)
            core.outputAudioDevice = core.audioDevices
                .firstOrNull { it.type == AudioDevice.Type.Earpiece }
                ?: core.outputAudioDevice
            // Boost received audio to match regular call loudness
            core.playbackGainDb = 6.0f
            // Maximize the Android voice call stream volume
            appContext?.let { ctx ->
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "acceptWithParams failed", e)
        }
    }

    fun hangupCurrentCall() {
        val call = core?.currentCall ?: core?.calls?.firstOrNull()
        call?.terminate()
        _callState.value = CallState.IDLE
    }

    /** Start recording the current call. Record file is pre-set at answer time. */
    fun startRecording(context: Context): String? {
        val call = core?.currentCall ?: return null
        val path = currentRecordFile ?: return null
        call.startRecording()
        Log.i(TAG, "Recording started: $path")
        return path
    }

    /** Stop recording the current call. */
    fun stopRecording() {
        core?.currentCall?.stopRecording()
        Log.i(TAG, "Recording stopped")
    }

    /** Toggle earpiece ↔ speaker during an active call. */
    fun setSpeaker(on: Boolean) {
        val c = core ?: return
        c.outputAudioDevice = c.audioDevices.firstOrNull {
            it.type == if (on) AudioDevice.Type.Speaker else AudioDevice.Type.Earpiece
        } ?: c.outputAudioDevice
    }

    /** Send a DTMF tone on the active call (for navigating IVR menus, entering
     *  PINs, etc.). Linphone relays it as RFC 2833 / SIP INFO, and Asterisk
     *  forwards it to the PSTN leg. Accepts 0-9, * and #. */
    fun sendDtmf(digit: Char) {
        val call = core?.currentCall ?: core?.calls?.firstOrNull() ?: return
        try {
            call.sendDtmf(digit)
            Log.i(TAG, "Sent DTMF: $digit")
        } catch (e: Exception) {
            Log.w(TAG, "sendDtmf($digit) failed: ${e.message}")
        }
    }

    fun resetState() {
        _callState.value = CallState.IDLE
    }
}
