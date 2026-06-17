package io.vocaguard.service

import android.content.Context
import android.util.Log
import io.vocaguard.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.linphone.core.*

/**
 * Manages VocaGuard's embedded SIP client (Linphone Core).
 * Registers as PJSIP endpoint "vocaguard" on the Asterisk server.
 * When Asterisk originates a call here (after AMI Originate), the SDK
 * auto-answers if [pendingAccept] is set, and provides audio directly in-app.
 */
object VocaGuardSipManager {

    private const val TAG       = "VocaGuardSIP"
    private const val SERVER_IP = "178.105.164.91"
    private const val SIP_USER  = "vocaguard"
    private const val SIP_REALM = "asterisk"

    enum class CallState { IDLE, INCOMING, ACTIVE, ENDED }

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState

    private var core: Core? = null

    /** Set to true when user taps Accept — SDK will auto-answer the next incoming INVITE. */
    @Volatile var pendingAccept = false

    fun initialize(context: Context) {
        if (core != null) return
        try {
            val factory = Factory.instance()
            factory.setDebugMode(false, "VocaGuard")

            val c = factory.createCore(null, null, context)

            // Audio-only — disable video
            c.isVideoCaptureEnabled = false
            c.isVideoDisplayEnabled = false

            // SIP auth credentials
            val authInfo = factory.createAuthInfo(
                SIP_USER, null, BuildConfig.SIP_PASSWORD, null, SIP_REALM, SERVER_IP
            )
            c.addAuthInfo(authInfo)

            // SIP account
            val params = c.createAccountParams()
            params.identityAddress = factory.createAddress("sip:$SIP_USER@$SERVER_IP")
            val serverAddr = factory.createAddress("sip:$SERVER_IP")
            serverAddr?.transport = TransportType.Udp
            params.serverAddress = serverAddr
            params.isRegisterEnabled = true
            params.expires = 120

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
                        }
                        else -> {}
                    }
                }

                override fun onAccountRegistrationStateChanged(
                    core: Core, account: Account,
                    state: RegistrationState, message: String
                ) {
                    Log.i(TAG, "Registration: $state — $message")
                }
            })

            c.start()
            core = c
            Log.i(TAG, "SIP core initialised and registered")
        } catch (e: Exception) {
            Log.e(TAG, "SIP init failed", e)
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

    private fun answerCall(core: Core, call: Call) {
        val callParams = core.createCallParams(call)
        callParams?.isAudioEnabled = true
        callParams?.isVideoEnabled = false
        try {
            call.acceptWithParams(callParams)
            // Route audio to earpiece by default (user holds phone to ear)
            core.outputAudioDevice = core.audioDevices
                .firstOrNull { it.type == AudioDevice.Type.Earpiece }
                ?: core.outputAudioDevice
        } catch (e: Exception) {
            Log.e(TAG, "acceptWithParams failed", e)
        }
    }

    fun hangupCurrentCall() {
        val call = core?.currentCall ?: core?.calls?.firstOrNull()
        call?.terminate()
        _callState.value = CallState.IDLE
    }

    /** Toggle earpiece ↔ speaker during an active call. */
    fun setSpeaker(on: Boolean) {
        val c = core ?: return
        c.outputAudioDevice = c.audioDevices.firstOrNull {
            it.type == if (on) AudioDevice.Type.Speaker else AudioDevice.Type.Earpiece
        } ?: c.outputAudioDevice
    }

    fun resetState() {
        _callState.value = CallState.IDLE
    }
}
