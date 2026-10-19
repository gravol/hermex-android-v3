package com.hermex.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The journal reload at startup must cost proportional to the byte budget, not to the file size.
 *
 * Why: `readTail` used to be `f.readLines()` with the budget applied afterwards — so the whole previous
 * session's journal was read and split on the MAIN THREAD from `Application.onCreate`. With rotation at
 * 2 MB that was never noticed; after v0.1.894 raised the ceiling for debug/harness builds the Pixel
 * carried a 64 MB + 6.3 MB pair and startup blocked past 15 s, so the system ANR-killed the app before
 * MainActivity drew ("failed to complete startup", three launches in a row). These tests are the guard.
 */
class DebugLogJournalReloadTest {

    private val tmpDir = createTmpDir()

    private fun createTmpDir(): File =
        File(System.getProperty("java.io.tmpdir"), "hermex-journal-test-${System.nanoTime()}")
            .apply { mkdirs(); deleteOnExit() }

    /** Journal-shaped lines: `ts|LEVEL|tag|message`, ts ascending so order is checkable. */
    private fun writeJournal(name: String, lines: Int, padTo: Int = 40): File {
        val f = File(tmpDir, name)
        f.bufferedWriter().use { w ->
            for (i in 1..lines) {
                val msg = "line $i".padEnd(padTo, 'x')
                w.write("${1_700_000_000_000L + i}|INFO|Test|$msg\n")
            }
        }
        return f
    }

    /** The line number encoded in a journal entry's message ("line 42xxx"). */
    private fun lineNumberOf(e: DebugLog.Entry): Long =
        e.message.removePrefix("line ").takeWhile { it.isDigit() }.toLong()

    @Test fun readingATailTouchesTheBudgetNotTheWholeFile() {
        val f = writeJournal("big.log", lines = 60_000, padTo = 60)     // ~4 MB
        val budget = 8_192L
        val entries = DebugLog.readTail(f, budget)

        assertTrue("file should be much larger than the budget for this test to mean anything",
            f.length() > 20 * budget)
        assertTrue(
            "measured bytes read (${DebugLog.lastTailBytesTouched}) must stay inside the budget $budget — " +
                "a whole-file read is exactly the startup ANR this guards",
            DebugLog.lastTailBytesTouched in 1..budget)
        assertTrue("expected roughly budget/line bytes of entries, got ${entries.size}",
            entries.isNotEmpty() && entries.size.toLong() * 70L <= budget * 2)
        assertEquals("the last entry must be the file's last line", 60_000L, lineNumberOf(entries.last()))
        assertEquals("and the first must be exactly `size - count + 1`: the newest block, oldest first",
            (60_000 - entries.size + 1).toLong(), lineNumberOf(entries.first()))
    }

    @Test fun entriesComeBackOldestFirstAndStopAtTheEndOfTheFile() {
        val f = writeJournal("order.log", lines = 5_000, padTo = 60)
        val entries = DebugLog.readTail(f, 20_000)
        assertTrue(entries.size in 1..5_000)
        val ts = entries.map { it.timestamp }
        assertEquals("timestamps must be ascending (oldest first)", ts.sorted(), ts)
        assertEquals("the last entry must be the last line of the file",
            1_700_000_005_000L, ts.last())
    }

    @Test fun aSeekIntoAMiddleOfALineDropsThatPartialLineInsteadOfGarbage() {
        val f = writeJournal("midline.log", lines = 400, padTo = 100)
        // Any budget that is not a whole number of lines forces the seek into the middle of one.
        val entries = DebugLog.readTail(f, 3_333)
        assertTrue(entries.isNotEmpty())
        for (e in entries) {
            assertTrue("every returned entry must parse cleanly: ${e.message.take(20)}",
                e.timestamp > 0 && e.level == "INFO" && e.tag == "Test")
        }
    }

    @Test fun parsingNeverExceedsTheInMemoryRingSize() {
        val f = writeJournal("ring.log", lines = 9_000, padTo = 8)      // small lines, big count
        val entries = DebugLog.readTail(f, 6_000_000)                    // generous budget
        assertTrue("ring holds 4000; nothing older can be shown anyway, got ${entries.size}",
            entries.size <= 4_000)
        assertEquals("and what it keeps must be the newest",
            1_700_000_009_000L, entries.last().timestamp)
    }

    @Test fun zeroBudgetAndEmptyFileBothYieldNothing() {
        val empty = writeJournal("empty.log", lines = 0)
        assertEquals(emptyList<DebugLog.Entry>(), DebugLog.readTail(empty, 5_000))
        assertEquals("an empty file touches nothing", 0L, DebugLog.lastTailBytesTouched)

        // DebugLog is a singleton, so check the zero-budget path against a marker set in this same test:
        // it must return nothing AND leave the seam exactly where it was (no read happened).
        val g = writeJournal("some.log", lines = 10)
        DebugLog.readTail(g, 64)
        val marker = DebugLog.lastTailBytesTouched
        assertTrue("the marker read must itself stay inside its budget", marker in 1..64L)
        assertEquals(emptyList<DebugLog.Entry>(), DebugLog.readTail(g, 0))
        assertEquals("a zero budget must not read the file at all", marker, DebugLog.lastTailBytesTouched)
    }

    @Test fun aWholeSmallFileIsReadFromTheStart() {
        val f = writeJournal("small.log", lines = 12, padTo = 20)
        val entries = DebugLog.readTail(f, 1_000_000)
        assertEquals(12, entries.size)
        assertEquals("budget larger than the file: touched bytes are the file size, not the budget",
            f.length(), DebugLog.lastTailBytesTouched)
    }
}
