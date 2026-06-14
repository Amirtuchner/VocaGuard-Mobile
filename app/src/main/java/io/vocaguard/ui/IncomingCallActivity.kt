package io.vocaguard.ui

import android.app.KeyguardManager
import android.app.NotificationManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class IncomingCallActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CALLER_NUMBER = "caller_number"
        const val NOTIFICATION_ID = 2001
    }

    private var ringtone: Ringtone? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        val km = getSystemService(KeyguardManager::class.java)
        km.requestDismissKeyguard(this, null)

        val callerNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: ""

        // Start looping ringtone
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(this, uri)?.also {
            it.isLooping = true
            it.play()
        }

        setContent {
            IncomingCallScreen(
                callerNumber = callerNumber,
                onAccept = { dismiss() },
                onDecline = { dismiss() }
            )
        }
    }

    private fun dismiss() {
        ringtone?.stop()
        ringtone = null
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        ringtone?.stop()
    }
}

@Composable
private fun IncomingCallScreen(
    callerNumber: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val displayNumber = if (callerNumber.isNotBlank()) callerNumber else "Unknown"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(Modifier.height(48.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Incoming Call", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))
            Text(displayNumber, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("VocaGuard is monitoring this call", color = Color(0xFFAAAAAA), fontSize = 14.sp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onDecline,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("X", fontSize = 24.sp, color = Color.White)
                }
                Spacer(Modifier.height(8.dp))
                Text("Decline", color = Color.White, fontSize = 12.sp)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
                ) {
                    Text("OK", fontSize = 20.sp, color = Color.White)
                }
                Spacer(Modifier.height(8.dp))
                Text("Accept", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
