package com.hermex.core.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionWithItems(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("items") val items: List<SessionItem> = emptyList(),
)

@Serializable
data class SessionItem(
    @SerialName("item_id") val itemId: String = "",
    @SerialName("quantity") val quantity: Int = 0,
    @SerialName("price") val price: Double = 0.0,
)
