package com.efisuby.abl.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.efisuby.abl.R
import com.efisuby.abl.databinding.ItemBookBinding
import com.efisuby.abl.databinding.ItemSeriesHeaderBinding
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.ui.common.formatDuration
import com.efisuby.abl.ui.common.labelRes
import com.efisuby.abl.ui.common.needsDownload
import com.efisuby.abl.ui.common.loadCover
import com.efisuby.abl.ui.common.setCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Library rows: series headers, and books with cover, author, length, status and listened share. */
class BookListAdapter(
    private val scope: CoroutineScope,
    private val onClick: (Book) -> Unit,
) : ListAdapter<LibraryItem, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is LibraryItem.SeriesHeader -> TYPE_SERIES
        is LibraryItem.BookItem -> TYPE_BOOK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SERIES) {
            SeriesHolder(ItemSeriesHeaderBinding.inflate(inflater, parent, false))
        } else {
            BookHolder(ItemBookBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is LibraryItem.SeriesHeader -> (holder as SeriesHolder).bind(item)
            is LibraryItem.BookItem -> (holder as BookHolder).bind(item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? BookHolder)?.recycle()
    }

    private class SeriesHolder(private val binding: ItemSeriesHeaderBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LibraryItem.SeriesHeader) {
            binding.name.text = item.name
            binding.count.text = binding.root.resources.getQuantityString(R.plurals.series_books, item.count, item.count)
        }
    }

    private inner class BookHolder(private val binding: ItemBookBinding) : RecyclerView.ViewHolder(binding.root) {

        private val basePaddingStart = binding.root.paddingStart
        private var coverJob: Job? = null

        fun bind(item: LibraryItem.BookItem) {
            val book = item.book
            val context = binding.root.context
            binding.root.setOnClickListener { onClick(book) }
            binding.root.updatePaddingRelative(
                start = basePaddingStart + if (item.inSeries) context.resources.getDimensionPixelSize(R.dimen.series_indent) else 0,
            )
            binding.title.text = book.title
            binding.subtitle.text = listOfNotNull(
                book.seriesIndex?.takeIf { item.inSeries }?.let { context.getString(R.string.list_series_index, it) },
                book.author,
                formatDuration(book.totalDurationMs),
            ).joinToString(" · ")

            val status = context.getString(book.status.labelRes())
            val statusText = if (book.status == BookStatus.IN_PROGRESS) {
                context.getString(R.string.status_with_percent, status, item.percent)
            } else {
                status
            }
            // Cloud books that are not (fully) on the device say so next to the listening status.
            val cloud = book.downloadState.labelRes()?.takeIf { book.needsDownload }?.let(context::getString)
            binding.status.text = listOfNotNull(statusText, cloud).joinToString(" · ")
            binding.progress.isVisible = book.status == BookStatus.IN_PROGRESS
            binding.progress.progress = item.percent

            recycle()
            val size = context.resources.getDimensionPixelSize(R.dimen.list_cover_size)
            coverJob = scope.launch { binding.cover.setCover(loadCover(book.coverPath, size)) }
        }

        fun recycle() {
            coverJob?.cancel()
            binding.cover.setImageResource(R.drawable.ic_cover_placeholder)
        }
    }

    private object Diff : DiffUtil.ItemCallback<LibraryItem>() {
        override fun areItemsTheSame(oldItem: LibraryItem, newItem: LibraryItem) = when {
            oldItem is LibraryItem.BookItem && newItem is LibraryItem.BookItem -> oldItem.book.id == newItem.book.id
            oldItem is LibraryItem.SeriesHeader && newItem is LibraryItem.SeriesHeader -> oldItem.name == newItem.name
            else -> false
        }

        override fun areContentsTheSame(oldItem: LibraryItem, newItem: LibraryItem) = oldItem == newItem
    }

    private companion object {
        const val TYPE_SERIES = 0
        const val TYPE_BOOK = 1
    }
}
