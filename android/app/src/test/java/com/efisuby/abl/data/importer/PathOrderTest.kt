package com.efisuby.abl.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathOrderTest {

    @Test
    fun `nested folders are ordered folder by folder`() {
        val paths = listOf("part-1/02.mp3", "part-1/01.mp3", "part-2/01.mp3")

        assertEquals(
            listOf("part-1/01.mp3", "part-1/02.mp3", "part-2/01.mp3"),
            paths.sortedWith(PathOrder),
        )
    }

    @Test
    fun `numbers compare by value, not as text`() {
        val paths = listOf("10.mp3", "2.mp3", "1.mp3", "Chapter 11.mp3", "Chapter 9.mp3")

        assertEquals(
            listOf("1.mp3", "2.mp3", "10.mp3", "Chapter 9.mp3", "Chapter 11.mp3"),
            paths.sortedWith(PathOrder),
        )
    }

    @Test
    fun `part-10 comes after part-9`() {
        assertTrue(PathOrder.compare("part-9/01.mp3", "part-10/01.mp3") < 0)
    }

    @Test
    fun `letters compare case-insensitively but the order stays total`() {
        assertTrue(PathOrder.compare("a.mp3", "B.mp3") < 0)
        assertFalse(PathOrder.compare("A.mp3", "a.mp3") == 0)
        assertTrue(PathOrder.compare("01.mp3", "1.mp3") != 0)
    }

    @Test
    fun `unsupported files are recognised by extension`() {
        assertTrue(AudioFormats.isSupported("book.M4B"))
        assertTrue(AudioFormats.isSupported("01.mp3"))
        assertFalse(AudioFormats.isSupported("cover.jpg"))
        assertFalse(AudioFormats.isSupported("mp3"))
    }
}
