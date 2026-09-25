package com.hermex.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.hermex.core.data.db.dao.*

@Database(
    entities = [
        Session::class,
        Message::class,
        ToolCall::class,
        ThinkingCard::class,
        CachedSession::class,
        CachedMessage::class
    ],
    version = 1,
    exportSchema = false
)
abstract class Database : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun thinkingCardDao(): ThinkingCardDao

    companion object {
        @Volatile
        private var INSTANCE: com.hermex.core.data.db.Database? = null

        fun getInstance(context: Context): com.hermex.core.data.db.Database {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    com.hermex.core.data.db.Database::class.java,
                    "hermex_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
