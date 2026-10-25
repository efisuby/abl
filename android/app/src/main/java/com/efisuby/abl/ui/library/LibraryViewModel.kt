package com.efisuby.abl.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.repository.LibraryRepository
import com.efisuby.abl.domain.repository.ProgressRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The "Continue listening" card: the last played book and where it stopped. */
data class ContinueListening(
    val book: Book,
    val chapterTitle: String?,
    val positionMs: Long,
    val chapterDurationMs: Long,
)

class LibraryViewModel(library: LibraryRepository, progress: ProgressRepository) : ViewModel() {

    /** Null until the first database read, so the empty state does not flash on start. */
    val books: StateFlow<List<Book>?> = library.observeBooks()
        .map<List<Book>, List<Book>?> { it }
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
