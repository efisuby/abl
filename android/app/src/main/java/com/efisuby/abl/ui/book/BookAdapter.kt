package com.efisuby.abl.ui.book

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.efisuby.abl.R
import com.efisuby.abl.databinding.ItemBookHeaderBinding
import com.efisuby.abl.databinding.ItemChapterBinding
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.ui.common.formatDuration
import com.efisuby.abl.ui.common.loadCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/** The book header followed by its chapters; [onPlay] gets null for "listen from the saved position". */
class BookAdapter(
    private val scope: CoroutineScope,
    private val onPlay: (Chapter?) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var state = BookUiState()

    // The whole screen changes only on import or metadata edits, so a full rebind is cheap enough.
    @SuppressLint("NotifyDataSetChanged")
    fun submit(newState: BookUiState) {
        state = newState
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (state.book == null) 0 else 1 + state.chapters.size

    override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_HEADER else TYPE_CHAPTER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemBookHeaderBinding.inflate(inflater, parent, false))
        } else {
            ChapterHolder(ItemChapterBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> holder.bind(state)
            is ChapterHolder -> holder.bind(position, state.chapters[position - 1])
        }
    }

    private inner class HeaderHolder(private val binding: ItemBookHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var coverPath: String? = null
        private var coverJob: Job? = null

        fun bind(state: BookUiState) {
            val book = state.book ?: return
            val context = binding.root.context
            binding.listen.setOnClickListener { onPlay(null) }
            binding.title.text = book.title
            binding.author.text = book.author
            binding.author.isVisible = book.author != null
            binding.narrator.text = book.narrator?.let { context.getString(R.string.book_narrator, it) }
            binding.narrator.isVisible = book.narrator != null
            binding.summary.text = context.getString(
                R.string.book_summary,
                context.resources.getQuantityString(R.plurals.book_chapters, state.chapters.size, state.chapters.size),
                formatDuration(book.totalDurationMs),
            )
            if (book.coverPath != coverPath || coverJob == null) {
                coverPath = book.coverPath
                coverJob?.cancel()
                binding.cover.setImageDrawable(null)
                val size = context.resources.getDimensionPixelSize(R.dimen.book_cover_size)
                coverJob = scope.launch { binding.cover.setImageBitmap(loadCover(book.coverPath, size)) }
            }
        }
    }

    private inner class ChapterHolder(private val binding: ItemChapterBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(number: Int, chapter: Chapter) {
            binding.root.setOnClickListener { onPlay(chapter) }
            binding.number.text = String.format(Locale.getDefault(), "%d", number)
            binding.title.text = chapter.title
            binding.duration.text = formatDuration(chapter.durationMs)
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CHAPTER = 1
    }
}
