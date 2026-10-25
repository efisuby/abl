package com.efisuby.abl.playback

import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.Progress

/**
 * One queue item of the player, free of Media3 types so that queue building is testable on the
 * JVM. [clipStartMs]/[clipEndMs] are set for chapters that share a file (m4b, design D3);
 * [clipEndMs] is null for "to the end of the file".
 */
data class QueueEntry(
    val mediaId: String,
    val chapterId: String,
    val filePath: String,
    val title: String,
    val clipStartMs: Long? = null,
    val clipEndMs: Long? = null,
)

/** Where a book starts playing: queue index and position inside that chapter. */
data class StartPoint(val index: Int, val positionMs: Long)

object ChapterQueue {

    fun build(bookId: String, chapters: List<Chapter>): List<QueueEntry> {
        val ordered = chapters.sortedBy { it.chapterIndex }
        val chaptersPerFile = ordered.groupingBy { it.filePath }.eachCount()
        return ordered.mapIndexed { i, chapter ->
            val shared = (chaptersPerFile[chapter.filePath] ?: 0) > 1 || chapter.startMs > 0
            // The last chapter of a file plays to its end: stored durations can be a little short.
            val lastInFile = ordered.getOrNull(i + 1)?.filePath != chapter.filePath
            QueueEntry(
                mediaId = PlaybackIds.chapter(bookId, chapter.id),
                chapterId = chapter.id,
                filePath = chapter.filePath,
                title = chapter.title,
                clipStartMs = if (shared) chapter.startMs else null,
                clipEndMs = if (shared && !lastInFile) chapter.startMs + chapter.durationMs else null,
            )
        }
    }

    /**
     * An explicitly picked chapter starts at [requestedPositionMs] (a bookmark) or its beginning;
     * otherwise the saved position is restored, unless the book was finished or the saved
     * chapter no longer exists.
     */
    fun startPoint(
        queue: List<QueueEntry>,
        progress: Progress?,
        requestedChapterId: String?,
        requestedPositionMs: Long = 0L,
    ): StartPoint {
        if (requestedChapterId != null) {
            val index = queue.indexOfFirst { it.chapterId == requestedChapterId }
            if (index >= 0) return StartPoint(index, requestedPositionMs.coerceAtLeast(0L))
        }
        if (progress == null || progress.finished) return StartPoint(0, 0L)
        val index = queue.indexOfFirst { it.chapterId == progress.chapterId }
        return if (index >= 0) StartPoint(index, progress.positionMs.coerceAtLeast(0L)) else StartPoint(0, 0L)
    }
}

/** What a controller asks to play: a book, optionally from a chapter and a position in it. */
data class PlaybackRequest(val bookId: String, val chapterId: String? = null, val positionMs: Long = 0L)

/**
 * Media ids shared by the player service and its controllers. A controller asks for a book with
 * a request id; the service answers with one queue item per chapter.
 */
object PlaybackIds {

    private const val REQUEST_PREFIX = "abl:book:"

    fun request(bookId: String, chapterId: String? = null, positionMs: Long = 0L): String = buildString {
        append(REQUEST_PREFIX).append(bookId)
        if (chapterId != null) {
            append(':').append(chapterId)
            if (positionMs > 0) append(':').append(positionMs)
        }
    }

    /** The request behind a request id, or null for any other media id. */
    fun parseRequest(mediaId: String): PlaybackRequest? {
        if (!mediaId.startsWith(REQUEST_PREFIX)) return null
        val parts = mediaId.removePrefix(REQUEST_PREFIX).split(':')
        val bookId = parts[0].takeIf { it.isNotEmpty() } ?: return null
        return PlaybackRequest(
            bookId = bookId,
            chapterId = parts.getOrNull(1)?.ifEmpty { null },
            positionMs = parts.getOrNull(2)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
        )
    }

    fun chapter(bookId: String, chapterId: String): String = "$bookId/$chapterId"

    /** Book and chapter of a queue item id, or null for any other media id. */
    fun parseChapter(mediaId: String): Pair<String, String>? {
        val parts = mediaId.split('/')
        return if (parts.size == 2 && parts.all { it.isNotEmpty() }) parts[0] to parts[1] else null
    }
}
