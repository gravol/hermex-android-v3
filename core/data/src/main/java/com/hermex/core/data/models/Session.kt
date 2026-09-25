package com.hermex.core.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Session(
    @SerialName("session_id") val sessionId: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("is_active") val isActive: Boolean? = true,
)
