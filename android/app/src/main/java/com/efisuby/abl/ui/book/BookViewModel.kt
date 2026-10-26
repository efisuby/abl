package com.efisuby.abl.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.efisuby.abl.data.disk.DownloadController
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.repository.BookActions
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.Bookmark
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.model.Progress
import com.efisuby.abl.domain.repository.BookmarkRepository
import com.efisuby.abl.domain.repository.LibraryRepository
import com.efisuby.abl.domain.repository.ProgressRepository
import com.efisuby.abl.ui.library.listenedPercent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
    val size: BookSize? = null,
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

/** [bytes] taken on the device, or, for a book in the cloud, what it will take once downloaded. */
data class BookSize(val bytes: Long, val onDevice: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class BookViewModel(
    library: LibraryRepository,
    progress: ProgressRepository,
    bookmarks: BookmarkRepository,
    private val actions: BookActions,
    private val storage: BookStorage,
    private val downloads: DownloadController,
    /** Deletion and status writes outlive the screen that asked for them. */
    private val writeScope: CoroutineScope,
    private val bookId: String,
) : ViewModel() {

    // Recomputed when the download state changes, not on every progress tick.
    private val size = library.observeBook(bookId)
        .map { book -> book?.takeIf { it.source == BookSource.YANDEX_DISK }?.downloadState to (book != null) }
        .distinctUntilChanged()
        .mapLatest { (downloadState, exists) ->
            when {
                !exists -> null
                downloadState == null || downloadState == DownloadState.DOWNLOADED ->
                    BookSize(withContext(Dispatchers.IO) { storage.bookBytes(bookId) }, onDevice = true)
                else -> downloads.totalBytes(bookId)?.let { BookSize(it, onDevice = false) }
            }
        }

    val state: StateFlow<BookUiState> =
        combine(
            library.observeBook(bookId),
            library.observeChapters(bookId),
            bookmarks.observe(bookId),
            progress.observe(bookId),
            size,
        ) { book, chapters, marks, saved, size -> BookUiState(book, chapters, marks, saved, loaded = true, size = size) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookUiState())

    fun setStatus(status: BookStatus) {
        writeScope.launch { actions.setStatus(bookId, status) }
    }

    fun cancelDownload() {
        writeScope.launch { downloads.cancel(bookId) }
    }

    fun removeDownload() {
        writeScope.launch { actions.removeDownload(bookId) }
    }

    fun delete() {
        writeScope.launch { actions.delete(bookId) }
    }
}
