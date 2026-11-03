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
                if (drawable.intrinsicWidth < 4 || drawable.intrinsicHeight < 4) {
                    // JLaTeXMath accepts some broken input by drawing nothing at all —
                    // treat that as a failure so the user sees the source, not a gap.
                    android.util.Log.w("HermexMath", "empty drawable for [$latex]")
                    return@withContext rawFallback(latex, textSizePx, colorArgb)
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
            } catch (e: Throwable) {
                android.util.Log.w("HermexMath", "render failed for [$latex]: $e")
                rawFallback(latex, textSizePx, colorArgb) // never blank: show the source
            }
        }
    }

    /** Failure is allowed to be seen: paint the raw source monospace so the user can
     *  copy it; only a truly degenerate string returns null. */
    private fun rawFallback(latex: String, textSizePx: Float, colorArgb: Int): ImageBitmap? = try {
        val paint = android.graphics.Paint().apply {
            color = colorArgb
            textSize = textSizePx.coerceAtMost(28f)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val src = latex.replace('\n', ' ').take(300)
        val lines = src.chunked(64)
        val w = (0 until lines.size).maxOf { i -> paint.measureText(lines[i]) }
        val h = lines.size * paint.fontSpacing
        val bmp = Bitmap.createBitmap(w.toInt() + 12, h.toInt() + 12, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        lines.forEachIndexed { i, line -> c.drawText(line, 6f, paint.fontSpacing * (i + 1), paint) }
        bmp.asImageBitmap()
    } catch (_: Throwable) {
        null
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
