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

    @Test
    fun `the full decoy paragraph survives intact`() {
        val para = ("**And two decoys that must stay plain text**, not render: the "
            + "panel cost **\$5 CAD and \$20** at Gescan for a **total \$145**, and "
            + "code stays code: `\$\$x\$\$` inline, plus a fenced block:")
        assertEquals(para, MathTransform.toMarkdown(para))
    }

    @Test
    fun `inline math paragraph is converted to code spans, display untouched otherwise`() {
        val para = ("An inline one: effective refresh rate is "
            + "\$R_{eff} = B_w \\times f_{util} + A_w\$ where \$R_{eff} \\leq B_w + A_w\$.")
        val out = MathTransform.toMarkdown(para)
        // both inline spans converted, nothing else changed
        assertTrue(out.contains("R_eff = B_w × f_util + A_w"))
        assertTrue(out.contains("R_eff ≤ B_w + A_w"))
        assertTrue(out.startsWith("An inline one:"))
    }

    @Test
    fun `exact test message renders the designed output`() {
        val src = "Here's your test reply — this message itself is the test. It contains:" + "\n" +
            "" + "\n" +
            "- A display equation: \$\$\\text{bytes/token} = \\sum_{l=1}^{L} \\left( \\frac{P_l \\cdot n_{active}}{n_{total}} + K_l + V_l \\right)\$\$" + "\n" +
            "- An inline one: effective refresh rate is \$R_{eff} = B_w \\times f_{util} + A_w\$ where \$R_{eff} \\leq B_w + A_w\$." + "\n" +
            "- A multi-line display block that should collapse into one rendered equation:" + "\n" +
            "" + "\n" +
            "\$\$\\Delta_{eff} = (0.49 \\times 0.9) + 0.21" + "\n" +
            "= 0.651\$\$" + "\n" +
            "" + "\n" +
            "- And two decoys that must **stay plain text, not render**: the panel cost **\$5 CAD and \$20** at Gescan for a **total \$145**, and code stays code: `\$\$x\$\$` inline, plus a fenced block:" + "\n" +
            "" + "\n" +
            "```" + "\n" +
            "\$\$this should stay raw\$\$" + "\n" +
            "val price = \"\$\$not math\$\$\"" + "\n" +
            "```" + "\n" +
            "" + "\n" +
            "If the two big equations show as typeset math, the inline ones show as clean text like `R_eff = B_w × f_util + A_w`, and the dollar amounts + code block look exactly as written — it's all working."
        val out = MathTransform.toMarkdown(src)
        // exactly 2 images: the two display equations. The fenced $$…$$ and the
        // `$$x$$` inline-code decoy must NOT become images, and currency stays prose.
        val imgs = Regex("""!\[math]\(latex:""").findAll(out).toList()
        assertEquals("IMG POSITIONS: " + imgs.map { it.range }, 2, imgs.size)
        assertTrue(out.contains("R_eff = B_w \u00d7 f_util + A_w"))
        assertTrue(out.contains("R_eff \u2264 B_w + A_w"))
        val decoy = src.substringAfter("and code stays code: ").substringBefore(" inline")
        assertTrue("inline-code decoy mangled: " + out.substringAfter("Gescan").take(120),
            out.contains(decoy))
        val fencedLine = src.substringAfter("```\n").substringBefore("\nval price")
        assertTrue("fence mangled: " + out.substringAfter("```\n").take(60), out.contains(fencedLine))
    }
}
