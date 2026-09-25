package com.hermex.core.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionSearchResponse(
    @SerialName("server_tz") val serverTz: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)
