package com.hermex.v3.feature.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.hermex.v3.feature.chat.mathAnnotate
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5.7 acceptance spec, end to end minus pixels:
 *   MathTransform -> real parser -> real annotator -> AnnotatedString.
 *
 * The regression this pins: the parser's native math tokens were silently dropped
 * by the library annotator, which ate currency ("cost $5 CAD and $20" lost its
 * dollars) and left display math invisible. After the fix, currency text survives
 * verbatim and display math reaches the image pipeline as `latex:` placeholders.
 */
class MathPipelineTest {

    private val flavour = GFMFlavourDescriptor()

    private fun annotateThroughLibrary(md: String): AnnotatedString {
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(md)
        val settings = DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(SpanStyle()),
            codeSpanStyle = SpanStyle(),
            annotator = markdownAnnotator(annotate = mathAnnotate),
            referenceLinkHandler = null,
            linkInteractionListener = null,
        )
        val sb = AnnotatedString.Builder()
        tree.children.forEach { child ->
            if (child.type == MarkdownElementTypes.PARAGRAPH) {
                sb.buildMarkdownAnnotatedString(md, child.children, settings)
            }
        }
        return sb.toAnnotatedString()
    }

    @Test
    fun `currency survives the full pipeline`() {
        val md = MathTransform.toMarkdown(
            "the panel cost **\$5 CAD and \$20** at Gescan for a **total \$145**"
        )
        val text = annotateThroughLibrary(md).text
        assertTrue("currency was eaten: $text", text.contains("\$5 CAD and \$20"))
        assertTrue(text.contains("total \$145"))
    }

    @Test
    fun `display math reaches the image pipeline as latex placeholder`() {
        val md = MathTransform.toMarkdown("x\n\n\$\$a^2 = b^2\$\$")
        val annotated = annotateThroughLibrary(md)
        // The IMAGE inline-content placeholder carries the latex: URL (base64-wrapped body).
        assertTrue("no latex placeholder produced: ${annotated.text}",
            annotated.text.contains("latex:"))
    }

    @Test
    fun `inline real math becomes readable code, not a dropped token`() {
        val md = MathTransform.toMarkdown("rate is \$R_{eff} = B_w \\times f_{util}\$ ok")
        val text = annotateThroughLibrary(md).text
        assertTrue(text.contains("R_eff = B_w × f_util"))
    }

    @Test
    fun `fenced math stays raw in the parse tree`() {
        // Code fences never reach the annotator (they render as code components);
        // pin that the tree keeps fence content verbatim.
        val md = MathTransform.toMarkdown("```\n\$\$raw\$\$\n```")
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(md)
        val flat = StringBuilder()
        fun walk(n: org.intellij.markdown.ast.ASTNode) {
            if (n.type == org.intellij.markdown.MarkdownElementTypes.CODE_FENCE) {
                flat.append(md.substring(n.startOffset, n.endOffset))
            }
            n.children.forEach(::walk)
        }
        walk(tree)
        assertTrue("fence lost raw math", flat.toString().contains("\$\$raw\$\$"))
    }

    @Test
    fun `no paragraph emits the silent-drop marker`() {
        // Before the fix INLINE_MATH produced nothing at all; guard that nothing
        // in the mixed test message disappears this time.
        val src = "cost \$5 CAD and \$20 total and \$R_{eff} = x\$ inline"
        val md = MathTransform.toMarkdown(src)
        val text = annotateThroughLibrary(md).text
        assertTrue(text.contains("cost \$5 CAD and \$20 total"))
        assertTrue(text.contains("R_eff = x"))
        assertFalse(text.contains("INLINE"))
    }
}
