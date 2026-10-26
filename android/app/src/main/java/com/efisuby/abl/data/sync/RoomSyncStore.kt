package com.efisuby.abl.data.sync

import androidx.room.withTransaction
import com.efisuby.abl.data.db.AblDatabase
import com.efisuby.abl.data.db.entity.BookmarkEntity
import com.efisuby.abl.data.db.entity.ProgressEntity
import com.efisuby.abl.data.settings.SettingsRepository
import com.efisuby.abl.data.settings.SyncableSettings
import com.efisuby.abl.domain.model.BookStatus

/** [SyncStore] on the Room database and the settings store. */
class RoomSyncStore(
    private val database: AblDatabase,
    private val settings: SettingsRepository,
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

    private fun ProgressEntity.toDomainEntry() =
        ProgressEntry(bookId, chapterId, positionMs, totalListenedMs, finished, updatedAt, deviceId)

    private fun Long?.orZero() = this ?: 0L
}
