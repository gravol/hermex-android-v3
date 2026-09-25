package com.hermex.core.data.db.dao

import androidx.room.*
import com.hermex.core.data.db.ToolCall
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolCallDao {
    @Query("SELECT * FROM tool_calls WHERE messageId = :messageId")
    fun getToolCalls(messageId: String): Flow<List<ToolCall>>

    @Query("SELECT * FROM tool_calls WHERE id = :toolCallId")
    fun getToolCall(toolCallId: String): Flow<ToolCall?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertToolCall(toolCall: ToolCall)

    @Update
    suspend fun updateToolCall(toolCall: ToolCall)

    @Delete
    suspend fun deleteToolCall(toolCall: ToolCall)

    @Query("DELETE FROM tool_calls WHERE id = :toolCallId")
    suspend fun deleteToolCallById(toolCallId: String)
}