package com.example.vocaguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.example.vocaguard.ui.theme.NavyDark
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.vocaguard.data.CallTranscript
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.vocaguard.data.CommunityScamSyncWorker
import com.example.vocaguard.data.DetectionSettings
import com.example.vocaguard.data.ScamType
import com.example.vocaguard.monitor.PhoneStateMonitor
import com.example.vocaguard.ui.CrashReporter
import com.example.vocaguard.ui.DailyCallCount
import com.example.vocaguard.ui.HistoryViewModel
import com.example.vocaguard.ui.HomeStats
import com.example.vocaguard.ui.HomeViewModel
import com.example.vocaguard.ui.OnboardingScreen
import com.example.vocaguard.ui.PermissionsManager
import com.example.vocaguard.ui.SettingsViewModel
import com.example.vocaguard.ui.theme.VocaGuardTheme
import com.example.vocaguard.widget.VocaGuardWidget
import com.example.vocaguard.BuildConfig

class MainActivity : ComponentActivity() {

    private lateinit var permissionsManager: PermissionsManager
    private lateinit var phoneStateMonitor: PhoneStateMonitor

    // Hoisted so onNewIntent can drive tab selection reactively inside setContent.
    private val selectedTab = mutableStateOf(0)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result reflected via checkAllPermissions() on next recomposition */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CrashReporter.init()

        permissionsManager = PermissionsManager(this)
        phoneStateMonitor = PhoneStateMonitor(this)

        // Schedule background community blocklist sync (once every 24 h)
        CommunityScamSyncWorker.schedule(this)

        // Handle deep-link from scam alert notification on cold launch
        applyIntentTab(intent)

        setContent {
            VocaGuardTheme {
                val detectionSettings = remember { DetectionSettings.getInstance(this) }
                var onboardingDone by remember { mutableStateOf(detectionSettings.onboardingComplete) }
                if (onboardingDone) {
                    MainScreen(permissionsManager, selectedTab)
                } else {
                    OnboardingScreen(onFinish = {
                        detectionSettings.onboardingComplete = true
                        onboardingDone = true
                    })
                }
            }
        }
    }

    // Called when the app is already running and a notification is tapped.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntentTab(intent)
    }

    /** If the intent carries a scam alert extra, navigate to the History tab. */
    private fun applyIntentTab(intent: Intent?) {
        if (intent?.getStringExtra("scam_type") != null) {
            selectedTab.value = 1 // History tab
        }
    }

    override fun onResume() {
        super.onResume()
        val missing = PermissionsManager.REQUIRED_PERMISSIONS.filter { perm ->
            checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing)
        }
        // Start monitoring now if READ_PHONE_STATE has been granted (may have just been approved)
        phoneStateMonitor.startMonitoring()
        // Refresh home screen widget so its status reflects the latest permission state
        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(ComponentName(this, VocaGuardWidget::class.java))
        ids.forEach { id -> VocaGuardWidget.updateWidget(this, manager, id) }
    }
}

