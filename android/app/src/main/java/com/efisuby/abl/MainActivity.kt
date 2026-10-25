package com.efisuby.abl

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.efisuby.abl.databinding.ActivityMainBinding
import com.efisuby.abl.ui.book.BookActivity
import com.efisuby.abl.ui.common.applySystemBarInsets
import com.efisuby.abl.ui.common.formatDuration
import com.efisuby.abl.ui.common.loadCover
import com.efisuby.abl.ui.importer.ImportActivity
import com.efisuby.abl.ui.player.PlayerActivity
import com.efisuby.abl.ui.library.BookListAdapter
import com.efisuby.abl.ui.library.ContinueListening
import com.efisuby.abl.ui.library.LibraryViewModel
import kotlinx.coroutines.launch

/** Library list with the two import entry points: a folder (one book) or a single file. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var continueCoverPath: String? = null

    private val viewModel: LibraryViewModel by viewModels {
        viewModelFactory {
            initializer { LibraryViewModel(appContainer.libraryRepository, appContainer.progressRepository) }
        }
    }

    // Files are copied right away, so the temporary grant of the picker is enough: no
    // persistable permission is taken.
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { startImport(it, isFolder = true) }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { startImport(it, isFolder = false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        val adapter = BookListAdapter(lifecycleScope) { startActivity(BookActivity.intent(this, it.id)) }
        binding.books.layoutManager = LinearLayoutManager(this)
        binding.books.adapter = adapter

        val continuePlayback = View.OnClickListener {
            viewModel.continueListening.value?.let { startActivity(PlayerActivity.intent(this, it.book.id)) }
        }
        binding.continueCard.setOnClickListener(continuePlayback)
        binding.continuePlay.setOnClickListener(continuePlayback)

        binding.importFolder.setOnClickListener { pickFolder.launch(null) }
        // Providers report m4b inconsistently (audio/mp4, audio/x-m4b, application/octet-stream);
        // the importer checks extensions itself and explains a wrong pick.
        binding.importFile.setOnClickListener { pickFile.launch(arrayOf("audio/*", "application/octet-stream")) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.books.collect { books ->
                        adapter.submitList(books)
                        binding.empty.isVisible = books?.isEmpty() == true
                    }
                }
                viewModel.continueListening.collect(::renderContinue)
            }
        }
    }

    private fun renderContinue(card: ContinueListening?) {
        binding.continueCard.isVisible = card != null
        if (card == null) return
        binding.continueTitle.text = card.book.title
        binding.continueChapter.text = card.chapterTitle
        binding.continuePosition.text = if (card.chapterDurationMs > 0) {
            getString(R.string.continue_position, formatDuration(card.positionMs), formatDuration(card.chapterDurationMs))
        } else {
            formatDuration(card.positionMs)
        }
        if (card.book.coverPath != continueCoverPath) {
            continueCoverPath = card.book.coverPath
            val size = resources.getDimensionPixelSize(R.dimen.continue_cover_size)
            lifecycleScope.launch { binding.continueCover.setImageBitmap(loadCover(card.book.coverPath, size)) }
        }
    }

    private fun startImport(uri: Uri, isFolder: Boolean) {
        startActivity(ImportActivity.intent(this, uri, isFolder))
    }
}
