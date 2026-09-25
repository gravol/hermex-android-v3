package com.hermex.v3.feature.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.hermex.v3.ui.theme.LocalUiSurfaces
import com.hermex.v3.ui.theme.hexToColor
import com.hermex.v3.ui.theme.isDarkForeground
import com.hermex.core.data.auth.KeychainStore
import com.hermex.core.network.DashboardApiClient
import com.hermex.core.network.DebugLog
import com.hermex.core.network.NetworkResult
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("App Info", style = MaterialTheme.typography.titleSmall)
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            Text("Version: ${pkgInfo.versionName} (${pkgInfo.longVersionCode})", style = MaterialTheme.typography.bodyMedium)
            Text("Server: ${DashboardApiClient.baseUrl()}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                style = MaterialTheme.typography.bodyMedium,
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Connection (v0.1.72): change server / credentials in-app ──
            Text("Connection", style = MaterialTheme.typography.titleSmall)
            Text(
                "Server address, username and password for your Hermes gateway. Saved credentials are encrypted on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val keychainUrl = remember { KeychainStore.getDashboardUrl(context) }
            val keychainUser = remember { KeychainStore.getDashboardUsername(context) }
            val keychainPass = remember { KeychainStore.getDashboardPassword(context) }
            var serverUrl by remember { mutableStateOf(keychainUrl ?: DashboardApiClient.baseUrl()) }
            var username by remember { mutableStateOf(keychainUser ?: "jeff") }
            var password by remember { mutableStateOf(keychainPass ?: "") }
            var saving by remember { mutableStateOf(false) }
            var connError by remember { mutableStateOf<String?>(null) }

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it; connError = null },
                label = { Text("Server address") },
                placeholder = { Text("http://100.80.204.66:9119") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; connError = null },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; connError = null },
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            Button(
                onClick = {
                    scope.launch {
                        saving = true
                        connError = null
                        try {
                            var url = serverUrl.trim().removeSuffix("/")
                            if (url.isBlank()) {
                                connError = "Server address is required"
                                return@launch
                            }
                            if (!url.startsWith("http")) url = "http://$url"
                            val user = username.ifBlank { "jeff" }
                            when (val statusResult = DashboardApiClient.status(url)) {
                                is NetworkResult.Success -> {
                                    DashboardApiClient.setDashboardUrl(url)
                                    DashboardApiClient.setPassword(password)
                                    DashboardApiClient.setUsername(user)
                                    when (val loginResult = DashboardApiClient.login(user, password)) {
                                        is NetworkResult.Success -> {
                                            KeychainStore.saveDashboardCredentials(context, url, password, user)
                                            Toast.makeText(context, "✓ Saved — reconnecting", Toast.LENGTH_SHORT).show()
                                            (context as? Activity)?.recreate()
                                        }
                                        is NetworkResult.HttpError ->
                                            connError = "Login failed (${loginResult.code}) — check username/password"
                                        is NetworkResult.Error ->
                                            connError = loginResult.exception.message ?: "Login failed"
                                    }
                                }
                                is NetworkResult.HttpError ->
                                    connError = "Server unreachable (${statusResult.code}) — check address/port"
                                is NetworkResult.Error ->
                                    connError = statusResult.exception.message ?: "Connection failed"
                            }
                        } catch (e: Exception) {
                            connError = e.message ?: "Save failed"
                        }
                        saving = false
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Save & Reconnect")
                }
            }

            connError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Display", style = MaterialTheme.typography.titleSmall)
            Text(
                "Applies live to every screen. Text size stacks on top of UI zoom.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val settingsRepo = remember { SettingsRepository(context) }
            val savedZoom by settingsRepo.uiZoomPercent.collectAsState(initial = 100)
            val savedText by settingsRepo.textScalePercent.collectAsState(initial = 100)
            var zoomPercent by remember { mutableStateOf(savedZoom) }
            var textPercent by remember { mutableStateOf(savedText) }
            // Keep local slider state in sync if the persisted value changes externally
            LaunchedEffect(savedZoom) { zoomPercent = savedZoom }
            LaunchedEffect(savedText) { textPercent = savedText }

            Text("UI zoom: $zoomPercent%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = zoomPercent.toFloat(),
                onValueChange = { v ->
                    zoomPercent = v.roundToInt()
                    scope.launch { settingsRepo.setUiZoomPercent(zoomPercent) }
                },
                valueRange = 80f..130f,
            )

            Text("Text size: $textPercent%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = textPercent.toFloat(),
                onValueChange = { v ->
                    textPercent = v.roundToInt()
                    scope.launch { settingsRepo.setTextScalePercent(textPercent) }
                },
                valueRange = 80f..150f,
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Text(
                "Accent color for bubbles, buttons and highlights. System follows your wallpaper.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val savedAccent by settingsRepo.accentColorHex.collectAsState(initial = null)
            ColorSwatchRow(
                options = accentOptions,
                selectedHex = savedAccent,
                onSelect = { hex -> scope.launch { settingsRepo.setAccentColorHex(hex) } },
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Appearance", style = MaterialTheme.typography.titleSmall)
            Text(
                "Tweak individual UI parts, or apply a full preset. Assistant bubbles and top bars share one color; code blocks, thinking box, tool cards and the context gauge have their own (v0.1.95).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val savedBg by settingsRepo.uiBackgroundHex.collectAsState(initial = null)
            val savedUserBubble by settingsRepo.uiUserBubbleHex.collectAsState(initial = null)
            val savedAssistantBubble by settingsRepo.uiAssistantBubbleHex.collectAsState(initial = null)
            val savedTextColor by settingsRepo.uiTextHex.collectAsState(initial = null)
            // v0.1.95: extra chat surfaces
            val savedCodeBlock by settingsRepo.uiCodeBlockHex.collectAsState(initial = null)
            val savedThinking by settingsRepo.uiThinkingHex.collectAsState(initial = null)
            val savedToolCard by settingsRepo.uiToolCardHex.collectAsState(initial = null)
            val savedGauge by settingsRepo.uiGaugeHex.collectAsState(initial = null)
            val savedMonospace by settingsRepo.uiMonospace.collectAsState(initial = false)
            // v0.1.96: tool-call visibility (also toggled from the chat top bar)
            val savedShowToolCalls by settingsRepo.showToolCalls.collectAsState(initial = true)
            // v0.1.97: thinking visibility (also toggled from the chat top bar)
            val savedShowThinking by settingsRepo.showThinking.collectAsState(initial = true)

            // Live preview — see color changes instantly (v0.1.79)
            AppearancePreview(
                accentHex = savedAccent,
                bgHex = savedBg,
                userBubbleHex = savedUserBubble,
                assistantBubbleHex = savedAssistantBubble,
                textHex = savedTextColor,
                codeBlockHex = savedCodeBlock,
                thinkingHex = savedThinking,
                toolCardHex = savedToolCard,
                gaugeHex = savedGauge,
                monospace = savedMonospace,
            )

            // Presets — apply accent + per-part overrides at once
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = savedAccent.equals("#00D1FF", ignoreCase = true) &&
                        savedBg.isNullOrBlank() &&
                        savedUserBubble.isNullOrBlank() &&
                        savedAssistantBubble.isNullOrBlank() &&
                        savedTextColor.isNullOrBlank() &&
                        savedCodeBlock.isNullOrBlank() &&
                        savedThinking.isNullOrBlank() &&
                        savedToolCard.isNullOrBlank() &&
                        savedGauge.isNullOrBlank() &&
                        !savedMonospace,
                    onClick = {
                        scope.launch {
                            settingsRepo.applyAppearance("#00D1FF", null, null, null, null)
                            settingsRepo.setUiMonospace(false)
                        }
                    },
                    label = { Text("Classic") },
                )
                FilterChip(
                    selected = savedAccent.equals("#00FF41", ignoreCase = true) &&
                        savedBg.equals("#0A0C0A", ignoreCase = true) &&
                        savedUserBubble.equals("#1E3D24", ignoreCase = true) &&
                        savedAssistantBubble.equals("#0A0C0A", ignoreCase = true) &&
                        savedTextColor.equals("#A5D6A7", ignoreCase = true) &&
                        savedCodeBlock.equals("#121512", ignoreCase = true) &&
                        savedThinking.isNullOrBlank() &&
                        savedToolCard.isNullOrBlank() &&
                        savedGauge.isNullOrBlank() &&
                        savedMonospace,
                    onClick = {
                        // Desktop look: flat charcoal, no assistant bubble (same
                        // color as bg), muted green user box, mint text, mono font,
                        // input-dark code blocks.
                        scope.launch {
                            settingsRepo.applyAppearance(
                                "#00FF41", "#0A0C0A", "#1E3D24", "#0A0C0A", "#A5D6A7",
                                codeBlockHex = "#121512",
                            )
                            settingsRepo.setUiMonospace(true)
                        }
                    },
                    label = { Text("Terminal") },
                )
                FilterChip(
                    selected = savedAccent.isNullOrBlank() &&
                        savedBg.isNullOrBlank() &&
                        savedUserBubble.isNullOrBlank() &&
                        savedAssistantBubble.isNullOrBlank() &&
                        savedTextColor.isNullOrBlank() &&
                        savedCodeBlock.isNullOrBlank() &&
                        savedThinking.isNullOrBlank() &&
                        savedToolCard.isNullOrBlank() &&
                        savedGauge.isNullOrBlank() &&
                        !savedMonospace,
                    onClick = {
                        scope.launch {
                            settingsRepo.applyAppearance(null, null, null, null, null)
                            settingsRepo.setUiMonospace(false)
                        }
                    },
                    label = { Text("Reset") },
                )
            }

            Spacer(Modifier.height(8.dp))

            Text("Background", style = MaterialTheme.typography.bodyMedium)
            ColorSwatchRow(
                options = uiOptions,
                selectedHex = savedBg,
                onSelect = { hex -> scope.launch { settingsRepo.setUiBackgroundHex(hex) } },
            )

            Spacer(Modifier.height(4.dp))

            Text("User bubbles", style = MaterialTheme.typography.bodyMedium)
            ColorSwatchRow(
                options = uiOptions,
                selectedHex = savedUserBubble,
                onSelect = { hex -> scope.launch { settingsRepo.setUiUserBubbleHex(hex) } },
            )

            Spacer(Modifier.height(4.dp))

            Text("Assistant bubbles & top bars", style = MaterialTheme.typography.bodyMedium)
            ColorSwatchRow(
                options = uiOptions,
                selectedHex = savedAssistantBubble,
                onSelect = { hex -> scope.launch { settingsRepo.setUiAssistantBubbleHex(hex) } },
            )

            Spacer(Modifier.height(4.dp))

            Text("Text color", style = MaterialTheme.typography.bodyMedium)
            ColorSwatchRow(
                options = uiOptions,
                selectedHex = savedTextColor,
                onSelect = { hex -> scope.launch { settingsRepo.setUiTextHex(hex) } },
            )

            Spacer(Modifier.height(4.dp))

            // v0.1.95: extra chat surfaces — separate from the assistant bubble
            Text("Code blocks", style = MaterialTheme.typography.bodyMedium)
            ColorSwatchRow(
                options = uiOptions,
                selectedHex = savedCodeBlock,
                onSelect = { hex -> scope.launch { settingsRepo.setUiCodeBlockHex(hex) } },
            )

            Spacer(Modifier.height(4.dp))

            Text("Thinking box", style = MaterialTheme.typography.bodyMedium)
            ColorSwatchRow(
                options = uiOptions,
                selectedHex = savedThinking,
                onSelect = { hex -> scope.launch { settingsRepo.setUiThinkingHex(hex) } },
            )

            Spacer(Modifier.height(4.dp))

            Text("Tool cards", style = MaterialTheme.typography.bodyMedium)
            ColorSwatchRow(
                options = uiOptions,
                selectedHex = savedToolCard,
                onSelect = { hex -> scope.launch { settingsRepo.setUiToolCardHex(hex) } },
            )

            Spacer(Modifier.height(4.dp))

            Text("Context gauge", style = MaterialTheme.typography.bodyMedium)
            ColorSwatchRow(
                options = uiOptions,
                selectedHex = savedGauge,
                onSelect = { hex -> scope.launch { settingsRepo.setUiGaugeHex(hex) } },
            )

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Monospace font", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Terminal look, like the desktop app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = savedMonospace,
                    onCheckedChange = { checked -> scope.launch { settingsRepo.setUiMonospace(checked) } },
                )
            }

            // v0.1.96: tool-call visibility (persisted; also toggled from the chat top bar)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show tool calls", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Tool-call boxes while working and above each answer. Off hides tools only — thinking and replies stay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = savedShowToolCalls,
                    onCheckedChange = { checked -> scope.launch { settingsRepo.setShowToolCalls(checked) } },
                )
            }

            // v0.1.97: thinking visibility (persisted; also toggled from the chat top bar)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show thinking", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Thinking boxes while working and above each answer. Off hides thinking only — tools and replies stay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = savedShowThinking,
                    onCheckedChange = { checked -> scope.launch { settingsRepo.setShowThinking(checked) } },
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Debug Log", style = MaterialTheme.typography.titleSmall)
            Text(
                "${DebugLog.entryCount()} entries in buffer",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // v0.1.156: filter sections + levels before exporting
            DebugLogFilters()

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        exportAndShare(context)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Export Debug Log")
            }

            OutlinedButton(
                onClick = {
                    copyToClipboard(context)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Copy Debug Log")
            }
        }
    }
}

