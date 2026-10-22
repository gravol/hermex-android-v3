package com.hermex.v3.feature.menu

import com.hermex.core.network.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Window math for the Insights panel (v0.1.902 extraction; the behaviour is older than this test).
 *
 * Why these cases exist: the panel has been wrong twice by construction rather than by coding
 * mistake — once when the gateway stopped returning `model` on session.list (every session
 * collapsed into one "Unknown" row), and again when v0.1.901 cut the drawer's default page to 25
 * rows and the panel kept totalling whatever page it was handed. The second one is a data-supply
 * bug the ViewModel owns, but the boundary rules below are what make a DAY/WEEK/MONTH figure
 * mean anything, so they are pinned here with fixed clocks and a fixed zone instead of device
 * trials.
 */
class InsightsAggregateTest {

    private val zone: ZoneId = ZoneId.of("America/Vancouver")

    /** 2026-10-22 12:00 local — midday, so no DST midnight edge is being tested by accident. */
    private val now: Long = ZonedDateTime.of(2026, 10, 22, 12, 0, 0, 0, zone).toEpochSecond()

    private fun at(y: Int, mo: Int, d: Int, h: Int = 10, min: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, min, 0, 0, zone).toEpochSecond()

    private fun session(
        id: String,
        model: String?,
        activeAt: Long,
        input: Long,
        output: Long,
    ) = SessionSummary(
        id = id,
        title = id,
        model = model,
        startedAt = activeAt.toDouble(),
        lastActivityAt = activeAt.toDouble(),
        inputTokens = input,
        outputTokens = output,
        messageCount = 4,
    )

    // ── window boundaries ──────────────────────────────────────────────────────────────

    @Test
    fun `day window covers today only`() {
        assertTrue(InsightsAggregate.isInside(at(2026, 10, 22, 0, 1), InsightsAggregate.DAY, zone, now))
        assertTrue(InsightsAggregate.isInside(at(2026, 10, 22, 23, 59), InsightsAggregate.DAY, zone, now))
        assertFalse("yesterday is not today", InsightsAggregate.isInside(at(2026, 10, 21, 23, 59), InsightsAggregate.DAY, zone, now))
    }

    @Test
    fun `week window is seven local days inclusive of the sixth day back`() {
        assertTrue(InsightsAggregate.isInside(at(2026, 10, 22), InsightsAggregate.WEEK, zone, now))
        assertTrue(InsightsAggregate.isInside(at(2026, 10, 16), InsightsAggregate.WEEK, zone, now))
        assertFalse("the eighth day is out", InsightsAggregate.isInside(at(2026, 10, 15), InsightsAggregate.WEEK, zone, now))
    }

    @Test
    fun `month window is the calendar month, not a rolling thirty days`() {
        // A rolling-30-day reading would wrongly include these.
        assertFalse(InsightsAggregate.isInside(at(2026, 9, 25), InsightsAggregate.MONTH, zone, now))
        assertFalse(InsightsAggregate.isInside(at(2026, 9, 30, 23, 59), InsightsAggregate.MONTH, zone, now))
        assertTrue(InsightsAggregate.isInside(at(2026, 10, 1, 0, 1), InsightsAggregate.MONTH, zone, now))
        assertTrue(InsightsAggregate.isInside(at(2026, 10, 22), InsightsAggregate.MONTH, zone, now))
    }

    @Test
    fun `zero or missing timestamps never count`() {
        assertFalse(InsightsAggregate.isInside(0, InsightsAggregate.MONTH, zone, now))
        assertFalse(InsightsAggregate.isInside(-5, InsightsAggregate.DAY, zone, now))
    }

    // ── aggregation ────────────────────────────────────────────────────────────────────

    @Test
    fun `totals only count sessions inside the selected window`() {
        val rows = listOf(
            session("today-a", "flashnext-iq3s", at(2026, 10, 22, 9), input = 1_000, output = 500),
            session("today-b", "swift15-iq3xxs", at(2026, 10, 22, 11), input = 2_000, output = 100),
            session("lastweek", "flashnext-iq3s", at(2026, 10, 10), input = 99_000, output = 9_000),
        )
        val day = InsightsAggregate.aggregate(rows, InsightsAggregate.DAY, zone, now)
        assertEquals(2, day.inWindow.size)
        assertEquals(3_000L, day.grandInput)
        assertEquals(600L, day.grandOutput)
        assertEquals(3_600L, day.grandTotal)

        val month = InsightsAggregate.aggregate(rows, InsightsAggregate.MONTH, zone, now)
        assertEquals(3, month.inWindow.size)
        assertEquals(102_000L, month.grandInput)
    }

    @Test
    fun `per-model rows group by model and sort by total tokens`() {
        val rows = listOf(
            session("a", "custom:big-model", at(2026, 10, 22, 8), input = 5_000, output = 1_000),
            session("b", "custom:big-model", at(2026, 10, 22, 9), input = 1_000, output = 1_000),
            session("c", "small-model", at(2026, 10, 22, 10), input = 100, output = 10),
        )
        val r = InsightsAggregate.aggregate(rows, InsightsAggregate.DAY, zone, now)
        assertEquals(listOf("big-model", "small-model"), r.modelRows.map { it.model })
        assertEquals(2, r.modelRows.first().sessionCount)
        // 5_000+1_000 from "a" and 1_000+1_000 from "b" — both are the same model after the
        // provider prefix is stripped, which is the point of the row.
        assertEquals(8_000L, r.modelRows.first().totalTokens)
    }

    @Test
    fun `sessions with no reported usage are excluded from totals but counted`() {
        val rows = listOf(
            session("has-usage", "flashnext-iq3s", at(2026, 10, 22, 9), input = 700, output = 300),
            session("no-usage", "flashnext-iq3s", at(2026, 10, 22, 10), input = -1, output = -1),
        )
        val r = InsightsAggregate.aggregate(rows, InsightsAggregate.DAY, zone, now)
        assertEquals(1_000L, r.grandTotal)
        assertEquals(1, r.rowsWithoutUsage)
        assertEquals(2, r.totalRows)
    }

    @Test
    fun `an empty list is distinguishable from an empty window`() {
        val empty = InsightsAggregate.aggregate(emptyList(), InsightsAggregate.MONTH, zone, now)
        assertEquals(0, empty.totalRows)
        assertTrue(empty.modelRows.isEmpty())

        val outOfWindow = InsightsAggregate.aggregate(
            listOf(session("old", "m", at(2025, 3, 3), 10, 10)), InsightsAggregate.MONTH, zone, now,
        )
        assertEquals(1, outOfWindow.totalRows)
        assertEquals(0, outOfWindow.inWindow.size)
    }

    // ── display formatting (the UI asserts the same strings the tests do) ──────────────

    @Test
    fun `token formatting`() {
        assertEquals("999", InsightsAggregate.formatTokens(999))
        assertEquals("1.0k", InsightsAggregate.formatTokens(1_000))
        assertEquals("85.1k", InsightsAggregate.formatTokens(85_123))
        assertEquals("1.0M", InsightsAggregate.formatTokens(1_048_576))
    }
}
