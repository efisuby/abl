package com.efisuby.abl.data.sync

import com.efisuby.abl.data.settings.SyncableSettings
import com.efisuby.abl.domain.model.Bookmark
import com.efisuby.abl.domain.model.Progress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Version of every synchronization file; readers ignore unknown fields, so additive changes keep it. */
const val SYNC_SCHEMA_VERSION = 1

/** `progress/<deviceId>.json`: the positions this device has recorded. */
@Serializable
data class ProgressFile(
    val schemaVersion: Int = SYNC_SCHEMA_VERSION,
    val deviceId: String,
    val books: List<ProgressEntry> = emptyList(),
)

@Serializable
data class ProgressEntry(
    val bookId: String,
    val chapterId: String? = null,
    val positionMs: Long,
    val totalListenedMs: Long = 0L,
    val finished: Boolean = false,
    val updatedAt: Long,
    val deviceId: String,
)

/** `bookmarks.json`: union of all devices, deletions stay as tombstones. */
@Serializable
data class BookmarksFile(
    val schemaVersion: Int = SYNC_SCHEMA_VERSION,
    val bookmarks: List<BookmarkEntry> = emptyList(),
)

@Serializable
data class BookmarkEntry(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val positionMs: Long,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** `settings/<deviceId>.json`: syncable keys only, each with its own modification time. */
@Serializable
data class SettingsFile(
    val schemaVersion: Int = SYNC_SCHEMA_VERSION,
    val deviceId: String,
    val values: Map<String, SettingEntry> = emptyMap(),
)

@Serializable
data class SettingEntry(val value: String, val updatedAt: Long)

/** The one JSON dialect of the cloud files: stable key order and layout, so re-encoding is byte-identical. */
object SyncJson {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(file: ProgressFile): String = json.encodeToString(ProgressFile.serializer(), file.canonical())

    fun encode(file: BookmarksFile): String = json.encodeToString(BookmarksFile.serializer(), file.canonical())

    fun encode(file: SettingsFile): String = json.encodeToString(SettingsFile.serializer(), file.canonical())

    fun decodeProgress(text: String): ProgressFile = json.decodeFromString(ProgressFile.serializer(), text)

    fun decodeBookmarks(text: String): BookmarksFile = json.decodeFromString(BookmarksFile.serializer(), text)

    fun decodeSettings(text: String): SettingsFile = json.decodeFromString(SettingsFile.serializer(), text)

    private fun ProgressFile.canonical() = copy(books = books.sortedBy { it.bookId })

    private fun BookmarksFile.canonical() = copy(bookmarks = bookmarks.sortedBy { it.id })

    private fun SettingsFile.canonical() = copy(values = values.toSortedMap())
}

fun Progress.toEntry() = ProgressEntry(bookId, chapterId, positionMs, totalListenedMs, finished, updatedAt, deviceId)

fun ProgressEntry.toProgress() = Progress(bookId, chapterId, positionMs, totalListenedMs, finished, updatedAt, deviceId)

fun Bookmark.toEntry() = BookmarkEntry(id, bookId, chapterId, positionMs, note, createdAt, updatedAt, deleted)

fun BookmarkEntry.toBookmark() = Bookmark(id, bookId, chapterId, positionMs, note, createdAt, updatedAt, deleted)

/** What may leave the device: only keys marked syncable. */
fun SettingsFile.syncableOnly() = copy(values = values.filterKeys { it in SyncableSettings.keys })
