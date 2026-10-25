package com.efisuby.abl.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class BookUiState(
    /** Null while loading and after the book was deleted. */
    val book: Book? = null,
    val chapters: List<Chapter> = emptyList(),
)

class BookViewModel(library: LibraryRepository, bookId: String) : ViewModel() {

    val state: StateFlow<BookUiState> =
        combine(library.observeBook(bookId), library.observeChapters(bookId), ::BookUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookUiState())
}
