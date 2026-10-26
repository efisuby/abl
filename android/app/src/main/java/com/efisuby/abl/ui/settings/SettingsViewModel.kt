package com.efisuby.abl.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.efisuby.abl.data.auth.AuthRepository
import com.efisuby.abl.data.auth.AuthState
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.repository.BookActions
import com.efisuby.abl.data.settings.SettingsRepository
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val wifiOnly: Boolean,
    val lastSyncAt: Long?,
    /** Bytes taken by all books on the device. */
    val usedBytes: Long,
    /** Cloud books whose audio "Delete all downloads" would remove. */
    val removableBooks: Int,
    val auth: AuthState,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    library: LibraryRepository,
    private val storage: BookStorage,
    private val actions: BookActions,
    private val auth: AuthRepository,
) : ViewModel() {

    /** Bumped after a cleanup: files go after the database update, so the size is measured again. */
    private val filesChanged = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val usedBytes = combine(library.observeBooks(), filesChanged) { _, _ -> }
        .mapLatest { storage.usedBytes() }
        .flowOn(Dispatchers.IO)

    /** Null until loaded. */
    val state: StateFlow<SettingsUiState?> = combine(settings.settings, library.observeBooks(), usedBytes, auth.state) { prefs, books, bytes, authState ->
        SettingsUiState(
            wifiOnly = prefs.wifiOnly,
            lastSyncAt = prefs.lastSyncAt,
            usedBytes = bytes,
            removableBooks = books.count {
                it.source == BookSource.YANDEX_DISK && it.downloadState != DownloadState.IN_CLOUD
            },
            auth = authState,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _downloadsRemoved = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** Number of books cleaned up by "Delete all downloads". */
    val downloadsRemoved: SharedFlow<Int> = _downloadsRemoved

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settings.setWifiOnly(enabled) }
    }

    fun signOut() = auth.signOut()

    fun removeDownloads() {
        viewModelScope.launch {
            val count = actions.removeDownloads()
            filesChanged.value++
            _downloadsRemoved.emit(count)
        }
    }
}
