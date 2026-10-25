package com.efisuby.abl.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackSpeedTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `speed is clamped to the range and rounded to a tenth`() {
        assertEquals(0.5f, AppSettings.normalizeSpeed(0.1f))
        assertEquals(3.0f, AppSettings.normalizeSpeed(7f))
        assertEquals(1.5f, AppSettings.normalizeSpeed(1.46f))
        assertEquals(1.2f, AppSettings.normalizeSpeed(1.2f))
        assertEquals(1.0f, AppSettings.normalizeSpeed(Float.NaN))
    }

    @Test
    fun `speed survives a restart`() = runBlocking {
        val file = temp.newFile("settings.preferences_pb").apply { delete() }

        val first = CoroutineScope(Dispatchers.IO + SupervisorJob())
        SettingsRepository(PreferenceDataStoreFactory.create(scope = first) { file }).setPlaybackSpeed(1.5f)
        first.cancel()

        // A new DataStore over the same file stands for the next app launch.
        val second = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val restored = SettingsRepository(PreferenceDataStoreFactory.create(scope = second) { file }).settings.first()
        second.cancel()

        assertEquals(1.5f, restored.playbackSpeed)
    }
}
