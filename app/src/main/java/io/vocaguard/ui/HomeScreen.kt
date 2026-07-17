package io.vocaguard.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.vocaguard.R
import io.vocaguard.data.DetectionSettings
import io.vocaguard.ui.theme.NavyDark

@Composable
fun HomeTab(
    permissionsManager: PermissionsManager,
    viewModel: HomeViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel()
) {
    var permissions by remember { mutableStateOf(permissionsManager.checkAllPermissions()) }
    var refreshKey by remember { mutableStateOf(0) }
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val trendData by viewModel.trendData.collectAsStateWithLifecycle()

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
        if (permissions["Call Screening"] == false) sysPerms.add("Call Screening")
        if (sysPerms.isNotEmpty()) {
            pendingSystemPerms = sysPerms
            showSystemGuide = true
        }
    }

    LaunchedEffect(refreshKey) {
        permissions = permissionsManager.checkAllPermissions()
    }

    // After reinstall, runtime permissions persist but the Call Screening role is reset.
    // runtimePermLauncher never fires in that case, so auto-prompt here on first composition.
    LaunchedEffect(Unit) {
        if (permissions["Call Screening"] == false) {
            pendingSystemPerms = listOf("Call Screening")
            showSystemGuide = true
        }
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
                        "Draw Overlay"        -> permissionsManager.openOverlaySettings()
                        "Notification Access" -> permissionsManager.openNotificationListenerSettings()
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
    var permissionsExpanded by remember { mutableStateOf(!allGranted) }
    val context = LocalContext.current
    val callForwardingEnabled by settingsViewModel.callForwardingEnabled.collectAsStateWithLifecycle()
    val serverActivationCode by settingsViewModel.serverActivationCode.collectAsStateWithLifecycle()
    val forwardingCode = serverActivationCode.ifEmpty { "*21*+97233741493#" }

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

        // Call forwarding warning banner
        if (!callForwardingEnabled && serverActivationCode.isNotEmpty()) item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Call forwarding not enabled",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Incoming calls are not being analyzed by VocaGuard's server.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_DIAL,
                                Uri.parse("tel:" + forwardingCode.replace("#", "%23"))
                            )
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Fix Now — Dial $forwardingCode")
                    }
                }
            }
        }

        // Protection Score
        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                ProtectionScoreCard(stats.protectionScore, stats.installTimestamp)
            }
        }

        // Estimated Money Saved
        if (stats.estimatedMoneySaved > 0) item {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                MoneySavedCard(stats.estimatedMoneySaved)
            }
        }

        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                StatsCard(stats)
            }
        }

        // Lifetime stats
        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                LifetimeStatsCard(stats)
            }
        }

        // Message scanning stats
        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                MessageStatsCard(stats.messagesScanned, stats.messagesFlagged)
            }
        }

        // Blocklist size
        if (stats.blocklistSize > 0) item {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                BlocklistCard(stats.blocklistSize)
            }
        }

        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                TrendChartCard(trendData)
            }
        }

        // Permissions section header — tappable toggle
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { permissionsExpanded = !permissionsExpanded }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (permissionsExpanded) Icons.Default.KeyboardArrowUp
                                  else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (permissionsExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (permissionsExpanded) {
            items(permissions.toList()) { (name, granted) ->
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    PermissionItem(
                        name = name,
                        granted = granted,
                        onClick = if (granted) null else ({
                            when (name) {
                                "Draw Overlay"       -> permissionsManager.openOverlaySettings()
                                "Call Screening"     -> permissionsManager.openCallScreeningSettings()
                                "Notification Access"-> permissionsManager.openNotificationListenerSettings()
                                else                 -> permissionsManager.openAppSettings()
                            }
                        })
                    )
                }
            }

            if (!allGranted) {
                item {
                    Button(
                        onClick = {
                            val anyRuntimeMissing = PermissionsManager.REQUIRED_PERMISSIONS.any {
                                permissions.getOrDefault(
                                    when (it) {
                                        android.Manifest.permission.READ_PHONE_STATE -> "Phone State"
                                        android.Manifest.permission.ANSWER_PHONE_CALLS -> "Answer Calls"
                                        android.Manifest.permission.RECORD_AUDIO -> "Record Audio"
                                        android.Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
                                        else -> it
                                    }, true
                                ) == false
                            }
                            if (anyRuntimeMissing) {
                                runtimePermLauncher.launch(PermissionsManager.REQUIRED_PERMISSIONS)
                            } else {
                                val sysPerms = mutableListOf<String>()
                                if (permissions["Draw Overlay"] == false) sysPerms.add("Draw Overlay")
                                if (permissions["Call Screening"] == false) sysPerms.add("Call Screening")
                                if (permissions["Notification Access"] == false) sysPerms.add("Notification Access")
                                if (sysPerms.isNotEmpty()) {
                                    pendingSystemPerms = sysPerms
                                    showSystemGuide = true
                                }
                            }
                        },
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
private fun ProtectionScoreCard(score: Int, installTimestamp: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Protection Score",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$score / 100",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = when {
                    score >= 80 -> MaterialTheme.colorScheme.primary
                    score >= 50 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }
            )
            if (installTimestamp > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                // Live-updating elapsed time since install
                var now by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        now = System.currentTimeMillis()
                        kotlinx.coroutines.delay(60_000L)
                    }
                }
                val elapsedMs = now - installTimestamp
                val totalMinutes = (elapsedMs / 60_000L).coerceAtLeast(1)
                val days = (totalMinutes / 1440).toInt()
                val hours = ((totalMinutes % 1440) / 60).toInt()
                val mins = (totalMinutes % 60).toInt()
                val timeText = when {
                    days > 0 -> "Protected for $days day${if (days != 1) "s" else ""} \u00B7 $hours hr${if (hours != 1) "s" else ""} \u00B7 $mins min"
                    hours > 0 -> "Protected for $hours hour${if (hours != 1) "s" else ""} \u00B7 $mins min"
                    else -> "Protected for $mins minute${if (mins != 1) "s" else ""}"
                }
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MoneySavedCard(amount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Estimated Money Saved",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${"%,d".format(amount)}$",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                text = "Based on FTC average loss of 1,480$ per scam call blocked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LifetimeStatsCard(stats: HomeStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Lifetime Protection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = "Total Calls", value = stats.totalCallsLifetime.toString())
                StatItem(label = "Scams Blocked", value = stats.scamCallsLifetime.toString())
                StatItem(label = "Clean Calls", value = stats.callsScreenedClean.toString())
            }
        }
    }
}

