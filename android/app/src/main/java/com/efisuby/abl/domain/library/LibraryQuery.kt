package com.efisuby.abl.domain.library

import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.DownloadState
import java.text.Collator
import java.util.Locale

/** Where the audio is: on this device or still (partly) in the cloud. */
enum class Availability { ALL, ON_DEVICE, IN_CLOUD }

enum class SortOrder { TITLE, AUTHOR, ADDED, LAST_LISTENED }

/** Search text plus filters and sort order of the library list (library spec). */
data class LibraryQuery(
    val text: String = "",
    /** Null shows every status. */
    val status: BookStatus? = null,
    val availability: Availability = Availability.ALL,
    val sort: SortOrder = SortOrder.TITLE,
) {
    val isFiltered: Boolean get() = text.isNotBlank() || status != null || availability != Availability.ALL
}

/** A row of the library list. */
sealed interface LibraryRow {
    data class SeriesHeader(val name: String, val count: Int) : LibraryRow

    /** [inSeries] marks a book shown under a [SeriesHeader]. */
    data class BookRow(val book: Book, val inSeries: Boolean) : LibraryRow
}

/**
 * Filters, groups and sorts the library in memory. A personal library is small, and SQLite's
 * case-insensitive matching covers ASCII only, so a query in Russian would miss books.
 *
 * Books sharing a series (two or more) form a group ordered by series number; a group takes its
 * place in the list by the series name, or by its newest member for the date orders.
 */
class LibraryArranger(
    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY },
) {

    fun arrange(books: List<Book>, query: LibraryQuery): List<LibraryRow> {
        val tokens = normalize(query.text).split(' ').filter { it.isNotEmpty() }
        val matching = books.filter { book ->
            (query.status == null || book.status == query.status) &&
                query.availability.accepts(book.downloadState) &&
                tokens.all { token -> searchText(book).contains(token) }
        }

        val bySeries = matching.filter { !it.series.isNullOrBlank() }
            .groupBy { normalize(it.series!!) }
            .filterValues { it.size > 1 }
        val grouped = bySeries.values.flatten().toSet()
        val entries = bySeries.values.map { Entry.Series(it.sortedWith(seriesOrder)) } +
            matching.filter { it !in grouped }.map { Entry.Single(it) }

        return entries.sortedWith(entryOrder(query.sort)).flatMap { entry ->
            when (entry) {
                is Entry.Single -> listOf(LibraryRow.BookRow(entry.book, inSeries = false))
                is Entry.Series -> listOf(LibraryRow.SeriesHeader(entry.name, entry.books.size)) +
                    entry.books.map { LibraryRow.BookRow(it, inSeries = true) }
            }
        }
    }

    private sealed interface Entry {
        val name: String
        val author: String?
        val addedAt: Long
        val lastListenedAt: Long?

        data class Single(val book: Book) : Entry {
            override val name get() = book.title
            override val author get() = book.author
            override val addedAt get() = book.addedAt
            override val lastListenedAt get() = book.lastListenedAt
        }

        data class Series(val books: List<Book>) : Entry {
            override val name get() = books.first().series!!.trim()
            override val author get() = books.firstNotNullOfOrNull { it.author }
            override val addedAt get() = books.maxOf { it.addedAt }
            override val lastListenedAt get() = books.mapNotNull { it.lastListenedAt }.maxOrNull()
        }
    }

    private val byName = Comparator<Entry> { a, b -> collator.compare(a.name, b.name) }

    private fun entryOrder(sort: SortOrder): Comparator<Entry> = when (sort) {
        SortOrder.TITLE -> byName
        SortOrder.AUTHOR -> compareBy<Entry, String?>(nullsLast(collator)) { it.author }.then(byName)
        SortOrder.ADDED -> compareByDescending<Entry> { it.addedAt }.then(byName)
        SortOrder.LAST_LISTENED -> compareByDescending<Entry, Long?>(nullsFirst()) { it.lastListenedAt }.then(byName)
    }

    /** Series number first, books without one after the numbered ones, then by title. */
    private val seriesOrder: Comparator<Book> =
        compareBy<Book, Int?>(nullsLast()) { it.seriesIndex }.thenComparator { a, b -> collator.compare(a.title, b.title) }

    private fun searchText(book: Book): String =
        normalize(listOfNotNull(book.title, book.author, book.series).joinToString(" "))

    private fun Availability.accepts(state: DownloadState): Boolean = when (this) {
        Availability.ALL -> true
        Availability.ON_DEVICE -> state == DownloadState.DOWNLOADED
        Availability.IN_CLOUD -> state != DownloadState.DOWNLOADED
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("\\s+"), " ").trim()
}
