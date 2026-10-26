package com.efisuby.abl.data.disk

import com.efisuby.abl.data.importer.BookManifestCodec
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.importer.ManifestFile
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException

/** The upload host answered [code] instead of accepting the bytes. */
class UploadHttpException(val code: Int) : IOException("Upload answered HTTP $code")

/**
 * Copies a local book into the app folder on Disk (`<root>/<title>/<relative path>`), one file at
 * a time, and only then turns it into a cloud book: `book.json` gets the remote paths and the
 * record the source and `remotePath`, while the book id, chapters, progress and bookmarks stay.
 *
 * An interrupted run is resumed by the next one: the chosen folder is kept in `book.json`, and
 * a file that is already there with the right size and md5 is not sent again.
 */
class BookUploader(
    private val disk: DiskUploads,
    private val http: OkHttpClient,
    private val storage: BookStorage,
    private val library: LibraryRepository,
    private val root: String = DiskApi.BOOKS_ROOT,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun run(bookId: String, progress: DownloadProgress = DownloadProgress { _, _ -> }) {
        gate.withPermit { upload(bookId, progress) }
    }

    private suspend fun upload(bookId: String, progress: DownloadProgress) {
        val book = requireNotNull(library.getBook(bookId)) { "No book $bookId" }
        if (book.source == BookSource.YANDEX_DISK && book.remotePath != null) return // already in the cloud
        var manifest = withContext(ioDispatcher) { BookManifestCodec.read(storage.manifestFile(bookId)) }
        require(manifest.files.isNotEmpty()) { "Book $bookId has no files" }

        val target = manifest.remotePath ?: chooseFolder(book.title).also {
            // Remembered before the first byte goes out, so a rerun fills the same folder.
            manifest = manifest.copy(remotePath = it)
            withContext(ioDispatcher) { BookManifestCodec.write(manifest, storage.manifestFile(bookId)) }
        }
        disk.ensureFolder(target)

        val bookDir = storage.bookDir(bookId)
        val total = manifest.files.sumOf { it.sizeBytes }
        var done = 0L
        progress.onBytes(done, total)
        val folders = mutableSetOf(target)
        val remoteFiles = manifest.files.map { file ->
            val remote = "$target/${file.path.removePrefix("${BookStorage.AUDIO_DIR}/")}"
            val parent = remote.substringBeforeLast('/')
            if (folders.add(parent)) disk.ensureFolder(parent)
            val local = File(bookDir, file.path)
            val base = done
            if (!alreadyThere(remote, file)) {
                send(remote, local, file) { progress.onBytes(base + it, total) }
            }
            done += file.sizeBytes
            progress.onBytes(done, total)
            file.copy(remote = remote)
        }

        BookManifestCodec.write(
            manifest.copy(source = BookSource.YANDEX_DISK.name, remotePath = target, files = remoteFiles),
            storage.manifestFile(bookId),
        )
        library.saveBook(
            book.copy(source = BookSource.YANDEX_DISK, remotePath = target, downloadState = DownloadState.DOWNLOADED),
            library.getChapters(bookId),
        )
    }

    /** The title as a folder name, with a number added if a folder of that name is taken. */
    private suspend fun chooseFolder(title: String): String {
        val name = folderName(title)
        var candidate = "$root/$name"
        var n = 2
        while (disk.find(candidate) != null) candidate = "$root/$name ($n)".also { n++ }
        return candidate
    }

    private suspend fun alreadyThere(remote: String, file: ManifestFile): Boolean {
        val existing = disk.find(remote) ?: return false
        return existing.size == file.sizeBytes && existing.md5.equals(file.md5, ignoreCase = true)
    }

    private suspend fun send(remote: String, local: File, file: ManifestFile, onBytes: (Long) -> Unit) {
        val href = disk.uploadLink(remote, overwrite = true).href
        withContext(ioDispatcher) {
            val body = FileBody(local, onBytes)
            http.newCall(Request.Builder().url(href).put(body).build()).execute().use { response ->
                if (!response.isSuccessful) throw UploadHttpException(response.code)
            }
        }
        // Disk reports the md5 of what it stored: a mismatch means the bytes were damaged on the way.
        val stored = disk.find(remote)
        if (stored?.md5 != null && file.md5.isNotEmpty() && !stored.md5.equals(file.md5, ignoreCase = true)) {
            throw ChecksumMismatchException(remote)
        }
    }

    private class FileBody(private val file: File, private val onBytes: (Long) -> Unit) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength() = file.length()

        override fun writeTo(sink: BufferedSink) {
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var written = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    onBytes(written)
                }
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        /** One upload at a time, like downloads: a phone link does not benefit from more. */
        private val gate = Semaphore(1)

        /** Characters Disk paths cannot carry are replaced; an empty result gets a fixed name. */
        fun folderName(title: String): String =
            title.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').take(100).trim().ifEmpty { "Book" }
    }
}
