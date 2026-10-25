package com.efisuby.abl.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.efisuby.abl.domain.library.Availability
import com.efisuby.abl.domain.library.LibraryArranger
import com.efisuby.abl.domain.library.LibraryQuery
import com.efisuby.abl.domain.library.LibraryRow
import com.efisuby.abl.domain.library.SortOrder
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.repository.LibraryRepository
import com.efisuby.abl.domain.repository.ProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** The "Continue listening" card: the last played book and where it stopped. */
data class ContinueListening(
    val book: Book,
    val chapterTitle: String?,
    val positionMs: Long,
    val chapterDurationMs: Long,
)

/** A row of the library list as shown: series headers and books with their listened share. */
sealed interface LibraryItem {
    data class SeriesHeader(val name: String, val count: Int) : LibraryItem

    data class BookItem(val book: Book, val inSeries: Boolean, val percent: Int) : LibraryItem
}

data class LibraryUiState(
    val items: List<LibraryItem>,
    /** True when there are no books at all, as opposed to none matching the query. */
    val libraryEmpty: Boolean,
    val query: LibraryQuery,
)

class LibraryViewModel(
    library: LibraryRepository,
    progress: ProgressRepository,
    private val arranger: LibraryArranger = LibraryArranger(),
) : ViewModel() {

    private val _query = MutableStateFlow(LibraryQuery())
    val query: StateFlow<LibraryQuery> = _query

    /** Null until the first database read, so the empty state does not flash on start. */
    val state: StateFlow<LibraryUiState?> =
        combine(library.observeBooks(), progress.observeBookPositions(), _query) { books, positions, query ->
            LibraryUiState(
                items = arranger.arrange(books, query).map { row -> row.toItem(positions) },
                libraryEmpty = books.isEmpty(),
                query = query,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A finished book is not offered for continuing. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val continueListening: StateFlow<ContinueListening?> = library.observeBooks()
        .map { books ->
            books.filter { it.lastListenedAt != null && it.status != BookStatus.FINISHED }
                .maxByOrNull { it.lastListenedAt ?: 0L }
        }
        .distinctUntilChanged()
        .flatMapLatest { book -> book?.let { card(library, progress, it) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setText(text: String) = _query.update { it.copy(text = text) }

    fun setStatus(status: BookStatus?) = _query.update { it.copy(status = status) }

    fun setAvailability(availability: Availability) = _query.update { it.copy(availability = availability) }

    fun setSort(sort: SortOrder) = _query.update { it.copy(sort = sort) }

    /** Clears search text and filters; the sort order is a preference, not a filter, and stays. */
    fun resetFilters() = _query.update { LibraryQuery(sort = it.sort) }

    private fun LibraryRow.toItem(positions: Map<String, Long>): LibraryItem = when (this) {
        is LibraryRow.SeriesHeader -> LibraryItem.SeriesHeader(name, count)
        is LibraryRow.BookRow -> LibraryItem.BookItem(book, inSeries, listenedPercent(book, positions[book.id]))
    }

    private fun card(library: LibraryRepository, progress: ProgressRepository, book: Book): Flow<ContinueListening?> =
        combine(progress.observe(book.id), library.observeChapters(book.id)) { saved, chapters ->
            val chapter = chapters.firstOrNull { it.id == saved?.chapterId }
            ContinueListening(
                book = book,
                chapterTitle = chapter?.title,
                positionMs = saved?.positionMs ?: 0L,
                chapterDurationMs = chapter?.durationMs ?: 0L,
            )
        }
}

/** Share of the book before the saved position; a finished book counts as fully listened. */
internal fun listenedPercent(book: Book, positionMs: Long?): Int = when {
    book.status == BookStatus.FINISHED -> 100
    positionMs == null || book.totalDurationMs <= 0 -> 0
    else -> (positionMs * 100 / book.totalDurationMs).toInt().coerceIn(0, 100)
}
