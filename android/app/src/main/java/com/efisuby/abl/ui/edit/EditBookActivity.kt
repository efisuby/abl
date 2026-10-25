package com.efisuby.abl.ui.edit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.repository.MetadataEdit
import com.efisuby.abl.databinding.ActivityEditBookBinding
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.ui.common.applySystemBarInsets
import com.efisuby.abl.ui.common.loadCover
import kotlinx.coroutines.launch

/** Title, author, narrator, series, series number and cover of one book. */
class EditBookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditBookBinding
    private var filled = false

    private val viewModel: EditBookViewModel by viewModels {
        val bookId = requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)) { "EditBookActivity needs a book id" }
        viewModelFactory {
            initializer { EditBookViewModel(appContainer.libraryRepository, appContainer.bookActions, contentResolver, bookId) }
        }
    }

    private val pickCover = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::pickCover)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityEditBookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        // Fields restore themselves after a configuration change; fill them only once.
        filled = savedInstanceState?.getBoolean(STATE_FILLED) == true

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId != R.id.save) return@setOnMenuItemClickListener false
            save()
            true
        }
        binding.pickCover.setOnClickListener {
            pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.events.collect(::onEvent) }
                var shownCover: Any? = null
                viewModel.state.collect { state ->
                    val book = state.book ?: return@collect
                    if (!filled) fill(book)
                    binding.toolbar.menu.findItem(R.id.save).isEnabled = !state.saving
                    val cover = state.newCover ?: book.coverPath
                    if (cover != shownCover) {
                        shownCover = cover
                        binding.cover.setImageBitmap(
                            state.newCover ?: loadCover(book.coverPath, resources.getDimensionPixelSize(R.dimen.book_cover_size)),
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_FILLED, filled)
    }

    private fun fill(book: Book) {
        filled = true
        binding.titleField.setText(book.title)
        binding.authorField.setText(book.author)
        binding.narratorField.setText(book.narrator)
        binding.seriesField.setText(book.series)
        binding.seriesIndexField.setText(book.seriesIndex?.toString())
    }

    private fun save() {
        val title = binding.titleField.text?.toString().orEmpty()
        if (title.isBlank()) {
            binding.titleLayout.error = getString(R.string.edit_title_required)
            return
        }
        binding.titleLayout.error = null
        viewModel.save(
            MetadataEdit(
                title = title,
                author = binding.authorField.text?.toString(),
                narrator = binding.narratorField.text?.toString(),
                series = binding.seriesField.text?.toString(),
                seriesIndex = binding.seriesIndexField.text?.toString()?.toIntOrNull(),
            ),
        )
    }

    private fun onEvent(event: EditEvent) {
        when (event) {
            EditEvent.Saved -> finish()
            EditEvent.SaveFailed -> Toast.makeText(this, R.string.edit_save_failed, Toast.LENGTH_LONG).show()
            EditEvent.CoverUnreadable -> Toast.makeText(this, R.string.edit_cover_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"
        private const val STATE_FILLED = "filled"

        fun intent(context: Context, bookId: String): Intent =
            Intent(context, EditBookActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
