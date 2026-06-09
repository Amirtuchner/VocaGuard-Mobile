package io.vocaguard.ui

import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.vocaguard.BuildConfig
import io.vocaguard.data.DetectionSettings
import io.vocaguard.data.FamilyContact
import io.vocaguard.data.ScamType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    permissionsManager: PermissionsManager,
    onSeniorModeChanged: (Boolean) -> Unit = {},
    onThemeChanged: (String) -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current

    val sensitivity    by viewModel.sensitivity.collectAsStateWithLifecycle()
    val locale         by viewModel.locale.collectAsStateWithLifecycle()
    val apiKey         by viewModel.apiKey.collectAsStateWithLifecycle()
    val apiKeySaved    by viewModel.apiKeySaved.collectAsStateWithLifecycle()
    val isSyncing      by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncStatus     by viewModel.syncStatus.collectAsStateWithLifecycle()
    val importResult   by viewModel.importResult.collectAsStateWithLifecycle()
    val enableTts      by viewModel.enableTts.collectAsStateWithLifecycle()
    val enableSound    by viewModel.enableSound.collectAsStateWithLifecycle()
    val enableVibration by viewModel.enableVibration.collectAsStateWithLifecycle()
    val messageScanEnabled by viewModel.messageScanEnabled.collectAsStateWithLifecycle()
    val alertTypeEnabled by viewModel.alertTypeEnabled.collectAsStateWithLifecycle()
    val themePreference  by viewModel.themePreference.collectAsStateWithLifecycle()
    val reportEndpointUrl by viewModel.reportEndpointUrl.collectAsStateWithLifecycle()
    val reportEndpointSaved by viewModel.reportEndpointSaved.collectAsStateWithLifecycle()
    val modelUpdateStatus by viewModel.modelUpdateStatus.collectAsStateWithLifecycle()

    // Family Guard
    val familyGuardEnabled  by viewModel.familyGuardEnabled.collectAsStateWithLifecycle()
    val seniorModeEnabled   by viewModel.seniorModeEnabled.collectAsStateWithLifecycle()
    val seniorName          by viewModel.seniorName.collectAsStateWithLifecycle()
    val callAlertEnabled    by viewModel.callAlertEnabled.collectAsStateWithLifecycle()
    val familyWebhookUrl    by viewModel.familyWebhookUrl.collectAsStateWithLifecycle()
    val familyContacts      by viewModel.familyContacts.collectAsStateWithLifecycle()

    // Local slider state for smooth drag without flooding the ViewModel.
    var sliderValue by remember(sensitivity) { mutableStateOf(sensitivity.toFloat()) }
    var localeDropdownExpanded by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var alertTypeExpanded by remember { mutableStateOf(false) }
    var reportEndpointInput by remember(reportEndpointUrl) { mutableStateOf(reportEndpointUrl) }
    var showMessengerTipDialog by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    // Family Guard local state
    var familyWebhookInput by remember(familyWebhookUrl) { mutableStateOf(familyWebhookUrl) }
    var newContactName by remember { mutableStateOf("") }
    var newContactPhone by remember { mutableStateOf("") }
    var seniorNameInput by remember(seniorName) { mutableStateOf(seniorName) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val json = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readText()
            if (json != null) viewModel.applySettingsJson(json)
        }
    }

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicy = false })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        // ── Family Guard Mode card ─────────────────────────────────────────────
        item {
            FamilyGuardCard(
                enabled          = familyGuardEnabled,
                onEnabledChange  = { viewModel.setFamilyGuardEnabled(it) },
                seniorMode       = seniorModeEnabled,
                onSeniorMode     = { enabled ->
                    viewModel.setSeniorModeEnabled(enabled)
                    onSeniorModeChanged(enabled)
                },
                seniorNameInput  = seniorNameInput,
                onSeniorNameChange = { seniorNameInput = it },
                onSeniorNameSave = { viewModel.saveSeniorName() },
                callAlert        = callAlertEnabled,
                onCallAlert      = { viewModel.setCallAlertEnabled(it) },
                contacts         = familyContacts,
                newContactName   = newContactName,
                onNewContactName = { newContactName = it },
                newContactPhone  = newContactPhone,
                onNewContactPhone = { newContactPhone = it },
                onAddContact     = {
                    viewModel.addFamilyContact(newContactName, newContactPhone)
                    newContactName  = ""
                    newContactPhone = ""
                },
                onRemoveContact  = { viewModel.removeFamilyContact(it) },
                webhookUrl       = familyWebhookInput,
                onWebhookChange  = { familyWebhookInput = it },
                onWebhookSave    = {
                    viewModel.updateFamilyWebhookUrl(familyWebhookInput)
                    viewModel.saveFamilyWebhookUrl()
                },
                onTestAlert      = { viewModel.sendTestAlert() },
                hasSmsPermission = permissionsManager.hasSendSms(),
                hasCallPermission = permissionsManager.hasCallPhone(),
                onGrantPermissions = { permissionsManager.openAppSettings() }
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Detection Sensitivity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Higher sensitivity flags more calls but may produce false positives.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = { viewModel.setSensitivity(sliderValue.toInt()) },
                        valueRange = 0f..100f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Fewer alerts", style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("More alerts", style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Language / Locale",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Sets the language used for speech recognition and spoken alerts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val localeLabel = DetectionSettings.SUPPORTED_LOCALES
                        .firstOrNull { it.first == locale }?.second ?: locale
                    ExposedDropdownMenuBox(
                        expanded = localeDropdownExpanded,
                        onExpandedChange = { localeDropdownExpanded = !localeDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = localeLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Language") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = localeDropdownExpanded)
                            },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = localeDropdownExpanded,
                            onDismissRequest = { localeDropdownExpanded = false }
                        ) {
                            DetectionSettings.SUPPORTED_LOCALES.forEach { (tag, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        viewModel.setLocale(tag)
                                        localeDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Alert Sounds",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose how VocaGuard notifies you when a scam is detected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AlertToggleRow(
                        label = "Spoken alert (TTS)",
                        description = "Reads the scam type aloud during the call",
                        checked = enableTts,
                        onCheckedChange = { viewModel.setEnableTts(it) }
                    )
                    AlertToggleRow(
                        label = "Alarm tone",
                        description = "Plays three warning beeps",
                        checked = enableSound,
                        onCheckedChange = { viewModel.setEnableSound(it) }
                    )
                    AlertToggleRow(
                        label = "Vibration",
                        description = "Vibrates the phone on detection",
                        checked = enableVibration,
                        onCheckedChange = { viewModel.setEnableVibration(it) }
                    )
                }
            }
        }

        // ── Message Scanning (WhatsApp / Telegram / Messenger) ──────────────────
        item {
            val hasAccess = permissionsManager.hasNotificationListenerAccess()

            if (showMessengerTipDialog) {
                AlertDialog(
                    onDismissRequest = { showMessengerTipDialog = false },
                    title = { Text("Enable Messenger Previews") },
                    text = {
                        Text(
                            "VocaGuard scans your Facebook Messenger notifications to detect scam " +
                            "messages before you open them.\n\n" +
                            "For this to work, Messenger must show message previews in notifications:\n\n" +
                            "1. Open Facebook Messenger\n" +
                            "2. Tap your profile picture (top left)\n" +
                            "3. Scroll down and tap Notifications & Sounds\n" +
                            "4. Make sure Preview in notifications is turned ON"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showMessengerTipDialog = false }) {
                            Text("Got it")
                        }
                    }
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Message Scanning",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Scans WhatsApp, Telegram, and Facebook Messenger notifications for " +
                            "scam patterns. When a scam is detected the notification is dismissed and " +
                            "you receive a VocaGuard warning instead. Requires Notification Access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AlertToggleRow(
                        label = "Scan messages",
                        description = "WhatsApp, Telegram, Messenger",
                        checked = messageScanEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setMessageScanEnabled(enabled)
                            if (enabled) showMessengerTipDialog = true
                        }
                    )
                    if (!hasAccess) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Notification Access required",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Grant VocaGuard Notification Access so it can read message " +
                                        "notification text. Message content is only analysed on-device " +
                                        "and never sent anywhere.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { permissionsManager.openNotificationListenerSettings() }
                                ) {
                                    Text("Open Notification Access")
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── App Theme ──────────────────────────────────────────────────────────
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "App Theme",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose light, dark, or follow your system setting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val themeOptions = listOf(
                        io.vocaguard.data.DetectionSettings.THEME_SYSTEM to "System default",
                        io.vocaguard.data.DetectionSettings.THEME_LIGHT  to "Light",
                        io.vocaguard.data.DetectionSettings.THEME_DARK   to "Dark"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        themeOptions.forEach { (value, label) ->
                            FilterChip(
                                selected = themePreference == value,
                                onClick  = { viewModel.setThemePreference(value); onThemeChanged(value) },
                                label    = { Text(label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // ── Per-scam-type alert filter ──────────────────────────────────────────
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Alert Filters",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        TextButton(onClick = { alertTypeExpanded = !alertTypeExpanded }) {
                            Text(if (alertTypeExpanded) "Hide" else "Customize")
                        }
                    }
                    Text(
                        text = "Silence alerts for specific scam categories.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (alertTypeExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ScamType.entries.forEach { scamType ->
                            val enabled = alertTypeEnabled[scamType] != false
                            AlertToggleRow(
                                label = scamType.displayName(),
                                description = "",
                                checked = enabled,
                                onCheckedChange = { viewModel.setAlertTypeEnabled(scamType, it) }
                            )
                        }
                    }
                }
            }
        }

        // ── ML model update ────────────────────────────────────────────────────
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Detection Model",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Check for an updated scam detection model. The app must be restarted to use a newly downloaded model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.checkForModelUpdate() },
                        enabled = modelUpdateStatus != "Checking…",
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (modelUpdateStatus == "Checking…") "Checking…" else "Check for Model Update")
                    }
                    if (modelUpdateStatus.isNotEmpty() && modelUpdateStatus != "Checking…") {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = modelUpdateStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── Advanced settings toggle ───────────────────────────────────────────
        item {
            OutlinedButton(
                onClick = { advancedExpanded = !advancedExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (advancedExpanded) "Hide Advanced Settings" else "Advanced Settings")
            }
        }

        if (advancedExpanded) item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Community Blocklist",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Download the latest community-reported scam numbers. Updated at most once every 24 hours.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.syncNow() },
                        enabled = !isSyncing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Syncing…")
                        } else {
                            Text("Sync Now")
                        }
                    }
                    if (syncStatus.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = syncStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (advancedExpanded) item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Report Aggregation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Optional: enter an HTTPS endpoint to contribute your scam reports to a community database. Only the phone number and scam type are sent — no transcript or personal data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = reportEndpointInput,
                        onValueChange = {
                            reportEndpointInput = it
                            viewModel.updateReportEndpointUrl(it)
                        },
                        label = { Text("Endpoint URL (https://…)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.saveReportEndpointUrl() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (reportEndpointSaved) "Saved!" else "Save Endpoint")
                    }
                }
            }
        }

        if (advancedExpanded) item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "NumVerify API Key",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Optional: enables network-based phone number screening. Get a free key at numverify.com.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { viewModel.updateApiKey(it) },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.saveApiKey() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (apiKeySaved) "Saved!" else "Save API Key")
                    }
                }
            }
        }

        if (advancedExpanded) item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Backup & Restore",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Export your settings to share or restore them on another device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val json = viewModel.buildSettingsJson()
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_TEXT, json)
                                        putExtra(Intent.EXTRA_SUBJECT, "VocaGuard Settings Backup")
                                    }, "Export settings"
                                ))
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Export") }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Import") }
                    }
                    if (importResult.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = importResult,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (importResult.startsWith("Settings")) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        if (advancedExpanded) item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "System Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = { permissionsManager.openAccessibilitySettings() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Configure Accessibility Service") }
                    OutlinedButton(
                        onClick = { permissionsManager.openOverlaySettings() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Configure Overlay Permission") }
                    OutlinedButton(
                        onClick = { permissionsManager.openAppSettings() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("App Permissions") }
                    OutlinedButton(
                        onClick = { showPrivacyPolicy = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Privacy Policy") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Privacy Policy Dialog
// ---------------------------------------------------------------------------

@Composable
fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Privacy Policy",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                HorizontalDivider()
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewClient = WebViewClient()
                            loadUrl("file:///android_asset/privacy_policy.html")
                        }
                    },
                    onRelease = { webView -> webView.destroy() },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Family Guard Mode Card
// ---------------------------------------------------------------------------

@Composable
private fun FamilyGuardCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    seniorMode: Boolean,
    onSeniorMode: (Boolean) -> Unit,
    seniorNameInput: String,
    onSeniorNameChange: (String) -> Unit,
    onSeniorNameSave: () -> Unit,
    callAlert: Boolean,
    onCallAlert: (Boolean) -> Unit,
    contacts: List<FamilyContact>,
    newContactName: String,
    onNewContactName: (String) -> Unit,
    newContactPhone: String,
    onNewContactPhone: (String) -> Unit,
    onAddContact: () -> Unit,
    onRemoveContact: (String) -> Unit,
    webhookUrl: String,
    onWebhookChange: (String) -> Unit,
    onWebhookSave: () -> Unit,
    onTestAlert: () -> Unit,
    hasSmsPermission: Boolean,
    hasCallPermission: Boolean,
    onGrantPermissions: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Header with master toggle ──────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Family Guard Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Notify caregivers by SMS and phone call when a scam is detected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // ── Permission warnings ────────────────────────────────────
                if (!hasSmsPermission || !hasCallPermission) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Permissions required",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (!hasSmsPermission)
                                Text("• Send SMS — for text alerts",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            if (!hasCallPermission)
                                Text("• Call Phone — for voice alerts",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = onGrantPermissions) {
                                Text("Open App Permissions")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // ── Senior Mode toggle ─────────────────────────────────────
                AlertToggleRow(
                    label = "Senior Mode",
                    description = "Large text, voice guidance, and a simplified home screen.",
                    checked = seniorMode,
                    onCheckedChange = onSeniorMode
                )

                Spacer(modifier = Modifier.height(12.dp))

                // ── Name of the senior ─────────────────────────────────────
                Text(
                    text = "Protected person's name",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Used in SMS alert messages (e.g. \"Grandma's phone detected a scam\").",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = seniorNameInput,
                        onValueChange = onSeniorNameChange,
                        label = { Text("Name (e.g. Grandma)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onSeniorNameSave, modifier = Modifier.align(Alignment.CenterVertically)) {
                        Text("Save")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Phone call alert toggle ────────────────────────────────
                AlertToggleRow(
                    label = "Phone call alert",
                    description = "After a scam call ends, VocaGuard calls the primary contact and plays a voice message.",
                    checked = callAlert,
                    onCheckedChange = onCallAlert
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // ── Contact list ───────────────────────────────────────────
                Text(
                    text = "Family Contacts",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "These people receive SMS + call alerts. The first contact gets the phone call.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (contacts.isEmpty()) {
                    Text(
                        text = "No contacts added yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    contacts.forEach { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(contact.name, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Text(contact.phoneNumber, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onRemoveContact(contact.phoneNumber) }) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Add contact fields
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newContactName,
                        onValueChange = onNewContactName,
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = newContactPhone,
                        onValueChange = onNewContactPhone,
                        label = { Text("Phone") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onAddContact,
                    enabled = newContactName.isNotBlank() && newContactPhone.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add Contact") }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // ── Webhook URL ────────────────────────────────────────────
                Text(
                    text = "Webhook URL (optional)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Receive push notifications via ntfy.sh, Pushover, IFTTT, or a custom server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = webhookUrl,
                    onValueChange = onWebhookChange,
                    label = { Text("https://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onWebhookSave, modifier = Modifier.fillMaxWidth()) {
                    Text("Save Webhook")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Test alert button ──────────────────────────────────────
                OutlinedButton(
                    onClick = onTestAlert,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send Test Alert")
                }
                Text(
                    text = "Sends a simulated IRS Scam alert to all contacts without making a real phone call.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ScamType display name helper
// ---------------------------------------------------------------------------

private fun ScamType.displayName(): String = when (this) {
    ScamType.UNKNOWN -> "Suspicious / Unknown"
    ScamType.IRS_SCAM -> "IRS / Tax Scam"
    ScamType.TECH_SUPPORT -> "Tech Support Scam"
    ScamType.BANK_FRAUD -> "Bank Fraud"
    ScamType.LOTTERY_PRIZE -> "Lottery / Prize Scam"
    ScamType.SOCIAL_SECURITY -> "Social Security Scam"
    ScamType.ROBOCALL -> "Robocall"
    ScamType.PHISHING -> "Phishing"
    ScamType.INSURANCE -> "Insurance Scam"
    ScamType.INVESTMENT_SCAM -> "Investment Scam"
    ScamType.DONATION_FRAUD -> "Donation Fraud"
    ScamType.ROMANCE_SCAM -> "Romance / Pig-Butchering Scam"
    ScamType.DELIVERY_SCAM -> "Delivery / Package Scam"
    ScamType.JOB_SCAM -> "Job / Recruitment Scam"
    ScamType.SOCIAL_ENGINEERING -> "Social Engineering Scam"
}

// ---------------------------------------------------------------------------
// Alert Toggle Row (used in SettingsTab)
// ---------------------------------------------------------------------------

@Composable
private fun AlertToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
