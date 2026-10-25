package com.efisuby.abl.ui.edit

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.efisuby.abl.data.repository.BookActions
import com.efisuby.abl.data.repository.MetadataEdit
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

data class EditUiState(
    /** Null until loaded. */
    val book: Book? = null,
    /** A newly picked cover, not saved yet. */
    val newCover: Bitmap? = null,
    val saving: Boolean = false,
)

sealed interface EditEvent {
    data object Saved : EditEvent
    data object SaveFailed : EditEvent
    data object CoverUnreadable : EditEvent
}

class EditBookViewModel(
    private val library: LibraryRepository,
    private val actions: BookActions,
    private val resolver: ContentResolver,
    private val bookId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(EditUiState())
    val state: StateFlow<EditUiState> = _state

    private val _events = MutableSharedFlow<EditEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<EditEvent> = _events

    private var newCoverJpeg: ByteArray? = null

    init {
        viewModelScope.launch { _state.update { it.copy(book = library.getBook(bookId)) } }
    }

    fun pickCover(uri: Uri) {
        viewModelScope.launch {
            val cover = withContext(Dispatchers.IO) { decodeCover(uri) }
            if (cover == null) {
                _events.tryEmit(EditEvent.CoverUnreadable)
                return@launch
            }
            newCoverJpeg = cover.second
            _state.update { it.copy(newCover = cover.first) }
        }
    }

    fun save(edit: MetadataEdit) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val event = try {
                actions.updateMetadata(bookId, edit, newCoverJpeg)
                EditEvent.Saved
            } catch (e: IOException) {
                EditEvent.SaveFailed
            }
            _state.update { it.copy(saving = false) }
            _events.tryEmit(event)
        }
    }

    /** The picked image scaled down to [MAX_COVER_PX] on its longer side, and its JPEG bytes. */
    private fun decodeCover(uri: Uri): Pair<Bitmap, ByteArray>? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_COVER_PX) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            decoded?.let { bitmap ->
                val scale = MAX_COVER_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
                val scaled = if (scale < 1f) {
                    bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
                } else {
                    bitmap
                }
                val jpeg = ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    out.toByteArray()
                }
                scaled to jpeg
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private companion object {
        const val MAX_COVER_PX = 1024
        const val JPEG_QUALITY = 90
    }
}
