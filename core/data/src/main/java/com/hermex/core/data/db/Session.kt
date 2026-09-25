package com.hermex.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey
    val id: String,
    val userId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isActive: Boolean
)