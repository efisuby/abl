package com.efisuby.abl.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.efisuby.abl.data.db.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND deleted = 0 ORDER BY positionMs ASC")
    fun observeActiveByBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND deleted = 0 ORDER BY positionMs ASC")
    suspend fun getActiveByBook(bookId: String): List<BookmarkEntity>

    /** Includes tombstones: they must reach the cloud before they can be dropped locally. */
    @Query("SELECT * FROM bookmarks")
    suspend fun getAll(): List<BookmarkEntity>

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    @Upsert
    suspend fun upsertAll(bookmarks: List<BookmarkEntity>)

    @Query("UPDATE bookmarks SET deleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, updatedAt: Long)
}
