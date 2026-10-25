package com.efisuby.abl.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.efisuby.abl.data.db.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): BookEntity?

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    @Query(
        """
        UPDATE books
        SET title = :title,
            author = :author,
            narrator = :narrator,
            series = :series,
            seriesIndex = :seriesIndex,
            coverPath = :coverPath
        WHERE id = :id
        """,
    )
    suspend fun updateMetadata(
        id: String,
        title: String,
        author: String?,
        narrator: String?,
        series: String?,
        seriesIndex: Int?,
        coverPath: String?,
    )

    @Query("UPDATE books SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE books SET lastListenedAt = :timestamp WHERE id = :id")
    suspend fun updateLastListenedAt(id: String, timestamp: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)
}
