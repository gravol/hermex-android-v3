package com.hermex.core.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionsResponse(
    @SerialName("sessions") val sessions: List<SessionSummary>? = null,
    @SerialName("cliCount") val cliCount: Int? = null,
    @SerialName("serverTime") val serverTime: Double? = null,
)

@Serializable
data class SessionSummary(
    val id: String = "",
    val title: String? = null,
    val createdAt: String? = null,
)
