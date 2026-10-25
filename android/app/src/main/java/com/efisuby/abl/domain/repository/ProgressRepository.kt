package com.efisuby.abl.domain.repository

import com.efisuby.abl.domain.model.Progress
import kotlinx.coroutines.flow.Flow

/**
 * Listening positions. Saves are monotonic in time: [Progress.updatedAt] never goes backwards,
 * even if the device clock does, so that merging across devices stays deterministic.
 */
interface ProgressRepository {

    fun observe(bookId: String): Flow<Progress?>

    /** Listened position from the start of each book that has progress, by book id. */
    fun observeBookPositions(): Flow<Map<String, Long>>

    suspend fun get(bookId: String): Progress?

    suspend fun savePosition(
        bookId: String,
        chapterId: String?,
        positionMs: Long,
        totalListenedMs: Long,
    )

    suspend fun markFinished(bookId: String, finished: Boolean)

    suspend fun delete(bookId: String)
}
