package com.efisuby.abl.ui.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.importer.ImportException
import com.efisuby.abl.databinding.ActivityImportBinding
import com.efisuby.abl.ui.book.BookActivity
import com.efisuby.abl.ui.common.applySystemBarInsets
import kotlinx.coroutines.launch

/** Copies what the user picked into the library, shows progress and opens the new book. */
class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding

    private val viewModel: ImportViewModel by viewModels {
        val uri = requireNotNull(intent.data) { "ImportActivity needs a picked URI" }
        val isFolder = intent.getBooleanExtra(EXTRA_FOLDER, false)
        viewModelFactory {
            initializer {
                val container = appContainer
                ImportViewModel(container.bookImporter) {
                    if (isFolder) container.importSources.folder(uri) else container.importSources.file(uri)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        // Leaving the screen cancels the import, see ImportViewModel.
        binding.action.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: ImportUiState) {
        val running = state is ImportUiState.Preparing || state is ImportUiState.Copying
        // Copying a large book takes minutes; do not let the screen lock interrupt watching it.
        if (running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        binding.progress.isVisible = running
        binding.action.setText(if (running) R.string.import_cancel else R.string.import_close)

        when (state) {
            ImportUiState.Preparing -> {
                binding.status.setText(R.string.import_preparing)
                binding.progress.isIndeterminate = true
                binding.file.text = null
                binding.bytes.text = null
            }
            is ImportUiState.Copying -> {
                val p = state.progress
                binding.status.setText(R.string.import_copying)
                binding.file.text = getString(R.string.import_file, p.fileIndex + 1, p.fileCount, p.currentFile)
                if (p.totalBytes > 0) {
                    binding.progress.isIndeterminate = false
                    binding.progress.setProgressCompat((p.copiedBytes * PROGRESS_MAX / p.totalBytes).toInt(), false)
                    binding.bytes.text = getString(
                        R.string.import_bytes,
                        Formatter.formatShortFileSize(this, p.copiedBytes),
                        Formatter.formatShortFileSize(this, p.totalBytes),
                    )
                } else {
                    binding.progress.isIndeterminate = true
                    binding.bytes.text = Formatter.formatShortFileSize(this, p.copiedBytes)
                }
            }
            is ImportUiState.Failed -> {
                binding.status.setText(R.string.import_failed)
                binding.file.setText(
                    when (state.reason) {
                        ImportException.Reason.NO_AUDIO_FILES -> R.string.import_error_no_audio
                        ImportException.Reason.IO -> R.string.import_error_io
                        null -> R.string.import_error_unknown
                    },
                )
                binding.bytes.text = null
            }
            is ImportUiState.Done -> {
                startActivity(BookActivity.intent(this, state.bookId))
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_FOLDER = "folder"
        private const val PROGRESS_MAX = 1000L

        fun intent(context: Context, uri: Uri, isFolder: Boolean): Intent =
            Intent(context, ImportActivity::class.java)
                .setData(uri)
                .putExtra(EXTRA_FOLDER, isFolder)
                // The picker grant must reach this activity: it reads the documents itself.
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
