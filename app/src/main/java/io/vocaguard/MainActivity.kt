package io.vocaguard

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import io.vocaguard.billing.BillingManager
import io.vocaguard.billing.SubscriptionStatus
import io.vocaguard.data.CommunityScamSyncWorker
import io.vocaguard.data.DetectionSettings
import io.vocaguard.data.FamilyGuardSettings
import io.vocaguard.service.PhoneMonitorService
import io.vocaguard.ui.CrashReporter
import io.vocaguard.ui.HistoryTab
import io.vocaguard.ui.HomeTab
import io.vocaguard.ui.OnboardingScreen
import io.vocaguard.ui.PaywallScreen
import io.vocaguard.ui.PermissionsManager
import io.vocaguard.ui.SeniorHomeScreen
import io.vocaguard.ui.SettingsTab
import io.vocaguard.ui.theme.VocaGuardTheme
import io.vocaguard.service.VocaGuardFcmService
import io.vocaguard.service.VocaGuardSipManager
import io.vocaguard.widget.VocaGuardWidget

class MainActivity : ComponentActivity() {

    private lateinit var permissionsManager: PermissionsManager

    // Reactive tab index — hoisted so onNewIntent can drive it from outside setContent.
    private val selectedTab = mutableStateOf(0)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result reflected via checkAllPermissions() on next recomposition */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CrashReporter.init()

        permissionsManager = PermissionsManager(this)

        // Keep TelephonyCallback-based call detection alive in background
        // (fallback for Samsung devices where CallScreeningService is not invoked)
        startForegroundService(Intent(this, PhoneMonitorService::class.java))

        BillingManager.getInstance(this)   // initialise early so status is ready by first frame
        CommunityScamSyncWorker.schedule(this)
        VocaGuardFcmService.refreshToken()
        applyIntentTab(intent)

        setContent {
            val detectionSettings = remember { DetectionSettings.getInstance(this) }
            val familySettings   = remember { FamilyGuardSettings.getInstance(this) }

            // Senior mode is reactive so toggling it in Settings rebuilds the theme immediately.
            val seniorMode = remember { mutableStateOf(familySettings.seniorModeEnabled) }

            // Theme preference is read reactively via the SettingsViewModel so a toggle in
            // Settings takes effect immediately without restarting the activity.
            val themePreference = remember { mutableStateOf(detectionSettings.themePreference) }
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themePreference.value) {
                DetectionSettings.THEME_DARK  -> true
                DetectionSettings.THEME_LIGHT -> false
                else -> systemDark
            }

            VocaGuardTheme(darkTheme = darkTheme, seniorMode = seniorMode.value) {
                var onboardingDone by remember { mutableStateOf(detectionSettings.onboardingComplete) }
                val billing = BillingManager.getInstance(this@MainActivity)
                val billingStatus by billing.status.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    billing.billingError.collect { msg ->
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    }
                }

                when {
                    !onboardingDone -> OnboardingScreen(
                        permissionsManager = permissionsManager,
                        onFinish = {
                            detectionSettings.onboardingComplete = true
                            onboardingDone = true
                        }
                    )
                    billingStatus == SubscriptionStatus.Expired -> Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) { data ->
                            Snackbar(snackbarData = data)
                        }}
                    ) { _ ->
                        PaywallScreen(
                            onSubscribeClick = { billing.startSubscriptionFlow(this@MainActivity) },
                            onRestoreClick   = { billing.refresh() }
                        )
                    }
                    else -> MainScreen(
                        permissionsManager  = permissionsManager,
                        selectedTab         = selectedTab,
                        seniorMode          = seniorMode,
                        themePreference     = themePreference
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntentTab(intent)
    }

    /**
     * Routes the intent to the correct tab.
     * - Scam-alert notification extra → History tab (index 1)
     */
    private fun applyIntentTab(intent: Intent?) {
        if (intent?.getStringExtra("scam_type") != null) {
            selectedTab.value = 1  // History tab
        }
    }

    override fun onResume() {
        super.onResume()
        BillingManager.getInstance(this).refresh()
        val missing = PermissionsManager.REQUIRED_PERMISSIONS.filter { perm ->
            checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty()) requestPermissionsLauncher.launch(missing)

        VocaGuardSipManager.ensureRegistered()

        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(ComponentName(this, VocaGuardWidget::class.java))
        ids.forEach { id -> VocaGuardWidget.updateWidget(this, manager, id) }
    }
}

// ── Main composable ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
@Composable
fun MainScreen(
    permissionsManager: PermissionsManager,
    selectedTab: MutableState<Int>,
    seniorMode: MutableState<Boolean>,
    themePreference: MutableState<String>,
) {
    var selectedTabValue by selectedTab
    val context = LocalContext.current

    // ── Senior Mode: TTS voice guidance when switching tabs ──────────────────
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) tts.value = instance
        }
        onDispose {
            instance?.stop()
            instance?.shutdown()
            tts.value = null
        }
    }
    LaunchedEffect(selectedTabValue) {
        if (seniorMode.value) {
            val label = when (selectedTabValue) {
                0 -> "Home"
                1 -> "Call History"
                2 -> "Settings"
                else -> ""
            }
            if (label.isNotEmpty()) {
                tts.value?.speak(label, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    // NavigationSuiteScaffold auto-switches between NavigationBar (phones),
    // NavigationRail (medium tablets), and NavigationDrawer (large tablets).
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(
                selected = selectedTabValue == 0,
                onClick  = { selectedTabValue = 0 },
                icon     = { Icon(Icons.Default.Home, contentDescription = "Home") },
                label    = { Text("Home") }
            )
            item(
                selected = selectedTabValue == 1,
                onClick  = { selectedTabValue = 1 },
                icon     = { Icon(Icons.Default.History, contentDescription = "History") },
                label    = { Text("History") }
            )
            item(
                selected = selectedTabValue == 2,
                onClick  = { selectedTabValue = 2 },
                icon     = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                label    = { Text("Settings") }
            )
        }
    ) {
        when (selectedTabValue) {
            0 -> if (seniorMode.value) SeniorHomeScreen() else HomeTab(permissionsManager)
            1 -> HistoryTab()
            2 -> SettingsTab(
                permissionsManager  = permissionsManager,
                onSeniorModeChanged = { seniorMode.value = it },
                onThemeChanged      = { themePreference.value = it }
            )
        }
    }
}
