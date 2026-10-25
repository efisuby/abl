package com.efisuby.abl.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.efisuby.abl.data.repository.BookActions
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.Bookmark
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.Progress
import com.efisuby.abl.domain.repository.BookmarkRepository
import com.efisuby.abl.domain.repository.LibraryRepository
import com.efisuby.abl.domain.repository.ProgressRepository
import com.efisuby.abl.ui.library.listenedPercent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookUiState(
    /** Null while loading and after the book was deleted. */
    val book: Book? = null,
    val chapters: List<Chapter> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val progress: Progress? = null,
    val loaded: Boolean = false,
) {
    /** The chapter playback continues from, if the book was started. */
    val currentChapterId: String? get() = progress?.chapterId?.takeIf { book?.status == BookStatus.IN_PROGRESS }

    val percent: Int get() {
        val book = book ?: return 0
        val index = chapters.indexOfFirst { it.id == progress?.chapterId }
        val position = if (index < 0) null else chapters.take(index).sumOf { it.durationMs } + (progress?.positionMs ?: 0L)
        return listenedPercent(book, position)
    }
}

class BookViewModel(
    library: LibraryRepository,
    progress: ProgressRepository,
    bookmarks: BookmarkRepository,
    private val actions: BookActions,
    /** Deletion and status writes outlive the screen that asked for them. */
    private val writeScope: CoroutineScope,
    private val bookId: String,
) : ViewModel() {

    val state: StateFlow<BookUiState> =
        combine(
            library.observeBook(bookId),
            library.observeChapters(bookId),
            bookmarks.observe(bookId),
            progress.observe(bookId),
        ) { book, chapters, marks, saved -> BookUiState(book, chapters, marks, saved, loaded = true) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookUiState())

    fun setStatus(status: BookStatus) {
        writeScope.launch { actions.setStatus(bookId, status) }
    }

    fun delete() {
        writeScope.launch { actions.delete(bookId) }
    }
}
