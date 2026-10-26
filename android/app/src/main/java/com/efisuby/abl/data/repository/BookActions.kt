package com.efisuby.abl.data.repository

import com.efisuby.abl.data.importer.BookManifestCodec
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.repository.LibraryRepository
import com.efisuby.abl.domain.repository.ProgressRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException

/** The user's edits of a book's metadata; the cover is passed separately. */
data class MetadataEdit(
    val title: String,
    val author: String?,
    val narrator: String?,
    val series: String?,
    val seriesIndex: Int?,
)

/**
 * Changes to a book that touch more than the database: its directory, its `book.json` and its
 * progress record.
 */
class BookActions(
    private val library: LibraryRepository,
    private val progress: ProgressRepository,
    private val storage: BookStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Removes the record (chapters, progress and bookmarks go with it) and the book's files on
     * the device. Files in the cloud are never touched.
     */
    suspend fun delete(bookId: String) {
        library.deleteBook(bookId)
        withContext(ioDispatcher) { storage.delete(bookId) }
    }

    /**
     * A manual status change keeps the saved position (library spec); only the "finished" flag
     * of the progress follows, so that "in progress" resumes where the listener stopped.
     */
    suspend fun setStatus(bookId: String, status: BookStatus) {
        library.setStatus(bookId, status)
        progress.markFinished(bookId, status == BookStatus.FINISHED)
    }

    /**
     * "Delete all downloads" (app-shell spec): the audio of cloud books goes, the books stay in
     * the library as "in the cloud". Imported books have no cloud copy and are left alone. The
     * state changes first, so the player stops before its files disappear. Returns the number of
     * books cleaned up.
     */
    suspend fun removeDownloads(): Int {
        val downloaded = library.observeBooks().first().filter {
            // A running download is cancelled by the download queue itself (group 7).
            it.source == BookSource.YANDEX_DISK && it.downloadState in REMOVABLE
        }
        downloaded.forEach { removeFiles(it.id) }
        return downloaded.size
    }

    /** Frees the space of one downloaded cloud book; it stays in the library as "in the cloud". */
    suspend fun removeDownload(bookId: String) {
        val book = library.getBook(bookId) ?: return
        if (book.source == BookSource.YANDEX_DISK && book.downloadState in REMOVABLE) removeFiles(bookId)
    }

    private suspend fun removeFiles(bookId: String) {
        library.setDownloadState(bookId, DownloadState.IN_CLOUD)
        withContext(ioDispatcher) { storage.audioDir(bookId).deleteRecursively() }
    }

    /** Saves the edit and, if given, a new cover as JPEG bytes; returns the updated book. */
    suspend fun updateMetadata(bookId: String, edit: MetadataEdit, coverJpeg: ByteArray?): Book {
        val book = requireNotNull(library.getBook(bookId)) { "No book $bookId" }
        val title = edit.title.trim()
        require(title.isNotEmpty()) { "Title must not be empty" }
        val cover = coverJpeg?.let { withContext(ioDispatcher) { storage.replaceCover(bookId, it, clock()) } }
        val updated = book.copy(
            title = title,
            author = edit.author.clean(),
            narrator = edit.narrator.clean(),
            series = edit.series.clean(),
            seriesIndex = edit.seriesIndex?.takeIf { edit.series.clean() != null },
            coverPath = cover?.absolutePath ?: book.coverPath,
            // Monotonic, like progress: a quick second edit must still beat the first on another device.
            metadataUpdatedAt = maxOf(clock(), book.metadataUpdatedAt + 1),
        )
        library.updateMetadata(updated)
        withContext(ioDispatcher) { updateManifest(updated) }
        return updated
    }

    /** `book.json` mirrors the record so a book can be restored without the database (design D4). */
    private fun updateManifest(book: Book) {
        val file = storage.manifestFile(book.id)
        if (!file.isFile) return // a cloud book that is not downloaded has no local manifest
        try {
            val manifest = BookManifestCodec.read(file)
            BookManifestCodec.write(
                manifest.copy(
                    title = book.title,
                    author = book.author,
                    narrator = book.narrator,
                    series = book.series,
                    seriesIndex = book.seriesIndex,
                    cover = book.coverPath?.let { File(it).name },
                ),
                file,
            )
        } catch (e: IOException) {
            // The database stays the source of truth; a stale manifest only matters for restore.
        } catch (e: SerializationException) {
            // Same for a manifest this version cannot parse.
        }
    }

    private companion object {
        val REMOVABLE = setOf(DownloadState.DOWNLOADED, DownloadState.PARTIAL)
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
