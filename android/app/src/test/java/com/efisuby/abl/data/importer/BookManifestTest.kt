package com.efisuby.abl.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookManifestTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val manifest = BookManifest(
        id = "book-1",
        title = "Час Быка",
        author = "Иван Ефремов",
        narrator = null,
        source = "LOCAL",
        cover = "cover.jpg",
        totalDurationMs = 7_200_000L,
        addedAt = 1_700_000_000_000L,
        chapters = listOf(
            ManifestChapter("c1", 0, "Пролог", "audio/part-1/01.mp3", 0L, 3_600_000L),
            ManifestChapter("c2", 1, "Глава 1", "audio/part-1/02.mp3", 0L, 3_600_000L),
        ),
        files = listOf(
            ManifestFile("audio/part-1/01.mp3", 1_000L, "d41d8cd98f00b204e9800998ecf8427e"),
            ManifestFile("audio/part-1/02.mp3", 2_000L, "0cc175b9c0f1b6a831c399e269772661"),
        ),
    )

    @Test
    fun `manifest round-trips and re-encodes identically`() {
        val text = BookManifestCodec.encode(manifest)
        val decoded = BookManifestCodec.decode(text)

        assertEquals(manifest, decoded)
        assertEquals(text, BookManifestCodec.encode(decoded))
    }

    @Test
    fun `schema version and nulls are written explicitly`() {
        val text = BookManifestCodec.encode(manifest)

        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertTrue(text.contains("\"narrator\": null"))
    }

    @Test
    fun `fields added by a newer version are ignored`() {
        val text = BookManifestCodec.encode(manifest)
            .replaceFirst("{", "{\n    \"introducedLater\": {\"x\": 1},")

        assertEquals(manifest, BookManifestCodec.decode(text))
    }

    @Test
    fun `writing leaves only the final file`() {
        val target = temp.newFolder("book-1").resolve(BookStorage.MANIFEST_FILE)

        BookManifestCodec.write(manifest, target)

        assertEquals(manifest, BookManifestCodec.read(target))
        assertFalse(target.resolveSibling(target.name + BookStorage.PART_SUFFIX).exists())
    }
}
