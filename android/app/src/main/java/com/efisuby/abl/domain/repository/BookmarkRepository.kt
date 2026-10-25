package com.efisuby.abl.domain.repository

import com.efisuby.abl.domain.model.Bookmark
import kotlinx.coroutines.flow.Flow

/** Bookmarks of a book. Deletion leaves a tombstone so that synchronization can carry it. */
interface BookmarkRepository {

    /** Active bookmarks in chapter order, then by position inside the chapter. */
    fun observe(bookId: String): Flow<List<Bookmark>>

    suspend fun add(bookId: String, chapterId: String, positionMs: Long, note: String?): Bookmark

    suspend fun delete(id: String)
}
