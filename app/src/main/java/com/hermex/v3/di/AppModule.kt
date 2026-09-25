package com.hermex.v3.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.hermex.core.data.DataStoreManager
import com.hermex.core.data.cache.CacheManager
import com.hermex.core.data.db.dao.MessageDao
import com.hermex.core.data.db.dao.SessionDao
import com.hermex.core.data.db.dao.ThinkingCardDao
import com.hermex.core.data.db.dao.ToolCallDao

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hermex_settings")

/**
 * Manual dependency injection — no Hilt/Dagger.
 * Provide singletons via lazy-initialized factory methods.
 */
object AppModule {

    @Volatile
    private var database: com.hermex.core.data.db.Database? = null
    @Volatile
    private var dataStoreManager: DataStoreManager? = null

    fun provideDatabase(context: Context): com.hermex.core.data.db.Database {
        return database ?: synchronized(this) {
            database ?: com.hermex.core.data.db.Database.getInstance(context).also { database = it }
        }
    }

    fun provideSessionDao(context: Context): SessionDao =
        provideDatabase(context).sessionDao()

    fun provideMessageDao(context: Context): MessageDao =
        provideDatabase(context).messageDao()

    fun provideToolCallDao(context: Context): ToolCallDao =
        provideDatabase(context).toolCallDao()

    fun provideThinkingCardDao(context: Context): ThinkingCardDao =
        provideDatabase(context).thinkingCardDao()

    fun provideCacheManager(): CacheManager = CacheManager

    fun provideDataStoreManager(context: Context): DataStoreManager {
        return dataStoreManager ?: synchronized(this) {
            dataStoreManager ?: DataStoreManager(context.dataStore).also { dataStoreManager = it }
        }
    }
}
