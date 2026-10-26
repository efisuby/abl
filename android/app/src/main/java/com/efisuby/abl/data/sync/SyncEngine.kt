package com.efisuby.abl.data.sync

import kotlinx.serialization.SerializationException

/** Local side of synchronization: what the engine reads from and applies to the device's data. */
interface SyncStore {
    suspend fun progress(): List<ProgressEntry>

    /** Stores records that are newer than the local ones; records of books not on this device are skipped. */
    suspend fun applyProgress(entries: Collection<ProgressEntry>)

    /** Includes tombstones. */
    suspend fun bookmarks(): List<BookmarkEntry>

    suspend fun applyBookmarks(entries: Collection<BookmarkEntry>)

    suspend fun settings(): Map<String, SettingEntry>

    suspend fun applySettings(values: Map<String, SettingEntry>)
}

data class SyncReport(val progressApplied: Int, val bookmarksApplied: Int, val settingsApplied: Int)

/**
 * One exchange with the cloud: read every device's files, merge ([SyncMerge]), apply what is
 * newer locally and publish this device's files. Network errors propagate to the caller, which
 * retries later; a damaged file of another device is skipped so that it cannot block the rest.
 */
class SyncEngine(
    private val store: SyncStore,
    private val files: CloudFiles,
    private val deviceId: String,
) {

    suspend fun sync(): SyncReport = SyncReport(
        progressApplied = syncProgress(),
        bookmarksApplied = syncBookmarks(),
        settingsApplied = syncSettings(),
    )

    private suspend fun syncProgress(): Int {
        val local = store.progress()
        val remote = otherDevices("progress").mapNotNull { name ->
            readOrNull(name) { SyncJson.decodeProgress(it).books }
        }
        val merged = SyncMerge.progress(local, *remote.toTypedArray())
        val localByBook = local.associateBy { it.bookId }
        val toApply = merged.values.filter { it != localByBook[it.bookId] }
        store.applyProgress(toApply)

        writeIfChanged(ownPath("progress"), SyncJson.encode(ProgressFile(deviceId = deviceId, books = store.progress())))
        return toApply.size
    }

    private suspend fun syncBookmarks(): Int {
        val local = store.bookmarks()
        val remoteText = files.read(BOOKMARKS)?.decodeToString()
        val remote = remoteText?.let { runCatching { SyncJson.decodeBookmarks(it).bookmarks }.getOrNull() }.orEmpty()
        val merged = SyncMerge.bookmarks(local, remote)
        val localById = local.associateBy { it.id }
        val toApply = merged.values.filter { it != localById[it.id] }
        store.applyBookmarks(toApply)

        // A damaged shared file is replaced by the merge of what this device knows.
        writeIfChanged(BOOKMARKS, SyncJson.encode(BookmarksFile(bookmarks = merged.values.toList())))
        return toApply.size
    }

    private suspend fun syncSettings(): Int {
        val local = store.settings()
        val own = SettingsFile(deviceId = deviceId, values = local).syncableOnly()
        val remote = otherDevices("settings").mapNotNull { name ->
            readOrNull(name) { SyncJson.decodeSettings(it) }
        }
        val merged = SyncMerge.settings(own, *remote.toTypedArray())
        val toApply = merged.filter { (key, entry) -> entry != local[key] }
        store.applySettings(toApply)

        val published = SettingsFile(deviceId = deviceId, values = store.settings()).syncableOnly()
        writeIfChanged(ownPath("settings"), SyncJson.encode(published))
        return toApply.size
    }

    private fun ownPath(dir: String) = "$dir/$deviceId.json"

    /** Paths of the other devices' files in [dir]. */
    private suspend fun otherDevices(dir: String): List<String> =
        files.list(dir).filter { it.endsWith(".json") && it != "$deviceId.json" }.sorted().map { "$dir/$it" }

    private suspend fun <T> readOrNull(path: String, decode: (String) -> T): T? {
        val bytes = files.read(path) ?: return null
        return try {
            decode(bytes.decodeToString())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private suspend fun writeIfChanged(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        if (files.read(path)?.contentEquals(bytes) == true) return
        files.write(path, bytes)
    }

    private companion object {
        const val BOOKMARKS = "bookmarks.json"
    }
}
