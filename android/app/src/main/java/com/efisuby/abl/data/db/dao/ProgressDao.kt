package com.efisuby.abl.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.efisuby.abl.data.db.entity.ProgressEntity
import kotlinx.coroutines.flow.Flow

/** Position from the start of the book: earlier chapters plus the offset in the saved one. */
data class BookPositionRow(val bookId: String, val positionMs: Long, val finished: Boolean)

@Dao
interface ProgressDao {

    @Query(
        """
        SELECT p.bookId AS bookId,
            p.positionMs + COALESCE((
                SELECT SUM(c.durationMs) FROM chapters c
                WHERE c.bookId = p.bookId
                    AND c.chapterIndex < (SELECT cur.chapterIndex FROM chapters cur WHERE cur.id = p.chapterId)
            ), 0) AS positionMs,
            p.finished AS finished
        FROM progress p
        """,
    )
    fun observeBookPositions(): Flow<List<BookPositionRow>>

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    fun observeByBook(bookId: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun getByBook(bookId: String): ProgressEntity?

    @Query("SELECT * FROM progress")
    suspend fun getAll(): List<ProgressEntity>

    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}
