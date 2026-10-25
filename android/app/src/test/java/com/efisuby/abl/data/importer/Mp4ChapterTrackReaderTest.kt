package com.efisuby.abl.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Mp4ChapterTrackReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val reader = Mp4ChapterTrackReader()

    private val expected = listOf(
        EmbeddedChapter(0, "Opening"),
        EmbeddedChapter(2_000, "Middle"),
        EmbeddedChapter(4_500, "Ending"),
    )

    @Test
    fun `quicktime chapter track gives exact boundaries and titles`() {
        assertEquals(expected, reader.read(fixture("chapters-qt-only.m4b")))
        assertEquals(expected, reader.read(fixture("chapters.m4b")))
    }

    @Test
    fun `boundaries become spans covering the file`() {
        val spans = ChapterBoundaries.build(reader.read(fixture("chapters.m4b")), fileDurationMs = 6_000)

        assertEquals(listOf(0L, 2_000L, 4_500L), spans.map { it.startMs })
        assertEquals(listOf(2_000L, 2_500L, 1_500L), spans.map { it.durationMs })
    }

    @Test
    fun `file without a chapter track gives no markers`() {
        assertTrue(reader.read(fixture("chapters-chpl-only.m4b")).isEmpty())
        assertTrue(reader.read(fixture("tagged/01.mp3")).isEmpty())
    }

    @Test
    fun `truncated or garbage files give no markers instead of failing`() {
        val original = fixture("chapters.m4b").readBytes()
        val truncated = temp.newFile("truncated.m4b").apply { writeBytes(original.copyOf(original.size / 3)) }
        val garbage = temp.newFile("garbage.m4b").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }

        assertTrue(reader.read(truncated).isEmpty())
        assertTrue(reader.read(garbage).isEmpty())
    }

    @Test
    fun `cascade prefers the first reader that finds several chapters`() {
        val single = object : ChapterReader {
            override fun read(file: File) = listOf(EmbeddedChapter(0, "whole"))
        }
        val none = object : ChapterReader {
            override fun read(file: File) = emptyList<EmbeddedChapter>()
        }
        val file = fixture("chapters.m4b")

        assertEquals(expected, CascadingChapterReader(listOf(single, reader)).read(file))
        assertEquals(listOf(EmbeddedChapter(0, "whole")), CascadingChapterReader(listOf(none, single)).read(file))
        assertTrue(CascadingChapterReader(listOf(none)).read(file).isEmpty())
    }

    private fun fixture(path: String): File {
        val url = requireNotNull(javaClass.classLoader?.getResource("fixtures/$path")) { path }
        return File(url.toURI())
    }
}