private val accentOptions = listOf(
    "System" to null,
    "Cyan" to "#00D1FF",
    "Blue" to "#5B7CFF",
    "Purple" to "#AF52DE",
    "Green" to "#34C759",
    "Red" to "#FF3B30",
    "Orange" to "#FF9500",
)

/** Richer palette for per-part UI colors — includes the Terminal palette + neutrals. */
private val uiOptions = listOf(
    "Default" to null,
    "Terminal green" to "#00FF41",
    "Charcoal" to "#0A0C0A",
    "Deep green" to "#1E3D24",
    "Mint" to "#A5D6A7",
    "Slate gray" to "#9BA3A0",
    "Input dark" to "#121512",
    "Cyan" to "#00D1FF",
    "Purple" to "#AF52DE",
    "Red" to "#FF3B30",
    "White" to "#FFFFFF",
)

/**
 * Live mini-chat preview (v0.1.79) — renders with the current appearance
 * overrides so color changes are visible instantly: a user bubble (right,
 * green-tinted), a flat assistant message, an accent context gauge, and since
 * v0.1.95 the extra chat surfaces (code block, thinking box, tool card) so
 * those new rows preview honestly too.
 */
@Composable
private fun AppearancePreview(
    accentHex: String?,
    bgHex: String?,
    userBubbleHex: String?,
    assistantBubbleHex: String?,
    textHex: String?,
    codeBlockHex: String?,
    thinkingHex: String?,
    toolCardHex: String?,
    gaugeHex: String?,
    monospace: Boolean,
) {
    val accent = accentHex?.let { runCatching { hexToColor(it) }.getOrNull() }
        ?: MaterialTheme.colorScheme.primary
    val bg = bgHex?.let { runCatching { hexToColor(it) }.getOrNull() }
        ?: MaterialTheme.colorScheme.background
    val userBubble = userBubbleHex?.let { runCatching { hexToColor(it) }.getOrNull() }
        ?: MaterialTheme.colorScheme.surfaceVariant
    val assistantBubble = assistantBubbleHex?.let { runCatching { hexToColor(it) }.getOrNull() }
        ?: MaterialTheme.colorScheme.background
    val textColor = textHex?.let { runCatching { hexToColor(it) }.getOrNull() }
        ?: MaterialTheme.colorScheme.onBackground
    // v0.1.95: extra surfaces — explicit hex wins, otherwise the exact resolved
    // value the chat uses (LocalUiSurfaces, provided by HermexTheme).
    val codeBlock = codeBlockHex?.let { runCatching { hexToColor(it) }.getOrNull() }
        ?: LocalUiSurfaces.current.codeBlock
    val thinkingBox = thinkingHex?.let { runCatching { hexToColor(it) }.getOrNull() }
        ?: LocalUiSurfaces.current.thinkingBox
    val toolCard = toolCardHex?.let { runCatching { hexToColor(it) }.getOrNull() }
        ?: LocalUiSurfaces.current.toolCard
    val gaugeTrack = gaugeHex?.let { runCatching { hexToColor(it) }.getOrNull() }
        ?: LocalUiSurfaces.current.gaugeTrack
    val fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
    val textStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = fontFamily,
        color = textColor,
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp),
            ),
        color = bg,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Context gauge — accent fill over the themeable track (v0.1.95)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(gaugeTrack),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.24f)
                            .background(accent),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "243k/1.0M",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = fontFamily,
                        color = textColor.copy(alpha = 0.7f),
                    ),
                )
            }
            Spacer(Modifier.height(10.dp))

            // User bubble (right-aligned, like the chat)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
            ) {
                Surface(
                    color = userBubble,
                    shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = "Good morning! Any jobs today?",
                        style = textStyle,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Thinking box (themeable surface, v0.1.95)
            Surface(
                color = thinkingBox,
                shape = RoundedCornerShape(10.dp),
            ) {
                Column {
                    Text(
                        text = "THINKING",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                    HorizontalDivider(color = textColor.copy(alpha = 0.15f))
                    Text(
                        text = "Checking the weather and calendar…",
                        style = textStyle.copy(fontSize = 10.sp, color = textStyle.color.copy(alpha = 0.7f)),
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Code block sample (themeable surface, v0.1.95)
            Surface(
                color = codeBlock,
                shape = RoundedCornerShape(10.dp),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "bash",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = fontFamily,
                                color = textColor.copy(alpha = 0.6f),
                            ),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "Copy",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = fontFamily,
                                color = textColor.copy(alpha = 0.6f),
                            ),
                        )
                    }
                    HorizontalDivider(color = textColor.copy(alpha = 0.15f))
                    Text(
                        text = "pip install hermes-agent",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = textColor.copy(alpha = 0.85f),
                        ),
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Tool card sample (themeable surface, v0.1.95)
            Surface(
                color = toolCard,
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔍", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "web_search · 1.2s ✓",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = fontFamily,
                            color = textColor.copy(alpha = 0.8f),
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Assistant reply (flat, full-width)
            Surface(color = assistantBubble, shape = RoundedCornerShape(14.dp)) {
                Text(
                    text = "Morning! Sunny 16°C today — no jobs logged yet.",
                    style = textStyle,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Horizontal row of circular color swatches (System gradient + solid palette).
 * Selection shown with a colored border + ✓ check.
 */
@Composable
private fun ColorSwatchRow(
    options: List<Pair<String, String?>>,
    selectedHex: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { (_, hex) ->
            val selected = if (hex == null) {
                selectedHex.isNullOrBlank()
            } else {
                selectedHex.equals(hex, ignoreCase = true)
            }
            val swatchColor = hex?.let { hexToColor(it) }
            val swatchBackground = swatchColor?.let { Modifier.background(it) }
                ?: Modifier.background(
                    Brush.linearGradient(
                        listOf(Color(0xFF00D1FF), Color(0xFFAF52DE), Color(0xFFFF3B30)),
                    ),
                )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .then(swatchBackground)
                    .border(
                        width = 2.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (swatchColor != null && isDarkForeground(swatchColor)) {
                            Color.Black
                        } else {
                            Color.White
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugLogFilters() {
    val sections = listOf(
        DebugLog.Section.CONNECTION to "Connection",
        DebugLog.Section.APP to "App",
        DebugLog.Section.SYSTEM to "System",
    )
    val levels = listOf("REQ" to "Requests", "RESP" to "Responses", "SSE" to "Events", "INFO" to "Info", "ERROR" to "Errors")

    // v0.1.157: the filter state lives in plain (non-snapshot) mutable fields
    // on DebugLog, so toggling a checkbox mutated them WITHOUT triggering a
    // recomposition — the M3 Checkbox animates to the new state then snaps back
    // to its last real `checked` value, so it always looked "solid". Read the
    // values through Compose snapshot state so the whole panel re-renders on
    // every toggle. The setters still write DebugLog directly (that's what the
    // export path reads); we just mirror into local val/vars for display.
    var sectionEnabled by remember { mutableStateOf(DebugLog.isSectionEnabled(DebugLog.Section.CONNECTION)) }
    var appEnabled by remember { mutableStateOf(DebugLog.isSectionEnabled(DebugLog.Section.APP)) }
    var systemEnabled by remember { mutableStateOf(DebugLog.isSectionEnabled(DebugLog.Section.SYSTEM)) }
    val levelEnabled = levels.associateBy({ it.first }) { DebugLog.isLevelEnabled(it.first) }

    Column {
        // Section toggles
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sections", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            val onCount = listOf(sectionEnabled, appEnabled, systemEnabled).count { it }
            Text(
                text = if (onCount == sections.size) "All" else "$onCount of ${sections.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sections.forEach { (section, label) ->
            val checked = when (section) {
                DebugLog.Section.CONNECTION -> sectionEnabled
                DebugLog.Section.APP -> appEnabled
                else -> systemEnabled
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { enabled ->
                        DebugLog.setSectionEnabled(section, enabled)
                        when (section) {
                            DebugLog.Section.CONNECTION -> sectionEnabled = enabled
                            DebugLog.Section.APP -> appEnabled = enabled
                            else -> systemEnabled = enabled
                        }
                    },
                )
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(10.dp))

        // Level toggles
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Levels", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            val onLevelCount = levels.count { levelEnabled[it.first] == true }
            Text(
                text = if (onLevelCount == levels.size) "All" else "$onLevelCount of ${levels.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        levels.forEach { (level, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Checkbox(
                    checked = levelEnabled[level] == true,
                    onCheckedChange = { enabled ->
                        DebugLog.setLevelEnabled(level, enabled)
                    },
                )
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(10.dp))

        // Reset filters button
        OutlinedButton(
            onClick = {
                DebugLog.resetFilters()
                sectionEnabled = true
                appEnabled = true
                systemEnabled = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset All Filters")
        }
    }
}

private suspend fun exportAndShare(context: Context) {
    withContext(Dispatchers.IO) {
        try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val appVersion = "${pkgInfo.versionName} (${pkgInfo.longVersionCode})"
            val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

            val content = DebugLog.export(
                appVersion = appVersion,
                deviceInfo = deviceInfo,
                serverUrl = DashboardApiClient.baseUrl(),
            )

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(context.cacheDir, "hermex_debug_$timestamp.txt")
            file.writeText(content)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Hermex Debug Log — $timestamp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(intent, "Share Debug Log"))
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                DebugLog.error("Settings", "Export failed", e)
            }
        }
    }
}

private fun copyToClipboard(context: Context) {
    try {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appVersion = "${pkgInfo.versionName} (${pkgInfo.longVersionCode})"

        val content = DebugLog.exportShort(appVersion, DashboardApiClient.baseUrl())
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Hermex Debug Log", content))
        Toast.makeText(context, "Debug log copied to clipboard", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
