package com.efisuby.abl.data.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterBoundariesTest {

    @Test
    fun `markers become contiguous spans ending at the file end`() {
        val spans = ChapterBoundaries.build(
            listOf(EmbeddedChapter(0, "Opening"), EmbeddedChapter(2_000, "Middle"), EmbeddedChapter(4_500, "Ending")),
            fileDurationMs = 6_000,
        )

        assertEquals(
            listOf(
                ChapterSpan(0, 2_000, "Opening"),
                ChapterSpan(2_000, 2_500, "Middle"),
                ChapterSpan(4_500, 1_500, "Ending"),
            ),
            spans,
        )
    }

    @Test
    fun `unsorted and duplicated markers are normalized`() {
        val spans = ChapterBoundaries.build(
            listOf(EmbeddedChapter(4_000, "B"), EmbeddedChapter(1_000, "A"), EmbeddedChapter(4_000, "B again")),
            fileDurationMs = 10_000,
        )

        // The first span is stretched to 0 so the start of the file is reachable.
        assertEquals(listOf(ChapterSpan(0, 4_000, "A"), ChapterSpan(4_000, 6_000, "B")), spans)
    }

    @Test
    fun `markers past the end of the file are dropped`() {
        val spans = ChapterBoundaries.build(
            listOf(EmbeddedChapter(0, "A"), EmbeddedChapter(5_000, "B"), EmbeddedChapter(12_000, "ghost")),
            fileDurationMs = 10_000,
        )

        assertEquals(listOf(ChapterSpan(0, 5_000, "A"), ChapterSpan(5_000, 5_000, "B")), spans)
    }

    @Test
    fun `no markers give one chapter for the whole file`() {
        assertEquals(listOf(ChapterSpan(0, 7_000, null)), ChapterBoundaries.build(emptyList(), 7_000))
    }

    @Test
    fun `a single marker gives one chapter that keeps its title`() {
        assertEquals(
            listOf(ChapterSpan(0, 7_000, "Only")),
            ChapterBoundaries.build(listOf(EmbeddedChapter(0, "Only")), 7_000),
        )
    }
}
