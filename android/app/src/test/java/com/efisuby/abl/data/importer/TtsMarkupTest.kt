package com.efisuby.abl.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsMarkupTest {

    @Test
    fun `titles from ebook2audiobook books lose stress marks and control tags`() {
        assertEquals("Дэн Абнетт Возвышение Хоруса", TtsMarkup.strip("Д+эн Абн+етт Возвыш+ение Х+оруса; "))
        assertEquals("ДЕЙСТВУЮЩИЕ ЛИЦА", TtsMarkup.strip("Д+ЕЙСТВУЮЩИЕ Л+ИЦА; "))
        assertEquals("ПРИМАРХИ Хорус. примарх, Воитель", TtsMarkup.strip("ПРИМ+АРХИ Х+орус[break]. прим+арх, Во+итель"))
        assertEquals("АНАФЕМ. Что же пропало", TtsMarkup.strip("АН+АФЕМ[break]. Чт+о ж+е проп+ало"))
        assertEquals("один; Dies Irae, лат. день гнева.", TtsMarkup.strip("од+ин; [break]Dies Irae, л+ат. [break]д+ень гн+ева. [pause:2]"))
        assertEquals("Между тем", TtsMarkup.strip("[voice:voices/rus/adult/male/Buhmin_AudioBook.mp3]Между тем"))
    }

    @Test
    fun `title made only of markup becomes null`() {
        assertNull(TtsMarkup.strip("[voice:/home/user/ebook2audiobook/tmp/proc-bdb583b7-2de3"))
        assertNull(TtsMarkup.strip(" [break] ; "))
        assertNull(TtsMarkup.strip(null))
    }

    @Test
    fun `lookalike text is kept`() {
        assertEquals("C++ для начинающих [Часть 1]", TtsMarkup.strip("C++ для начинающих [Часть 1]"))
        assertEquals("1+1=2 [voices]", TtsMarkup.strip("1+1=2 [voices]"))
        assertEquals("Глава 1. Начало", TtsMarkup.strip("  Глава 1. Начало  "))
    }

    @Test
    fun `tags are cleaned on read and a markup-only title falls back to the file name`() {
        val tags = FileTags.of("[voice:/tmp/a.wav", "Возвыш+ение Х+оруса", "Д+эн Абн+етт", null, null, null, 0)

        assertEquals("Возвышение Хоруса", tags.album)
        assertEquals("Дэн Абнетт", tags.artist)
        assertEquals("01", MetadataResolver.chapterTitle("01.m4b", tags))
    }
}
