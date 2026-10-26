package com.efisuby.abl

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.efisuby.abl.databinding.ActivityMainBinding
import com.efisuby.abl.domain.library.Availability
import com.efisuby.abl.domain.library.LibraryQuery
import com.efisuby.abl.domain.library.SortOrder
import com.efisuby.abl.domain.model.BookStatus
import com.google.android.material.chip.Chip
import com.efisuby.abl.ui.book.BookActivity
import com.efisuby.abl.ui.common.applySystemBarInsets
import com.efisuby.abl.ui.common.formatDuration
import com.efisuby.abl.ui.common.labelRes
import com.efisuby.abl.ui.common.loadCover
import com.efisuby.abl.ui.importer.ImportActivity
import com.efisuby.abl.ui.player.PlayerActivity
import com.efisuby.abl.ui.settings.SettingsActivity
import com.efisuby.abl.ui.library.BookListAdapter
import com.efisuby.abl.ui.library.ContinueListening
import com.efisuby.abl.ui.library.LibraryUiState
import com.efisuby.abl.ui.library.LibraryViewModel
import kotlinx.coroutines.launch

/**
 * Library list with search, filters, sort and series groups, plus the two import entry points:
 * a folder (one book) or a single file.
 */
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
        setUpSearch()
        setUpFilters()
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId != R.id.settings) return@setOnMenuItemClickListener false
            startActivity(SettingsActivity.intent(this))
            true
        }

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
                    viewModel.state.collect { state -> state?.let { render(it, adapter) } }
                }
                launch { viewModel.query.collect(::renderQuery) }
                viewModel.continueListening.collect(::renderContinue)
            }
        }
    }

    private fun setUpSearch() {
        val item = binding.toolbar.menu.findItem(R.id.search)
        val search = item.actionView as SearchView
        search.queryHint = getString(R.string.library_search)
        // Restores the field after a configuration change: the query lives in the view model.
        val text = viewModel.query.value.text
        if (text.isNotEmpty()) {
            item.expandActionView()
            search.setQuery(text, false)
            search.clearFocus()
        }
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                search.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                viewModel.setText(newText)
                return true
            }
        })
        binding.resetFilters.setOnClickListener {
            item.collapseActionView()
            viewModel.resetFilters()
        }
    }

    private fun setUpFilters() {
        binding.statusChip.setOnClickListener { chip ->
            val options = listOf<BookStatus?>(null) + BookStatus.entries
            showOptions(chip, options.map { it?.labelRes() ?: R.string.filter_status_all }) { viewModel.setStatus(options[it]) }
        }
        binding.availabilityChip.setOnClickListener { chip ->
            val options = Availability.entries
            showOptions(chip, options.map { it.labelRes() }) { viewModel.setAvailability(options[it]) }
        }
        binding.sortChip.setOnClickListener { chip ->
            val options = SortOrder.entries
            showOptions(chip, options.map { it.labelRes() }) { viewModel.setSort(options[it]) }
        }
        listOf(binding.statusChip, binding.availabilityChip, binding.sortChip).forEach { chip ->
            chip.setOnCloseIconClickListener { chip.performClick() }
        }
    }

    private fun showOptions(anchor: View, labels: List<Int>, onPick: (Int) -> Unit) {
        val popup = PopupMenu(this, anchor)
        labels.forEachIndexed { index, label -> popup.menu.add(0, index, index, label) }
        popup.setOnMenuItemClickListener { item ->
            onPick(item.itemId)
            true
        }
        popup.show()
    }

    private fun renderQuery(query: LibraryQuery) {
        binding.statusChip.render(query.status?.labelRes() ?: R.string.filter_status_all, active = query.status != null)
        binding.availabilityChip.render(query.availability.labelRes(), active = query.availability != Availability.ALL)
        binding.sortChip.render(query.sort.labelRes(), active = false)
    }

    private fun Chip.render(label: Int, active: Boolean) {
        setText(label)
        // Not checkable, so "checked" is only the highlight of an applied filter.
        isCheckable = true
        isChecked = active
        isCheckable = false
    }

    private fun render(state: LibraryUiState, adapter: BookListAdapter) {
        adapter.submitList(state.items)
        val nothingFound = !state.libraryEmpty && state.items.isEmpty()
        binding.empty.isVisible = state.libraryEmpty || nothingFound
        binding.emptyText.setText(if (state.libraryEmpty) R.string.library_empty else R.string.library_nothing_found)
        binding.resetFilters.isVisible = nothingFound && state.query.isFiltered
        binding.filters.isVisible = !state.libraryEmpty
        binding.toolbar.menu.findItem(R.id.search).isVisible = !state.libraryEmpty
        // The card would hide search results, and the book is one tap away in the list anyway.
        binding.continueCard.isVisible = viewModel.continueListening.value != null && !state.query.isFiltered
    }

    private fun renderContinue(card: ContinueListening?) {
        binding.continueCard.isVisible = card != null && viewModel.query.value.isFiltered.not()
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

    private fun Availability.labelRes(): Int = when (this) {
        Availability.ALL -> R.string.filter_availability_all
        Availability.ON_DEVICE -> R.string.availability_on_device
        Availability.IN_CLOUD -> R.string.availability_in_cloud
    }

    private fun SortOrder.labelRes(): Int = when (this) {
        SortOrder.TITLE -> R.string.sort_title
        SortOrder.AUTHOR -> R.string.sort_author
        SortOrder.ADDED -> R.string.sort_added
        SortOrder.LAST_LISTENED -> R.string.sort_last_listened
    }
}
