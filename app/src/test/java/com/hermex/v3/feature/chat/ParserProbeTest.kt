package com.hermex.v3.feature.chat

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Test

class ParserProbeTest {
    private fun dump(label: String, md: String) {
        println("=== $label ===")
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(md)
        fun walk(n: org.intellij.markdown.ast.ASTNode, d: Int) {
            println("  ".repeat(d) + n.type + " [" + n.startOffset + ".." + n.endOffset + "] " +
                md.substring(n.startOffset, minOf(n.endOffset, md.length)).take(50).replace("\n", "\\n"))
            n.children.forEach { walk(it, d + 1) }
        }
        walk(tree, 0)
    }

    @Test
    fun `probe display math and escaped dollars`() {
        dump("display math", "before\n\n\$\$a^2 = b^2\$\$\nafter")
        dump("escaped currency", "cost \\$5 CAD and \\$20 total")
        dump("code span with math", "use `\$\$x\$\$` here")
    }
}
