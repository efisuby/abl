package com.efisuby.abl.playback

import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterQueueTest {

    private val mp3Folder = listOf(
        chapter("c2", 1, "/b/audio/02.mp3"),
        chapter("c1", 0, "/b/audio/01.mp3"),
        chapter("c3", 2, "/b/audio/03.mp3"),
    )

    private val m4b = listOf(
        chapter("a", 0, "/b/audio/book.m4b", startMs = 0, durationMs = 2_000),
        chapter("b", 1, "/b/audio/book.m4b", startMs = 2_000, durationMs = 2_500),
        chapter("c", 2, "/b/audio/book.m4b", startMs = 4_500, durationMs = 1_500),
    )

    @Test
    fun `mp3 chapters are whole files in chapter order`() {
        val queue = ChapterQueue.build("book", mp3Folder)

        assertEquals(listOf("c1", "c2", "c3"), queue.map { it.chapterId })
        assertEquals(listOf("book/c1", "book/c2", "book/c3"), queue.map { it.mediaId })
        queue.forEach {
            assertNull(it.clipStartMs)
            assertNull(it.clipEndMs)
        }
    }

    @Test
    fun `m4b chapters are clips of one file and the last one plays to the end`() {
        val queue = ChapterQueue.build("book", m4b)

        assertEquals(listOf(0L, 2_000L, 4_500L), queue.map { it.clipStartMs })
        assertEquals(listOf(2_000L, 4_500L, null), queue.map { it.clipEndMs })
        assertEquals(1, queue.map { it.filePath }.distinct().size)
    }

    @Test
    fun `start point prefers the picked chapter, then the saved position`() {
        val queue = ChapterQueue.build("book", m4b)
        val saved = Progress("book", "b", 1_200, updatedAt = 1, deviceId = "d")

        assertEquals(StartPoint(2, 0), ChapterQueue.startPoint(queue, saved, requestedChapterId = "c"))
        assertEquals(StartPoint(2, 700), ChapterQueue.startPoint(queue, saved, "c", requestedPositionMs = 700))
        assertEquals(StartPoint(1, 1_200), ChapterQueue.startPoint(queue, saved, requestedChapterId = null))
        assertEquals(StartPoint(0, 0), ChapterQueue.startPoint(queue, null, null))
        assertEquals(StartPoint(0, 0), ChapterQueue.startPoint(queue, saved.copy(finished = true), null))
        assertEquals(StartPoint(0, 0), ChapterQueue.startPoint(queue, saved.copy(chapterId = "gone"), null))
    }

    @Test
    fun `media ids round-trip`() {
        assertEquals(PlaybackRequest("b1"), PlaybackIds.parseRequest(PlaybackIds.request("b1")))
        assertEquals(PlaybackRequest("b1", "c1"), PlaybackIds.parseRequest(PlaybackIds.request("b1", "c1")))
        assertEquals(PlaybackRequest("b1", "c1", 4_350_000), PlaybackIds.parseRequest(PlaybackIds.request("b1", "c1", 4_350_000)))
        assertEquals("b1" to "c1", PlaybackIds.parseChapter(PlaybackIds.chapter("b1", "c1")))
        assertNull(PlaybackIds.parseRequest("b1/c1"))
        assertNull(PlaybackIds.parseChapter("abl:book:b1"))
    }

    private fun chapter(id: String, index: Int, path: String, startMs: Long = 0, durationMs: Long = 1_000) =
        Chapter(id, "book", index, "Title $id", path, startMs, durationMs)
}
