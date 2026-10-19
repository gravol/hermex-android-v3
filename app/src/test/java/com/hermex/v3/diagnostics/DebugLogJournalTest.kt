package com.hermex.v3.diagnostics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermex.core.network.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Field

/**
 * A3 — a trial's journal must be complete or self-describing about its gaps.
 *
 * Two long-run investigations (D/14 included) read a pulled journal as if it were
 * continuous. It is not: the journal rotates at 2 MB into a single `.1` file, and the
 * D/14 turn window contained lines for only 3 of its own 30 seconds. Both halves are
 * pinned here:
 *  1. a debug/harness switch raises the rotation threshold far above the release cap,
 *     while the RELEASE default stays exactly 2 MB;
 *  2. whenever a rotation does happen — in any mode — an explicit `TRACE|rotate` line
 *     names the file moved and the byte count, so a gap can never be silent.
 *
 * The release default is read off the compiled constant rather than the live setting,
 * because DebugLog is a process singleton and these methods deliberately change it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class DebugLogJournalTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Suppress("UNCHECKED_CAST")
    private fun buffer(): Collection<DebugLog.Entry> {
        val f: Field = DebugLog::class.java.getDeclaredField("buffer").apply { isAccessible = true }
        return f.get(DebugLog) as Collection<DebugLog.Entry>
    }

    private fun staticLong(name: String): Long {
        val f = DebugLog::class.java.getDeclaredField(name).apply { isAccessible = true }
        return f.getLong(null)
    }

    private fun journalDir(): File {
        DebugLog.init(app())   // idempotent; wires the disk journal once per process
        val f = DebugLog::class.java.getDeclaredField("dir").apply { isAccessible = true }
        return (f.get(DebugLog) as File?) ?: error("journal dir not initialised")
    }

    private fun markerCount(): Int = buffer().count { it.tag == "rotate" }

    @Test
    fun releaseRotationCapIsUnchanged_andTheDebugSwitchRaisesItFar() {
        val releaseCap = staticLong("MAX_FILE_BYTES")
        val ceiling = staticLong("NO_ROTATION_CEILING_BYTES")
        assertEquals("release default rotation cap must stay 2 MB", 2_000_000L, releaseCap)
        assertTrue("the no-rotation ceiling must be far above the release cap ($ceiling)",
            ceiling >= 16 * releaseCap)
        try {
            DebugLog.setJournalRotationDisabled(true)
            assertTrue(
                "debug switch must raise the ACTIVE threshold past the release cap, got ${DebugLog.journalRotationBytes()}",
                DebugLog.journalRotationBytes() > releaseCap)
            DebugLog.setJournalRotationDisabled(false)
            assertEquals("switch off must restore exactly the release cap",
                releaseCap, DebugLog.journalRotationBytes())
        } finally {
            DebugLog.setJournalRotationBytes(releaseCap)
        }
    }

    @Test
    fun rotationWritesAnExplicitRotateMarker_andKeepsTheOldFile() {
        val d = journalDir()
        val releaseCap = staticLong("MAX_FILE_BYTES")
        try {
            DebugLog.clear()
            DebugLog.setJournalRotationBytes(800L)
            val before = markerCount()
            // Two flushes on purpose: a rotation only moves an EXISTING file aside, so
            // the first flush establishes hermex.log and the second one rotates it.
            repeat(20) { DebugLog.log("TRACE", "probeA", "rotation-probe-$it ${"x".repeat(40)}") }
            DebugLog.flushNow()
            repeat(20) { DebugLog.log("TRACE", "probeB", "rotation-probe-$it ${"y".repeat(40)}") }
            DebugLog.flushNow()

            val markers = buffer().filter { it.tag == "rotate" }
            assertTrue("expected a TRACE|rotate line on rotation (before=$before now=${markers.size})",
                markers.size > before)
            val line = markers.last().message
            assertTrue("rotate marker must name the files and bytes: $line",
                line.contains("file=hermex.log") && line.contains("file=hermex.log.1") &&
                    line.contains("bytes="))
            assertTrue("the rotated-away file must still exist for reconstruction",
                File(d, "hermex.log.1").exists())
        } finally {
            DebugLog.setJournalRotationBytes(releaseCap)
        }
    }

    @Test
    fun noRotationModeKeepsOneCompleteJournal() {
        val d = journalDir()
        val releaseCap = staticLong("MAX_FILE_BYTES")
        try {
            DebugLog.clear()
            DebugLog.setJournalRotationBytes(800L)   // prove the knob rotates at all
            repeat(20) { DebugLog.log("TRACE", "probeC", "rotate-me-probe-$it ${"z".repeat(40)}") }
            DebugLog.flushNow()
            repeat(20) { DebugLog.log("TRACE", "probeD", "rotate-me-probe-$it ${"w".repeat(40)}") }
            DebugLog.flushNow()
            assertTrue("sanity: a small cap must rotate", File(d, "hermex.log.1").exists())

            DebugLog.clear()
            DebugLog.setJournalRotationBytes(0L)     // 0 = never rotate
            val before = markerCount()
            repeat(60) { DebugLog.log("TRACE", "probeE", "single-file-probe-$it ${"q".repeat(40)}") }
            DebugLog.flushNow()
            repeat(60) { DebugLog.log("TRACE", "probeF", "single-file-probe-$it ${"r".repeat(40)}") }
            DebugLog.flushNow()
            assertEquals("no rotation may happen when the threshold is 0", before, markerCount())
            val text = File(d, "hermex.log").readText()
            assertTrue("the single journal must carry the whole run",
                text.contains("single-file-probe-0") && text.contains("single-file-probe-59"))
        } finally {
            DebugLog.setJournalRotationBytes(releaseCap)
        }
    }
}
