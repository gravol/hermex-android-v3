package com.hermex.v3.feature.chat

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * v0.5.6: LaTeX math → renderer-friendly markdown. The mikepenz renderer has no math
 * support (upstream issue #240), so we pre-transform the text:
 *
 *  - display math ($$...$$ or \[...\]) becomes a standalone image paragraph with a
 *    `latex:<urlsafe-b64>` URL, which our custom `image` component typesets via
 *    JLaTeXMath.
 *  - inline math ($...$) becomes an inline code span with a light unicode cleanup —
 *    monospace but readable, and immune to breaking the paragraph's text flow.
 *
 * The $-trap (currency) is the whole discipline: "$5 CAD and $20" must stay prose.
 * Fenced code blocks and `inline code` are never transformed.
 *
 * Order matters: fence split → display collapse → inline replace. Doing inline first
 * would consume the inner $ of a $$ pair and leave the block un-collapsible.
 */
object MathTransform {

    /** Cheap pre-check so plain messages never pay for the transform. */
    fun looksLikeMath(src: String): Boolean =
        src.contains("$$") || src.contains("\\[") || MATH_INLINE.containsMatchIn(src)

    private val MATH_INLINE = Regex("""\$[^$\d\n][^$\n]{0,400}?[^$\s]\$""")

    fun toMarkdown(src: String): String {
        val parts = splitFenced(src) // even indices outside fences
        for (i in parts.indices step 2) {
            parts[i] = processOutsideFences(parts[i])
        }
        return parts.joinToString("")
    }

    /** Outside-fence work, in order: split on `code` spans, then display collapse and
     *  inline replace only in the non-code segments (even). Doing display first inside a
     *  segment is safe because inline matching cannot consume a `$$` pair itself. */
    private fun processOutsideFences(part: String): String {
        val segs = part.split("`").toMutableList()
        for (j in segs.indices step 2) {
            segs[j] = replaceInline(displayMath(segs[j]))
        }
        return segs.joinToString("`")
    }

    /** Split on ```/~~~ fence lines, keeping everything (markers and newlines) verbatim. */
    private fun splitFenced(src: String): MutableList<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inFence = false
        var i = 0
        while (i < src.length) {
            val nl = src.indexOf('\n', i)
            val lineEnd = if (nl == -1) src.length else nl + 1 // include the newline
            val line = src.substring(i, lineEnd)
            val isFence = line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")
            if (isFence) {
                if (!inFence) { out.add(sb.toString()); sb.setLength(0) }
                sb.append(line); inFence = !inFence
                if (!inFence) { out.add(sb.toString()); sb.setLength(0) }
            } else {
                sb.append(line)
            }
            i = lineEnd
        }
        if (inFence) { // unterminated fence: rest of the message is code
            out.add(""); out.add(sb.toString())
        } else {
            out.add(sb.toString())
        }
        return out
    }

    private fun displayMath(text: String): String {
        var t = Regex("""\$\$([\s\S]{1,2000}?)\$\$""").replace(text) { m ->
            imageFor(m.groupValues[1].trim())
        }
        t = Regex("""(\\\[)([\s\S]{1,2000}?)(\\\])""").replace(t) { m ->
            imageFor(m.groupValues[2].trim())
        }
        return t
    }

    /** Replace $...$ math with code spans (already outside any code region). */
    private fun replaceInline(text: String): String =
        MATH_INLINE.replace(text) { m ->
            val content = m.value.removeSurrounding("$")
            if (isMathy(content)) "`${prettyInline(content)}`" else m.value
        }

    private val MATHY = Regex("""^[A-Za-z0-9{}\\^_+*/=().,%$\[\]|&:<>~!#;\-\\ ]+$""")

    /** Currency guard: content must not start with a digit and must contain at least
     *  one math signal (backslash command, sub/superscript, braces, or an operator
     *  between operands), so "$5 CAD and $20" stays prose. */
    fun isMathy(content: String): Boolean {
        if (content.isEmpty() || content.first().isDigit()) return false
        if (!MATHY.matches(content)) return false
        val signal = content.any { it in "\\^_{}≤≥±" } ||
            Regex("""[A-Za-z0-9)\]]\s*[+*/-]\s*[A-Za-z0-9(\\$]""").containsMatchIn(content)
        return signal
    }

    private val REPLACEMENTS = listOf(
        "\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\approx" to "≈",
        "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥",
        "\\pm" to "±", "\\rightarrow" to "→", "\\leftarrow" to "←", "\\to" to "→",
        "\\infty" to "∞", "\\Delta" to "Δ", "\\delta" to "δ", "\\alpha" to "α",
        "\\beta" to "β", "\\pi" to "π", "\\sum" to "Σ", "\\sqrt" to "√",
        "\\left" to "", "\\right" to "", "\\," to " ", "\\;" to " ", "\\ " to " ",
    )

    private fun prettyInline(content: String): String {
        var c = content
        for ((from, to) in REPLACEMENTS) c = c.replace(from, to)
        c = Regex("""\\frac\{([^{}]*)\}\{([^{}]*)\}""").replace(c) { "(${it.groupValues[1]})/(${it.groupValues[2]})" }
        c = c.replace(Regex("""\^\{([^{}]+)\}"""), "^$1").replace(Regex("_\\{([^{}]+)\\}"), "_$1")
        return c.trim()
    }

    private fun imageFor(latex: String): String {
        val b64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(latex.toByteArray(StandardCharsets.UTF_8))
        return "\n\n![math](latex:$b64)\n\n"
    }

    /** Decoder used by the image component; null for anything not ours. */
    fun decodeLatex(url: String): String? {
        if (!url.startsWith("latex:")) return null
        return runCatching {
            String(Base64.getUrlDecoder().decode(url.removePrefix("latex:")), StandardCharsets.UTF_8)
        }.getOrNull()
    }
}
