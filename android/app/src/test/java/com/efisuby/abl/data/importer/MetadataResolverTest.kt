package com.efisuby.abl.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataResolverTest {

    private val fullTags = FileTags.of(
        title = "Chapter 1",
        album = "The Bull's Hour",
        artist = "Ivan Yefremov",
        albumArtist = null,
        author = null,
        composer = "Sergey Larionov",
        durationMs = 1_000L,
    )

    private val partialTags = FileTags.of(
        title = "Only a title",
        album = "  ",
        artist = null,
        albumArtist = null,
        author = null,
        composer = "",
        durationMs = 1_000L,
    )

    @Test
    fun `full tags fill the book`() {
        val book = MetadataResolver.resolveBook("some-folder", fullTags, isFolder = true)

        assertEquals(BookMetadata("The Bull's Hour", "Ivan Yefremov", "Sergey Larionov"), book)
        assertEquals("Chapter 1", MetadataResolver.chapterTitle("01.mp3", fullTags))
    }

    @Test
    fun `partial tags in a folder fall back to the folder name for the book`() {
        val book = MetadataResolver.resolveBook("Ivan Yefremov - The Bull's Hour", partialTags, isFolder = true)

        // The title tag names a chapter, never the book, when a folder was picked.
        assertEquals(BookMetadata("The Bull's Hour", "Ivan Yefremov", null), book)
        assertEquals("Only a title", MetadataResolver.chapterTitle("01.mp3", partialTags))
    }

    @Test
    fun `partial tags in a single file name the book`() {
        val book = MetadataResolver.resolveBook("whatever.m4b", partialTags, isFolder = false)

        assertEquals("Only a title", book.title)
        assertNull(book.author)
    }

    @Test
    fun `no tags take title and author from the folder name`() {
        val book = MetadataResolver.resolveBook("Ivan Yefremov - The Bull's Hour", FileTags.EMPTY, isFolder = true)

        assertEquals(BookMetadata("The Bull's Hour", "Ivan Yefremov", null), book)
        assertEquals("01", MetadataResolver.chapterTitle("01.mp3", FileTags.EMPTY))
    }

    @Test
    fun `no tags in a single file use the file name without extension`() {
        val book = MetadataResolver.resolveBook("Иван Ефремов — Час Быка.m4b", FileTags.EMPTY, isFolder = false)

        assertEquals(BookMetadata("Час Быка", "Иван Ефремов", null), book)
    }

    @Test
    fun `album artist wins over track artist`() {
        val tags = fullTags.copy(albumArtist = "Ефремов И.")

        assertEquals("Ефремов И.", MetadataResolver.resolveBook("x", tags, isFolder = true).author)
    }

    @Test
    fun `folder names without a separator are a title only`() {
        assertEquals(null to "Hyphenated-Name", FolderNameParser.parse("Hyphenated-Name"))
        assertEquals("A" to "B - C", FolderNameParser.parse("A - B - C"))
    }
}
