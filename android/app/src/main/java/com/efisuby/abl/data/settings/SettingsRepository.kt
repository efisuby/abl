package com.efisuby.abl.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val SETTINGS_FILE = "settings"

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = SETTINGS_FILE)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            theme = preferences[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            language = preferences[LANGUAGE],
            playbackSpeed = preferences[PLAYBACK_SPEED] ?: AppSettings.DEFAULT_SPEED,
            wifiOnly = preferences[WIFI_ONLY] ?: true,
            lastSyncAt = preferences[LAST_SYNC_AT],
        )
    }

    suspend fun setTheme(mode: ThemeMode) {
        dataStore.edit { it[THEME] = mode.name }
    }

    suspend fun setLanguage(code: String?) {
        dataStore.edit { preferences ->
            if (code == null) preferences.remove(LANGUAGE) else preferences[LANGUAGE] = code
        }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        val normalized = AppSettings.normalizeSpeed(speed)
        dataStore.edit { it[PLAYBACK_SPEED] = normalized }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[WIFI_ONLY] = enabled }
    }

    suspend fun setLastSyncAt(timestamp: Long) {
        dataStore.edit { it[LAST_SYNC_AT] = timestamp }
    }

    private companion object {
        val THEME = stringPreferencesKey(SyncableSettings.KEY_THEME)
        val LANGUAGE = stringPreferencesKey(SyncableSettings.KEY_LANGUAGE)
        val PLAYBACK_SPEED = floatPreferencesKey(SyncableSettings.KEY_PLAYBACK_SPEED)
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
    }
}
