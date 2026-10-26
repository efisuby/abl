package com.efisuby.abl.ui.book

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.databinding.ActivityBookBinding
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.Bookmark
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.ui.common.applySystemBarInsets
import com.efisuby.abl.ui.common.labelRes
import com.efisuby.abl.ui.common.needsDownload
import com.efisuby.abl.ui.common.offerDownload
import com.efisuby.abl.ui.common.startDownload
import com.efisuby.abl.ui.edit.EditBookActivity
import com.efisuby.abl.ui.player.PlayerActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Cover, metadata, progress, bookmarks and chapters of one book, with status change, editing and
 * deletion. "Listen", a chapter or a bookmark opens the player.
 */
class BookActivity : AppCompatActivity(), BookScreenActions {

    private lateinit var binding: ActivityBookBinding
    private val bookId: String by lazy {
        requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)) { "BookActivity needs a book id" }
    }

    private val viewModel: BookViewModel by viewModels {
        viewModelFactory {
            initializer {
                val container = appContainer
                BookViewModel(
                    container.libraryRepository,
                    container.progressRepository,
                    container.bookmarkRepository,
                    container.bookActions,
                    container.bookStorage,
                    container.downloadController,
                    container.applicationScope,
                    bookId,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityBookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.edit -> startActivity(EditBookActivity.intent(this, bookId))
                R.id.delete -> confirmDelete()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        val adapter = BookAdapter(lifecycleScope, this)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    // Deleted here or elsewhere: nothing left to show.
                    if (state.loaded && state.book == null) {
                        finish()
                        return@collect
                    }
                    adapter.submit(state)
                }
            }
        }
    }

    override fun onPlay(chapter: Chapter?) {
        if (!offerDownloadIfNeeded()) startActivity(PlayerActivity.intent(this, bookId, chapter?.id))
    }

    override fun onBookmark(bookmark: Bookmark) {
        if (!offerDownloadIfNeeded()) {
            startActivity(PlayerActivity.intent(this, bookId, bookmark.chapterId, bookmark.positionMs))
        }
    }

    /** A cloud book that is not on the device cannot play: offer to download it instead. */
    private fun offerDownloadIfNeeded(): Boolean {
        val book = viewModel.state.value.book?.takeIf { it.needsDownload } ?: return false
        offerDownload(book)
        return true
    }

    override fun onDownloadAction() {
        val book = viewModel.state.value.book ?: return
        when (book.downloadState) {
            DownloadState.DOWNLOADING -> viewModel.cancelDownload()
            DownloadState.DOWNLOADED -> confirmRemoveDownload(book.title)
            else -> startDownload(bookId)
        }
    }

    private fun confirmRemoveDownload(title: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_remove_button)
            .setMessage(getString(R.string.download_remove_message, title))
            .setPositiveButton(R.string.settings_remove_downloads_confirm) { _, _ ->
                viewModel.removeDownload()
                Toast.makeText(this, R.string.download_removed, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.bookmark_cancel, null)
            .show()
    }

    override fun onDeleteBookmark(bookmark: Bookmark) {
        appContainer.applicationScope.launch { appContainer.bookmarkRepository.delete(bookmark.id) }
    }

    override fun onChangeStatus(anchor: View) {
        val popup = PopupMenu(this, anchor)
        BookStatus.entries.forEachIndexed { index, status -> popup.menu.add(0, index, index, status.labelRes()) }
        popup.setOnMenuItemClickListener { item ->
            viewModel.setStatus(BookStatus.entries[item.itemId])
            true
        }
        popup.show()
    }

    private fun confirmDelete() {
        val title = viewModel.state.value.book?.title ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.book_delete_title)
            .setMessage(getString(R.string.book_delete_message, title))
            .setPositiveButton(R.string.book_delete_confirm) { _, _ ->
                viewModel.delete()
                Toast.makeText(this, R.string.book_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.bookmark_cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"

        fun intent(context: Context, bookId: String): Intent =
            Intent(context, BookActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
