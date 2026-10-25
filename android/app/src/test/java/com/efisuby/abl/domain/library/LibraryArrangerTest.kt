package com.efisuby.abl.domain.library

import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator
import java.util.Locale

class LibraryArrangerTest {

    private val arranger = LibraryArranger(Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.SECONDARY })

    // Ten books: two series of three, four standalone, mixed statuses and availability.
    private val books = listOf(
        book("h2", "Лжебоги", "Грэм Макнилл", series = "Ересь Хоруса", index = 2, added = 20, listened = 500),
        book("h1", "Возвышение Хоруса", "Дэн Абнетт", series = "Ересь Хоруса", index = 1, added = 10, status = BookStatus.FINISHED),
        book("h3", "Галактика в огне", "Бен Каунтер", series = "ересь хоруса ", index = 3, added = 30),
        book("bull", "Час Быка", "Иван Ефремов", added = 5, status = BookStatus.IN_PROGRESS, listened = 900),
        book("andr", "Туманность Андромеды", "Иван Ефремов", added = 4, status = BookStatus.FINISHED),
        book("dune", "Dune", "Frank Herbert", added = 50, cloud = true),
        book("ёж", "Ёжик в тумане", "Сергей Козлов", added = 40),
        book("m1", "Mistborn", "Brandon Sanderson", series = "Mistborn", index = null, added = 60, cloud = true),
        book("m2", "The Well of Ascension", "Brandon Sanderson", series = "Mistborn", index = 2, added = 61),
        book("solo", "Пикник на обочине", null, series = "Одиночка", index = 1, added = 70, listened = 100),
    )

    @Test
    fun `series are grouped and ordered by number, 2-1-3 becomes 1-2-3`() {
        val rows = arranger.arrange(books, LibraryQuery())

        val horus = rows.dropWhile { it != LibraryRow.SeriesHeader("Ересь Хоруса", 3) }.drop(1).take(3)
        assertEquals(listOf("h1", "h2", "h3"), horus.map { (it as LibraryRow.BookRow).book.id })
        assertEquals(true, horus.all { (it as LibraryRow.BookRow).inSeries })
    }

    @Test
    fun `title order mixes series and standalone books by name, unnumbered books last in a series`() {
        val rows = arranger.arrange(books, LibraryQuery())

        assertEquals(
            listOf(
                // Russian collation sorts Ё as Е: "Ёжик" comes before "Ересь".
                "Dune", "[Mistborn]", "m2", "m1", "Ёжик в тумане", "[Ересь Хоруса]", "h1", "h2", "h3",
                "Пикник на обочине", "Туманность Андромеды", "Час Быка",
            ),
            rows.map(::label),
        )
    }

    @Test
    fun `a series with a single book in the library is not a group`() {
        val rows = arranger.arrange(books, LibraryQuery())

        assertEquals(false, rows.any { it is LibraryRow.SeriesHeader && it.name == "Одиночка" })
    }

    @Test
    fun `search matches title, author and series case-insensitively in any script`() {
        fun ids(text: String) = arranger.arrange(books, LibraryQuery(text = text))
            .filterIsInstance<LibraryRow.BookRow>().map { it.book.id }.toSet()

        assertEquals(setOf("bull", "andr"), ids("ефремов"))
        assertEquals(setOf("h1", "h2", "h3"), ids("ХОРУСА"))
        assertEquals(setOf("ёж"), ids("ежик"))
        assertEquals(setOf("m1", "m2"), ids("sanderson"))
        assertEquals(setOf("andr"), ids("иван андромеды"))
        assertEquals(emptySet<String>(), ids("толстой"))
    }

    @Test
    fun `filters by status and availability`() {
        fun ids(query: LibraryQuery) = arranger.arrange(books, query)
            .filterIsInstance<LibraryRow.BookRow>().map { it.book.id }.toSet()

        assertEquals(setOf("h1", "andr"), ids(LibraryQuery(status = BookStatus.FINISHED)))
        assertEquals(setOf("bull"), ids(LibraryQuery(status = BookStatus.IN_PROGRESS)))
        assertEquals(setOf("dune", "m1"), ids(LibraryQuery(availability = Availability.IN_CLOUD)))
        assertEquals(8, ids(LibraryQuery(availability = Availability.ON_DEVICE)).size)
        assertEquals(emptySet<String>(), ids(LibraryQuery(status = BookStatus.IN_PROGRESS, availability = Availability.IN_CLOUD)))
    }

    @Test
    fun `sorting by author, date added and last listened`() {
        fun order(sort: SortOrder) = arranger.arrange(books, LibraryQuery(sort = sort)).map(::label)

        assertEquals(listOf("[Mistborn]", "m2", "m1", "Dune"), order(SortOrder.AUTHOR).take(4))
        assertEquals("Пикник на обочине", order(SortOrder.AUTHOR).last()) // no author goes last
        assertEquals(listOf("Пикник на обочине", "[Mistborn]", "m2", "m1", "Dune"), order(SortOrder.ADDED).take(5))
        assertEquals(listOf("Час Быка", "[Ересь Хоруса]", "h1", "h2", "h3", "Пикник на обочине"), order(SortOrder.LAST_LISTENED).take(6))
    }

    private fun label(row: LibraryRow) = when (row) {
        is LibraryRow.SeriesHeader -> "[${row.name}]"
        is LibraryRow.BookRow -> if (row.inSeries) row.book.id else row.book.title
    }

    private fun book(
        id: String,
        title: String,
        author: String?,
        series: String? = null,
        index: Int? = null,
        added: Long,
        status: BookStatus = BookStatus.NOT_STARTED,
        listened: Long? = null,
        cloud: Boolean = false,
    ) = Book(
        id = id,
        title = title,
        author = author,
        series = series,
        seriesIndex = index,
        source = if (cloud) BookSource.YANDEX_DISK else BookSource.LOCAL,
        status = status,
        downloadState = if (cloud) DownloadState.IN_CLOUD else DownloadState.DOWNLOADED,
        addedAt = added,
        lastListenedAt = listened,
    )
}
