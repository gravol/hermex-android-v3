package com.hermex.v3.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * v0.5.7: the second half of math rendering (see MathTransform for the first half).
 *
 * The parser (org.jetbrains markdown 0.7.3 via mikepenz 0.34) natively tokenizes
 * $...$ / $$...$$ as GFM INLINE_MATH/BLOCK_MATH/DOLLAR nodes — and the renderer's
 * default annotator has no case for them, so they are SILENTLY DROPPED from text.
 * That is what ate the currency in "cost $5 CAD and $20" even though MathTransform
 * correctly left it alone: the parser paired the dollars itself and the renderer
 * dropped the pair.
 *
 * Fix 1 (annotator): intercept those tokens and emit their raw source text.
 * Real math never reaches this path, because MathTransform has already converted
 * display math to `![math](latex:…)` and inline math to readable code spans —
 * anything STILL tokenized as math is literal text (usually currency), so
 * emitting it verbatim is the correct behavior.
 *
 * Fix 2 (transformer): display-math images inside paragraphs do NOT go through
 * the `components.image` hook — the text pipeline routes inline images to an
 * InlineTextContent placeholder fed by `ImageTransformer`, whose library default
 * is a NO-OP (all markdown inline images were already invisible; that was the
 * blank gap in v0.5.6). We provide a transformer that resolves `latex:` URLs to
 * MathImages bitmaps and returns null for anything else — identical to the NoOp
 * default behavior the app has always had for other schemes.
 */

/** Intercepts math tokens and re-emits them as literal text. Returning true = handled. */
internal val mathAnnotate: (androidx.compose.ui.text.AnnotatedString.Builder, String, ASTNode) -> Boolean =
    { builder, content, node ->
    when (node.type) {
        GFMElementTypes.INLINE_MATH, GFMElementTypes.BLOCK_MATH, GFMTokenTypes.DOLLAR -> {
            builder.append(content.substring(node.startOffset, node.endOffset))
            true
        }
        else -> false
    }
}

fun mathAnnotator(): MarkdownAnnotator = markdownAnnotator(annotate = mathAnnotate)

/**
 * ImageTransformer for the `latex:` scheme. The bitmap's intrinsic size drives the
 * placeholder (the library's default placeholderConfig then fits it to the
 * container width). Non-math URLs return null, matching the NoOp default.
 */
@Composable
fun mathImageTransformer(): ImageTransformer = remember {
    object : ImageTransformer {
        @Composable
        override fun transform(link: String): ImageData? {
            android.util.Log.d("HermexMath", "transform link=${link.take(24)}… len=${link.length}")
            val latex = MathTransform.decodeLatex(link) ?: run {
                android.util.Log.d("HermexMath", "decode failed for ${link.take(16)}")
                return null
            }
            val color = LocalMarkdownColors.current.text.toArgb()
            val basePx = with(LocalDensity.current) {
                LocalMarkdownTypography.current.text.fontSize.toPx() * 1.25f
            }
            var bmp by remember(latex, color) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(latex, color, basePx) {
                // maxWidth unbounded: the library's placeholder fit downscales to the container.
                bmp = MathImages.render(latex, basePx, color, Int.MAX_VALUE)
                android.util.Log.d("HermexMath", "render latex=${latex.take(40)} basePx=$basePx -> ${bmp?.let { "${it.width}x${it.height}" } ?: "NULL"}")
            }
            val bitmap = bmp ?: return null
            return ImageData(
                painter = BitmapPainter(bitmap),
                contentDescription = "formula",
                modifier = Modifier,
                alignment = Alignment.Center,
                contentScale = ContentScale.Fit,
            )
        }
    }
}
