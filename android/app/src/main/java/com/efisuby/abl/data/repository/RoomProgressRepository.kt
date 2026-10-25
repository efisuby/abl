package com.efisuby.abl.data.repository

import androidx.room.withTransaction
import com.efisuby.abl.data.db.AblDatabase
import com.efisuby.abl.data.db.entity.ProgressEntity
import com.efisuby.abl.data.db.toDomain
import com.efisuby.abl.domain.model.Progress
import com.efisuby.abl.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomProgressRepository(
    private val database: AblDatabase,
    private val deviceIdProvider: DeviceIdProvider,
) : ProgressRepository {

    private val progressDao = database.progressDao()

    override fun observe(bookId: String): Flow<Progress?> =
        progressDao.observeByBook(bookId).map { it?.toDomain() }

    override suspend fun get(bookId: String): Progress? =
        progressDao.getByBook(bookId)?.toDomain()

    override suspend fun savePosition(
        bookId: String,
        chapterId: String?,
        positionMs: Long,
        totalListenedMs: Long,
    ) {
        database.withTransaction {
            val current = progressDao.getByBook(bookId)
            val now = System.currentTimeMillis()
            // Monotonic: a clock that jumped backwards must not make a newer position look older.
            val updatedAt = maxOf(now, (current?.updatedAt ?: 0L) + 1L)
            progressDao.upsert(
                ProgressEntity(
                    bookId = bookId,
                    chapterId = chapterId,
                    positionMs = positionMs,
                    totalListenedMs = totalListenedMs,
                    finished = false,
                    updatedAt = updatedAt,
                    deviceId = deviceIdProvider.deviceId(),
                ),
            )
            database.bookDao().updateLastListenedAt(bookId, updatedAt)
        }
    }

    override suspend fun markFinished(bookId: String, finished: Boolean) {
        database.withTransaction {
            val current = progressDao.getByBook(bookId) ?: return@withTransaction
            progressDao.upsert(
                current.copy(
                    finished = finished,
                    updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1L),
                ),
            )
        }
    }

    override suspend fun delete(bookId: String) {
        progressDao.deleteByBook(bookId)
    }
}
