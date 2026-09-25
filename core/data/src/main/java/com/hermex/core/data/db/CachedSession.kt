package com.hermex.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_sessions")
data class CachedSession(
    @PrimaryKey
    val sessionId: String,
    val userId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)