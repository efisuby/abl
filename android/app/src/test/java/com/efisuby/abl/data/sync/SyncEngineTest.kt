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

    private fun progress(device: String, position: Long, at: Long) =
        ProgressEntry("book", "c1", position, position, false, at, device)

    private fun bookmark(id: String, at: Long, deleted: Boolean = false) =
        BookmarkEntry(id, "book", "c1", 1000, null, 1, at, deleted)

    @Test
    fun twoDevicesConverge() = runBlocking {
        val cloud = DirectoryCloudFiles(tmp.newFolder())
        val phone = SyncEngineTestStore()
        val tablet = SyncEngineTestStore()
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
        val phone = SyncEngineTestStore().apply { progress["book"] = progress("phone", 5, at = 1) }

        SyncEngine(phone, cloud, "phone").sync()

        assertEquals(5L, phone.progress.getValue("book").positionMs)
        assertTrue(cloud.read("progress/phone.json") != null)
    }

    @Test
    fun secondSyncWritesNothingNew() = runBlocking {
        val cloud = DirectoryCloudFiles(tmp.newFolder())
        val phone = SyncEngineTestStore().apply { progress["book"] = progress("phone", 5, at = 1) }
        val engine = SyncEngine(phone, cloud, "phone")
        engine.sync()
        val report = engine.sync()
        assertEquals(SyncReport(0, 0, 0), report)
    }

    @Test
    fun changedCoverReachesTheOtherDevice() = runBlocking {
        val cloud = DirectoryCloudFiles(tmp.newFolder())
        val phone = SyncEngineTestStore()
        val tablet = SyncEngineTestStore()
        val phoneEngine = SyncEngine(phone, cloud, "phone")
        val tabletEngine = SyncEngine(tablet, cloud, "tablet")
        phone.library["book"] = LibraryEntry("book", "Old", metadataUpdatedAt = 1)
        tablet.library["book"] = LibraryEntry("book", "Old", metadataUpdatedAt = 1)

        // the phone edits the title and picks a new cover
        phone.library["book"] = LibraryEntry("book", "New", metadataUpdatedAt = 5)
        phone.covers["book"] = byteArrayOf(1, 2, 3)
        phoneEngine.sync()
        val report = tabletEngine.sync()

        assertEquals(1, report.booksApplied)
        assertEquals("New", tablet.library.getValue("book").title)
        assertEquals(listOf<Byte>(1, 2, 3), tablet.covers.getValue("book").toList())
        // an older copy never overwrites a newer one
        tabletEngine.sync()
        phoneEngine.sync()
        assertEquals("New", phone.library.getValue("book").title)
    }

    @Test
    fun reinstallBringsBackBooksPositionsAndBookmarks() = runBlocking {
        val cloud = DirectoryCloudFiles(tmp.newFolder())
        val old = SyncEngineTestStore().apply {
            library["book"] = LibraryEntry("book", "Title", remotePath = "disk:/b", metadataUpdatedAt = 3)
            library["local"] = LibraryEntry("local", "From phone storage") // no remotePath: no audio in the cloud
            covers["book"] = byteArrayOf(9)
            progress["book"] = progress("old", 777, at = 50)
            bookmarks["b1"] = bookmark("b1", 10)
        }
        SyncEngine(old, cloud, "old").sync()

        // app data cleared: a new device id and an empty database
        val fresh = SyncEngineTestStore().apply { restorable += "book" }
        // restoring creates the book first, as the real store does through the importer
        val engine = SyncEngine(fresh, cloud, "fresh")
        val report = engine.sync()

        assertEquals(1, report.booksRestored)
        assertEquals("Title", fresh.library.getValue("book").title)
        assertEquals(null, fresh.library["local"])
        assertEquals(listOf<Byte>(9), fresh.covers.getValue("book").toList())
        assertEquals(777L, fresh.progress.getValue("book").positionMs)
        assertTrue(fresh.bookmarks.containsKey("b1"))

        // a book deleted afterwards does not come back
        fresh.library.remove("book")
        assertEquals(0, engine.sync().booksRestored)
        assertNull(fresh.library["book"])
    }
}
