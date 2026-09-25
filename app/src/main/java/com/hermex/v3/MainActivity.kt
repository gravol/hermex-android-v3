package com.hermex.v3

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermex.v3.feature.chat.ChatScreen
import com.hermex.v3.feature.chat.ChatVmsHolder
import com.hermex.v3.feature.onboarding.DashboardSetupScreen
import com.hermex.v3.feature.sessions.SessionsScreen
import com.hermex.v3.feature.settings.SettingsRepository
import com.hermex.v3.feature.settings.SettingsScreen
import com.hermex.v3.feature.soul.SoulScreen
import com.hermex.v3.feature.system.ConfigScreen
import com.hermex.v3.feature.system.CronScreen
import com.hermex.v3.feature.system.SkillDetailScreen
import com.hermex.v3.feature.system.SkillsScreen
import com.hermex.v3.notify.CronWatcher
import com.hermex.v3.notify.NotificationHelper
import com.hermex.core.network.DashboardApiClient
import com.hermex.core.network.DebugLog
import com.hermex.v3.ui.theme.HermexTheme
import com.hermex.v3.ui.theme.UiColorOverrides
import com.hermex.v3.ui.theme.hexToColor
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // v0.1.74: arm the scheduled-alarm cron watcher + ensure channels exist
        CronWatcher.sync(this)
        NotificationHelper.ensureChannels(this)
        // Android 13+ needs a runtime grant for notifications
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4101)
        }

        setContent {
            // Display preferences: UI zoom (dp) + text scale (sp), applied as a
            // density override so every screen picks them up with no per-screen
            // changes. Text scales by zoom × textScale. Accent color (null =
            // follow system/wallpaper) overrides the theme primary.
            val appContext = LocalContext.current.applicationContext
            val settingsRepo = remember { SettingsRepository(appContext) }
            val zoomPercent by settingsRepo.uiZoomPercent.collectAsState(initial = 100)
            val textPercent by settingsRepo.textScalePercent.collectAsState(initial = 100)
            val accentHex by settingsRepo.accentColorHex.collectAsState(initial = null)
            val accentColor = remember(accentHex) {
                accentHex?.let { hex -> runCatching { hexToColor(hex) }.getOrNull() }
            }
            // Per-part appearance overrides (v0.1.49+) — null = derive from accent
            val uiBgHex by settingsRepo.uiBackgroundHex.collectAsState(initial = null)
            val uiUserBubbleHex by settingsRepo.uiUserBubbleHex.collectAsState(initial = null)
            val uiAssistantBubbleHex by settingsRepo.uiAssistantBubbleHex.collectAsState(initial = null)
            val uiTextHex by settingsRepo.uiTextHex.collectAsState(initial = null)
            // v0.1.95: extra chat surfaces (code/thinking/tool/gauge) — null = derive from scheme
            val uiCodeBlockHex by settingsRepo.uiCodeBlockHex.collectAsState(initial = null)
            val uiThinkingHex by settingsRepo.uiThinkingHex.collectAsState(initial = null)
            val uiToolCardHex by settingsRepo.uiToolCardHex.collectAsState(initial = null)
            val uiGaugeHex by settingsRepo.uiGaugeHex.collectAsState(initial = null)
            val uiMonospace by settingsRepo.uiMonospace.collectAsState(initial = false)
            val uiOverrides = remember(
                uiBgHex, uiUserBubbleHex, uiAssistantBubbleHex, uiTextHex,
                uiCodeBlockHex, uiThinkingHex, uiToolCardHex, uiGaugeHex,
            ) {
                UiColorOverrides(
                    background = uiBgHex?.let { hex -> runCatching { hexToColor(hex) }.getOrNull() },
                    userBubble = uiUserBubbleHex?.let { hex -> runCatching { hexToColor(hex) }.getOrNull() },
                    assistantBubble = uiAssistantBubbleHex?.let { hex -> runCatching { hexToColor(hex) }.getOrNull() },
                    text = uiTextHex?.let { hex -> runCatching { hexToColor(hex) }.getOrNull() },
                    codeBlock = uiCodeBlockHex?.let { hex -> runCatching { hexToColor(hex) }.getOrNull() },
                    thinkingBox = uiThinkingHex?.let { hex -> runCatching { hexToColor(hex) }.getOrNull() },
                    toolCard = uiToolCardHex?.let { hex -> runCatching { hexToColor(hex) }.getOrNull() },
                    gaugeTrack = uiGaugeHex?.let { hex -> runCatching { hexToColor(hex) }.getOrNull() },
                )
            }
            HermexTheme(accentColor = accentColor, uiOverrides = uiOverrides, monospace = uiMonospace) {
                val baseDensity = LocalDensity.current
                val scaledDensity = Density(
                    density = baseDensity.density * (zoomPercent / 100f),
                    fontScale = baseDensity.fontScale * (textPercent / 100f),
                )
                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                    // Activity-scoped: chat ViewModels outlive navigation so turns
                    // keep running when you leave a chat (see ChatVmsHolder).
                    val chatVmsHolder: ChatVmsHolder = viewModel()
                    HermexNavGraph(chatVmsHolder = chatVmsHolder)
                }
            }
        }
    }

    /** Notification taps re-deliver the intent while the activity is alive. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    // v0.1.75: track app foreground/background so turn-finished notifications
    // fire when the app is backgrounded (screenVisible alone misses that case).
    override fun onStart() {
        super.onStart()
        AppState.isBackgrounded = false
    }

    override fun onStop() {
        super.onStop()
        AppState.isBackgrounded = true
    }
}

@Composable
fun HermexNavGraph(chatVmsHolder: ChatVmsHolder) {
    val navController = rememberNavController()

    // v0.1.74: notification deep links — open_session extra → chat route.
    // v0.1.103: handle EVERY new intent (the old one-shot handledDeepLink flag
    // meant only the FIRST tap ever navigated — later taps did nothing). With
    // MainActivity now singleTop, notification taps arrive via onNewIntent →
    // setIntent, so this effect re-fires per tap; launchSingleTop makes re-taps
    // to the same chat a no-op instead of stacking duplicates.
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(activity?.intent) {
        val intent = activity?.intent ?: return@LaunchedEffect
        val sessionKey = intent.getStringExtra(NotificationHelper.EXTRA_OPEN_SESSION)
        if (!sessionKey.isNullOrBlank() && DashboardApiClient.isConfigured) {
            val title = intent.getStringExtra(NotificationHelper.EXTRA_OPEN_TITLE) ?: sessionKey
            navController.navigate(NotificationHelper.chatRoute(sessionKey, title)) {
                launchSingleTop = true
            }
        }
    }
    // Dashboard is the primary path. If not configured, user goes through
    // dashboard-setup flow. Legacy stack cleanup in progress.
    val startDest = when {
        DashboardApiClient.isConfigured -> {
            DebugLog.log("ROUTE", "MainActivity", "startup → home (dashboard configured)")
            "home"
        }
        else -> {
            DebugLog.log("ROUTE", "MainActivity", "startup → dashboard-setup")
            "dashboard-setup"
        }
    }

    NavHost(navController = navController, startDestination = startDest) {
        composable("dashboard-setup") {
            DashboardSetupScreen(
                onDone = {
                    navController.navigate("home") {
                        popUpTo("dashboard-setup") { inclusive = true }
                    }
                }
            )
        }
        composable("home") {
            SessionsScreen(
                onSessionTap = { session ->
                    val encodedTitle = URLEncoder.encode(session.title ?: session.id, "UTF-8")
                    navController.navigate("chat/${session.id}/$encodedTitle")
                },
                onSettings = {
                    navController.navigate("settings")
                },
                activeSessions = chatVmsHolder.activeSessions,
                onOpenCron = { navController.navigate("cron") },
                onOpenSkills = { navController.navigate("skills") },
                onOpenConfig = { navController.navigate("config") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable("soul") {
            SoulScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable("cron") {
            CronScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable("skills") {
            SkillsScreen(
                onBack = { navController.popBackStack() },
                onOpenSkill = { name ->
                    val encoded = URLEncoder.encode(name, "UTF-8")
                    navController.navigate("skills/$encoded")
                },
            )
        }
        composable(
            route = "skills/{name}",
            arguments = listOf(navArgument("name") { type = NavType.StringType }),
        ) { backStackEntry ->
            val name = URLDecoder.decode(backStackEntry.arguments?.getString("name") ?: "", "UTF-8")
            SkillDetailScreen(
                skillName = name,
                onBack = { navController.popBackStack() },
            )
        }
        composable("config") {
            ConfigScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "chat/{sessionId}/{title}",
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            val encodedTitle = backStackEntry.arguments?.getString("title") ?: ""
            val title = URLDecoder.decode(encodedTitle, "UTF-8")
            // Chat ViewModels are held at Activity scope (ChatVmsHolder) so the
            // WebSocket survives backing out of the chat — turns keep running
            // in the background instead of being torn down by the server's
            // orphan reaper. Reopening the same session returns the SAME VM
            // with its live state intact.
            val chatViewModel = chatVmsHolder.getOrCreate(sessionId)
            DebugLog.log("ROUTE", "MainActivity", "chat → DashboardChatViewModel (held)")
            ChatScreen(
                sessionId = sessionId,
                sessionTitle = title,
                onBack = { navController.popBackStack() },
                viewModel = chatViewModel,
                onNavigate = { route -> navController.navigate(route) },
                onOpenSession = { session ->
                    val encTitle = URLEncoder.encode(session.title ?: session.id, "UTF-8")
                    navController.navigate("chat/${session.id}/$encTitle") { launchSingleTop = true }
                },
            )
        }
    }
}
