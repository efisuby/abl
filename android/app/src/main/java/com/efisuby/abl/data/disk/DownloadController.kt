package com.efisuby.abl.data.disk

import androidx.work.NetworkType
import com.efisuby.abl.data.importer.BookManifestCodec
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.settings.SettingsRepository
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Queues and cancels background downloads; the real one is backed by WorkManager. */
interface DownloadScheduler {
    fun enqueue(bookId: String, wifiOnly: Boolean)

    fun cancel(bookId: String)
}

/** The network a download may use: unmetered (Wi-Fi) when the user asked for "Wi-Fi only". */
fun downloadNetworkType(wifiOnly: Boolean): NetworkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

sealed interface StartResult {
    data object Started : StartResult

    /** The files do not fit: [requiredBytes] still to download, [freeBytes] available. */
    data class NotEnoughSpace(val requiredBytes: Long, val freeBytes: Long) : StartResult

    data object AlreadyDownloading : StartResult

    /** Not a cloud book, or its manifest is gone. */
    data object Unavailable : StartResult
}

/** Start and cancel downloads of cloud books, with the free-space guard. */
class DownloadController(
    private val library: LibraryRepository,
    private val storage: BookStorage,
    private val settings: SettingsRepository,
    private val scheduler: DownloadScheduler,
    /** Free bytes on the volume of the given directory. */
    private val freeSpace: (File) -> Long = { dir -> generateSequence(dir) { it.parentFile }.first { it.exists() }.usableSpace },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Bytes of the book's audio still missing on the device; null if it is not a cloud book. */
    suspend fun remainingBytes(bookId: String): Long? = withContext(ioDispatcher) {
        val manifest = runCatching { BookManifestCodec.read(storage.manifestFile(bookId)) }.getOrNull()
            ?: return@withContext null
        manifest.files.sumOf { file ->
            val present = File(storage.bookDir(bookId), file.path).takeIf { it.isFile }?.length() ?: 0L
            val part = File(storage.bookDir(bookId), file.path + BookStorage.PART_SUFFIX).takeIf { it.isFile }?.length() ?: 0L
            (file.sizeBytes - maxOf(present, part)).coerceAtLeast(0L)
        }
    }

    /** Full size of the cloud book in bytes (what it takes once downloaded); null if unknown. */
    suspend fun totalBytes(bookId: String): Long? = withContext(ioDispatcher) {
        runCatching { BookManifestCodec.read(storage.manifestFile(bookId)) }.getOrNull()?.files?.sumOf { it.sizeBytes }
    }

    suspend fun start(bookId: String): StartResult {
        val book = library.getBook(bookId) ?: return StartResult.Unavailable
        if (book.downloadState == DownloadState.DOWNLOADING) return StartResult.AlreadyDownloading
        if (book.downloadState == DownloadState.DOWNLOADED) return StartResult.Started
        val required = remainingBytes(bookId) ?: return StartResult.Unavailable
        val free = withContext(ioDispatcher) { storage.root.mkdirs(); freeSpace(storage.root) }
        if (required + SPACE_HEADROOM_BYTES > free) return StartResult.NotEnoughSpace(required, free)

        library.setDownloadState(bookId, DownloadState.DOWNLOADING)
        scheduler.enqueue(bookId, settings.settings.first().wifiOnly)
        return StartResult.Started
    }

    /** Stops the job and returns the book to "in the cloud"; its temporary and partial files go. */
    suspend fun cancel(bookId: String) {
        // The state flips first: a stopping worker treats "not DOWNLOADING" as a user cancel.
        library.setDownloadState(bookId, DownloadState.IN_CLOUD)
        scheduler.cancel(bookId)
        withContext(ioDispatcher) { storage.audioDir(bookId).deleteRecursively() }
    }

    companion object {
        /** Kept free on top of the download so the device does not run out of space completely. */
        const val SPACE_HEADROOM_BYTES = 50L * 1024 * 1024
    }
}
