package com.efisuby.abl.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val SETTINGS_FILE = "settings"

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = SETTINGS_FILE)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            playbackSpeed = preferences[PLAYBACK_SPEED] ?: AppSettings.DEFAULT_SPEED,
            wifiOnly = preferences[WIFI_ONLY] ?: true,
            lastSyncAt = preferences[LAST_SYNC_AT],
        )
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        val normalized = AppSettings.normalizeSpeed(speed)
        dataStore.edit {
            it[PLAYBACK_SPEED] = normalized
            it[PLAYBACK_SPEED_UPDATED_AT] = maxOf(System.currentTimeMillis(), (it[PLAYBACK_SPEED_UPDATED_AT] ?: 0) + 1)
        }
    }

    /** The speed with its change time for synchronization; null while it was never set. */
    suspend fun syncedSpeed(): Pair<Float, Long>? {
        val preferences = dataStore.data.first()
        val speed = preferences[PLAYBACK_SPEED] ?: return null
        val updatedAt = preferences[PLAYBACK_SPEED_UPDATED_AT] ?: return null
        return speed to updatedAt
    }

    /** Takes a speed from another device without turning it into a newer local change. */
    suspend fun applySyncedSpeed(speed: Float, updatedAt: Long) {
        dataStore.edit {
            if (updatedAt <= (it[PLAYBACK_SPEED_UPDATED_AT] ?: 0)) return@edit
            it[PLAYBACK_SPEED] = AppSettings.normalizeSpeed(speed)
            it[PLAYBACK_SPEED_UPDATED_AT] = updatedAt
        }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[WIFI_ONLY] = enabled }
    }

    suspend fun setLastSyncAt(timestamp: Long) {
        dataStore.edit { it[LAST_SYNC_AT] = timestamp }
    }

    private companion object {
        val PLAYBACK_SPEED = floatPreferencesKey(SyncableSettings.KEY_PLAYBACK_SPEED)
        val PLAYBACK_SPEED_UPDATED_AT = longPreferencesKey("playback_speed_updated_at")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
    }
}
