package com.hermex.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionSummary(
    val id: String = "",
    val title: String? = null,
    val source: String? = null,
    val model: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("tool_call_count") val toolCallCount: Int = 0,
    // Token usage for Insights aggregation (input + output per session).
    @SerialName("input_tokens") val inputTokens: Long = -1,
    @SerialName("output_tokens") val outputTokens: Long = -1,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("last_activity_at") val lastActivityAt: Double? = null,
    @SerialName("last_activity_description") val lastActivityDescription: String? = null,
    val preview: String? = null,
)
