package com.efisuby.abl.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.efisuby.abl.data.importer.BookImporter
import com.efisuby.abl.data.importer.ImportException
import com.efisuby.abl.data.importer.ImportProgress
import com.efisuby.abl.data.importer.ImportSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ImportUiState {
    /** Listing the picked folder; no byte counts yet. */
    data object Preparing : ImportUiState

    data class Copying(val progress: ImportProgress) : ImportUiState

    /** [reason] is null for a failure the importer did not classify. */
    data class Failed(val reason: ImportException.Reason?) : ImportUiState

    data class Done(val bookId: String) : ImportUiState
}

/**
 * Runs one import for the lifetime of the screen. It survives rotation; leaving the screen
 * clears the ViewModel, which cancels the import and lets the importer remove the partial book.
 */
class ImportViewModel(
    importer: BookImporter,
    source: () -> ImportSource,
) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Preparing)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                val book = importer.import(source()) { _state.value = ImportUiState.Copying(it) }
                ImportUiState.Done(book.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportException) {
                ImportUiState.Failed(e.reason)
            } catch (e: Exception) {
                // A picker result the documents contract rejects, or a provider bug.
                ImportUiState.Failed(null)
            }
        }
    }
}
