package com.efisuby.abl.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.Player
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.databinding.DialogBookmarkBinding
import com.efisuby.abl.databinding.ItemBookmarkBinding
import com.efisuby.abl.databinding.SheetBookmarksBinding
import com.efisuby.abl.domain.model.Bookmark
import com.efisuby.abl.playback.PlaybackIds
import com.efisuby.abl.ui.common.formatDuration
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Bookmarks of the book in [player]: adding one at the current position with an optional note,
 * and a sheet listing them for jumping and deleting. The position is taken when the user taps
 * "add", not when the note is saved.
 */
internal class Bookmarks(
    private val activity: AppCompatActivity,
    private val scope: LifecycleCoroutineScope,
    private val player: () -> Player?,
) {
    private val repository get() = activity.appContainer.bookmarkRepository

    fun showAdd() {
        val controller = player() ?: return
        val (bookId, chapterId) = controller.currentMediaItem?.mediaId?.let(PlaybackIds::parseChapter) ?: return
        val positionMs = controller.currentPosition
        val dialog = DialogBookmarkBinding.inflate(activity.layoutInflater)
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.bookmark_new_title, formatDuration(positionMs)))
            .setView(dialog.root)
            .setPositiveButton(R.string.bookmark_save) { _, _ ->
                val note = dialog.note.text?.toString()
                activity.appContainer.applicationScope.launch { repository.add(bookId, chapterId, positionMs, note) }
                Snackbar.make(activity.findViewById(android.R.id.content), R.string.bookmark_added, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.bookmark_cancel, null)
            .show()
    }

    fun showList() {
        val controller = player() ?: return
        val bookId = controller.currentMediaItem?.mediaId?.let(PlaybackIds::parseChapter)?.first ?: return
        // Chapter titles and queue positions come from the player: the queue is the book.
        val chapters = (0 until controller.mediaItemCount).associate { index ->
            val item = controller.getMediaItemAt(index)
            PlaybackIds.parseChapter(item.mediaId)?.second to (index to item.mediaMetadata.title?.toString())
        }

        val sheet = BottomSheetDialog(activity)
        val binding = SheetBookmarksBinding.inflate(activity.layoutInflater)
        val adapter = BookmarkAdapter(
            chapterTitle = { chapters[it.chapterId]?.second },
            onJump = { bookmark ->
                val index = chapters[bookmark.chapterId]?.first
                player()?.let { current ->
                    if (index != null) {
                        current.seekTo(index, bookmark.positionMs)
                        current.play()
                    }
                }
                sheet.dismiss()
            },
            onDelete = { bookmark -> activity.appContainer.applicationScope.launch { repository.delete(bookmark.id) } },
        )
        binding.list.layoutManager = LinearLayoutManager(activity)
        binding.list.adapter = adapter
        sheet.setContentView(binding.root)

        val job = scope.launch {
            repository.observe(bookId).collectLatest { bookmarks ->
                adapter.submitList(bookmarks)
                binding.empty.isVisible = bookmarks.isEmpty()
            }
        }
        sheet.setOnDismissListener { job.cancel() }
        sheet.show()
    }
}

private class BookmarkAdapter(
    private val chapterTitle: (Bookmark) -> String?,
    private val onJump: (Bookmark) -> Unit,
    private val onDelete: (Bookmark) -> Unit,
) : ListAdapter<Bookmark, BookmarkAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemBookmarkBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(bookmark: Bookmark) {
            binding.position.text = formatDuration(bookmark.positionMs)
            binding.chapter.text = chapterTitle(bookmark)
            binding.note.text = bookmark.note
            binding.note.isVisible = bookmark.note != null
            binding.root.setOnClickListener { onJump(bookmark) }
            binding.delete.setOnClickListener { onDelete(bookmark) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Bookmark>() {
        override fun areItemsTheSame(oldItem: Bookmark, newItem: Bookmark) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Bookmark, newItem: Bookmark) = oldItem == newItem
    }
}
