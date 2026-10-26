package com.efisuby.abl.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {

    private fun entry(book: String = "b", position: Long, at: Long, device: String, finished: Boolean = false) =
        ProgressEntry(book, "c1", position, 0, finished, at, device)

    // 8.1: stable file format.
    @Test
    fun reEncodingProducesTheSameDocument() {
        val file = ProgressFile(
            deviceId = "phone",
            books = listOf(entry("z", 5, 10, "phone"), entry("a", 7, 11, "phone", finished = true)),
        )
        val first = SyncJson.encode(file)
        val second = SyncJson.encode(SyncJson.decodeProgress(first))

        assertEquals(first, second)
        assertTrue("books are sorted by id", first.indexOf("\"a\"") < first.indexOf("\"z\""))
        assertTrue(first.contains("\"schemaVersion\": 1"))
    }

    @Test
    fun unknownFieldsFromANewerVersionAreIgnored() {
        val text = """{"schemaVersion":2,"deviceId":"d","future":true,"books":[]}"""
        assertEquals("d", SyncJson.decodeProgress(text).deviceId)
    }

    // 8.2
    @Test
    fun theFresherPositionWins() {
        val phone = entry(position = 2 * 3600_000 + 10 * 60_000, at = 200, device = "phone")
        val tablet = entry(position = 30 * 60_000, at = 100, device = "tablet")

        assertEquals(phone, SyncMerge.progress(listOf(tablet), listOf(phone)).getValue("b"))
        assertEquals(phone, SyncMerge.progress(listOf(phone), listOf(tablet)).getValue("b"))
    }

    @Test
    fun aTieGoesToTheFurtherPositionWhileListening() {
        val a = entry(position = 100, at = 5, device = "a")
        val b = entry(position = 900, at = 5, device = "b")
        assertEquals(b, SyncMerge.progress(listOf(a, b)).getValue("b"))
        assertEquals(b, SyncMerge.progress(listOf(b, a)).getValue("b"))
    }

    @Test
    fun aTieOnAFinishedBookGoesToTheLargerDeviceIdWhateverTheOrder() {
        val a = entry(position = 900, at = 5, device = "a", finished = true)
        val b = entry(position = 100, at = 5, device = "b", finished = true)
        assertEquals(b, SyncMerge.progress(listOf(a, b)).getValue("b"))
        assertEquals(b, SyncMerge.progress(listOf(b, a)).getValue("b"))
    }

    @Test
    fun booksAreMergedIndependently() {
        val merged = SyncMerge.progress(
            listOf(entry("x", 1, 10, "a"), entry("y", 1, 50, "a")),
            listOf(entry("x", 2, 20, "b"), entry("y", 2, 40, "b")),
        )
        assertEquals("b", merged.getValue("x").deviceId)
        assertEquals("a", merged.getValue("y").deviceId)
    }

    // 8.3
    private fun mark(id: String, at: Long, deleted: Boolean = false, note: String? = null) =
        BookmarkEntry(id, "b", "c1", 1000, note, 1, at, deleted)

    @Test
    fun bookmarksFromBothDevicesAreKept() {
        val merged = SyncMerge.bookmarks(listOf(mark("1", 5)), listOf(mark("2", 6)))
        assertEquals(setOf("1", "2"), merged.keys)
    }

    @Test
    fun aDeletedBookmarkIsNotRestoredByAnOldCopy() {
        val tombstone = mark("1", at = 20, deleted = true)
        val oldCopy = mark("1", at = 10)

        assertTrue(SyncMerge.bookmarks(listOf(tombstone), listOf(oldCopy)).getValue("1").deleted)
        assertTrue(SyncMerge.bookmarks(listOf(oldCopy), listOf(tombstone)).getValue("1").deleted)
    }

    @Test
    fun aTombstoneBeatsACopyModifiedAtTheSameTime() {
        val merged = SyncMerge.bookmarks(listOf(mark("1", 10)), listOf(mark("1", 10, deleted = true)))
        assertTrue(merged.getValue("1").deleted)
    }

    @Test
    fun anEditAfterTheDeletionWinsOnlyIfNewer() {
        val merged = SyncMerge.bookmarks(listOf(mark("1", 10, deleted = true)), listOf(mark("1", 30, note = "later")))
        assertFalse(merged.getValue("1").deleted)
        assertEquals("later", merged.getValue("1").note)
    }

    // 8.5
    private fun settings(device: String, vararg values: Pair<String, SettingEntry>) = SettingsFile(deviceId = device, values = mapOf(*values))

    @Test
    fun speedIsSynchronizedAndWifiOnlyIsNot() {
        val tablet = settings(
            "tablet",
            "playback_speed" to SettingEntry("1.5", 100),
            "wifi_only" to SettingEntry("false", 100),
        )
        val phone = settings("phone", "playback_speed" to SettingEntry("1.25", 200))

        val merged = SyncMerge.settings(tablet, phone)

        assertEquals(setOf("playback_speed"), merged.keys)
        assertEquals("1.25", merged.getValue("playback_speed").value)
    }

    @Test
    fun outgoingSettingsCarryOnlySyncableKeys() {
        val file = settings("d", "playback_speed" to SettingEntry("1.0", 1), "wifi_only" to SettingEntry("true", 1), "last_sync_at" to SettingEntry("9", 1))
        assertEquals(setOf("playback_speed"), file.syncableOnly().values.keys)
    }
}
