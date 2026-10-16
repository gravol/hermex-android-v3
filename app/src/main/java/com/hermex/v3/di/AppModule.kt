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
import com.hermex.core.network.JsonRpcClient
import com.hermex.core.network.WsConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /**
     * v0.1.885: the app-wide connection scope. Chat, sessions, soul, bot screen and the
     * notification-reply service all share ONE WebSocket. A ViewModel scope is cancelled
     * when that ViewModel clears, which is why per-screen managers cannot host a shared
     * connection.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var wsConnection: WsConnectionManager? = null
    @Volatile
    private var rpcClient: JsonRpcClient? = null

    /**
     * The single app-wide WebSocket connection.
     *
     * Every screen used to build its own `WsConnectionManager(viewModelScope)` — 4 in
     * SessionsViewModel, 1 per chat session in ChatVmsHolder, plus SoulScreen,
     * BotScreenEngine and NotificationReplyService. Each instance opens its own socket,
     * and the gateway delivers every event to every socket, so the same delta lands N
     * times (doubled words in chat) and `session.resume` from a stale socket re-attaches
     * the session to the wrong connection — a running turn's events then go to a socket
     * whose ViewModel ignores them, so the turn never shows up.
     */
    fun provideWsConnection(): WsConnectionManager =
        wsConnection ?: synchronized(this) {
            wsConnection ?: WsConnectionManager(appScope).also { wsConnection = it }
        }

    /** The single app-wide JSON-RPC client, bound to [provideWsConnection]. */
    fun provideRpcClient(): JsonRpcClient =
        rpcClient ?: synchronized(this) {
            rpcClient ?: JsonRpcClient(provideWsConnection(), appScope).also { rpcClient = it }
        }

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
