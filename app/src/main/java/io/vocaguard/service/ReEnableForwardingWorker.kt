package io.vocaguard.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class ReEnableForwardingWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "ReEnableForwarding"
        const val WORK_NAME = "re_enable_call_forwarding"
    }

    override suspend fun doWork(): Result {
        ServerDetectionManager.init(ctx)
        val code = ServerDetectionManager.getActivationCode()
        if (code.isEmpty()) {
            Log.w(TAG, "No activation code — skipping")
            return Result.success()
        }

        val ussdOk = trySilentUssd(code)
        if (!ussdOk) {
            // Deliberately NO user-facing notification here. On carriers where CFU
            // (*21*) is persistent it is never actually cleared by a call, so the
            // silent re-enable "failing" is a false alarm — surfacing it just spams
            // the user after every call. The silent attempt above stays as a safety
            // net for carriers that DO clear CFU.
            Log.w(TAG, "USSD re-enable failed ($code) — suppressing notification (persistent-CFU carrier)")
        }
        return Result.success()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun trySilentUssd(code: String): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val tm = ctx.getSystemService(TelephonyManager::class.java)
                tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        tm: TelephonyManager, request: String, response: CharSequence
                    ) {
                        Log.i(TAG, "USSD re-enable success: $response")
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onReceiveUssdResponseFailed(
                        tm: TelephonyManager, request: String, failureCode: Int
                    ) {
                        Log.w(TAG, "USSD re-enable failed: $failureCode")
                        if (cont.isActive) cont.resume(false)
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.w(TAG, "USSD exception: $e")
                if (cont.isActive) cont.resume(false)
            }
        }

}
