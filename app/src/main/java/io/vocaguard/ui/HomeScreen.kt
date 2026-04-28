package io.vocaguard.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.vocaguard.R
import io.vocaguard.ui.theme.NavyDark

@Composable
fun HomeTab(
    permissionsManager: PermissionsManager,
    viewModel: HomeViewModel = viewModel()
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
                    onClick = { runtimePermLauncher.launch(PermissionsManager.REQUIRED_PERMISSIONS) },
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
