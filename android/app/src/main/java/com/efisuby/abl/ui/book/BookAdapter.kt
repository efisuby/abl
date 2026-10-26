package com.efisuby.abl.ui.book

import android.graphics.Typeface
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.efisuby.abl.R
import com.efisuby.abl.databinding.ItemBookHeaderBinding
import com.efisuby.abl.databinding.ItemBookmarkBinding
import com.efisuby.abl.databinding.ItemChapterBinding
import com.efisuby.abl.databinding.ItemSectionBinding
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.model.Bookmark
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.ui.common.formatDuration
import com.efisuby.abl.ui.common.labelRes
import com.efisuby.abl.ui.common.loadCover
import com.efisuby.abl.ui.common.setCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/** Callbacks of the book screen; [onPlay] gets null for "listen from the saved position". */
interface BookScreenActions {
    fun onPlay(chapter: Chapter?)
    fun onBookmark(bookmark: Bookmark)
    fun onDeleteBookmark(bookmark: Bookmark)
    fun onChangeStatus(anchor: View)

    /** Download button of a cloud book: start, cancel or remove, depending on its state. */
    fun onDownloadAction()
}

/** The book header, its bookmarks (when there are any) and its chapters. */
class BookAdapter(
    private val scope: CoroutineScope,
    private val actions: BookScreenActions,
) : ListAdapter<BookAdapter.Row, RecyclerView.ViewHolder>(Diff) {

    sealed interface Row {
        data class Header(val state: BookUiState) : Row
        data class Section(val title: Int) : Row
        data class BookmarkRow(val bookmark: Bookmark, val chapterTitle: String?) : Row
        data class ChapterRow(val number: Int, val chapter: Chapter, val current: Boolean) : Row
    }

    fun submit(state: BookUiState) {
        if (state.book == null) return submitList(emptyList())
        val titles = state.chapters.associate { it.id to it.title }
        val rows = buildList {
            add(Row.Header(state))
            if (state.bookmarks.isNotEmpty()) {
                add(Row.Section(R.string.book_bookmarks))
                state.bookmarks.forEach { add(Row.BookmarkRow(it, titles[it.chapterId])) }
            }
            add(Row.Section(R.string.book_chapters))
            state.chapters.forEachIndexed { i, chapter -> add(Row.ChapterRow(i + 1, chapter, chapter.id == state.currentChapterId)) }
        }
        submitList(rows)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Row.Header -> TYPE_HEADER
        is Row.Section -> TYPE_SECTION
        is Row.BookmarkRow -> TYPE_BOOKMARK
        is Row.ChapterRow -> TYPE_CHAPTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemBookHeaderBinding.inflate(inflater, parent, false))
            TYPE_SECTION -> SectionHolder(ItemSectionBinding.inflate(inflater, parent, false))
            TYPE_BOOKMARK -> BookmarkHolder(ItemBookmarkBinding.inflate(inflater, parent, false))
            else -> ChapterHolder(ItemChapterBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderHolder).bind(row.state)
            is Row.Section -> (holder as SectionHolder).bind(row)
            is Row.BookmarkRow -> (holder as BookmarkHolder).bind(row)
            is Row.ChapterRow -> (holder as ChapterHolder).bind(row)
        }
    }

    private inner class HeaderHolder(private val binding: ItemBookHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var coverPath: String? = null
        private var coverJob: Job? = null

        fun bind(state: BookUiState) {
            val book = state.book ?: return
            val context = binding.root.context
            binding.listen.setOnClickListener { actions.onPlay(null) }
            binding.status.setOnClickListener { actions.onChangeStatus(it) }
            binding.status.setText(book.status.labelRes())
            TooltipCompat.setTooltipText(binding.status, context.getString(R.string.book_change_status))
            binding.title.text = book.title
            binding.author.text = book.author
            binding.author.isVisible = book.author != null
            binding.series.text = book.series?.let { series ->
                book.seriesIndex?.let { context.getString(R.string.book_series, series, it) } ?: series
            }
            binding.series.isVisible = book.series != null
            binding.narrator.text = book.narrator?.let { context.getString(R.string.book_narrator, it) }
            binding.narrator.isVisible = book.narrator != null
            binding.summary.text = context.getString(
                R.string.book_summary,
                context.resources.getQuantityString(R.plurals.book_chapters, state.chapters.size, state.chapters.size),
                formatDuration(book.totalDurationMs),
            )

            binding.size.text = state.size?.let {
                context.getString(
                    if (it.onDevice) R.string.book_size_device else R.string.book_size_cloud,
                    Formatter.formatFileSize(context, it.bytes),
                )
            }
            binding.size.isVisible = state.size != null
            bindDownload(book)

            val current = state.chapters.indexOfFirst { it.id == state.currentChapterId }
            val started = book.status == BookStatus.IN_PROGRESS && current >= 0
            binding.progressText.isVisible = started
            binding.progress.isVisible = started
            if (started) {
                binding.progressText.text = context.getString(R.string.book_progress, current + 1, state.chapters.size, state.percent)
                binding.progress.progress = state.percent
            }

            if (book.coverPath != coverPath || coverJob == null) {
                coverPath = book.coverPath
                coverJob?.cancel()
                binding.cover.setImageResource(R.drawable.ic_cover_placeholder)
                val size = context.resources.getDimensionPixelSize(R.dimen.book_cover_size)
                coverJob = scope.launch { binding.cover.setCover(loadCover(book.coverPath, size)) }
            }
        }

        private fun bindDownload(book: Book) {
            val label = when {
                book.source != BookSource.YANDEX_DISK -> null
                book.downloadState == DownloadState.DOWNLOADING -> R.string.download_cancel_button
                book.downloadState == DownloadState.DOWNLOADED -> R.string.download_remove_button
                else -> R.string.download_start
            }
            binding.download.isVisible = label != null
            label?.let(binding.download::setText)
            binding.download.setOnClickListener { actions.onDownloadAction() }
        }
    }

    private class SectionHolder(private val binding: ItemSectionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Section) = binding.title.setText(row.title)
    }

    private inner class BookmarkHolder(private val binding: ItemBookmarkBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row.BookmarkRow) {
            val bookmark = row.bookmark
            binding.position.text = formatDuration(bookmark.positionMs)
            binding.chapter.text = row.chapterTitle
            binding.note.text = bookmark.note
            binding.note.isVisible = bookmark.note != null
            binding.root.setOnClickListener { actions.onBookmark(bookmark) }
            binding.delete.setOnClickListener { actions.onDeleteBookmark(bookmark) }
        }
    }

    private inner class ChapterHolder(private val binding: ItemChapterBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row.ChapterRow) {
            binding.root.setOnClickListener { actions.onPlay(row.chapter) }
            binding.number.text = String.format(Locale.getDefault(), "%d", row.number)
            binding.title.text = row.chapter.title
            binding.title.setTypeface(null, if (row.current) Typeface.BOLD else Typeface.NORMAL)
            binding.root.isActivated = row.current
            ViewCompat.setStateDescription(
                binding.root,
                if (row.current) binding.root.context.getString(R.string.book_current_chapter) else null,
            )
            binding.duration.text = formatDuration(row.chapter.durationMs)
        }
    }

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
            oldItem is Row.Header && newItem is Row.Header -> true
            oldItem is Row.Section && newItem is Row.Section -> oldItem.title == newItem.title
            oldItem is Row.BookmarkRow && newItem is Row.BookmarkRow -> oldItem.bookmark.id == newItem.bookmark.id
            oldItem is Row.ChapterRow && newItem is Row.ChapterRow -> oldItem.chapter.id == newItem.chapter.id
            else -> false
        }

        override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SECTION = 1
        const val TYPE_BOOKMARK = 2
        const val TYPE_CHAPTER = 3
    }
}
