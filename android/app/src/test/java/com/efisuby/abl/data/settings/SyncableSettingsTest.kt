package com.efisuby.abl.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncableSettingsTest {

    @Test
    fun `device-specific settings never enter the syncable set`() {
        val local = mapOf(
            "playback_speed" to 1.5f,
            "wifi_only" to true,
            "last_sync_at" to 1234L,
            "storage_path" to "/data/books",
        )

        val syncable = SyncableSettings.syncableOnly(local)

        assertEquals(mapOf("playback_speed" to 1.5f), syncable)
        assertTrue("wifi_only" !in syncable)
        assertTrue("last_sync_at" !in syncable)
    }

    @Test
    fun `all listed syncable keys survive filtering`() {
        val values = SyncableSettings.keys.associateWith { "value" }

        assertEquals(values, SyncableSettings.syncableOnly(values))
    }
}
