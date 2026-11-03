package com.hermex.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5.6: the math pre-transform. These are the acceptance tests promised when we
 * discussed stealing math rendering: "must render this, must not render that".
 * The must-not cases are the whole reason our earlier official-app comparison said
 * "glitchy" — currency and code must survive untouched.
 */
class MathTransformTest {

    @Test
    fun `currency stays prose (the dollar trap)`() {
        val s = "The breaker was \$5 CAD and the panel \$20 at Gescan, total \$145."
        assertEquals(s, MathTransform.toMarkdown(s))
    }

    @Test
    fun `dollar-adjacent text without math signal stays prose`() {
        val s = "I paid \$100 for the wire and \$50 for fittings."
        assertEquals(s, MathTransform.toMarkdown(s))
    }

    @Test
    fun `code fences are never touched`() {
        val s = "```kotlin\nval price = \"\$\$not math\$\$\"\nval m = 2 * \$x\$ \n```"
        assertEquals(s, MathTransform.toMarkdown(s))
    }

    @Test
    fun `inline code is never touched`() {
        val s = "use `\$\$x\$\$` as a literal"
        assertEquals(s, MathTransform.toMarkdown(s))
    }

    @Test
    fun `display block becomes a latex image and round-trips`() {
        val out = MathTransform.toMarkdown("before\n\n$$\\Delta_{eff} = (0.49 \\times 0.9) + 0.21$$\nafter")
        val url = Regex("""!\[math]\((latex:[^)]+)\)""").find(out)!!.groupValues[1]
        val latex = MathTransform.decodeLatex(url)!!
        assertTrue(latex.startsWith("\\Delta_{eff}"))
        assertTrue(out.startsWith("before"))
        assertTrue(out.contains("after"))
    }

    @Test
    fun `multi-line display block collapses to one image`() {
        val src = "$$\\sum_{l=1}^{L}\n\\left( \\frac{P_l}{n} + K_l \\right)$$"
        val out = MathTransform.toMarkdown(src)
        assertEquals(1, Regex("""!\[math]\(latex:""").findAll(out).count())
        val latex = MathTransform.decodeLatex(
            Regex("""!\[math]\((latex:[^)]+)\)""").find(out)!!.groupValues[1])!!
        assertTrue(latex.contains("\\sum"))
    }

    @Test
    fun `bracket display math also becomes an image`() {
        val out = MathTransform.toMarkdown("x \\[a_b = c\\] y")
        assertNotNull(MathTransform.decodeLatex(
            Regex("""!\[math]\((latex:[^)]+)\)""").find(out)!!.groupValues[1]))
    }

    @Test
    fun `inline math becomes readable code, unicode-cleaned`() {
        val out = MathTransform.toMarkdown("rate \$R_{eff} = B_w \\times f_{util}\$ here")
        assertTrue(out.contains("`"))
        assertTrue(out.contains("×"))                 // \times → ×
        assertTrue(out.contains("R_eff"))             // braces simplified
        assertFalse(out.contains("$"))
    }

    @Test
    fun `inline formula with frac and powers is readable`() {
        val out = MathTransform.toMarkdown("see \$\\frac{a}{b} = x^{2}\$ ok")
        assertTrue(out.contains("(a)/(b)"))
        assertTrue(out.contains("x^2"))
    }

    @Test
    fun `looksLikeMath guards the hot path`() {
        assertFalse(MathTransform.looksLikeMath("plain text with a \$ symbol even"))
        assertTrue(MathTransform.looksLikeMath("\$\$x=y\$\$"))
        assertTrue(MathTransform.looksLikeMath("the \$a_b = c\$ inline"))
    }

    @Test
    fun `decode rejects foreign urls`() {
        assertEquals(null, MathTransform.decodeLatex("https://example.com/x.png"))
    }

    @Test
    fun `user-style prose with a stray dollar pair is not mangled`() {
        // "$" pairs whose content has no math signal must survive.
        val s = "he said \$hello there\$ to me"
        assertEquals(s, MathTransform.toMarkdown(s))
    }
}
