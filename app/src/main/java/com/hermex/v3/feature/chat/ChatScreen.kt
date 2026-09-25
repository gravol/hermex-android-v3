package com.hermex.v3.feature.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.South
import androidx.compose.material.icons.outlined.North
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.hermex.v3.feature.settings.SettingsRepository
import com.hermex.v3.feature.menu.WebuiMenuDrawer
import com.hermex.v3.ui.theme.LocalUiSurfaces
import com.hermex.core.network.DashboardApiClient
import com.hermex.core.network.DebugLog
import com.hermex.core.network.JsonRpcClient
import com.hermex.core.network.NetworkResult
import com.hermex.core.network.SessionSummary
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    sessionId: String,
    sessionTitle: String?,
    onBack: () -> Unit,
    viewModel: ChatViewModelContract,
    onNavigate: (String) -> Unit = {},
    onOpenSession: (SessionSummary) -> Unit = {},
) {
    LaunchedEffect(sessionId) {
        viewModel.init(sessionId, sessionTitle)
    }

    // Report screen visibility so the VM can flag turns that finish while the
    // user is away (background turns — v0.1.60).
    DisposableEffect(Unit) {
        viewModel.setScreenVisible(true)
        onDispose { viewModel.setScreenVisible(false) }
    }

    val state = viewModel.uiState
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // /new /reset — navigate to the freshly created session (v0.1.130).
    LaunchedEffect(viewModel.resetTargetSession) {
        val sid = viewModel.resetTargetSession ?: return@LaunchedEffect
        viewModel.resetTargetSession = null
        onOpenSession(SessionSummary(id = sid, title = "New session"))
    }
    val listState = rememberLazyListState()
    var composerText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // v0.1.96: tool-call visibility toggle — persisted; flipped from the top
    // bar or Settings. Off hides tool-call boxes/rows (thinking + response stay).
    val settingsRepo = remember { SettingsRepository(context.applicationContext) }
    val showToolCalls by settingsRepo.showToolCalls.collectAsState(initial = true)
    // v0.1.97: thinking visibility toggle — same pattern (tools + response stay).
    val showThinking by settingsRepo.showThinking.collectAsState(initial = true)

    // v0.1.102: live tokens/sec readout while streaming. OpenAI-compatible APIs
    // don't stream per-delta token counts, so this estimates from text flow:
    // chars/sec ÷ ~4 chars/token. Works for cloud (DeepSeek) AND local models
    // (Ollama Qwen) — both arrive as plain message.delta text. The exact turn
    // average (real token counts, incl. Ollama eval stats) renders in the
    // message footer at completion.
    // v0.1.104: always visible while streaming — dimmed at 0 (e.g. during the
    // first-token / context-ingestion wait on a local model) so it's obviously
    // live instead of looking missing; EMA-smoothed for a steady readout.
    // v0.1.107: separate THINKING speed (from thinkingText deltas) shown beside
    // the "Live activity" label while reasoning flows.
    // v0.1.110: each tick re-reads viewModel.uiState (not the captured `state`)
    // so the meter sees growing content — the old `state` snapshot was stale
    // inside the LaunchedEffect coroutine, always returning the initial empty
    // message → len−lastLen = 0 → tok/s stuck at 0.
    var streamTokPerSec by remember { mutableStateOf(0f) }
    var streamThinkingTokPerSec by remember { mutableStateOf(0f) }
    LaunchedEffect(state.isStreaming) {
        if (!state.isStreaming) {
            streamTokPerSec = 0f
            streamThinkingTokPerSec = 0f
            return@LaunchedEffect
        }
        var lastLen = viewModel.uiState.messages.lastOrNull { it.isStreaming }?.content?.length ?: 0
        var lastThinkLen = viewModel.uiState.messages.lastOrNull { it.isStreaming }?.thinkingText?.length ?: 0
        var lastTime = System.currentTimeMillis()
        var firstSample = true
        var firstThinkSample = true
        while (true) {
            delay(1000)
            val currentUi = viewModel.uiState
            val streamingMsg = currentUi.messages.lastOrNull { it.isStreaming } ?: break
            val now = System.currentTimeMillis()
            val len = streamingMsg.content.length
            val thinkLen = streamingMsg.thinkingText?.length ?: 0
            val dtSec = (now - lastTime) / 1000f
            if (dtSec > 0f) {
                val sample = ((len - lastLen) / dtSec) / 4f
                streamTokPerSec = if (firstSample) {
                    sample
                } else {
                    streamTokPerSec * 0.6f + sample * 0.4f  // EMA smoothing
                }
                firstSample = false
                val thinkSample = ((thinkLen - lastThinkLen) / dtSec) / 4f
                streamThinkingTokPerSec = if (firstThinkSample) {
                    thinkSample
                } else {
                    streamThinkingTokPerSec * 0.6f + thinkSample * 0.4f
                }
                firstThinkSample = false
            }
            lastLen = len
            lastThinkLen = thinkLen
            lastTime = now
        }
    }

    // ── Photo attach state ──
    var pendingImageB64 by remember { mutableStateOf<String?>(null) }
    // Render the thumbnail from the already-decoded Bitmap (not the content URI).
    // The picker-provided URI is ephemeral — the backing file can be replaced or its
    // read permission revoked between pickup and a Compose re-compose, and passing it
    // straight to Coil makes Coil re-read a now-dead URI on every recomposition -> crash.
    var pendingImageBmp by remember { mutableStateOf<Bitmap?>(null) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val bmp = downscaleAndDecode(context, uri)
            if (bmp != null) {
                pendingImageBmp = bmp
                pendingImageB64 = bitmapToDataUrl(bmp)
            } else {
                Toast.makeText(context, "Couldn't read image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Attach chooser (v0.1.127): camera / gallery / file ──
    var showAttachSheet by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        if (ok) {
            val uri = pendingCameraUri
            if (uri != null) {
                val bmp = downscaleAndDecode(context, uri)
                if (bmp != null) {
                    pendingImageBmp = bmp
                    pendingImageB64 = bitmapToDataUrl(bmp)
                } else {
                    Toast.makeText(context, "Couldn't read photo", Toast.LENGTH_SHORT).show()
                }
            }
        }
        pendingCameraUri = null
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.isEmpty()) {
                        Toast.makeText(context, "Couldn't read file", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val name = queryFileName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    viewModel.sendMessageWithFile("", b64, mime, name)
                } catch (e: Exception) {
                    Toast.makeText(context, "File attach failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── Voice message state ──
    var isRecording by remember { mutableStateOf(false) }
    var isTranscribing by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    // v0.1.125: recorded voice staged for the user to Delete or Send instead of
    // auto-transcribing straight into the composer.
    var pendingVoiceB64 by remember { mutableStateOf<String?>(null) }
    var pendingVoiceDurSec by remember { mutableIntStateOf(0) }

    fun startRecording() {
        try {
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.webm")
            val rec = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.WEBM)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            recordingFile = file
            recordingSeconds = 0
            isRecording = true
        } catch (e: Exception) {
            Toast.makeText(context, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
        }
    }

    fun stopRecording() {
        val rec = recorder ?: return
        val file = recordingFile ?: return
        try {
            runCatching { rec.stop() }
        } catch (_: Exception) { /* short recording */ }
        rec.release()
        recorder = null
        recordingFile = null
        isRecording = false
        scope.launch {
            try {
                val bytes = file.readBytes()
                if (bytes.isEmpty()) {
                    Toast.makeText(context, "No audio recorded", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // Stage the recording — the user picks Delete or Send.
                pendingVoiceB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                pendingVoiceDurSec = recordingSeconds
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to capture recording: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                file.delete()
            }
        }
    }

    fun sendPendingVoice() {
        val b64 = pendingVoiceB64 ?: return
        scope.launch {
            isTranscribing = true
            try {
                val dataUrl = "data:audio/webm;base64,$b64"
                when (val r = DashboardApiClient.transcribeAudio(dataUrl, "audio/webm")) {
                    is NetworkResult.Success -> {
                        val transcript = r.data.transcript.orEmpty().trim()
                        if (transcript.isBlank()) {
                            Toast.makeText(context, "No speech detected", Toast.LENGTH_SHORT).show()
                        } else {
                            pendingVoiceB64 = null
                            pendingVoiceDurSec = 0
                            viewModel.sendMessage(transcript)
                        }
                    }
                    is NetworkResult.HttpError -> {
                        Toast.makeText(
                            context,
                            "Transcription failed (${r.code})",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is NetworkResult.Error -> {
                        Toast.makeText(
                            context,
                            "Transcription failed: ${r.exception.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Transcription failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isTranscribing = false
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(context, "Microphone permission needed for voice messages", Toast.LENGTH_LONG).show()
    }

    // Recording elapsed timer
    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000)
            recordingSeconds++
        }
    }

    fun sendComposer() {
        val text = composerText
        val img = pendingImageB64
        if (text.isBlank() && img == null) return
        if (img != null) {
            viewModel.sendMessageWithImage(text, img, "photo_${System.currentTimeMillis()}.jpg")
        } else {
            viewModel.sendMessage(text)
        }
        composerText = ""
        pendingImageB64 = null
        pendingImageBmp = null
    }

    // ── Slash-command completions (v0.1.65) ──
    var composerFocused by remember { mutableStateOf(false) }
    var slashItems by remember { mutableStateOf<List<JsonRpcClient.SlashItem>?>(null) }

    // ── Model picker (v0.1.88) ──
    var showModelPicker by remember { mutableStateOf(false) }
    var replyTarget by remember { mutableStateOf<UiMessage?>(null) }

    // ── Context details panel (WebUI): tap the ring to open a details card
    //    with tokens in/out, cost, and a compress affordance. ──
    var showCtxPanel by remember { mutableStateOf(false) }
    val ctxUsage = state.messages.lastOrNull { it.usage != null }?.usage

    LaunchedEffect(composerText, composerFocused, state.isStreaming) {
        val text = composerText
        // v0.1.99: completions work mid-turn too — /stop, /steer, /queue etc.
        // are first-class while a turn streams (v0.1.92).
        if (composerFocused && text.startsWith("/")) {
            delay(150)  // debounce while typing
            slashItems = runCatching { viewModel.completeSlash(text) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        } else {
            slashItems = null
        }
    }

    // Track whether user has manually scrolled away from the bottom
    var userScrolledUp by remember { mutableStateOf(false) }

    // Reset flag when user scrolls back to the bottom
    LaunchedEffect(listState.canScrollForward) {
        if (!listState.canScrollForward && userScrolledUp) {
            userScrolledUp = false
            DebugLog.log("SCROLL", "DragDetect", "userScrolledUp=false (scrolled back to bottom)")
        }
    }

    // ─── Debug: message count tracking ───
    var prevMessageCount by remember { mutableStateOf(0) }
    LaunchedEffect(state.messages.size) {
        val newCount = state.messages.size
        if (newCount != prevMessageCount) {
            DebugLog.log("UI", "MsgCount",
                "changed: $prevMessageCount → $newCount " +
                "(delta=${newCount - prevMessageCount}) " +
                "isStreaming=${state.isStreaming}")
            prevMessageCount = newCount
        }
    }

    // ─── One-shot: scroll to bottom on initial message load ───
    // Uses isNotEmpty() as key — fires exactly once when messages first arrive,
    // does NOT re-fire on subsequent sends (key stays true).
    LaunchedEffect(state.messages.isNotEmpty()) {
        if (state.messages.isNotEmpty()) {
            userScrolledUp = false
            autoScrollToBottom(
                listState = listState,
                targetIndex = state.messages.lastIndex,
                totalItems = state.messages.size,
                scrollGeneration = 0L,
                reason = "SessionOpen",
            )
            // v0.1.101: settle pass — the loaded conversation's final layout
            // (markdown parse, usage footer, bubble borders) lands a frame or two
            // AFTER the messages arrive, so the first scrollToItem clamps to the
            // pre-settle max scroll and the last message's bottom outline ends up
            // a few px behind the composer ("99%" visible). Wait a frame and
            // re-scroll to the true bottom until canScrollForward clears (same
            // idea as the v0.1.95 StreamEnd settle, looped for multi-frame
            // settles). No-op when the first scroll already landed exactly.
            repeat(3) { pass ->
                withFrameNanos { }
                if (!userScrolledUp && state.messages.isNotEmpty() && listState.canScrollForward) {
                    autoScrollToBottom(
                        listState = listState,
                        targetIndex = state.messages.lastIndex,
                        totalItems = state.messages.size,
                        scrollGeneration = 0L,
                        reason = "SessionOpenSettle$pass",
                    )
                }
            }
        }
    }

    // ─── Streaming auto-scroll: state-change driven (replaces 100ms poll) ───
    // Single source of truth for auto-scroll during streaming.
    // snapshotFlow + distinctUntilChanged fires ONLY when the last message's
    // visible content actually changes (text growth, thinking growth, new
    // message, tool card), instead of waking every 100ms — kills the no-op
    // wake-ups during thinking.
    // CRITICAL: read state via viewModel.uiState (the MutableState getter), NOT
    // the captured `state` val — a plain field read on the captured instance
    // registers no snapshot read, so snapshotFlow emits exactly once and never
    // re-fires (v0.1.44 regression: stream only scrolled at placeholder
    // creation, viewport never tracked the growing bubble).
    // The collect block runs to completion per emission in one coroutine; it is
    // NOT cancelled by rapid state writes (unlike the old LaunchedEffect keyed
    // on scrollGeneration), so the two-step scrollToItem+scrollBy compensation
    // still never gets interrupted mid-flight.
    // Respects manual scrolling: skips when userScrolledUp=true, resumes
    // automatically when user returns to bottom (userScrolledUp→false).
    // Gated on message presence (not isStreaming) so a session resumed while
    // the assistant is mid-response still tracks the stream.
    LaunchedEffect(state.isStreaming, state.messages.isNotEmpty()) {
        if (state.messages.isNotEmpty()) {
            DebugLog.log("SCROLL", "StreamLoop", "started (messages=${state.messages.size})")
            snapshotFlow {
                val s = viewModel.uiState
                val last = s.messages.last()
                Triple(
                    s.messages.size,
                    last.content.length,
                    last.toolCalls.size,
                )
            }
                .distinctUntilChanged()
                .collect {
                    val s = viewModel.uiState
                    if (!userScrolledUp && s.messages.isNotEmpty()) {
                        autoScrollToBottom(
                            listState = listState,
                            targetIndex = s.messages.lastIndex,
                            totalItems = s.messages.size,
                            scrollGeneration = s.scrollGeneration,
                            reason = "StreamLoop",
                        )
                        // Turn completion (v0.1.95): the finished message's final
                        // layout (usage footer, markdown settle) lands a frame
                        // AFTER the state write, so the snapshot key (content
                        // length) doesn't change and no re-scroll fires — the
                        // last line can end up behind the composer. Wait one
                        // frame and re-scroll to the true bottom.
                        if (!s.isStreaming) {
                            withFrameNanos { }
                            if (!userScrolledUp && s.messages.isNotEmpty()) {
                                autoScrollToBottom(
                                    listState = listState,
                                    targetIndex = s.messages.lastIndex,
                                    totalItems = s.messages.size,
                                    scrollGeneration = s.scrollGeneration,
                                    reason = "StreamEnd",
                                )
                            }
                        }
                    }
                }
            DebugLog.log("SCROLL", "StreamLoop", "ended (isStreaming=${state.isStreaming} messages=${state.messages.size})")
        }
    }

    // Detect keyboard open/close for scroll.
    // Read WindowInsets.ime BEFORE Scaffold.imePadding() consumes it.
    // Uses scrollToItem (instant) — animateScrollToItem's spring animation
    // fights the LazyColumn layout changes from imePadding() settling.
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val sysBottom = WindowInsets.systemBars.getBottom(density)
    val sysTop = WindowInsets.systemBars.getTop(density)
    val keyboardOpen = imeBottom > 0
    var wasKeyboardOpen by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(imeBottom) {
        val prevFirstVisible = listState.firstVisibleItemIndex
        val prevTotalItems = state.messages.size
        val prevViewportHeight = listState.layoutInfo.viewportSize.height
        val isOpen = imeBottom > 0
        val justOpened = isOpen && wasKeyboardOpen != true
        val justClosed = !isOpen && wasKeyboardOpen == true
        wasKeyboardOpen = isOpen

        // Log only on state transitions, not every intermediate frame
        if (justOpened || justClosed) {
            DebugLog.log("UI", "Keyboard",
                "event=${if (isOpen) "OPEN" else "CLOSE"} " +
                "imeHeight=${imeBottom}px messages=$prevTotalItems " +
                "firstVisibleBefore=$prevFirstVisible " +
                "viewportHeightBefore=$prevViewportHeight")
        }

        // Scroll adjustment on EVERY imeBottom change while open (tracks animation)
        if (isOpen && state.messages.isNotEmpty()) {
            // Log scroll-relevant metrics on first open frame (transition only)
            if (justOpened) {
                DebugLog.log("UI", "Keyboard",
                    "keyboard open details: ime=${imeBottom}px " +
                    "sysBottom=${sysBottom}px sysTop=${sysTop}px " +
                    "density=${density.density}")
            }

            // Wait for keyboard animation + layout to settle.
            // Uses frame-based waits (not a fixed delay) so we re-check
            // after Compose processes the IME-driven layout pass.
            var prevHeight = listState.layoutInfo.viewportSize.height
            repeat(3) { attempt ->
                withFrameNanos { }
                val currentHeight = listState.layoutInfo.viewportSize.height
                if (currentHeight != prevHeight) {
                    if (justOpened) {
                        DebugLog.log("UI", "Keyboard",
                            "frame $attempt: viewportHeight changed $prevHeight→$currentHeight (waiting for settle)")
                    }
                    prevHeight = currentHeight
                }
            }

            // Log viewport state after keyboard settles, before scroll
            val layoutInfo = listState.layoutInfo
            val firstVis = layoutInfo.visibleItemsInfo.firstOrNull()
            val lastVis = layoutInfo.visibleItemsInfo.lastOrNull()
            val targetIdx = state.messages.lastIndex
            val viewportHeightBefore = layoutInfo.viewportSize.height
            if (justOpened) {
                DebugLog.log("SCROLL", "Keyboard",
                    "reason=keyboard_open ime=${imeBottom}px " +
                    "target=$targetIdx totalItems=${state.messages.size} " +
                    "viewportBefore=[${firstVis?.index}..${lastVis?.index}] " +
                    "viewportHeight=$viewportHeightBefore " +
                    "totalViewportItems=${layoutInfo.visibleItemsInfo.size}")
            }

            autoScrollToBottom(
                listState = listState,
                targetIndex = targetIdx,
                totalItems = state.messages.size,
                scrollGeneration = state.scrollGeneration,
                reason = "Keyboard",
            )
            if (justOpened) {
                val viewportHeightAfter = listState.layoutInfo.viewportSize.height
                val firstVisAfter = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                val lastVisAfter = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                DebugLog.log("SCROLL", "Keyboard",
                    "scroll_result: target=$targetIdx " +
                    "firstVisible=${firstVisAfter?.index} lastVisible=${lastVisAfter?.index} " +
                    "viewportHeight=$viewportHeightAfter→$viewportHeightBefore")
            }
        }
    }

    // Track composer focus separately (for other uses if needed)

    WebuiMenuDrawer(
        drawerState = drawerState,
        onNavigate = onNavigate,
        onOpenSession = onOpenSession,
    ) {
    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                AttachOption(Icons.Outlined.PhotoCamera, "Take photo") {
                    showAttachSheet = false
                    pendingCameraUri = createCameraUri(context)
                    pendingCameraUri?.let { cameraPicker.launch(it) }
                }
                AttachOption(Icons.Outlined.PhotoLibrary, "Choose photo") {
                    showAttachSheet = false
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
                AttachOption(Icons.Outlined.Folder, "Choose file") {
                    showAttachSheet = false
                    filePicker.launch(arrayOf("*/*"))
                }
            }
        }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.sessionTitle,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Live context-window occupancy (session.info usage).
                        // v0.1.71: ALWAYS visible once the chat is open —
                        // mirrors the desktop's never-blank behavior. Shows
                        // the last-known reading when the server is quiet
                        // (e.g. reaped agent after app update), and "—" before
                        // any reading exists, instead of hiding the slot.
                        val ctxUsed = state.contextUsed
                        val ctxMax = state.contextMax
                        val knownMax = ctxMax != null && ctxMax > 0
                        val knownUsed = knownMax && ctxUsed != null
                        val fraction = if (knownUsed) {
                            (ctxUsed.toFloat() / ctxMax.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // v0.1.88: model chip — shows current model + reasoning
                            // effort; tap opens the picker sheet.
                            val modelText = buildString {
                                append(state.currentModel?.let { shortModelName(it) } ?: "")
                                if (isNotBlank()) append(" · ")
                                append(state.currentReasoning?.let { effortShort(it) } ?: "")
                            }
                            if (modelText.isNotBlank()) {
                                Text(
                                    text = modelText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    // v0.1.105: cap the chip so a long model name
                                    // can't push the gauge row off the top bar.
                                    modifier = Modifier
                                        .widthIn(max = 120.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { showModelPicker = true }
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier
                                    .width(64.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = if (knownUsed && fraction > 0.8f) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                // v0.1.95: gauge track is a themeable extra surface
                                trackColor = LocalUiSurfaces.current.gaugeTrack,
                            )
                            Text(
                                text = when {
                                    knownUsed -> "${formatTokens(ctxUsed)}/${formatTokens(ctxMax)}"
                                    knownMax -> "—/${formatTokens(ctxMax)}"
                                    else -> "—/—"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (knownUsed) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                },
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                // v0.1.96: quick tool-call visibility toggle — wrench icon,
                // tinted when shown, dimmed when hidden.
                actions = {
                    IconButton(
                        onClick = { scope.launch { drawerState.open() } },
                    ) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                    }
                    IconButton(
                        onClick = { scope.launch { settingsRepo.setShowToolCalls(!showToolCalls) } },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Handyman,
                            contentDescription = if (showToolCalls) "Hide tool calls" else "Show tool calls",
                            tint = if (showToolCalls) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            },
                        )
                    }
                    // v0.1.97: quick thinking visibility toggle — brain icon.
                    IconButton(
                        onClick = { scope.launch { settingsRepo.setShowThinking(!showThinking) } },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Psychology,
                            contentDescription = if (showThinking) "Hide thinking" else "Show thinking",
                            tint = if (showThinking) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            },
                        )
                    }
                    if (!state.isStreaming && state.messages.any { it.role == "assistant" }) {
                        IconButton(onClick = { viewModel.retry() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Column {
                    // Pending image thumbnail (removable)
                    if (pendingImageBmp != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, top = 8.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = pendingImageBmp,
                                contentDescription = "Attached image",
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Image attached",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                pendingImageB64 = null
                                pendingImageBmp = null
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove image")
                            }
                        }
                    }
                    // Recording indicator
                    if (isRecording || isTranscribing) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isTranscribing) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color(0xFFFF3B30)
                                    }),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when {
                                    isTranscribing -> "Transcribing…"
                                    else -> "Recording %d:%02d".format(recordingSeconds / 60, recordingSeconds % 60)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isTranscribing) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color(0xFFFF3B30)
                                },
                            )
                        }
                    }
                    // Pending voice message — recorded, awaiting Delete or Send (v0.1.125)
                    val pendingVoice = pendingVoiceB64
                    if (pendingVoice != null) {
                        val voiceLabel = "%d:%02d".format(pendingVoiceDurSec / 60, pendingVoiceDurSec % 60)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, top = 8.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Voice message ($voiceLabel)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (isTranscribing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = {
                                    pendingVoiceB64 = null
                                    pendingVoiceDurSec = 0
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Delete recording")
                                }
                                IconButton(onClick = { sendPendingVoice() }) {
                                    Icon(Icons.Default.Send, contentDescription = "Send voice message")
                                }
                            }
                        }
                    }
                    // Slash-command completions — pop up above the composer.
                    // Capture to a local: LazyColumn DEFERS its content lambda
                    // (runs later inside intervalContentState derivedStateOf),
                    // so a `!!` re-reading the mutable state there would NPE if
                    // the LaunchedEffect nulls slashItems (focus loss, text
                    // edit, suggestion tap) between guard and deferred exec.
                    val slashItemsNow = slashItems
                    if (slashItemsNow != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                        ) {
                            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                                itemsIndexed(slashItemsNow) { _, item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                // Server completions omit the
                                                // leading "/" (already typed) —
                                                // restore it or the command
                                                // becomes plain text.
                                                composerText = if (item.text.startsWith("/")) {
                                                    item.text
                                                } else {
                                                    "/" + item.text
                                                }
                                                slashItems = null
                                                focusRequester.requestFocus()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = item.display ?: item.text,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (item.kind == "skill") {
                                                MaterialTheme.colorScheme.tertiary
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (item.kind == "skill") {
                                            Text(
                                                text = "skill",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.tertiary,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        item.meta?.let { meta ->
                                            if (meta.isNotBlank()) {
                                                Text(
                                                    text = meta,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(0.9f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // ── Inline clarify card (v0.1.134): renders the server's
                    // question ABOVE the composer, dsh-style, instead of a modal
                    // popup so you can always read what's being asked. ──
                    val pendingClarify = state.pendingClarify
                    if (pendingClarify != null) {
                        InlineClarifyCard(
                            pendingClarify = pendingClarify,
                            onAnswer = { answer -> viewModel.respondToClarify(answer) },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // WebUI-style composer capsule (v0.1.124)
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(28.dp),
                            color = Color(0xFF1A1A1A),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Attach (paperclip — photo picker)
                                IconButton(
                                    onClick = { showAttachSheet = true },
                                    enabled = !isRecording && !isTranscribing,
                                ) {
                                    Icon(
                                        Icons.Outlined.AttachFile,
                                        contentDescription = "Attach photo",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // Voice message
                                IconButton(
                                    onClick = {
                                        if (isRecording) {
                                            stopRecording()
                                        } else {
                                            val granted = ContextCompat.checkSelfPermission(
                                                context, Manifest.permission.RECORD_AUDIO,
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (granted) startRecording()
                                            else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                    enabled = !isTranscribing && pendingVoiceB64 == null,
                                ) {
                                    Icon(
                                        imageVector = if (isRecording) Icons.Filled.Mic else Icons.Outlined.Mic,
                                        contentDescription = if (isRecording) "Stop recording" else "Voice message",
                                        tint = if (isRecording) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // Text field — transparent inside the capsule
                                OutlinedTextField(
                                    value = composerText,
                                    onValueChange = { composerText = it },
                                    placeholder = {
                                        Text(
                                            "Message Hermes...",
                                            fontStyle = FontStyle.Italic,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(focusRequester)
                                        .onFocusChanged { composerFocused = it.isFocused },
                                    maxLines = 4,
                                    enabled = true,
                                    shape = RoundedCornerShape(28.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                    ),
                                )
                                // Context ring (WebUI-style token gauge)
                                ContextRing(
                                    used = state.contextUsed,
                                    max = state.contextMax,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                    onClick = { showCtxPanel = true },
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        // Send — accent circle with up arrow (WebUI-style)
                        val canSend = (composerText.isNotBlank() || pendingImageB64 != null) && !isTranscribing
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                    },
                                )
                                .clickable(enabled = canSend) { sendComposer() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = "Send",
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Error banner for send failures (wrap_content height)
            if (state.error != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.error!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.loadMessages() }, modifier = Modifier.size(24.dp)) {
                            Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Agent task list (todo tool state) — pinned above the messages
            if (state.todos.isNotEmpty()) {
                TasksCard(
                    todos = state.todos,
                    expanded = state.todosExpanded,
                    isStreaming = state.isStreaming,
                    onToggle = { viewModel.toggleTodosExpanded() },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // Completed-while-away banner (background turns, v0.1.60)
            if (state.completedWhileAway) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "✓ Turn finished while you were away",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            viewModel.clearCompletedWhileAway()
                            scope.launch {
                                if (state.messages.isNotEmpty()) {
                                    listState.scrollToItem(state.messages.lastIndex)
                                }
                            }
                        }) {
                            Text("View latest")
                        }
                    }
                }
            }
            when {
                state.isLoading && state.messages.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null && state.messages.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = viewModel::loadMessages) {
                            Text("Retry")
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .nestedScroll(remember {
                                object : NestedScrollConnection {
                                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                        if (source == NestedScrollSource.UserInput && available.y > 0) {
                                            if (!userScrolledUp) {
                                                userScrolledUp = true
                                                DebugLog.log("SCROLL", "DragDetect",
                                                    "userScrolledUp=true (source=$source, " +
                                                    "available.y=${available.y}, " +
                                                    "isScrollInProgress=${listState.isScrollInProgress})")
                                            }
                                        }
                                        return Offset.Zero
                                    }
                                }
                            }),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        itemsIndexed(state.messages, key = { _, msg -> msg.id }) { index, msg ->
                            val sameSender = index > 0 && state.messages[index - 1].role == msg.role

                            // Live thinking block: shown ABOVE the assistant bubble
                            // while thinking is streaming and no real content has arrived yet.
                            // v0.1.163: the showLiveThinking local was removed — it fed a
                            // LiveThinkingTicker branch that required msg.isStreaming &&
                            // !state.isStreaming, but every Message's isStreaming is assigned
                            // = state.isStreaming (line 1144), so those two states can never
                            // diverge and the branch was provably unreachable. Kept here only as
                            // explanatory history; the inline thinking preview it guarded no
                            // longer exists in this list block.

                            // v0.1.162: removed — during streaming, thinking lives ONLY in
                            // the docked LiveActivityPanel (its own nested LazyColumn). The
                            // main list rendered a truncated inline preview line here too,
                            // so the same text scrolled in two independent places at
                            // different rates ("scroll in two places"). Main-list auto-scroll
                            // now keys on content + tool calls only; thinking scrolls inside
                            // the panel. Cleared once real content/tools arrive (turn ends →
                            // ThinkingScrollBox).

                            // v0.1.163: dead code removed. showLiveThinking requires
                            // msg.isStreaming == true, but line 1144 assigns every Message's
                            // isStreaming = state.isStreaming, so the global never lags the
                            // per-message flag. The condition below therefore required a message
                            // to be streaming while the global state was NOT — impossible by
                            // construction. LiveThinkingTicker only ever fired if those two states
                            // diverged, which they structurally cannot; removed rather than left
                            // as misleading dead code that looks like a real post-stream path.

                            // After the turn, thinking persists as a scrollable
                            // box above the tools+answer (during streaming it
                            // lives in the docked live panel instead). Hidden
                            // when the thinking toggle is off (v0.1.97).
                            if (msg.role == "assistant" && msg.thinkingText?.isNotBlank() == true &&
                                !msg.isStreaming && showThinking
                            ) {
                                ThinkingScrollBox(text = msg.thinkingText)
                            }

                            // Tool calls render ABOVE the response in one
                            // scrollable box (v0.1.68); tap a row for the full
                            // card (args/diff). Hidden when the tool-call toggle
                            // is off (v0.1.96).
                            if (msg.role == "assistant" && msg.toolCalls.isNotEmpty() && !msg.isStreaming && showToolCalls) {
                                ToolScrollBox(toolCalls = msg.toolCalls)
                            }

                            // Skip the empty bubble for tool-only assistant rows
                            // from history (content blank, not streaming) — the
                            // thinking + tools boxes above already tell the story.
                            if (msg.isCommandAck) {
                                CommandAckLine(text = msg.content)
                            } else if (msg.content.isNotBlank() || msg.isStreaming) {
                                MessageBubble(
                                    message = msg,
                                    sameSender = sameSender,
                                    onReply = { replyTarget = msg },
                                    onToggleThinking = { viewModel.toggleThinking(msg.id) },
                                )
                            }
                        }
                    }
                }
            }

            // Docked live-activity panel (v0.1.64): while a turn is running,
            // thinking + tool calls stream in a small scrollable box above the
            // composer. On completion the panel vanishes and the finished
            // message shows tools + thinking above the final answer instead.
            val liveMsg = state.messages.lastOrNull { it.isStreaming }
            // v0.1.106: panel is now always visible while streaming — it hosts
            // the tok/s readout; the thinking/tools sections inside still
            // respect the visibility toggles.
            // v0.1.110: use server-reported liveTokPerSec when available,
            // falling back to the char-count estimate (streamTokPerSec).
            val displayTokPerSec = state.liveTokPerSec?.takeIf { it > 0f } ?: streamTokPerSec
            val isServerReported = state.liveTokPerSec != null && state.liveTokPerSec!! > 0f
            val livePanelVisible = state.isStreaming && liveMsg != null
            if (livePanelVisible) {
                LiveActivityPanel(
                    thinking = if (showThinking) liveMsg!!.thinkingText.orEmpty() else "",
                    toolCalls = if (showToolCalls) liveMsg.toolCalls else emptyList(),
                    showTools = showToolCalls,
                    showThinking = showThinking,
                    tokPerSec = displayTokPerSec,
                    thinkingTokPerSec = streamThinkingTokPerSec,
                    isServerReported = isServerReported,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }

    // ── Model Picker Sheet (v0.1.88) ──
    if (showModelPicker) {
        ModelPickerSheet(
            viewModel = viewModel,
            onDismiss = { showModelPicker = false },
        )
    }

    // ── Context Details Panel (WebUI): tap the ring to open details + compress ──
    if (showCtxPanel) {
        ContextDetailsPanel(
            used = state.contextUsed,
            max = state.contextMax,
            usage = ctxUsage,
            isStreaming = state.isStreaming,
            onCompress = { viewModel.sendMessage("/compress") },
            onDismiss = { showCtxPanel = false },
        )
    }

    // ── Reply / Copy dialog (v0.1.93) — long-press a message ──
    replyTarget?.let { target ->
        val replyText = target.content.ifBlank { target.thinkingText ?: "" }
        AlertDialog(
            onDismissRequest = { replyTarget = null },
            title = { Text("Message") },
            text = {
                Text(
                    text = replyText.take(300).ifBlank { "(tool activity)" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Quote the message into the composer so the user can ask
                    // "what did you mean by this".
                    val quote = replyText.take(500).lineSequence()
                        .joinToString("\n") { if (it.isBlank()) ">" else "> $it" }
                    composerText = "$quote\n\n"
                    replyTarget = null
                }) { Text("Reply") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (replyText.isNotBlank()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Hermes message", replyText))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    replyTarget = null
                }) { Text("Copy") }
            },
        )
    }

    // ── Tool Approval Dialog ──
    val pendingApproval = state.pendingApproval
    if (pendingApproval != null) {
        Dialog(onDismissRequest = { /* must approve or deny */ }) {
            Card(
                modifier = Modifier
                    .widthIn(min = 300.dp, max = 460.dp)
                    .padding(8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .heightIn(max = 640.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // ── Title with tool name ──
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Approve: ${pendingApproval.toolName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // ── Command description (what will happen) — scrollable ──
                    if (pendingApproval.description.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Command",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        ) {
                            Text(
                                text = pendingApproval.description,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp),
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // ── Raw arguments (full detail) — scrollable ──
                    if (pendingApproval.toolArgs.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Arguments",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        ) {
                            Text(
                                text = pendingApproval.toolArgs,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // ── Action buttons ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.denyCurrentTool() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Deny")
                        }
                        Button(
                            onClick = { viewModel.approveCurrentTool() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Approve")
                        }
                    }
                }
            }
        }
    }

    // ── Clarify is rendered INLINE above the composer (bottomBar), see
    // InlineClarifyCard below — not as a modal Dialog popup. ──
    }
}

// ── LIVE THINKING TICKER ──
// Shown above the assistant bubble while the model is thinking
// and no real content has arrived yet. Dimmed, italic, live-updating.

/**
 * Scrollable thinking box shown above the final answer once the turn is done
 * (v0.1.66). During streaming the live docked panel owns thinking instead.
 */
@Composable
private fun ThinkingScrollBox(text: String) {
    // v0.1.120: track when the thinking box first appeared so we can show how
    // long the model spent reasoning, displayed at the bottom of the box.
    var startedAt by remember { mutableStateOf<Long?>(null) }
    if (startedAt == null) startedAt = System.currentTimeMillis()
    // v0.1.121: a one-shot ticker that flips `now` once per second while the box
    // is visible, so the elapsed readout actually advances instead of freezing
    // at 0s. Without this the remember{} snapshot captured "now" once and never
    // updated — hence "Thought for 0s".
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val elapsedMs = now - (startedAt ?: now)
    fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        val sec = s % 60
        return if (m > 0) "${m}m ${sec}s" else "${sec}s"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        // v0.1.95: thinking box is a themeable extra surface
        color = LocalUiSurfaces.current.thinkingBox,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "THINKING",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            // v0.1.120: elapsed reasoning time at the bottom of the box.
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Thought for ${formatElapsed(elapsedMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun LiveThinkingTicker(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            // v0.1.95: live thinking pill shares the themeable thinking-box color
            color = LocalUiSurfaces.current.thinkingBox,
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // Spinning indicator
                Text(
                    text = "●",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = FontStyle.Italic,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// v0.1.159: single-line live-thinking preview rendered INSIDE the main chat
// list during streaming (see ChatScreen ~line 1246). The StreamLoop's
// auto-scroll keys on the last item's content length, so this growing line is
// what makes the chat follow while thinking — before it existed the main list
// showed only a frozen spinner placeholder and never scrolled until the first
// tool call. Kept visually quiet (dimmed monospace) so it reads as "thinking",
// not an answer, and disappears once the turn ends.
@Composable
private fun LiveThinkingPreviewLine(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = "●",
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 1.dp, end = 6.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontStyle = FontStyle.Italic,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── MESSAGE BUBBLE (Telegram-style) ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: UiMessage,
    sameSender: Boolean,
    onReply: () -> Unit,
    onToggleThinking: () -> Unit,
) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    // Telegram-style corner radii: user = top-left/bottom-left/bottom-right rounded,
    // top-right sharp; assistant = top-right/bottom-right/bottom-left rounded, top-left sharp
    val bubbleShape = if (isUser) {
        RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 4.dp,
            bottomStart = 18.dp,
            bottomEnd = 18.dp,
        )
    } else {
        RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 18.dp,
            bottomStart = 18.dp,
            bottomEnd = 18.dp,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                // v0.1.87: assistant bubbles are full-bleed (edge to edge) —
                // only user bubbles keep side insets.
                start = if (isUser) 8.dp else 0.dp,
                end = if (isUser) 8.dp else 0.dp,
                top = if (sameSender) 1.dp else 6.dp,
                bottom = 0.dp,
            ),
        horizontalAlignment = alignment,
    ) {
        // Timestamp centered above first message of a group
        if (!sameSender) {
            Text(
                text = formatTimestamp(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        // Assistant bubbles span the full width (edge to edge); user bubbles
        // stay capped at a chat-style max width, aligned right.
        val bubbleWidthModifier = if (isUser) {
            Modifier.widthIn(min = 60.dp, max = 320.dp)
        } else {
            Modifier.fillMaxWidth()
        }

        Box {
            Column(
                modifier = bubbleWidthModifier
                    .clip(bubbleShape)
                    .background(bubbleColor)
                    // Border in the context-gauge color (primary) — subtle outline
                    // around each message (v0.1.67).
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        shape = bubbleShape,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
            // Thinking no longer renders in the message (v0.1.65): it streams
            // in the docked Live Activity panel while working — showing it here
            // too caused double-thinking. Tool cards above the answer remain.

            // Content
            if (message.content.isNotBlank()) {
                // Use immediate=true to avoid Loading→Success height oscillation
                // during streaming (fixes scroll-crazy feedback loop).
                // Override heading typography with reasonable sizes (not
                // displayLarge ~57sp which "balloons" text in the bubble).
                val mdState = com.mikepenz.markdown.model.rememberMarkdownState(
                    content = message.content,
                    immediate = true,
                )
                // Selectable text (v0.1.93): wrap in SelectionContainer so any
                // text — not just whole-message copy — can be selected.
                SelectionContainer {
                    Markdown(
                        markdownState = mdState,
                        // v0.1.95: code block + inline code backgrounds are a
                        // themeable extra surface (library reads these via
                        // MarkdownTheme.colors inside highlightedCodeBlock).
                        colors = markdownColor(
                            codeBackground = LocalUiSurfaces.current.codeBlock,
                            inlineCodeBackground = LocalUiSurfaces.current.codeBlock,
                        ),
                        // v0.1.87: no width cap for assistant messages — text spans
                        // the full bubble edge to edge. User bubbles keep the cap
                        // (they're 320dp max anyway).
                        modifier = if (isUser) Modifier.widthIn(max = 400.dp) else Modifier.fillMaxWidth(),
                        typography = markdownTypography(
                            h1 = MaterialTheme.typography.titleLarge,
                            h2 = MaterialTheme.typography.titleMedium,
                            h3 = MaterialTheme.typography.titleSmall,
                            h4 = MaterialTheme.typography.bodyLarge,
                            h5 = MaterialTheme.typography.bodyMedium,
                            h6 = MaterialTheme.typography.bodyMedium,
                            text = MaterialTheme.typography.bodyMedium,
                        ),
                        components = markdownComponents(
                            codeBlock = { CopyableCodeBlock(it) },
                            codeFence = { CopyableCodeFence(it) },
                        ),
                    )
                }
                if (message.isStreaming) {
                    Text(
                        text = " ▌",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (message.isStreaming && message.thinkingText == null) {
                // No content yet — show typing dots while waiting, cursor otherwise.
                // v0.1.120: add a small "waiting for model" label so the state is
                // obvious when reasoning is OFF and the model emits no thinking
                // deltas (e.g. Ornith with effort=off) — otherwise just dots can
                // look stuck during the long first-token wait on a local model.
                if (message.isWaitingForFirstEvent) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 6.dp),
                    ) {
                        TypingDots()
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "waiting for model…",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontStyle = FontStyle.Italic,
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                } else {
                    Text(
                        text = "▌",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (message.isStreaming && message.thinkingHasContent && message.content.isBlank()) {
                // Thinking done, content starting soon — show cursor
                Text(
                    text = "▌",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Usage footer + inline timestamp
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                message.usage?.let { usage ->
                    // v0.1.102: exact turn-average speed — real token count
                    // (Ollama eval stats for local models included) over the
                    // stream duration (message.timestamp = placeholder creation).
                    // Frozen via remember so it doesn't drift on recomposition.
                    val avgTokPerSec = remember(usage.totalTokens, message.timestamp) {
                        val secs = ((System.currentTimeMillis() - message.timestamp) / 1000f)
                            .coerceAtLeast(1f)
                        usage.totalTokens / secs
                    }
                    Text(
                        text = buildString {
                            append("${usage.totalTokens} tokens")
                            append(" · ≈${String.format("%.1f", avgTokPerSec)} tok/s")
                            usage.estimatedCostUsd?.let { append(" · \$${String.format("%.4f", it)}") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            }
            // "⋯" message actions — long-press is reserved for text selection,
            // so Reply/Copy lives behind this small button (v0.1.94).
            IconButton(
                onClick = onReply,
                modifier = Modifier
                    .align(if (isUser) Alignment.TopStart else Alignment.TopEnd)
                    .size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Message actions",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

// ── THINKING TOGGLE (collapsed state) ──

@Composable
private fun ThinkingToggle(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "▼ Thinking" else "▶ Show thinking",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ── TOOL CALL CARD ──

@Composable
private fun ToolCallCard(toolCall: UiToolCall) {
    var expanded by remember { mutableStateOf(false) }
    val now by remember { mutableStateOf(System.currentTimeMillis()) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        // v0.1.95: tool cards are a themeable extra surface
        color = LocalUiSurfaces.current.toolCard,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // ── Header row: icon + name + elapsed + expand arrow ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = !expanded },
            ) {
                // Tool icon
                Text(
                    text = toolIcon(toolCall.toolName),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.width(6.dp))
                // Tool name
                Text(
                    text = toolCall.toolName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(8.dp))
                // Elapsed time
                Text(
                    text = formatElapsed(toolCall.startedAt, now, toolCall.completed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Spacer(Modifier.weight(1f))
                // Status + expand arrow
                Text(
                    text = if (toolCall.completed) "✓" else "◌",
                    color = if (toolCall.completed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }

            // ── Preview (always visible, one line) ──
            val previewText = toolCall.preview
                ?: toolCall.summary
                ?: toolCall.args?.take(100)
            if (!previewText.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            // ── Expandable detail section ──
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    // Args section
                    if (!toolCall.args.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Arguments",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(2.dp))
                        Surface(
                            // v0.1.95: args box inside a tool card
                            color = LocalUiSurfaces.current.toolCard,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = toolCall.args,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(6.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    }

                    // Result section (completed only)
                    if (toolCall.completed && !toolCall.result.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Result",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(2.dp))
                        Surface(
                            // v0.1.95: result box inside a tool card
                            color = LocalUiSurfaces.current.toolCard,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = toolCall.result.take(500),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(6.dp),
                                maxLines = 10,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    }

                    // Diff section (file edits — server inline_diff, desktop-style)
                    if (!toolCall.inlineDiff.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Diff",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(2.dp))
                        DiffView(toolCall.inlineDiff)
                    }
                }
            }
        }
    }
}

/**
 * Desktop-style unified diff: monospace lines with red/green tinting for
 * removed/added lines, highlighted hunk headers and file lines. The server's
 * inline_diff carries ANSI color codes (terminal rendering) — we strip them
 * and re-classify by line prefix so colors follow the app theme.
 */
@Composable
private fun DiffView(diffText: String) {
    val lines = remember(diffText) { parseDiffLines(diffText) }
    Surface(
        color = Color(0xFF0D1117),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            lines.forEach { line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(line.bgColor),
                ) {
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        ),
                        color = line.textColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

private enum class DiffKind { FILE, HUNK, ADD, DEL, CONTEXT, OTHER }

private data class DiffLine(val text: String, val kind: DiffKind) {
    val textColor: Color
        get() = when (kind) {
            DiffKind.ADD -> Color(0xFFA5D6A7)
            DiffKind.DEL -> Color(0xFFEF9A9A)
            DiffKind.HUNK -> Color(0xFF82B1FF)
            DiffKind.FILE -> Color(0xFF80CBC4)
            else -> Color(0xFFB0BEC5)
        }
    val bgColor: Color
        get() = when (kind) {
            DiffKind.ADD -> Color(0x1F2E7D32)
            DiffKind.DEL -> Color(0x1FB71C1C)
            else -> Color.Transparent
        }
}

private val ansiRegex = Regex("\u001B\\[[0-9;]*[A-Za-z]")

private fun stripAnsi(text: String): String = ansiRegex.replace(text, "")

/** Parse ANSI-stripped inline_diff text into classified lines (desktop-style). */
private fun parseDiffLines(raw: String): List<DiffLine> {
    return raw.lineSequence().mapNotNull { rawLine ->
        val line = stripAnsi(rawLine).trimEnd('\r')
        if (line.isBlank() && !rawLine.startsWith(" ")) return@mapNotNull null
        val kind = when {
            line.startsWith("@@") -> DiffKind.HUNK
            line.startsWith("+") && !line.startsWith("+++") -> DiffKind.ADD
            line.startsWith("-") && !line.startsWith("---") -> DiffKind.DEL
            line.startsWith(" ") -> DiffKind.CONTEXT
            line.startsWith("a/") || line.startsWith("b/") || line.contains("→") -> DiffKind.FILE
            else -> DiffKind.OTHER
        }
        DiffLine(line, kind)
    }.toList()
}

/** Map tool name to an icon character. */
private fun toolIcon(name: String): String = when {
    name.contains("web_search", ignoreCase = true) -> "🔍"
    name.contains("web_fetch", ignoreCase = true) || name.contains("http", ignoreCase = true) -> "🌐"
    name.contains("read_file", ignoreCase = true) || name.contains("cat", ignoreCase = true) -> "📄"
    name.contains("write_file", ignoreCase = true) || name.contains("edit", ignoreCase = true) -> "✏️"
    name.contains("bash", ignoreCase = true) || name.contains("terminal", ignoreCase = true) ||
        name.contains("command", ignoreCase = true) || name.contains("shell", ignoreCase = true) -> "💻"
    name.contains("python", ignoreCase = true) || name.contains("code", ignoreCase = true) ||
        name.contains("run", ignoreCase = true) -> "▶️"
    name.contains("search", ignoreCase = true) -> "🔎"
    name.contains("list", ignoreCase = true) || name.contains("dir", ignoreCase = true) -> "📋"
    name.contains("think", ignoreCase = true) -> "🧠"
    else -> "⚙️"
}

/** Format elapsed time for a tool call. Shows live duration for running tools. */
private fun formatElapsed(startedAt: Long?, now: Long, completed: Boolean): String {
    val start = startedAt ?: return ""
    val elapsedMs = now - start
    val seconds = elapsedMs / 1000
    val millis = elapsedMs % 1000
    return if (completed || seconds >= 60) {
        // Show mm:ss for long or completed tools
        val m = seconds / 60
        val s = seconds % 60
        if (m > 0) "${m}m ${s}s" else "${seconds}.${millis / 100}s"
    } else {
        // Show X.Xs for short running tools
        "${seconds}.${millis / 100}s"
    }
}

// ── TIMESTAMP HELPERS ──

private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
    .withZone(ZoneId.systemDefault())
private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    .withZone(ZoneId.systemDefault())

private fun formatTimestamp(epochMillis: Long): String {
    return try {
        dateFormatter.format(Instant.ofEpochMilli(epochMillis))
    } catch (_: Exception) {
        ""
    }
}

private fun formatTime(epochMillis: Long): String {
    return try {
        timeFormatter.format(Instant.ofEpochMilli(epochMillis))
    } catch (_: Exception) {
        ""
    }
}

// ── TYPING DOTS (bouncing animation, iMessage-style) ──

@Composable
private fun TypingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val delays = listOf(0, 150, 300)

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        delays.forEachIndexed { i, delayMs ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, delayMillis = delayMs),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                    ),
            )
        }
    }
}

// ── AUTO-SCROLL HELPER ──
// Scrolls to the target item, then compensates for items that extend beyond
// the viewport (common during streaming when content grows taller than the
// visible area). Uses scrollToItem (instant) + scrollBy for the remainder.
// Logs comprehensive debug info: first/last visible indices, viewport height,
// canScrollForward, and actual item bottom offset.
//
// IMPORTANT: This function MUST complete fully (both steps) to keep the
// bottom of a tall message visible. The polling loop (StreamLoop) ensures
// this function runs uninterrupted — it is NOT cancelled by rapid key
// changes (unlike the old LaunchedEffect on scrollGeneration).

private suspend fun autoScrollToBottom(
    listState: LazyListState,
    targetIndex: Int,
    totalItems: Int,
    scrollGeneration: Long,
    reason: String,
) {
    val beforeFirst = listState.firstVisibleItemIndex
    val beforeLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    val beforeCanScroll = listState.canScrollForward
    val beforeViewportHeight = listState.layoutInfo.viewportSize.height

    DebugLog.log("SCROLL", reason,
        "scrollToItem(target=$targetIndex) totalItems=$totalItems " +
        "gen=$scrollGeneration " +
        "firstVisibleBefore=$beforeFirst " +
        "lastVisibleBefore=$beforeLast " +
        "viewportHeight=$beforeViewportHeight " +
        "canScrollForward=$beforeCanScroll")

    // Step 1: default scroll to make the target item visible
    listState.scrollToItem(targetIndex)

    // Step 2: compensate for item taller than viewport.
    // After scrollToItem, if the last visible item includes the target and
    // canScrollForward is still true, the item extends below the viewport.
    // Compute the remaining scroll distance from layout info and scrollBy it.
    val afterFirst = listState.firstVisibleItemIndex
    val afterCanScroll = listState.canScrollForward
    val layoutInfo = listState.layoutInfo
    val viewportHeight = layoutInfo.viewportSize.height
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()

    if (afterCanScroll && lastVisible != null && lastVisible.index >= targetIndex) {
        val itemBottom = lastVisible.offset + lastVisible.size
        val beyondViewport = itemBottom - viewportHeight
        if (beyondViewport > 0) {
            DebugLog.log("SCROLL", reason,
                "compensating: item[${lastVisible.index}] offset=${lastVisible.offset} " +
                "size=${lastVisible.size} bottom=$itemBottom " +
                "viewportHeight=$viewportHeight beyond=$beyondViewport px")
            listState.scrollBy(beyondViewport.toFloat())
        }
    }

    val afterFirst2 = listState.firstVisibleItemIndex
    val afterLast2 = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    val afterCanScroll2 = listState.canScrollForward
    DebugLog.log("SCROLL", reason,
        "done: firstVisible=$afterFirst2 " +
        "lastVisible=$afterLast2 " +
        "canScrollForward=$afterCanScroll2")
}

/** Compact token count formatting: 85123 → "85.1k", 1048576 → "1.0M". */
private fun formatTokens(tokens: Long): String {
    return when {
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000f)
        tokens >= 1_000 -> String.format("%.1fk", tokens / 1_000f)
        else -> tokens.toString()
    }
}

/**
 * Docked live-activity panel (v0.1.64): small scrollable box above the composer
 * showing thinking + tool calls as they stream, while the answer grows above it.
 * Disappears when the turn completes (the finished message then shows tools +
 * thinking above the final answer instead).
 */
@Composable
private fun LiveActivityPanel(
    thinking: String,
    toolCalls: List<UiToolCall>,
    showTools: Boolean = true,
    showThinking: Boolean = true,
    tokPerSec: Float = 0f,
    thinkingTokPerSec: Float = 0f,
    isServerReported: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }
    // v0.1.96: tools are their own labeled section, and disappear entirely
    // when the tool-call toggle is off.
    val toolsVisible = showTools && toolCalls.isNotEmpty()
    // v0.1.97: thinking section disappears when the thinking toggle is off.
    val thinkingVisible = showThinking && thinking.isNotBlank()
    // v0.1.115: track how long the model has been thinking.
    var thinkingStartedAt by remember { mutableStateOf<Long?>(null) }
    if (thinkingVisible) {
        if (thinkingStartedAt == null) thinkingStartedAt = System.currentTimeMillis()
    } else {
        thinkingStartedAt = null
    }
    val thinkingElapsedMs = remember(now, thinkingStartedAt) {
        if (thinkingStartedAt != null) now - thinkingStartedAt!! else 0L
    }
    fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        val sec = s % 60
        return if (m > 0) "${m}m ${sec}s" else "${sec}s"
    }
    // v0.1.138: live panel is dedicated to thinking — cap visible tool rows at
    // two, with an expand toggle so the rest are still reachable (not hidden).
    var showAllTools by remember { mutableStateOf(false) }
    val maxVisibleTools = 2
    val visibleToolCount = if (showAllTools) toolCalls.size else kotlin.math.min(maxVisibleTools, toolCalls.size)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        // v0.1.95: live activity panel is a themeable extra surface (tool cards)
        color = LocalUiSurfaces.current.toolCard,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Live activity",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                // v0.1.107: thinking speed beside the label while reasoning
                // flows (fades out via EMA once thinking stops).
                if (thinkingTokPerSec > 0f) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "thinking ${String.format("%.1f", thinkingTokPerSec)} tok/s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                    )
                }
                // v0.1.115: show how long the model has been thinking.
                if (thinkingVisible && thinkingElapsedMs > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatElapsed(thinkingElapsedMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.weight(1f))
                // v0.1.106/110: live streaming speed — server-reported (no ≈)
                // when available, estimated (≈) from char-count fallback.
                Text(
                    text = "${if (isServerReported) "" else "≈"}${String.format("%.1f", tokPerSec)} tok/s",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tokPerSec > 0f) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            val listState = rememberLazyListState()
            // v0.1.161: measured pixel height of the growing THINKING item
            // (captured via BoxWithConstraints during layout). Used to follow
            // its bottom edge without racing live layoutInfo timing.
            var thinkingHeight by remember { mutableStateOf(0f) }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
            ) {
                if (thinkingVisible) {
                    item(key = "thinking") {
                        BoxWithConstraints(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            // Record the measured height so the scroll effect
                            // can pin the bottom edge of a tall single item.
                            thinkingHeight = this.minHeight.value.toFloat()
                            Column {
                                Text(
                                    text = "THINKING",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = thinking,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
                }
                // v0.1.96: TOOLS section — its own header (matching the finished
                // ToolScrollBox), visually separated from the THINKING block.
                // v0.1.138: capped at 2 visible rows with an expand toggle so the
                // live panel stays focused on thinking, not a tool dump.
                if (toolsVisible) {
                    item(key = "tools-header") {
                        Column {
                            if (thinkingVisible) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // v0.1.107: working count folded in here (was a
                                // header counter; moved to make room for speeds).
                                val working = toolCalls.count { !it.completed }
                                Text(
                                    text = "TOOLS · ${toolCalls.size}" +
                                        if (working > 0) " · $working working" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.weight(1f))
                                // v0.1.138: expand/collapse when there's more than the
                                // capped 2 visible rows.
                                if (toolCalls.size > maxVisibleTools) {
                                    Text(
                                        text = if (showAllTools) "Show less" else "Show ${toolCalls.size - maxVisibleTools} more",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    // itemsIndexed (positional key): tool ids can duplicate (history
                    // replay defaults them to "tc") — an explicit key would crash.
                    // v0.1.138: only render the first N (or all when expanded).
                    val cappedTools = if (showAllTools) toolCalls else toolCalls.take(maxVisibleTools)
                    itemsIndexed(cappedTools) { _, tc ->
                        ToolActivityRow(toolCall = tc, now = now)
                    }
                }
            }
            // Auto-scroll the panel to the newest activity.
            // v0.1.161: when thinking is the only item (no tool calls yet), it's
            // a single tall item at index 0; scrollToItem(0) pins its TOP, so new
            // text streaming past the 200.dp viewport scrolled off-screen and only
            // started tracking once a tool call added later items. In that
            // pure-thinking case scroll by the measured height minus the viewport
            // so its bottom edge is always visible (measured via BoxWithConstraints).
            LaunchedEffect(thinking.length, toolCalls.size, toolsVisible, showThinking, showAllTools) {
                val thinkingShown = showThinking && thinking.isNotBlank()
                val headerCount = (if (thinkingShown) 1 else 0) + (if (toolsVisible) 1 else 0)
                val count = (if (toolsVisible) visibleToolCount else 0) + headerCount
                if (count > 0) {
                    // Pure-thinking case: thinking is the only item and grows in place.
                    // v0.1.163: self-healing bottom-pin. scrollToItem(0) aligns the item
                    // TOP, so as a tall single item grows past the 200.dp viewport its new
                    // content below the fold scrolls off until something re-pins the bottom.
                    // The old code relied solely on scrollBy(thinkingHeight - viewportHeight);
                    // thinkingHeight (BoxWithConstraints minHeight) is authoritative but can go
                    // stale if the item isn't laid out every frame, so drift accumulated with no
                    // correction path — it only "recovered" when a tool call flipped to the other
                    // branch. Fix: after scrollToItem (which forces a layout pass), read the actual
                    // item bottom from live layoutInfo and scrollBy the overflow — the same proven
                    // pattern autoScrollToBottom() uses for the main list — then cross-check
                    // thinkingHeight so any residual frame drift self-corrects instead of piling up.
                    if (!toolsVisible && thinkingShown) {
                        listState.scrollToItem(0)
                        val layoutInfo = listState.layoutInfo
                        val viewportHeight = layoutInfo.viewportSize.height.toFloat()
                        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                        val overflow = if (lastVisible != null) {
                            (lastVisible.offset + lastVisible.size).toFloat() - viewportHeight
                        } else 0f
                        val bestOverflow = overflow.coerceAtLeast(
                            if (thinkingHeight > viewportHeight) thinkingHeight - viewportHeight else 0f
                        )
                        if (bestOverflow > 0f) {
                            listState.scrollBy(bestOverflow)
                        }
                    } else {
                        listState.scrollToItem(count - 1)
                    }
                }
            }
        }
    }
}

/** One compact tool row: icon · name · elapsed · spinner-or-check. */
@Composable
private fun ToolActivityRow(toolCall: UiToolCall, now: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = toolIcon(toolCall.toolName),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = toolCall.toolName,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatElapsed(toolCall.startedAt, now, toolCall.completed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        if (toolCall.completed) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF4CAF50),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Scrollable tool-call box shown above the finished answer (v0.1.68).
 * Compact rows; tap a row for the full card (args/diff/preview).
 */
@Composable
private fun ToolScrollBox(toolCalls: List<UiToolCall>) {
    // v0.1.120: each tool gets its own collapsible box — collapsed shows just the
    // one-line row (icon · name · elapsed · status), tap to expand for full
    // args/diff/result. A "show all" toggle at the bottom expands/collapses them
    // together so a long turn doesn't blow past the fold.
    val now by remember { mutableStateOf(System.currentTimeMillis()) }
    var detail by remember { mutableStateOf<UiToolCall?>(null) }
    var expandAll by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        // v0.1.95: tools box is a themeable extra surface
        color = LocalUiSurfaces.current.toolCard,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "TOOLS · ${toolCalls.size}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Column {
                toolCalls.forEachIndexed { index, tc ->
                    ToolCallCard(
                        toolCall = tc,
                        now = now,
                        expanded = expandAll,
                        onToggleDetail = { detail = tc },
                    )
                    // Divider between cards (not after the last one).
                    if (index < toolCalls.size - 1) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            // v0.1.120: expand/collapse-all toggle at the bottom.
            if (toolCalls.size > 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clickable { expandAll = !expandAll },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (expandAll) "Hide all" else "Show all",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (expandAll) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }

    detail?.let { tc ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(tc.toolName) },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 420.dp),
                ) {
                    if (tc.preview != null) {
                        Text(
                            text = "CALL",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(tc.preview, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (tc.args != null) {
                        Text(
                            text = "ARGS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = tc.args,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (tc.inlineDiff != null) {
                        DiffView(diffText = tc.inlineDiff)
                    } else if (tc.summary != null) {
                        Text(
                            text = "RESULT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(tc.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { detail = null }) { Text("Close") }
            },
        )
    }
}

/**
 * One collapsible tool card (v0.1.120): a one-line header row that taps to
 * expand/collapse. Expanded view reveals full Arguments / inline diff / Result,
 * matching the old ToolCallCard detail layout. [expanded] is an external toggle
 * so "show all" can open every card at once; individual taps still flip this one.
 */
@Composable
private fun ToolCallCard(
    toolCall: UiToolCall,
    now: Long,
    expanded: Boolean = false,
    onToggleDetail: () -> Unit,
) {
    var localExpanded by remember { mutableStateOf(false) }
    val isOpen = expanded || localExpanded
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (!isOpen) onToggleDetail() else localExpanded = !localExpanded
            },
    ) {
        // Collapsed: one-line header row (icon · name · elapsed · status).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = toolIcon(toolCall.toolName),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = toolCall.toolName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatElapsed(toolCall.startedAt, now, toolCall.completed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Spacer(Modifier.width(6.dp))
            if (toolCall.completed) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF4CAF50),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (isOpen) "▲" else "▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }

        // Expanded: full detail section (args / diff / result).
        AnimatedVisibility(visible = isOpen) {
            Column {
                if (!toolCall.args.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Arguments",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        color = LocalUiSurfaces.current.toolCard,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = toolCall.args,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(6.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }

                if (toolCall.preview?.isNotBlank() == true && toolCall.args.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Call",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = toolCall.preview!!,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (toolCall.inlineDiff != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Diff",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(2.dp))
                    DiffView(diffText = toolCall.inlineDiff!!)
                } else if (toolCall.completed && !toolCall.result.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Result",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        color = LocalUiSurfaces.current.toolCard,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = toolCall.result!!.take(500),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(6.dp),
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/**
 * Downscale an image URI to a Bitmap (<=1600px). Returns null on failure. Runs on
 * the caller's thread — decodes once, no second read later (so Coil never re-reads a
 * now-dead content URI and crashes on recompose). The base64 payload is derived from
 * this same Bitmap via bitmapToDataUrl().
 */
private fun downscaleAndDecode(context: Context, uri: Uri): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        var sample = 1
        val maxDim = 1600
        while ((bounds.outWidth / sample) > maxDim || (bounds.outHeight / sample) > maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
    } catch (_: Exception) {
        null
    }
}

/** Encode a decoded Bitmap as a base64 JPEG data URL for sending to the server. */
private fun bitmapToDataUrl(bmp: Bitmap): String {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    return "data:image/jpeg;base64,$b64"
}

/**
 * Collapsible agent task list card, pinned above the messages.
 * Collapsed: "Tasks 2/5" + active task + thin progress bar.
 * Expanded: full list with done / active (spinner) / pending states.
 */
@Composable
private fun TasksCard(
    todos: List<UiTodo>,
    expanded: Boolean,
    isStreaming: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val doneCount = todos.count { it.isDone }
    val active = todos.firstOrNull { it.isActive }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$doneCount/${todos.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (doneCount == todos.size && todos.isNotEmpty()) {
                            "All tasks done"
                        } else {
                            "Tasks"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isStreaming && active != null) {
                        Text(
                            text = active.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.ExpandLess
                    } else {
                        Icons.Filled.ExpandMore
                    },
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { if (todos.isEmpty()) 0f else doneCount.toFloat() / todos.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    todos.forEach { todo ->
                        TodoRow(todo)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoRow(todo: UiTodo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            todo.isDone -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Done",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            todo.isActive -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Icon(
                Icons.Outlined.RadioButtonUnchecked,
                contentDescription = "Pending",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = todo.content,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                todo.isDone -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                todo.isActive -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (todo.isActive) FontWeight.SemiBold else FontWeight.Normal,
            textDecoration = if (todo.status == "cancelled") TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Code block / fence wrapper with a copy button (v0.1.93). */
@Composable
private fun CopyableCodeBlock(model: MarkdownComponentModel) {
    CodeBlockShell(code = model.content, language = (model.extra["language"] as? String) ?: "code") {
        highlightedCodeBlock(model)
    }
}

@Composable
private fun CopyableCodeFence(model: MarkdownComponentModel) {
    CodeBlockShell(code = model.content, language = (model.extra["language"] as? String) ?: "code") {
        highlightedCodeFence(model)
    }
}

@Composable
private fun CodeBlockShell(code: String, language: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                // v0.1.95: code block header is a themeable extra surface
                .background(LocalUiSurfaces.current.codeBlock)
                .padding(start = 10.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                    Toast.makeText(context, "Copied code", Toast.LENGTH_SHORT).show()
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy code", modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy", style = MaterialTheme.typography.labelSmall)
            }
        }
        content()
    }
}

/** Short display name for a model id (v0.1.88). */
private fun shortModelName(model: String): String {
    // "deepseek/deepseek-v4-pro" → "v4-pro"; "deepseek-v4-flash" → "v4-flash"
    val last = model.substringAfterLast('/').substringAfterLast(':')
    return last.removePrefix("deepseek-").removePrefix("deepseek").take(24)
}

/** Reasoning effort label (v0.1.88; v0.1.116: off/minimal/xhigh added). */
private fun effortShort(effort: String): String = when (effort.lowercase()) {
    "off" -> "off"
    "minimal", "min" -> "min"
    "ultra" -> "ultra"
    "xhigh", "extreme" -> "xhigh"
    "high" -> "high"
    "medium", "med" -> "med"
    "low" -> "low"
    else -> effort.take(8)
}

/** All effort levels the picker offers, ordered quiet → loud. "off" disables thinking. */
private val EFFORT_OPTIONS = listOf("off", "minimal", "low", "medium", "high", "xhigh")

/** True when this effort string means "don't reason at all". */
internal fun effortIsOff(effort: String?): Boolean =
    effort?.lowercase() == "off"

/** Lowest non-off effort (used as the "thinking on" default). */
internal val DEFAULT_EFFORT = "low"

/**
 * Model + reasoning picker (v0.1.88). Reads model.options from the server,
 * shows providers grouped with their models; the selection persists and
 * applies to NEW sessions (desktop-composer contract — there is no
 * mid-conversation switch RPC yet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    viewModel: ChatViewModelContract,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<JsonRpcClient.ModelOptionsResult?>(null) }
    var selectedModel by remember { mutableStateOf("") }
    // Start from the session's current effort so "Apply to this chat" doesn't
    // silently reset reasoning to medium when the user only meant to switch model.
    var selectedEffort by remember { mutableStateOf(viewModel.uiState.currentReasoning ?: DEFAULT_EFFORT) }
    // Thinking on/off is driven by whether effort is "off" (off) vs any real level (on).
    var selectedThinkingOn by remember { mutableStateOf(!effortIsOff(viewModel.uiState.currentReasoning)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val opts = viewModel.loadModelOptions()
            options = opts
            selectedModel = opts.model ?: ""
            selectedEffort = viewModel.uiState.currentReasoning ?: selectedEffort
            selectedThinkingOn = selectedEffort.isBlank() || !effortIsOff(selectedEffort)
        } catch (e: Exception) {
            error = e.message ?: "Failed to load models"
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Model & Reasoning", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Apply to this chat, or save as the default for new chats.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            if (options == null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                options!!.providers.forEach { provider ->
                    if (provider.models.isEmpty()) return@forEach
                    Text(
                        text = provider.name ?: provider.slug,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    provider.models.forEach { model ->
                        val selected = model == selectedModel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                                .clickable { selectedModel = model }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = shortModelName(model),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // v0.1.116: Thinking on/off toggle — effort "off" disables reasoning;
                // any real level turns it on. Effort selector is disabled while off.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Thinking",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = selectedThinkingOn,
                        onCheckedChange = { on ->
                            selectedThinkingOn = on
                            // Pick a sensible effort when flipping on; keep the current
                            // level when flipping off (we just mark it off).
                            if (on && selectedEffort.isBlank()) selectedEffort = DEFAULT_EFFORT
                        },
                    )
                }

                if (selectedThinkingOn) {
                    Spacer(Modifier.height(6.dp))
                    Text("Effort", style = MaterialTheme.typography.labelMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        EFFORT_OPTIONS.filterNot { it == "off" }.forEach { effort ->
                            FilterChip(
                                selected = selectedEffort == effort,
                                onClick = { if (!effortIsOff(effort)) selectedEffort = effort },
                                label = { Text(effort.replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Thinking is off — the model won't reason before answering.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }

                Spacer(Modifier.height(16.dp))
                // v0.1.89: switch THIS chat immediately (slash commands)…
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            viewModel.applyModelToSession(selectedModel, selectedEffort, selectedThinkingOn)
                            saving = false
                            Toast.makeText(context, "Applied to this chat", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                    enabled = !saving && selectedModel.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (saving) "Applying…" else "Apply to this chat")
                }
                Spacer(Modifier.height(8.dp))
                // …and persist the pick for future chats.
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            saving = true
                            viewModel.saveModelPick(selectedModel, selectedEffort, selectedThinkingOn)
                            saving = false
                            Toast.makeText(context, "Saved — applies to new chats", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                    enabled = !saving && selectedModel.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save for new chats")
                }
            }
        }
    }
}

// ── ATTACH CHOOSER HELPERS (v0.1.127) ──
@Composable
private fun AttachOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CommandAckLine(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

private fun createCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "cam").apply { mkdirs() }
    val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun queryFileName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
} catch (_: Exception) {
    null
}

// ── WEBUI-STYLE CONTEXT RING (v0.1.124) ──
/** Circular token-usage gauge matching the WebUI composer. */
@Composable
private fun ContextRing(
    used: Long?,
    max: Long?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val fraction = if (max != null && max > 0 && used != null) {
        (used.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val pct = (fraction * 100f).roundToInt()
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val arcColor = when {
        fraction > 0.75f -> Color(0xFFEF5350)
        fraction > 0.5f -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = modifier
            .size(34.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft = Offset(inset, inset),
                size = arcSize,
            )
            if (fraction > 0f) {
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                )
            }
        }
        Text(
            text = if (max != null && max > 0) "$pct" else "·",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── CONTEXT DETAILS PANEL (WebUI) ──
// Tap the context ring → open a details card mirroring the WebUI ctx-indicator:
// % used, tokens in/out, estimated cost, and a compress affordance.
@Composable
private fun ContextDetailsPanel(
    used: Long?,
    max: Long?,
    usage: UiUsage?,
    isStreaming: Boolean,
    onCompress: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fraction = if (max != null && max > 0 && used != null) {
        (used.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    } else { 0f }
    val pct = (fraction * 100f).roundToInt()
    val promptTok = usage?.promptTokens ?: 0
    val completionTok = usage?.completionTokens ?: 0
    val cost = usage?.estimatedCostUsd
    val arcColor = when {
        fraction > 0.75f -> Color(0xFFEF5350)
        fraction > 0.5f -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.primary
    }
    val hasUsageData = promptTok > 0 || completionTok > 0 || cost != null
    val ringTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Context",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(16.dp))
                // Ring + percentage
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(64.dp)) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = 4.dp.toPx()
                            val inset = stroke / 2f
                            val arcSize = Size(size.width - stroke, size.height - stroke)
                            drawArc(
                                color = ringTrackColor,
                                startAngle = -90f, sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = stroke, cap = StrokeCap.Round),
                                topLeft = Offset(inset, inset), size = arcSize,
                            )
                            if (fraction > 0f) {
                                drawArc(
                                    color = arcColor,
                                    startAngle = -90f, sweepAngle = 360f * fraction,
                                    useCenter = false,
                                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                                    topLeft = Offset(inset, inset), size = arcSize,
                                )
                            }
                        }
                        Box(modifier = Modifier.align(Alignment.Center)) {
                            Text(
                                text = if (fraction > 0f) "$pct" else "·",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (fraction > 0f)
                                "Used ${pct}% of context" else "Context window",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (max != null && max > 0) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${formatTokens(used ?: 0)} / ${formatTokens(max)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                // Token breakdown
                if (hasUsageData) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DetailStat(
                            label = "In",
                            value = formatTokens(promptTok.toLong()),
                            icon = Icons.Outlined.South,
                        )
                        Spacer(Modifier.weight(1f))
                        DetailStat(
                            label = "Out",
                            value = formatTokens(completionTok.toLong()),
                            icon = Icons.Outlined.North,
                        )
                        Spacer(Modifier.weight(1f))
                        DetailStat(
                            label = "Cost",
                            value = if (cost != null) "$${String.format("%.4f", cost)}" else "—",
                            icon = Icons.Outlined.AttachMoney,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
                // Compress affordance (WebUI shows it when context is high)
                if (fraction >= 0.50f || hasUsageData) {
                    Button(
                        onClick = onCompress,
                        enabled = !isStreaming,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            Icons.Filled.Compress,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Compress context")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            fraction >= 0.75f -> "Context is nearly full — compress to free space."
                            fraction >= 0.50f -> "Half of context used. Compress to stay efficient."
                            else -> "Compress earlier turns to free context for a focused task."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "No context pressure yet. Usage will appear after your next message.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailStat(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

// ── INLINE CLARIFY CARD ──
// v0.1.134: renders a pending clarification INLINE above the composer (dsh-style)
// instead of a modal Dialog popup, so the question is always readable and the
// answer chips are selectable. The server sends either a single question with
// `choices`, or nothing — in which case we fall back to free text (plus a
// dismiss/Cancel that sends an empty string to unblock the turn).
@Composable
private fun InlineClarifyCard(
    pendingClarify: PendingClarify,
    onAnswer: (String) -> Unit,
) {
    var customAnswer by remember(pendingClarify.requestId) { mutableStateOf("") }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Needs your input",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // The actual question — always readable, never truncated.
            if (pendingClarify.question.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pendingClarify.question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Server-provided answer choices as selectable chips (if any).
            if (pendingClarify.choices.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                for (choice in pendingClarify.choices) {
                    AssistChip(
                        onClick = { onAnswer(choice) },
                        label = {
                            Text(
                                text = choice,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            // Free-text fallback + actions.
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = customAnswer,
                    onValueChange = { customAnswer = it },
                    placeholder = { Text("Type your answer…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    shape = RoundedCornerShape(10.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onAnswer("") }, // empty = dismiss/unblock the turn
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val answer = customAnswer.trim()
                        if (answer.isNotEmpty()) onAnswer(answer)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = customAnswer.isNotBlank(),
                ) {
                    Text("Send")
                }
            }
        }
    }
}
