package com.hermex.v3.feature.menu

import com.hermex.core.network.InsightsUsage
import com.hermex.core.network.UsageModel
import com.hermex.core.network.UsageTask
import com.hermex.core.network.UsageTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rendering of the gateway's usage rollup (`insights.get` → `usage`).
 *
 * Why these cases exist: the panel used to total `session.list` rows on the phone, and those rows
 * carry only main-agent-loop calls with **uncached** prompt tokens. On a real 30-day store that
 * showed 77.6M input against 2.55B actually sent, plus 1,572 auxiliary calls that were invisible —
 * which is why the in/out ratio looked absurd rather than merely low. The tests below pin the
 * distinction the fix is about: a prompt figure that includes cached tokens, an "input" figure that
 * does not, and auxiliary work that has to appear somewhere.
 */
class InsightsUsageRenderTest {

    private val month = InsightsUsage(
        periodDays = 30,
        totals = UsageTotals(
            inputTokens = 58_945_860,
            outputTokens = 14_680_849,
            cacheReadTokens = 2_078_366_867,
            cacheWriteTokens = 0,
            reasoningTokens = 3_071_780,
            apiCallCount = 15_890,
            estimatedCost = 4.764,
            sessions = 120,
        ),
        models = listOf(
            UsageModel(
                model = "custom:clerk-flashnext-uncensored",
                tasks = listOf("main"),
                apiCalls = 9_000,
                estimatedCost = 0.0,
                inputTokens = 30_000_000,
                outputTokens = 9_000_000,
                cacheReadTokens = 1_200_000_000,
                totalTokens = 1_239_000_000,
            ),
        ),
        tasks = listOf(
            UsageTask(
                task = "background_review",
                models = listOf("deepseek-v4-pro"),
                inputTokens = 6_990_000,
                outputTokens = 1_200_000,
                apiCallCount = 1_385,
                estimatedCost = 0.46,
            ),
            UsageTask(
                task = "compression",
                inputTokens = 2_780_000,
                outputTokens = 90_000,
                apiCallCount = 77,
                estimatedCost = 0.98,
            ),
            UsageTask(
                task = "title_generation",
                inputTokens = 34_000,
                outputTokens = 4_000,
                apiCallCount = 109,
            ),
        ),
    )

    @Test
    fun `prompt volume counts cached tokens, input does not`() {
        val t = month.totals
        assertEquals(58_945_860L + 2_078_366_867L, t.promptTokens)
        val d = InsightsAggregate.renderUsage(month)
        // The headline leads with what the model was shown, not with the cache misses — that is the
        // whole reason the old figure read as a broken ratio.
        assertEquals("2.1B prompt / 14.7M out", d.headline)
        assertTrue(d.lines.first().label.startsWith("Prompt"))
        val uncached = d.lines.first { it.label.trim() == "uncached" }.value
        assertEquals("58.9M", uncached)
        val cached = d.lines.first { it.label.trim() == "cached" }.value
        assertEquals("2.1B", cached)
    }

    @Test
    fun `auxiliary work is listed by task with plain names`() {
        val d = InsightsAggregate.renderUsage(month)
        val labels = d.overhead.map { it.label }
        assertEquals(listOf("memory review", "context compaction", "titling"), labels)
        // Heaviest first: a user asking "what did this month cost" needs the big line at the top.
        assertTrue(d.overhead.first().value.startsWith("8.2M"))
        assertTrue(d.overhead.first().value.contains("1385 calls"))
    }

    @Test
    fun `thinking tokens are named inside output rather than added to it`() {
        val d = InsightsAggregate.renderUsage(month)
        val out = d.lines.first { it.label == "Output" }.value
        // reasoning_tokens are a subset of completion_tokens; showing them as a separate total
        // would double-count them against the provider bill.
        assertTrue(out.startsWith("14.7M (3.1M thinking)"))
    }

    @Test
    fun `model rows keep the gateway label readable and the total honest`() {
        val d = InsightsAggregate.renderUsage(month)
        assertEquals(1, d.models.size)
        assertEquals("clerk-flashnext-uncensored", d.models.first().model)
        assertEquals(1_239_000_000L, d.models.first().totalTokens)
    }

    @Test
    fun `cost shows up when there is one and stays hidden when there is not`() {
        val d = InsightsAggregate.renderUsage(month)
        assertEquals("$4.76", d.lines.first { it.label == "Estimated cost" }.value)

        val local = month.copy(
            totals = month.totals.copy(estimatedCost = 0.0, actualCost = 0.0),
        )
        assertFalse(InsightsAggregate.renderUsage(local).lines.any { it.label == "Estimated cost" })
    }

    @Test
    fun `window selector maps to the gateway's period in days`() {
        assertEquals(1, InsightsAggregate.daysFor(InsightsAggregate.DAY))
        assertEquals(7, InsightsAggregate.daysFor(InsightsAggregate.WEEK))
        assertEquals(30, InsightsAggregate.daysFor(InsightsAggregate.MONTH))
    }

    @Test
    fun `money formatting keeps sub-cent real figures visible`() {
        // Local routes bill at exactly zero; a sub-cent figure is a real number here, not rounding.
        assertEquals("$0.00", InsightsAggregate.formatCost(0.0))
        assertEquals("$0.0045", InsightsAggregate.formatCost(0.0045))
        assertEquals("$6.19", InsightsAggregate.formatCost(6.19))
    }

    @Test
    fun `task names fall through unchanged when the gateway invents one`() {
        assertEquals("memory review", InsightsAggregate.taskLabel("background_review"))
        assertEquals("image description", InsightsAggregate.taskLabel("vision"))
        assertEquals("web extraction", InsightsAggregate.taskLabel("web_extraction"))
    }
}
