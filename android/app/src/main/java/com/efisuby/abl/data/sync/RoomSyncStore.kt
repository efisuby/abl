package com.efisuby.abl.data.sync

import androidx.room.withTransaction
import com.efisuby.abl.data.db.AblDatabase
import com.efisuby.abl.data.db.entity.BookmarkEntity
import com.efisuby.abl.data.db.entity.ProgressEntity
import com.efisuby.abl.data.settings.SettingsRepository
import com.efisuby.abl.data.settings.SyncableSettings
import com.efisuby.abl.data.disk.CloudBookImporter
import com.efisuby.abl.data.disk.DiskException
import com.efisuby.abl.data.disk.DiskResource
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.importer.ImportException
import com.efisuby.abl.domain.model.BookStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** [SyncStore] on the Room database and the settings store. */
class RoomSyncStore(
    private val database: AblDatabase,
    private val settings: SettingsRepository,
    private val storage: BookStorage,
    private val importer: CloudBookImporter,
    /** Looks a Disk path up; throws [DiskException.NotFound] when it is gone. */
    private val resolve: suspend (String) -> DiskResource,
) : SyncStore {

    private val books = database.bookDao()
    private val progressDao = database.progressDao()
    private val bookmarkDao = database.bookmarkDao()

    override suspend fun progress(): List<ProgressEntry> =
        progressDao.getAll().map { it.toDomainEntry() }

    override suspend fun applyProgress(entries: Collection<ProgressEntry>) {
        database.withTransaction {
            for (entry in entries) {
                val book = books.getById(entry.bookId) ?: continue // the book is not on this device
                val current = progressDao.getByBook(entry.bookId)
                if (current != null && current.updatedAt >= entry.updatedAt && current.deviceId == entry.deviceId) continue
                progressDao.upsert(
                    ProgressEntity(
                        entry.bookId, entry.chapterId, entry.positionMs, entry.totalListenedMs,
                        entry.finished, entry.updatedAt, entry.deviceId,
                    ),
                )
                val status = when {
                    entry.finished -> BookStatus.FINISHED
                    book.status == BookStatus.FINISHED.name -> BookStatus.IN_PROGRESS
                    book.status == BookStatus.NOT_STARTED.name -> BookStatus.IN_PROGRESS
                    else -> null
                }
                if (status != null) books.updateStatus(entry.bookId, status.name)
                if (entry.updatedAt > book.lastListenedAt.orZero()) books.updateLastListenedAt(entry.bookId, entry.updatedAt)
            }
        }
    }

    override suspend fun bookmarks(): List<BookmarkEntry> =
        bookmarkDao.getAll().map {
            BookmarkEntry(it.id, it.bookId, it.chapterId, it.positionMs, it.note, it.createdAt, it.updatedAt, it.deleted)
        }

    override suspend fun applyBookmarks(entries: Collection<BookmarkEntry>) {
        database.withTransaction {
            val present = entries.filter { books.getById(it.bookId) != null }
            bookmarkDao.upsertAll(
                present.map {
                    BookmarkEntity(it.id, it.bookId, it.chapterId, it.positionMs, it.note, it.createdAt, it.updatedAt, it.deleted)
                },
            )
        }
    }

    override suspend fun settings(): Map<String, SettingEntry> {
        val speed = settings.syncedSpeed() ?: return emptyMap()
        return mapOf(SyncableSettings.KEY_PLAYBACK_SPEED to SettingEntry(speed.first.toString(), speed.second))
    }

    override suspend fun applySettings(values: Map<String, SettingEntry>) {
        values[SyncableSettings.KEY_PLAYBACK_SPEED]?.let { entry ->
            entry.value.toFloatOrNull()?.let { settings.applySyncedSpeed(it, entry.updatedAt) }
        }
    }

    override suspend fun books(): List<LibraryEntry> = books.getAll().map {
        LibraryEntry(
            bookId = it.id, title = it.title, author = it.author, narrator = it.narrator,
            series = it.series, seriesIndex = it.seriesIndex, remotePath = it.remotePath,
            totalDurationMs = it.totalDurationMs, addedAt = it.addedAt, metadataUpdatedAt = it.metadataUpdatedAt,
            hasCover = it.coverPath?.let { path -> File(path).isFile } == true,
        )
    }

    override suspend fun cover(bookId: String): ByteArray? = withContext(Dispatchers.IO) {
        books.getById(bookId)?.coverPath?.let(::File)?.takeIf { it.isFile }?.readBytes()
    }

    override suspend fun applyBook(entry: LibraryEntry, cover: ByteArray?) {
        val book = books.getById(entry.bookId) ?: return
        if (entry.metadataUpdatedAt <= book.metadataUpdatedAt) return
        val coverPath = cover?.let {
            withContext(Dispatchers.IO) { storage.replaceCover(entry.bookId, it, entry.metadataUpdatedAt).absolutePath }
        } ?: book.coverPath
        books.updateMetadata(
            id = entry.bookId, title = entry.title, author = entry.author, narrator = entry.narrator,
            series = entry.series, seriesIndex = entry.seriesIndex, coverPath = coverPath,
            metadataUpdatedAt = entry.metadataUpdatedAt,
        )
    }

    override suspend fun libraryRestored(): Boolean = settings.libraryRestored()

    override suspend fun markLibraryRestored() = settings.markLibraryRestored()

    override suspend fun restoreBook(entry: LibraryEntry, cover: ByteArray?): Boolean {
        val path = entry.remotePath ?: return false
        try {
            importer.import(resolve(path), entry.bookId)
        } catch (e: DiskException.NotFound) {
            return false
        } catch (e: ImportException) {
            return false // the folder has no audio any more
        }
        val book = books.getById(entry.bookId) ?: return false
        val coverPath = cover?.let {
            withContext(Dispatchers.IO) { storage.replaceCover(entry.bookId, it, entry.metadataUpdatedAt).absolutePath }
        }
        books.upsert(
            book.copy(
                title = entry.title, author = entry.author, narrator = entry.narrator,
                series = entry.series, seriesIndex = entry.seriesIndex, totalDurationMs = entry.totalDurationMs,
                addedAt = entry.addedAt, metadataUpdatedAt = entry.metadataUpdatedAt,
                coverPath = coverPath ?: book.coverPath,
            ),
        )
        return true
    }

    private fun ProgressEntity.toDomainEntry() =
        ProgressEntry(bookId, chapterId, positionMs, totalListenedMs, finished, updatedAt, deviceId)

    private fun Long?.orZero() = this ?: 0L
}