@Composable
fun MainScreen(permissionsManager: PermissionsManager, selectedTab: MutableState<Int>) {
    var selectedTabValue by selectedTab

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTabValue == 0,
                    onClick = { selectedTabValue = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedTabValue == 1,
                    onClick = { selectedTabValue = 1 },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("History") }
                )
                NavigationBarItem(
                    selected = selectedTabValue == 2,
                    onClick = { selectedTabValue = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTabValue) {
                0 -> HomeTab(permissionsManager)
                1 -> HistoryTab()
                2 -> SettingsTab(permissionsManager)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Home Tab
// ---------------------------------------------------------------------------

@Composable
fun HomeTab(
    permissionsManager: PermissionsManager,
    viewModel: HomeViewModel = viewModel()
) {
    var permissions by remember { mutableStateOf(permissionsManager.checkAllPermissions()) }
    var refreshKey by remember { mutableStateOf(0) }
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val trendData by viewModel.trendData.collectAsStateWithLifecycle()

    var showConsentDialog by remember { mutableStateOf(false) }
    var pendingSystemPerms by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSystemGuide by remember { mutableStateOf(false) }

    val callScreeningLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        permissions = permissionsManager.checkAllPermissions()
        val remaining = pendingSystemPerms.drop(1)
        pendingSystemPerms = remaining
        refreshKey++
        if (remaining.isNotEmpty()) showSystemGuide = true
    }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissions = permissionsManager.checkAllPermissions()
        val sysPerms = mutableListOf<String>()
        if (permissions["Draw Overlay"] == false) sysPerms.add("Draw Overlay")
        if (permissions["Accessibility"] == false) sysPerms.add("Accessibility")
        if (permissions["Call Screening"] == false) sysPerms.add("Call Screening")
        if (sysPerms.isNotEmpty()) {
            pendingSystemPerms = sysPerms
            showSystemGuide = true
        }
    }

    LaunchedEffect(refreshKey) {
        permissions = permissionsManager.checkAllPermissions()
    }

    if (showConsentDialog) {
        PermissionConsentDialog(
            onAllow = {
                showConsentDialog = false
                runtimePermLauncher.launch(PermissionsManager.REQUIRED_PERMISSIONS)
            },
            onDismiss = { showConsentDialog = false }
        )
    }

    if (showSystemGuide && pendingSystemPerms.isNotEmpty()) {
        val currentPerm = pendingSystemPerms.first()
        if (currentPerm == "Call Screening") {
            LaunchedEffect(currentPerm) {
                showSystemGuide = false
                callScreeningLauncher.launch(permissionsManager.createCallScreeningIntent())
            }
        } else {
            SystemPermissionGuideDialog(
                permissionName = currentPerm,
                onOpen = {
                    showSystemGuide = false
                    when (currentPerm) {
                        "Draw Overlay" -> permissionsManager.openOverlaySettings()
                        "Accessibility" -> permissionsManager.openAccessibilitySettings()
                    }
                    val remaining = pendingSystemPerms.drop(1)
                    pendingSystemPerms = remaining
                    refreshKey++
                    if (remaining.isNotEmpty()) showSystemGuide = true
                },
                onSkip = {
                    showSystemGuide = false
                    val remaining = pendingSystemPerms.drop(1)
                    pendingSystemPerms = remaining
                    if (remaining.isNotEmpty()) showSystemGuide = true
                }
            )
        }
    }

    val allGranted = permissions.values.all { it }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Logo banner — dark background matching the logo itself
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NavyDark)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.vocaguard_logo),
                    contentDescription = "VocaGuard Logo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Protection status badge — prominent, right below the logo
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (allGranted) MaterialTheme.colorScheme.tertiaryContainer
                                     else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (allGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (allGranted) MaterialTheme.colorScheme.tertiary
                               else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (allGranted) "Protection Active" else "Setup Required",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (allGranted) MaterialTheme.colorScheme.onTertiaryContainer
                                    else MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = if (allGranted) "VocaGuard is monitoring your calls"
                                   else "Some permissions are missing",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (allGranted) MaterialTheme.colorScheme.onTertiaryContainer
                                    else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                StatsCard(stats)
            }
        }

        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                TrendChartCard(trendData)
            }
        }

        item {
            Text(
                text = "Permissions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        items(permissions.toList()) { (name, granted) ->
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                PermissionItem(name = name, granted = granted)
            }
        }

        if (!allGranted) {
            item {
                Button(
                    onClick = { showConsentDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Set Up Protection")
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun StatsCard(stats: HomeStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Last 30 Days",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = "Scam Calls", value = stats.scamCallsThisMonth.toString())
                StatItem(label = "Calls Analyzed", value = stats.totalCallsThisMonth.toString())
            }

            if (stats.scamTypeBreakdown.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Breakdown by type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                stats.scamTypeBreakdown
                    .entries
                    .sortedByDescending { it.value }
                    .forEach { (type, count) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = type.replace("_", " ")
                                    .lowercase()
                                    .replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrendChartCard(data: List<DailyCallCount>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "7-Day Call Trend",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (data.isEmpty() || data.all { it.total == 0 }) {
                Text(
                    text = "No calls recorded this week",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val maxTotal = data.maxOf { it.total }.coerceAtLeast(1)
                val primaryColor = MaterialTheme.colorScheme.primary
                val errorColor   = MaterialTheme.colorScheme.error
                val surfaceColor = MaterialTheme.colorScheme.surfaceVariant

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    data.forEach { day ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = day.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(34.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(10.dp)
                                    .background(surfaceColor, RoundedCornerShape(4.dp))
                            ) {
                                // Stacked bar: clean (blue) then scam (red)
                                val totalFraction = day.total.toFloat() / maxTotal
                                Row(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(totalFraction)
                                ) {
                                    val cleanCount = (day.total - day.scams).coerceAtLeast(0)
                                    if (cleanCount > 0) {
                                        Box(
                                            modifier = Modifier
                                                .weight(cleanCount.toFloat())
                                                .fillMaxHeight()
                                                .background(
                                                    primaryColor.copy(alpha = 0.7f),
                                                    RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
                                                )
                                        )
                                    }
                                    if (day.scams > 0) {
                                        val scamRoundedEnd = day.scams == day.total
                                        Box(
                                            modifier = Modifier
                                                .weight(day.scams.toFloat())
                                                .fillMaxHeight()
                                                .background(
                                                    errorColor.copy(alpha = 0.8f),
                                                    if (scamRoundedEnd) RoundedCornerShape(4.dp)
                                                    else RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
                                                )
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${day.total}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.width(18.dp),
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(primaryColor.copy(alpha = 0.7f), RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Clean", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(errorColor.copy(alpha = 0.8f), RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Scam", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private val permissionIcons: Map<String, ImageVector> = mapOf(
    "Phone State"    to Icons.Default.Phone,
    "Call Log"       to Icons.Default.History,
    "Answer Calls"   to Icons.Default.Call,
    "Record Audio"   to Icons.Default.Mic,
    "Notifications"  to Icons.Default.Notifications,
    "Call Screening" to Icons.Default.Shield,
    "Accessibility"  to Icons.Default.Accessibility,
    "Draw Overlay"   to Icons.Default.Layers,
)

@Composable
fun PermissionItem(name: String, granted: Boolean) {
    val permIcon = permissionIcons[name] ?: Icons.Default.Shield
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) MaterialTheme.colorScheme.secondaryContainer
                             else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = permIcon,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.secondary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (granted) "Enabled" else "Not granted",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.error
                )
            }
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.secondary
                       else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun PermissionConsentDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
    val permissionDescriptions = listOf(
        "Phone State" to "Monitor call events to detect scam calls",
        "Call Log" to "Access recent calls for scam history analysis",
        "Answer Calls" to "Intercept and screen incoming calls",
        "Record Audio" to "Analyze speech during calls (never sent off-device)",
        "Notifications" to "Alert you when a scam call is detected",
        "Call Screening" to "Screen and block identified scam numbers",
        "Accessibility" to "Read on-screen call content for real-time detection",
        "Draw Overlay" to "Show scam alerts on top of the phone app"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable VocaGuard Protection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "VocaGuard needs the following permissions to protect you from scam calls:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                permissionDescriptions.forEach { (name, desc) ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("• ", style = MaterialTheme.typography.bodySmall)
                        Column {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Your audio is never sent off-device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic
                )
            }
        },
        confirmButton = {
            Button(onClick = onAllow) { Text("Allow All") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not Now") }
        }
    )
}

private val systemPermDescriptions = mapOf(
    "Draw Overlay" to "VocaGuard needs to draw over other apps to show scam alerts during calls. " +
            "Please enable \"Display over other apps\" for VocaGuard on the next screen.",
    "Accessibility" to "VocaGuard uses the Accessibility Service to read on-screen call content for real-time detection. " +
            "Please enable the VocaGuard service on the next screen.",
    "Call Screening" to "VocaGuard needs to be set as the default caller ID & spam app to screen incoming calls. " +
            "Please select VocaGuard on the next screen."
)

@Composable
fun SystemPermissionGuideDialog(
    permissionName: String,
    onOpen: () -> Unit,
    onSkip: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Enable $permissionName") },
        text = {
            Text(
                text = systemPermDescriptions[permissionName]
                    ?: "Please enable $permissionName for VocaGuard on the next screen.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onOpen) { Text("Open Settings") }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("Skip") }
        }
    )
}

// ---------------------------------------------------------------------------
// History Tab
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryTab(viewModel: HistoryViewModel = viewModel()) {
    val displayed    by viewModel.displayedTranscripts.collectAsStateWithLifecycle()
    val filter       by viewModel.filter.collectAsStateWithLifecycle()
    val hasMore      by viewModel.hasMore.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var reportingTranscript by remember { mutableStateOf<CallTranscript?>(null) }
    var transcriptToDelete  by remember { mutableStateOf<CallTranscript?>(null) }
    var exportMenuExpanded  by remember { mutableStateOf(false) }
    var typeDropdownExpanded by remember { mutableStateOf(false) }

    val scamTypes = enumValues<ScamType>()
        .filter { it != ScamType.UNKNOWN }
        .map { it.name }

    val isFilterActive = filter.searchQuery.isNotBlank() ||
        filter.scamTypeFilter != null || filter.showScamOnly

    // Delete confirmation dialog
    transcriptToDelete?.let { transcript ->
        AlertDialog(
            onDismissRequest = { transcriptToDelete = null },
            title = { Text("Delete Transcript") },
            text = { Text("Delete this call transcript? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.delete(transcript.id)
                        transcriptToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { transcriptToDelete = null }) { Text("Cancel") }
            }
        )
    }

    reportingTranscript?.let { transcript ->
        ReportScamDialog(
            initialPhoneNumber = transcript.phoneNumber,
            onConfirm = { phoneNumber, scamType ->
                viewModel.reportScamNumber(phoneNumber, scamType)
                reportingTranscript = null
            },
            onDismiss = { reportingTranscript = null }
        )
    }

    if (displayed.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (isFilterActive) {
                    Text(
                        text = "No matching transcripts",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.clearFilters() }) { Text("Clear filters") }
                } else {
                    Text(
                        text = "No call transcripts yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Transcripts appear here after monitored calls",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Call History",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Box {
                    TextButton(onClick = { exportMenuExpanded = true }) { Text("Export") }
                    DropdownMenu(
                        expanded = exportMenuExpanded,
                        onDismissRequest = { exportMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Export as text") },
                            onClick = {
                                exportMenuExpanded = false
                                val text = viewModel.exportAsText()
                                ctx.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                        putExtra(Intent.EXTRA_SUBJECT, "VocaGuard Call History")
                                    }, "Export transcripts"
                                ))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export as CSV") },
                            onClick = {
                                exportMenuExpanded = false
                                val csv = viewModel.exportAsCsv()
                                ctx.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_TEXT, csv)
                                        putExtra(Intent.EXTRA_SUBJECT, "VocaGuard Call History")
                                    }, "Export CSV"
                                ))
                            }
                        )
                    }
                }
            }
        }

        // ── Search bar ────────────────────────────────────────────────────────
        item {
            OutlinedTextField(
                value = filter.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search by number or transcript…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )
        }

        // ── Filter chips ──────────────────────────────────────────────────────
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = filter.showScamOnly,
                    onClick = { viewModel.setShowScamOnly(!filter.showScamOnly) },
                    label = { Text("Scams only") }
                )

                // Scam type chip
                ExposedDropdownMenuBox(
                    expanded = typeDropdownExpanded,
                    onExpandedChange = { typeDropdownExpanded = !typeDropdownExpanded }
                ) {
                    FilterChip(
                        selected = filter.scamTypeFilter != null,
                        onClick = { typeDropdownExpanded = true },
                        label = {
                            Text(
                                filter.scamTypeFilter
                                    ?.replace("_", " ")
                                    ?.lowercase()
                                    ?.replaceFirstChar { it.uppercase() }
                                    ?: "All types"
                            )
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeDropdownExpanded)
                        },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = typeDropdownExpanded,
                        onDismissRequest = { typeDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All types") },
                            onClick = { viewModel.setScamTypeFilter(null); typeDropdownExpanded = false }
                        )
                        scamTypes.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Text(type.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() })
                                },
                                onClick = {
                                    viewModel.setScamTypeFilter(type)
                                    typeDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── Transcript list ───────────────────────────────────────────────────
        items(displayed, key = { it.id }) { transcript ->
            TranscriptCard(
                transcript = transcript,
                onDelete = { transcriptToDelete = transcript },
                onReport = { reportingTranscript = transcript },
                onWhitelist = {
                    if (transcript.phoneNumber.isNotEmpty()) {
                        viewModel.addToWhitelist(transcript.phoneNumber)
                    }
                }
            )
        }

        // ── Load more ─────────────────────────────────────────────────────────
        if (hasMore) {
            item {
                OutlinedButton(
                    onClick = { viewModel.loadMore() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Load more") }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
fun TranscriptCard(
    transcript: CallTranscript,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onWhitelist: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()) }
    val isScam = transcript.detectedScamTypes.isNotEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isScam) MaterialTheme.colorScheme.errorContainer
                             else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dateFormat.format(Date(transcript.timestamp)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (transcript.phoneNumber.isNotEmpty()) {
                        Text(
                            text = transcript.phoneNumber,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (transcript.phoneNumber.isNotEmpty()) {
                    var whitelisted by remember { mutableStateOf(false) }
                    IconButton(onClick = {
                        onWhitelist()
                        whitelisted = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Whitelist number",
                            tint = if (whitelisted) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onReport) {
                    Icon(
                        imageVector = Icons.Default.Flag,
                        contentDescription = "Report scam",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isScam) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    transcript.detectedScamTypes.forEach { type ->
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = type.replace("_", " ")
                                        .lowercase()
                                        .replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (expanded) transcript.text
                       else transcript.text.take(120) + if (transcript.text.length > 120) "…" else "",
                style = MaterialTheme.typography.bodySmall
            )

            if (transcript.text.length > 120) {
                Row(
                    modifier = Modifier.clickable { expanded = !expanded }.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (expanded) "Show less" else "Show more",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                                      else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings Tab
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    permissionsManager: PermissionsManager,
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

    // Local slider state for smooth drag without flooding the ViewModel.
    var sliderValue by remember(sensitivity) { mutableStateOf(sensitivity.toFloat()) }
    var localeDropdownExpanded by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }

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

        item {
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

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "About VocaGuard",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "VocaGuard protects you from scam calls by:\n\n" +
                                "• Pre-screening calls against scam databases\n" +
                                "• Real-time audio analysis during calls\n" +
                                "• Detecting common scam patterns\n" +
                                "• Alerting you with audio and visual warnings\n\n" +
                                "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        item {
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

        item {
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
// Report Scam Dialog
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScamDialog(
    initialPhoneNumber: String,
    onConfirm: (phoneNumber: String, scamType: ScamType) -> Unit,
    onDismiss: () -> Unit
) {
    var phoneNumber by remember { mutableStateOf(initialPhoneNumber) }
    var selectedScamType by remember { mutableStateOf(ScamType.IRS_SCAM) }
    var expanded by remember { mutableStateOf(false) }

    val scamTypes = enumValues<ScamType>().filter { it != ScamType.UNKNOWN }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report Scam Number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text("Phone Number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedScamType.name.replace("_", " ").lowercase()
                            .replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Scam Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        scamTypes.forEach { scamType ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        scamType.name.replace("_", " ").lowercase()
                                            .replaceFirstChar { it.uppercase() }
                                    )
                                },
                                onClick = {
                                    selectedScamType = scamType
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(phoneNumber, selectedScamType) },
                enabled = phoneNumber.isNotBlank()
            ) { Text("Report") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
