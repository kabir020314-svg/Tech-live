package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.ThemeModePreference
import com.example.data.model.safeEnumValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tech_live_prefs")

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LOW_LATENCY_MODE = booleanPreferencesKey("low_latency_mode")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val STREAM_NOTIFICATIONS = booleanPreferencesKey("stream_notifications")
        val BACKEND_URL_OVERRIDE = stringPreferencesKey("backend_url_override")
    }

    val themeMode: Flow<ThemeModePreference> = context.dataStore.data.map { prefs ->
        safeEnumValue(prefs[Keys.THEME_MODE], ThemeModePreference.DARK)
    }

    val lowLatencyMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.LOW_LATENCY_MODE] ?: true
    }

    val autoReconnectEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_RECONNECT] ?: true
    }

    val streamNotificationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.STREAM_NOTIFICATIONS] ?: true
    }

    val backendUrlOverride: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.BACKEND_URL_OVERRIDE] ?: ""
    }

    suspend fun setThemeMode(mode: ThemeModePreference) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setLowLatencyMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LOW_LATENCY_MODE] = enabled }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_RECONNECT] = enabled }
    }

    suspend fun setStreamNotifications(enabled: Boolean) {
        context.dataStore.edit { it[Keys.STREAM_NOTIFICATIONS] = enabled }
    }

    suspend fun setBackendUrlOverride(url: String) {
        context.dataStore.edit { it[Keys.BACKEND_URL_OVERRIDE] = url.trim() }
    }

    // Resumable upload checkpointing by local URI hash
    fun uploadOffsetKey(uriHash: String) = longPreferencesKey("upload_offset_$uriHash")

    suspend fun saveUploadOffset(uriHash: String, bytesUploaded: Long) {
        context.dataStore.edit { it[uploadOffsetKey(uriHash)] = bytesUploaded }
    }

    fun observeUploadOffset(uriHash: String): Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[uploadOffsetKey(uriHash)] ?: 0L
    }
}
