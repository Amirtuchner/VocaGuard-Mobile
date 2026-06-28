package io.vocaguard.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private const val TOTAL_STEPS = 5  // Welcome · Accessibility · Call Screening · Registration · Forwarding

/**
 * Full-screen setup wizard shown once after install.
 * Guides the user through the 4 required setup actions.
 */
@Composable
fun OnboardingScreen(
    permissionsManager: PermissionsManager,
    viewModel: SettingsViewModel = viewModel(),
    onFinish: () -> Unit
) {
    var step by remember { mutableStateOf(0) }
    val context = LocalContext.current

    // Re-check system permission states whenever the user returns from system settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    var accessibilityOk  by remember { mutableStateOf(permissionsManager.isAccessibilityEnabled()) }
    var callScreeningOk  by remember { mutableStateOf(permissionsManager.isCallScreeningEnabled()) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOk = permissionsManager.isAccessibilityEnabled()
                callScreeningOk = permissionsManager.isCallScreeningEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val serverRegistered    by viewModel.serverRegistered.collectAsStateWithLifecycle()
    val serverRegStatus     by viewModel.serverRegStatus.collectAsStateWithLifecycle()
    val serverIsRegistering by viewModel.serverIsRegistering.collectAsStateWithLifecycle()
    val serverPhoneInput    by viewModel.serverPhoneInput.collectAsStateWithLifecycle()
    val activationCode      by viewModel.serverActivationCode.collectAsStateWithLifecycle()
    val callForwardingEnabled by viewModel.callForwardingEnabled.collectAsStateWithLifecycle()

    val callScreeningLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { callScreeningOk = permissionsManager.isCallScreeningEnabled() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Progress dots ────────────────────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(TOTAL_STEPS) { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == step) 12.dp else 8.dp)
                        .background(
                            color = if (index == step)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Step content ─────────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            when (step) {
                0 -> WelcomeStep()
                1 -> AccessibilityStep(
                    isDone = accessibilityOk,
                    onOpen  = { permissionsManager.openAccessibilitySettings() }
                )
                2 -> CallScreeningStep(
                    isDone   = callScreeningOk,
                    onEnable = { callScreeningLauncher.launch(permissionsManager.createCallScreeningIntent()) }
                )
                3 -> RegistrationStep(
                    phoneInput    = serverPhoneInput,
                    onPhoneChange = viewModel::updateServerPhoneInput,
                    onRegister    = viewModel::registerWithServer,
                    isRegistering = serverIsRegistering,
                    isRegistered  = serverRegistered,
                    status        = serverRegStatus
                )
                4 -> CallForwardingStep(
                    activationCode = activationCode,
                    isEnabled      = callForwardingEnabled,
                    onDial         = {
                        val code = activationCode.ifEmpty { "*21*+97233741493#" }
                        val intent = Intent(Intent.ACTION_DIAL,
                            Uri.parse("tel:" + code.replace("#", "%23")))
                        context.startActivity(intent)
                    },
                    onToggle = viewModel::setCallForwardingEnabled
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Navigation buttons ───────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step > 0) {
                TextButton(onClick = { step-- }) { Text("Back") }
            } else {
                Spacer(Modifier.width(64.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step < TOTAL_STEPS - 1) {
                    TextButton(onClick = { step++ }) { Text("Skip") }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = { if (step < TOTAL_STEPS - 1) step++ else onFinish() },
                    modifier = Modifier.defaultMinSize(minWidth = 120.dp)
                ) {
                    Text(if (step == TOTAL_STEPS - 1) "Get Started" else "Next")
                }
            }
        }
    }
}

// ── Step composables ──────────────────────────────────────────────────────────

@Composable
private fun WelcomeStep() {
    StepLayout(
        icon        = Icons.Default.Shield,
        title       = "Welcome to VocaGuard",
        description = "Let's get you set up in 4 quick steps so VocaGuard can protect you from scam calls."
    )
}

@Composable
private fun AccessibilityStep(isDone: Boolean, onOpen: () -> Unit) {
    StepLayout(
        icon        = Icons.Default.Accessibility,
        title       = "Enable Accessibility Service",
        description = "VocaGuard needs Accessibility access to detect active calls and show scam alerts on screen.",
        isDone      = isDone,
        actionLabel = "Open Settings",
        onAction    = onOpen
    )
}

@Composable
private fun CallScreeningStep(isDone: Boolean, onEnable: () -> Unit) {
    StepLayout(
        icon        = Icons.Default.Phone,
        title       = "Set as Call Screener",
        description = "Set VocaGuard as your default call screening app to identify and filter known scam numbers before your phone rings.",
        isDone      = isDone,
        actionLabel = "Enable",
        onAction    = onEnable
    )
}

@Composable
private fun RegistrationStep(
    phoneInput: String,
    onPhoneChange: (String) -> Unit,
    onRegister: () -> Unit,
    isRegistering: Boolean,
    isRegistered: Boolean,
    status: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StepIcon(icon = Icons.Default.CloudUpload, isDone = isRegistered)
        Text(
            text = "Register Your Number",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Enter your phone number to enable server-side scam detection. Your calls will be routed through VocaGuard's analysis server.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = phoneInput,
            onValueChange = onPhoneChange,
            label = { Text("Phone number (e.g. +972501234567)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onRegister,
            enabled = !isRegistering && !isRegistered,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isRegistering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(if (isRegistered) "Registered ✓" else "Register")
            }
        }
        if (status.isNotEmpty()) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (isRegistered)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun CallForwardingStep(
    activationCode: String,
    isEnabled: Boolean,
    onDial: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val code = activationCode.ifEmpty { "*21*+97233741493#" }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StepIcon(icon = Icons.Default.PhoneForwarded, isDone = isEnabled)
        Text(
            text = "Enable Call Forwarding",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Forward your incoming calls through VocaGuard's server so scammer voices can be analyzed in real time.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }
        Button(onClick = onDial, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Dial Now")
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(checked = isEnabled, onCheckedChange = onToggle)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "I've dialled the code and call forwarding is active",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

// ── Shared layout helpers ─────────────────────────────────────────────────────

@Composable
private fun StepLayout(
    icon: ImageVector,
    title: String,
    description: String,
    isDone: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StepIcon(icon = icon, isDone = isDone)
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionLabel != null && onAction != null && !isDone) {
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(actionLabel)
            }
        }
        if (isDone) {
            Text(
                text = "✓ Done",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun StepIcon(icon: ImageVector, isDone: Boolean) {
    Box(
        modifier = Modifier
            .size(100.dp)
            .background(
                color = if (isDone)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isDone) Icons.Default.CheckCircle else icon,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = if (isDone)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
