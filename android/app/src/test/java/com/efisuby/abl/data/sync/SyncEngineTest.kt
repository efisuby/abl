package com.efisuby.abl.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeStore : SyncStore {
        val progress = mutableMapOf<String, ProgressEntry>()
        val bookmarks = mutableMapOf<String, BookmarkEntry>()
        val settings = mutableMapOf<String, SettingEntry>()

        override suspend fun progress() = progress.values.toList()
        override suspend fun applyProgress(entries: Collection<ProgressEntry>) {
            entries.forEach { progress[it.bookId] = it }
        }
        override suspend fun bookmarks() = bookmarks.values.toList()
        override suspend fun applyBookmarks(entries: Collection<BookmarkEntry>) {
            entries.forEach { bookmarks[it.id] = it }
        }
        override suspend fun settings(): Map<String, SettingEntry> = settings.toMap()
        override suspend fun applySettings(values: Map<String, SettingEntry>) {
            settings.putAll(values)
        }
    }

    private fun progress(device: String, position: Long, at: Long) =
        ProgressEntry("book", "c1", position, position, false, at, device)

    private fun bookmark(id: String, at: Long, deleted: Boolean = false) =
        BookmarkEntry(id, "book", "c1", 1000, null, 1, at, deleted)

    @Test
    fun twoDevicesConverge() = runBlocking {
        val cloud = DirectoryCloudFiles(tmp.newFolder())
        val phone = FakeStore()
        val tablet = FakeStore()
        val phoneEngine = SyncEngine(phone, cloud, "phone")
        val tabletEngine = SyncEngine(tablet, cloud, "tablet")

        phone.progress["book"] = progress("phone", 2 * 3600_000 + 10 * 60_000, at = 200)
        tablet.progress["book"] = progress("tablet", 30 * 60_000, at = 100)
        phone.bookmarks["b1"] = bookmark("b1", 10)
        phone.bookmarks["b2"] = bookmark("b2", 10)
        phone.settings["playback_speed"] = SettingEntry("1.5", 50)
        phone.settings["wifi_only"] = SettingEntry("true", 50)

        phoneEngine.sync()
        tabletEngine.sync()
        // the tablet deletes b2; the phone must not resurrect it
        tablet.bookmarks["b2"] = bookmark("b2", 20, deleted = true)
        tabletEngine.sync()
        phoneEngine.sync()

        assertEquals(2 * 3600_000 + 10 * 60_000L, tablet.progress.getValue("book").positionMs)
        assertEquals(tablet.progress, phone.progress)
        assertTrue(phone.bookmarks.getValue("b2").deleted)
        assertEquals(false, phone.bookmarks.getValue("b1").deleted)
        assertEquals("1.5", tablet.settings["playback_speed"]?.value)
        assertNull(tablet.settings["wifi_only"])
    }

    @Test
    fun damagedFileOfAnotherDeviceIsSkipped() = runBlocking {
        val dir = tmp.newFolder()
        val cloud = DirectoryCloudFiles(dir)
        cloud.write("progress/broken.json", "{not json".encodeToByteArray())
        val phone = FakeStore().apply { progress["book"] = progress("phone", 5, at = 1) }

        SyncEngine(phone, cloud, "phone").sync()

        assertEquals(5L, phone.progress.getValue("book").positionMs)
        assertTrue(cloud.read("progress/phone.json") != null)
    }

    @Test
    fun secondSyncWritesNothingNew() = runBlocking {
        val cloud = DirectoryCloudFiles(tmp.newFolder())
        val phone = FakeStore().apply { progress["book"] = progress("phone", 5, at = 1) }
        val engine = SyncEngine(phone, cloud, "phone")
        engine.sync()
        val report = engine.sync()
        assertEquals(SyncReport(0, 0, 0), report)
    }
}
