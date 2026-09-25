package com.hermex.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreManager(private val dataStore: DataStore<Preferences>) {

    fun getString(key: String): Flow<String?> = dataStore.data.map { preferences ->
        preferences[stringPreferencesKey(key)]
    }

    suspend fun setString(key: String, value: String) {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey(key)] = value
        }
    }

    fun getStringSet(key: String): Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[stringSetPreferencesKey(key)] ?: emptySet()
    }

    suspend fun setStringSet(key: String, value: Set<String>) {
        dataStore.edit { preferences ->
            preferences[stringSetPreferencesKey(key)] = value
        }
    }

    fun getBoolean(key: String): Flow<Boolean?> = dataStore.data.map { preferences ->
        preferences[booleanPreferencesKey(key)]
    }

    suspend fun setBoolean(key: String, value: Boolean) {
        dataStore.edit { preferences ->
            preferences[booleanPreferencesKey(key)] = value
        }
    }

    fun getInt(key: String): Flow<Int?> = dataStore.data.map { preferences ->
        preferences[intPreferencesKey(key)]
    }

    suspend fun setInt(key: String, value: Int) {
        dataStore.edit { preferences ->
            preferences[intPreferencesKey(key)] = value
        }
    }
}
