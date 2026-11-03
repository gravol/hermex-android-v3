package com.hermex.v3.feature.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * v0.5.6: LaTeX display-math bitmaps for the chat's image hook. Rendering runs off the
 * main thread and is cached per (latex, color, size) — the same equation repeated across
 * turns costs nothing, and scrolling never re-renders.
 *
 * Wide equations shrink by re-typesetting at smaller text sizes (crisper than scaling a
 * bitmap), down to ~55% before we give up and return the widest fit.
 */
object MathImages {

    private const val MAX_ENTRIES = 60
    private val cache = object : LruCache<String, ImageBitmap>(MAX_ENTRIES) {
        override fun sizeOf(key: String, value: ImageBitmap) = 1
    }

    /** Returns null if the LaTeX is invalid (callers show the raw source instead). */
    suspend fun render(
        latex: String,
        textSizePx: Float,
        colorArgb: Int,
        maxWidthPx: Int,
    ): ImageBitmap? {
        val key = "$colorArgb|${textSizePx.toInt()}|$maxWidthPx|$latex"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                var px = textSizePx
                var drawable = build(latex, px, colorArgb)
                var guard = 0
                while (drawable.intrinsicWidth > maxWidthPx && px > textSizePx * 0.55f && guard < 6) {
                    px *= 0.85f
                    drawable = build(latex, px, colorArgb)
                    guard++
                }
                val bmp = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtMost(maxWidthPx),
                    drawable.intrinsicHeight,
                    Bitmap.Config.ARGB_8888,
                )
                drawable.draw(Canvas(bmp))
                val out = bmp.asImageBitmap()
                cache.put(key, out)
                out
            } catch (_: Throwable) {
                null // malformed LaTeX: caller falls back to the raw source text
            }
        }
    }

    private fun build(latex: String, textSizePx: Float, colorArgb: Int): JLatexMathDrawable =
        JLatexMathDrawable.builder(latex)
            .textSize(textSizePx)
            .color(colorArgb)
            .padding(6)
            .align(JLatexMathDrawable.ALIGN_CENTER)
            .fitCanvas(true)
            .build()
}