@Composable
private fun MessageStatsCard(scanned: Long, flagged: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Message Scanning",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = "Messages Scanned", value = scanned.toString())
                StatItem(label = "Scams Caught", value = flagged.toString())
            }
        }
    }
}

@Composable
private fun BlocklistCard(size: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Community Blocklist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "%,d".format(size),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "known scam numbers",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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

private data class PermissionDef(
    val key: String,
    val label: String,
    val description: String,
    val icon: ImageVector,
    val optional: Boolean = false
)

private val permissionDefs = listOf(
    PermissionDef("Phone State",       "Phone Access",         "Detects incoming and outgoing calls",                Icons.Default.Phone),
    PermissionDef("Answer Calls",      "Manage Calls",         "Can silence or reject detected scam calls",          Icons.Default.Call),
    PermissionDef("Record Audio",      "Microphone",           "Analyzes call audio for scam patterns",              Icons.Default.Mic),
    PermissionDef("Notifications",     "Notifications",        "Sends instant scam alerts to your screen",           Icons.Default.Notifications),
    PermissionDef("Call Screening",    "Call Screening",       "Screens calls before your phone rings",              Icons.Default.Shield),
    PermissionDef("Draw Overlay",      "Screen Overlay",       "Shows a warning banner during scam calls",           Icons.Default.Layers),
    PermissionDef("Notification Access","Message Scanning",   "Detects scams in SMS, WhatsApp, Telegram & Facebook Messenger messages", Icons.Default.Notifications, optional = true),
)

@Composable
fun PermissionItem(name: String, granted: Boolean, onClick: (() -> Unit)? = null) {
    val def = permissionDefs.find { it.key == name }
    val label = def?.label ?: name
    val description = def?.description ?: ""
    val icon = def?.icon ?: Icons.Default.Shield
    val optional = def?.optional == true

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
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
                imageVector = icon,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.secondary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (optional) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Optional",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = if (granted) description else "Tap to enable",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) MaterialTheme.colorScheme.onSecondaryContainer
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

private val systemPermDescriptions = mapOf(
    "Draw Overlay" to "VocaGuard needs to draw over other apps to show scam alerts during calls. " +
            "Please enable \"Display over other apps\" for VocaGuard on the next screen.",
    "Call Screening" to "VocaGuard needs to be set as the default caller ID & spam app to screen incoming calls. " +
            "Please select VocaGuard on the next screen.",
    "Notification Access" to "VocaGuard needs Notification Access to detect scams in WhatsApp and Telegram messages. " +
            "Please enable VocaGuard on the next screen."
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
