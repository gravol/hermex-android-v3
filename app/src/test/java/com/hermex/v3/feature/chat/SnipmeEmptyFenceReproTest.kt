package com.hermex.v3.feature.chat

import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Test

/**
 * Repro for the 2026-10-20 crash storm (bug report 79d693d68801): two
 * java_crash NoSuchElementException events on v0.1.897(43), and an ANR whose
 * main thread sat in snipme CodeAnalyzer inside CopyableCodeFence. Theory:
 * a mid-stream empty/partial ``` fence goes through snipme's language
 * auto-analysis and throws NoSuchElementException.
 */
class SnipmeEmptyFenceReproTest {

    private fun analyze(code: String): Result<Int> = runCatching {
        // language unset => CodeAnalyzer auto-analysis (mikepenz default path)
        Highlights.Builder().code(code).build().getHighlights().size
    }

    @Test fun emptyFence() { println("REPRO empty -> " + analyze("")) }

    @Test fun newlineOnlyFence() { println("REPRO newline -> " + analyze("\n")) }

    @Test fun whitespaceFence() { println("REPRO spaces -> " + analyze("   ")) }

    @Test fun taggedEmptyFence() {
        val r = runCatching {
            Highlights.Builder().language(SyntaxLanguage.KOTLIN).code("").build().getHighlights().size
        }
        println("REPRO tagged-empty -> $r")
    }

    @Test fun partialStreamingFence() { println("REPRO partial -> " + analyze("def f(x):\n    re")) }

    @Test fun untaggedRealCode() { println("REPRO untagged-real -> " + analyze("SELECT * FROM t WHERE x=1")) }
}

class SnipmeUnknownLanguageReproTest {
    @Test fun unknownLanguageTag() {
        val r = runCatching { SyntaxLanguage.getByName("brainfuck") }
        println("REPRO byname-unknown -> $r")
        val ok = runCatching { SyntaxLanguage.getByName("python") }
        println("REPRO byname-python -> ${ok.isSuccess}")
    }
}
