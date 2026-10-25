package com.efisuby.abl.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.efisuby.abl.R
import com.efisuby.abl.databinding.ItemBookBinding
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.ui.common.formatDuration
import com.efisuby.abl.ui.common.loadCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A minimal book row: cover, title, author and length. Statuses and progress come with task 4.1. */
class BookListAdapter(
    private val scope: CoroutineScope,
    private val onClick: (Book) -> Unit,
) : ListAdapter<Book, BookListAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: Holder) = holder.recycle()

    inner class Holder(private val binding: ItemBookBinding) : RecyclerView.ViewHolder(binding.root) {

        private var coverJob: Job? = null

        fun bind(book: Book) {
            binding.root.setOnClickListener { onClick(book) }
            binding.title.text = book.title
            val duration = formatDuration(book.totalDurationMs)
            binding.subtitle.text = book.author?.let { "$it · $duration" } ?: duration
            recycle()
            val size = binding.root.resources.getDimensionPixelSize(R.dimen.list_cover_size)
            coverJob = scope.launch { binding.cover.setImageBitmap(loadCover(book.coverPath, size)) }
        }

        fun recycle() {
            coverJob?.cancel()
            binding.cover.setImageDrawable(null)
        }
    }

    private object Diff : DiffUtil.ItemCallback<Book>() {
        override fun areItemsTheSame(oldItem: Book, newItem: Book) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Book, newItem: Book) = oldItem == newItem
    }
}
