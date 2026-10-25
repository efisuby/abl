package com.efisuby.abl.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Mp4TagsTest {

    @Test
    fun `quicktime keys metadata is read as text`() {
        val keys = Mp4Tags.read(fixture("mdta-tags.m4b"))

        assertEquals("Возвышение Хоруса", keys["title"])
        assertEquals("Дэн Абнетт", keys["artist"])
        assertEquals("ebook2audiobook", keys["publisher"])
    }

    @Test
    fun `files without these tags give nothing`() {
        assertTrue(Mp4Tags.read(fixture("chapters.m4b")).isEmpty())
        assertTrue(Mp4Tags.read(fixture("tagged/01.mp3")).isEmpty())
    }

    private fun fixture(path: String): File {
        val url = requireNotNull(javaClass.classLoader?.getResource("fixtures/$path")) { path }
        return File(url.toURI())
    }
}
