package com.efisuby.abl.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SeriesTagsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `id3 v2_4 user frames carry the series`() {
        val frames = Id3Frames.read(fixture("series-id3/01.mp3"))

        assertEquals("Ересь Хоруса", frames["series"])
        assertEquals("1", frames["series-part"])
        assertEquals("Chapter 1", frames["tit2"])
    }

    @Test
    fun `id3 v2_3 utf-16 movement frames carry the series`() {
        val frames = Id3Frames.read(fixture("series-mvnm.mp3"))

        assertEquals("Ересь Хоруса", frames["mvnm"])
        assertEquals("2/10", frames["mvin"])
    }

    @Test
    fun `mp4 free-form and movement items carry the series`() {
        val freeForm = Mp4Tags.read(fixture("series.m4b"))
        assertEquals("Ересь Хоруса", freeForm["series"])
        assertEquals("3", freeForm["series-part"])

        val movement = Mp4Tags.read(fixture("series-mvn.m4b"))
        assertEquals("Ересь Хоруса", movement["mvnm"])
        assertEquals("4", movement["mvin"])
    }

    @Test
    fun `files without a tag or with garbage give nothing`() {
        val garbage = temp.newFile("garbage.mp3").apply { writeBytes("ID3".toByteArray() + ByteArray(64) { 0x7F }) }

        assertTrue(Id3Frames.read(fixture("chapters.m4b")).isEmpty())
        assertTrue(Id3Frames.read(garbage).isEmpty())
        assertTrue(Mp4Tags.read(fixture("tagged/01.mp3")).isEmpty())
    }

    @Test
    fun `series index takes the first number of the part tag`() {
        assertEquals(3, MetadataResolver.seriesIndex("3"))
        assertEquals(3, MetadataResolver.seriesIndex("03"))
        assertEquals(2, MetadataResolver.seriesIndex("2/10"))
        assertEquals(7, MetadataResolver.seriesIndex("Book 7"))
        assertNull(MetadataResolver.seriesIndex("first"))
        assertNull(MetadataResolver.seriesIndex(null))
    }

    @Test
    fun `book gets the series from tags and no index without a series`() {
        val tags = FileTags.of("Ch 1", "Возвышение Хоруса", "Дэн Абнетт", null, null, null, 0, "Ересь Хоруса", "1/54")
        val book = MetadataResolver.resolveBook("folder", tags, isFolder = true)
        assertEquals("Ересь Хоруса", book.series)
        assertEquals(1, book.seriesIndex)

        val orphan = MetadataResolver.resolveBook("folder", tags.copy(series = null), isFolder = true)
        assertNull(orphan.series)
        assertNull(orphan.seriesIndex)
    }

    private fun fixture(path: String): File {
        val url = requireNotNull(javaClass.classLoader?.getResource("fixtures/$path")) { path }
        return File(url.toURI())
    }
}
