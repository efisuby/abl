package com.efisuby.abl.data.repository

import com.efisuby.abl.data.db.AblDatabase
import com.efisuby.abl.data.db.toDomain
import com.efisuby.abl.data.db.toEntity
import com.efisuby.abl.domain.model.Bookmark
import com.efisuby.abl.domain.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

class RoomBookmarkRepository(
    database: AblDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : BookmarkRepository {

    private val bookmarkDao = database.bookmarkDao()
    private val chapterDao = database.chapterDao()

    // The DAO orders by position only, which interleaves chapters; chapter order comes first.
    override fun observe(bookId: String): Flow<List<Bookmark>> =
        combine(bookmarkDao.observeActiveByBook(bookId), chapterDao.observeByBook(bookId)) { bookmarks, chapters ->
            val order = chapters.associate { it.id to it.chapterIndex }
            bookmarks.map { it.toDomain() }
                .sortedWith(compareBy({ order[it.chapterId] ?: Int.MAX_VALUE }, { it.positionMs }))
        }

    override suspend fun add(bookId: String, chapterId: String, positionMs: Long, note: String?): Bookmark {
        val now = clock()
        val bookmark = Bookmark(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            chapterId = chapterId,
            positionMs = positionMs.coerceAtLeast(0L),
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = now,
            updatedAt = now,
        )
        bookmarkDao.upsert(bookmark.toEntity())
        return bookmark
    }

    override suspend fun delete(id: String) {
        bookmarkDao.markDeleted(id, clock())
    }
}
