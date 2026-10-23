package com.hermex.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-side usage rollup for ONE window (`insights.get` → `usage`).
 *
 * Why this exists instead of summing [SessionSummary] on the phone: the session rows carry the
 * gateway's `sessions` counters, which hold **main-agent-loop calls only** and **uncached prompt
 * tokens only**. Measured against a real store over 30 days that hid 1,572 API calls / 9.8M input
 * tokens (+13%) of auxiliary work — background memory review, context compaction, title generation,
 * vision — plus 2.47B cached prompt tokens against the 77.6M it did show. A panel built from those
 * rows therefore reports an in/out ratio that is an artifact of what it cannot see.
 *
 * These figures come from `session_model_usage`, the same accounting `/insights` uses, so totals
 * and breakdowns agree with each other. Absent (null) when the gateway predates the rollup — the
 * caller must then say it is approximating rather than quietly showing the thin numbers.
 */
@Serializable
data class UsageTotals(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long = 0,
    @SerialName("reasoning_tokens") val reasoningTokens: Long = 0,
    @SerialName("api_call_count") val apiCallCount: Long = 0,
    @SerialName("estimated_cost") val estimatedCost: Double = 0.0,
    @SerialName("actual_cost") val actualCost: Double = 0.0,
    val sessions: Int = 0,
) {
    /** Everything the model was actually shown: uncached + cached prompt tokens. */
    val promptTokens: Long get() = inputTokens + cacheReadTokens + cacheWriteTokens
}

@Serializable
data class UsageTask(
    val task: String = "",
    val models: List<String> = emptyList(),
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long = 0,
    @SerialName("reasoning_tokens") val reasoningTokens: Long = 0,
    @SerialName("api_call_count") val apiCallCount: Long = 0,
    @SerialName("estimated_cost") val estimatedCost: Double = 0.0,
)

@Serializable
data class UsageModel(
    val model: String = "",
    val tasks: List<String> = emptyList(),
    @SerialName("api_calls") val apiCalls: Long = 0,
    @SerialName("estimated_cost") val estimatedCost: Double = 0.0,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long = 0,
    @SerialName("reasoning_tokens") val reasoningTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
)

@Serializable
data class InsightsUsage(
    @SerialName("period_days") val periodDays: Int = 0,
    @SerialName("generated_at") val generatedAt: Double = 0.0,
    val totals: UsageTotals = UsageTotals(),
    val models: List<UsageModel> = emptyList(),
    val tasks: List<UsageTask> = emptyList(),
)

/**
 * `insights.get` result. The three legacy keys are what gateways before the rollup returned; they
 * stay so an older build keeps working, and [usage] being null is a *server capability* signal,
 * never a zero-usage answer.
 */
@Serializable
data class InsightsGetResult(
    val days: Int = 0,
    val sessions: Int = 0,
    val messages: Int = 0,
    val usage: InsightsUsage? = null,
    @SerialName("usage_unavailable") val usageUnavailable: String? = null,
)
