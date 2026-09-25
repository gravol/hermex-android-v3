package com.hermex.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "thinking_cards")
data class ThinkingCard(
    @PrimaryKey
    val id: String,
    val messageId: String,
    val content: String,
    val timestamp: Long,
    val isExpanded: Boolean
)