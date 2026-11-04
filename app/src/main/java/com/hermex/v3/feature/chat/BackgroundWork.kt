package com.hermex.v3.feature.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermex.v3.di.AppModule
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * v0.7.0: Background Work — the official-app affordance for the session's
 * background terminal tasks. A chip above the composer counts what's running
 * (polled `process.list`); tapping it opens a sheet listing every task with a
 * stop control. `process.list`/`process.kill` are live-session RPCs, so the
 * poller resumes with omit_messages first and targets the LIVE sid — the same
 * authority dance the Children sheet uses.
 */

private val runningStatuses = setOf("running", "starting", "pending")

data class BgProc(
    val id: String,          // the process's own id (registry session_id)
    val command: String,
    val status: String,
    val uptimeSeconds: Int,
    val exitCode: Int?,
    val outputPreview: String,
) {
    val running: Boolean get() = status.lowercase() in runningStatuses
}

private fun parseProcs(payload: JsonObject): List<BgProc> = try {
    payload["processes"]?.jsonArray?.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o["session_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        BgProc(
            id = id,
            command = o["command"]?.jsonPrimitive?.contentOrNull ?: "",
            status = o["status"]?.jsonPrimitive?.contentOrNull ?: "running",
            uptimeSeconds = o["uptime_seconds"]?.jsonPrimitive?.intOrNull ?: 0,
            exitCode = o["exit_code"]?.jsonPrimitive?.intOrNull,
            outputPreview = o["output_preview"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }.orEmpty()
} catch (_: Exception) {
    emptyList()
}

/** Polls process.list for the session; call [refresh] to force an immediate read. */
@Composable
fun rememberBgTasks(sessionDbKey: String, enabled: Boolean): Pair<List<BgProc>, () -> Unit> {
    val scope = rememberCoroutineScope()
    var procs by remember(sessionDbKey) { mutableStateOf<List<BgProc>>(emptyList()) }
    var pollJob by remember(sessionDbKey) { mutableStateOf<Job?>(null) }

    fun fetch() {
        pollJob?.cancel()
        pollJob = scope.launch {
            try {
                val rpc = AppModule.provideRpcClient()
                val resume = rpc.sessionResume(sessionDbKey, omitMessages = true)
                val liveSid = resume.session_id.ifBlank { sessionDbKey }
                procs = parseProcs(rpc.processList(liveSid))
            } catch (_: Exception) {
                // Transient (session reaped, network) — keep the last snapshot.
            }
        }
    }

    val refresh = { fetch() }
    DisposableEffect(sessionDbKey, enabled) {
        if (enabled) {
            fetch()
            val ticker = scope.launch {
                while (isActive) {
                    delay(8_000)
                    fetch()
                }
            }
            onDispose { ticker.cancel(); pollJob?.cancel() }
        } else {
            onDispose { pollJob?.cancel() }
        }
    }
    return procs to refresh
}

/** Kill one background task (resume for the live sid, then process.kill). */
suspend fun killBgTask(sessionDbKey: String, procId: String) {
    try {
        val rpc = AppModule.provideRpcClient()
        val resume = rpc.sessionResume(sessionDbKey, omitMessages = true)
        val liveSid = resume.session_id.ifBlank { sessionDbKey }
        rpc.processKill(liveSid, procId)
    } catch (_: Exception) {
        // The next poll reflects the truth either way.
    }
}

@Composable
fun BackgroundTaskChip(count: Int, onClick: () -> Unit) {
    // The official app's animated "Running N background task" pill. The icon
    // spins while anything runs — the only honest animation is a busy one.
    val transition = rememberInfiniteTransition(label = "bgspin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.GraphicEq,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(angle),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Running $count background task${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open background work",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundWorkSheet(
    procs: List<BgProc>,
    onKill: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Background Work",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Background Tasks",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (procs.isEmpty()) {
                        Text(
                            text = "No background tasks for this session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                    procs.forEachIndexed { idx, p ->
                        if (idx > 0) {
                            androidx.compose.material3.HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                        BgTaskRow(p, onKill)
                    }
                }
            }
        }
    }
}

@Composable
private fun BgTaskRow(p: BgProc, onKill: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(
            text = p.command,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (p.running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Running",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Stop this one task (process.kill) — red circle, official style.
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color(0xFFB3261E))
                        .clickable { onKill(p.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White),
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = Color(0xFF43A047),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        p.exitCode != null && p.exitCode != 0 -> "Exited (${p.exitCode})"
                        else -> "Finished"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (p.outputPreview.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = p.outputPreview,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 10.sp,
                ),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

/**
 * v0.7.0: minimal finger-drawing surface (the official menu's "Drawing" entry).
 * Strokes are kept as point lists so the save path can replay them onto a real
 * Bitmap at full resolution; the preview canvas scales points by a stored factor.
 */
@Composable
fun DrawingDialog(onDismiss: () -> Unit, onSave: (android.graphics.Bitmap) -> Unit) {
    val canvasW = 1080
    val canvasH = 1440
    val strokes = remember { androidx.compose.runtime.mutableStateListOf<Pair<List<Offset>, Float>>() }
    var current by remember { mutableStateOf(listOf<Offset>()) }
    var strokeW by remember { mutableStateOf(10f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Drawing") },
        text = {
            Column {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 320.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .pointerInput(strokeW) {
                            detectDragGestures(
                                onDragStart = { pos -> current = listOf(pos) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    current = current + change.position
                                },
                                onDragEnd = {
                                    if (current.size > 1) strokes.add(current to strokeW)
                                    current = emptyList()
                                },
                            )
                        },
                ) {
                    val sx = size.width / canvasW
                    val sy = size.height / canvasH
                    fun drawStroke(points: List<Offset>, w: Float, color: Color) {
                        if (points.size < 2) return
                        val path = Path()
                        path.moveTo(points[0].x * sx, points[0].y * sy)
                        points.drop(1).forEach { path.lineTo(it.x * sx, it.y * sy) }
                        drawPath(path, color, style = Stroke(width = w * sx, cap = StrokeCap.Round))
                    }
                    strokes.forEach { (pts, w) -> drawStroke(pts, w, Color.DarkGray) }
                    drawStroke(current, strokeW, Color.DarkGray)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Width", style = MaterialTheme.typography.labelMedium)
                    androidx.compose.material3.Slider(
                        value = strokeW,
                        onValueChange = { strokeW = it },
                        valueRange = 4f..40f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    )
                    IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) {
                        Icon(Icons.Outlined.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { strokes.clear(); current = emptyList() }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val bmp = android.graphics.Bitmap.createBitmap(canvasW, canvasH, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                c.drawColor(android.graphics.Color.WHITE)
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    style = android.graphics.Paint.Style.STROKE
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    isAntiAlias = true
                }
                (strokes + (if (current.size > 1) listOf(current to strokeW) else emptyList()))
                    .forEach { (pts, w) ->
                        if (pts.size < 2) return@forEach
                        val p = android.graphics.Path()
                        p.moveTo(pts[0].x, pts[0].y)
                        pts.drop(1).forEach { p.lineTo(it.x, it.y) }
                        paint.strokeWidth = w
                        c.drawPath(p, paint)
                    }
                onSave(bmp)
            }) { Text("Attach") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
