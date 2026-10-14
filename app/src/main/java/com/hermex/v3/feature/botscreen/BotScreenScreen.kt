package com.hermex.v3.feature.botscreen

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Bot Screen pane — live view of the bot's Linux desktop (Tier 3).
 *
 * View-only by default; "Take over" grabs the input lease so pointer and
 * keystrokes drive the bot's desktop (login walls, 2FA, CAPTCHAs).
 * "Hand back" releases the lease so the agent's own automation resumes.
 */
@Composable
fun BotScreenScreen(onBack: () -> Unit) {
    val engine: BotScreenEngine = viewModel()
    val state = engine.state

    LaunchedEffect(Unit) { engine.connect() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Text("Bot Screen", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            when (state) {
                is BotScreenEngine.State.Takeover -> Text(
                    "YOU CONTROL",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
                is BotScreenEngine.State.Streaming -> Text(
                    "viewing",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelLarge,
                )
                else -> {}
            }
        }

        when (state) {
            is BotScreenEngine.State.Idle,
            is BotScreenEngine.State.Connecting -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Connecting to the bot desktop…")
                }
            }

            is BotScreenEngine.State.Error -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Not available", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { engine.connect() }) { Text("Retry") }
                }
            }

            is BotScreenEngine.State.Streaming,
            is BotScreenEngine.State.Takeover -> {
                ScreenCanvas(engine, takeover = state is BotScreenEngine.State.Takeover,
                    modifier = Modifier.weight(1f))
                Controls(engine, takeover = state is BotScreenEngine.State.Takeover)
            }
        }
    }
}

@Composable
private fun ScreenCanvas(engine: BotScreenEngine, takeover: Boolean, modifier: Modifier = Modifier) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }

    // Repaint when the engine decodes a new frame.
    LaunchedEffect(engine.frames) {
        val w0 = engine.width
        val h0 = engine.height
        if (w0 <= 0 || h0 <= 0) return@LaunchedEffect
        var b = bmp
        if (b == null || b.width != w0 || b.height != h0) {
            b = Bitmap.createBitmap(w0, h0, Bitmap.Config.ARGB_8888)
            bmp = b
        }
        if (engine.blitTo(b)) {
            image = b.asImageBitmap()
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(8.dp)
            .background(Color.Black, RoundedCornerShape(8.dp))
            .pointerInput(takeover) {
                if (!takeover) return@pointerInput
                detectTapGestures(
                    onPress = { offset ->
                        val nx = (offset.x / size.width).coerceIn(0f, 1f)
                        val ny = (offset.y / size.height).coerceIn(0f, 1f)
                        engine.pointer(nx, ny, 0x01) // left button down
                        tryAwaitRelease()
                        engine.pointer(nx, ny, 0x00) // release
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(img, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
            }
        } else {
            Text("waiting for first frame…", color = Color.White)
        }
    }
}

@Composable
private fun Controls(engine: BotScreenEngine, takeover: Boolean) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (takeover) {
                Button(onClick = { engine.handback() }) {
                    Icon(Icons.Outlined.Computer, null); Spacer(Modifier.width(6.dp)); Text("Hand back")
                }
            } else {
                FilledTonalButton(onClick = { engine.takeover() }) {
                    Icon(Icons.Outlined.Gesture, null); Spacer(Modifier.width(6.dp)); Text("Take over")
                }
            }
            TextButton(onClick = { engine.disconnect() }) { Text("Close") }
        }
        if (takeover) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("type for the bot…") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (text.isNotEmpty()) { engine.typeText(text); text = "" }
                }) { Text("Send") }
            }
        }
    }
}
