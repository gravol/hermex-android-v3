package com.hermex.core.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class SessionStatus {
    @Serializable @SerialName("active") data object Active : SessionStatus()
    @Serializable @SerialName("inactive") data object Inactive : SessionStatus()
    @Serializable @SerialName("expired") data object Expired : SessionStatus()
    @Serializable @SerialName("pending") data class Pending(val reason: String? = null) : SessionStatus()
}
