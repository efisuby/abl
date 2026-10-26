package com.efisuby.abl.data.disk

import com.efisuby.abl.data.settings.SettingsRepository
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.io.IOException

/** Starts and stops the background job that uploads one book. */
interface UploadScheduler {
    fun enqueue(bookId: String, wifiOnly: Boolean)

    fun cancel(bookId: String)
}

/** Why an upload stopped, in terms the user can act on. */
enum class UploadError {
    /** The token is missing or was rejected. */
    SIGN_IN,

    /** The Disk is full (HTTP 507). */
    QUOTA,
    SERVER,
    NETWORK,
    OTHER,
}

fun Throwable.toUploadError(): UploadError = when {
    this is DiskException.NotSignedIn || this is DiskException.Unauthorized -> UploadError.SIGN_IN
    this is DiskException.Http && code == 507 -> UploadError.QUOTA
    this is UploadHttpException && code == 507 -> UploadError.QUOTA
    this is DiskException.Http || this is DiskException.RateLimited -> UploadError.SERVER
    this is UploadHttpException -> UploadError.SERVER
    this is IOException -> UploadError.NETWORK
    else -> UploadError.OTHER
}

sealed interface UploadStatus {
    data class Running(val doneBytes: Long, val totalBytes: Long) : UploadStatus {
        val percent: Int get() = if (totalBytes > 0) (doneBytes * 100 / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    data class Failed(val error: UploadError) : UploadStatus
}

/**
 * What the screens show about uploads in progress. Kept in memory on purpose: the job itself
 * lives in WorkManager and the result lives in the book record, so nothing here must survive
 * a process restart.
 */
class UploadTracker {
    private val _state = MutableStateFlow<Map<String, UploadStatus>>(emptyMap())
    val state: StateFlow<Map<String, UploadStatus>> = _state.asStateFlow()

    fun set(bookId: String, status: UploadStatus?) {
        _state.update { if (status == null) it - bookId else it + (bookId to status) }
    }
}

/** Entry point of "Upload to cloud": only a local book that is not being uploaded can start. */
class UploadController(
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val scheduler: UploadScheduler,
    val tracker: UploadTracker,
) {

    /** False if the book cannot be uploaded (not local, or already uploading). */
    suspend fun start(bookId: String): Boolean {
        val book = library.getBook(bookId) ?: return false
        if (book.source != BookSource.LOCAL) return false
        if (tracker.state.value[bookId] is UploadStatus.Running) return false
        tracker.set(bookId, UploadStatus.Running(0, 0))
        scheduler.enqueue(bookId, settings.settings.first().wifiOnly)
        return true
    }

    fun cancel(bookId: String) {
        scheduler.cancel(bookId)
        tracker.set(bookId, null)
    }
}
