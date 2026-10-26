package com.efisuby.abl.data.disk

import com.efisuby.abl.data.importer.BookManifest
import com.efisuby.abl.data.importer.BookManifestCodec
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.importer.DownloadFailure
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Runs once the last file is in place: reads durations and chapters and marks the book downloaded. */
fun interface BookFinalizer {
    suspend fun finalize(book: Book, manifest: BookManifest, bookDir: File)
}

class ChecksumMismatchException(path: String) : IOException("md5 of $path does not match")

/** The file server answered [code] instead of the bytes. */
class DownloadHttpException(val code: Int) : IOException("Download answered HTTP $code")

/** A download stopped on [file] (path relative to the book directory); [cause] says why. */
class FileDownloadException(val file: String, override val cause: Exception) : IOException("Cannot download $file: ${cause.message}", cause) {

    /** The HTTP status behind the failure, if the server was reached. */
    val code: Int? get() = when (cause) {
        is DownloadHttpException -> cause.code
        is DiskException.Http -> cause.code
        is DiskException.NotFound -> 404
        else -> null
    }
}

/** Bytes done and total over all files of the book. */
fun interface DownloadProgress {
    fun onBytes(done: Long, total: Long)
}

/**
 * Downloads a cloud book's files into its directory, one at a time. Each file goes through
 * `<name>.part`; after an interruption the next run asks for `Range: bytes=<part size>-` and
 * continues from there. A finished file is checked against the md5 Disk reported.
 */
class BookDownloader(
    private val links: DiskLinks,
    private val http: OkHttpClient,
    private val storage: BookStorage,
    private val library: LibraryRepository,
    private val finalizer: BookFinalizer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Downloads [bookId] to the end. Only one book downloads at a time in the whole app.
     * If the user cancelled (the state is no longer DOWNLOADING), partial files are removed;
     * a stop by the system keeps them for the next attempt.
     */
    suspend fun run(bookId: String, progress: DownloadProgress = DownloadProgress { _, _ -> }) {
        gate.withPermit {
            try {
                download(bookId, progress)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { if (cancelledByUser(bookId)) discardFiles(bookId) }
                throw e
            }
        }
    }

    private suspend fun cancelledByUser(bookId: String): Boolean =
        library.getBook(bookId)?.downloadState != DownloadState.DOWNLOADING

    private suspend fun discardFiles(bookId: String) {
        withContext(ioDispatcher) { storage.audioDir(bookId).deleteRecursively() }
    }

    private suspend fun download(bookId: String, progress: DownloadProgress) {
        val book = requireNotNull(library.getBook(bookId)) { "No book $bookId" }
        val bookDir = storage.bookDir(bookId)
        val manifest = withContext(ioDispatcher) { BookManifestCodec.read(storage.manifestFile(bookId)) }
        library.setDownloadState(bookId, DownloadState.DOWNLOADING)

        val wanted = manifest.files.filterNot { it.skipped }
        val total = wanted.sumOf { it.sizeBytes }
        var done = 0L
        progress.onBytes(done, total)
        for (file in wanted) {
            val target = File(bookDir, file.path)
            if (target.isFile && target.length() == file.sizeBytes) {
                done += file.sizeBytes
                progress.onBytes(done, total)
                continue
            }
            val base = done
            try {
                withContext(ioDispatcher) {
                    downloadFile(requireNotNull(file.remote) { "No remote path for ${file.path}" }, target, file.sizeBytes, file.md5) {
                        progress.onBytes(base + it, total)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw FileDownloadException(file.path, e)
            } catch (e: DiskException) {
                throw FileDownloadException(file.path, e)
            }
            done += file.sizeBytes
        }
        finalizer.finalize(book, manifest, bookDir)
        withContext(ioDispatcher) { writeFailure(bookId, null) }
        library.setDownloadState(bookId, DownloadState.DOWNLOADED)
    }

    private suspend fun downloadFile(remote: String, target: File, size: Long, md5: String, onBytes: (Long) -> Unit) {
        target.parentFile?.mkdirs()
        val part = File(target.path + BookStorage.PART_SUFFIX)
        if (part.isFile && part.length() > size) part.delete()
        if (!part.isFile || part.length() < size) fetch(remote, part, size, onBytes)

        if (md5.isNotEmpty() && md5Of(part) != md5) {
            part.delete()
            throw ChecksumMismatchException(remote)
        }
        if (!part.renameTo(target)) throw IOException("Cannot rename $part to $target")
    }

    /** Appends to [part] until it holds [size] bytes; a server that ignores `Range` restarts the file. */
    private suspend fun fetch(remote: String, part: File, size: Long, onBytes: (Long) -> Unit) {
        val offset = if (part.isFile) part.length() else 0L
        val href = links.downloadLink(remote).href
        val request = Request.Builder().url(href).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        http.newCall(request).execute().use { response ->
            val resume = response.code == 206 && offset > 0
            if (!response.isSuccessful) throw DownloadHttpException(response.code)
            val body = response.body ?: throw IOException("Empty download response")
            RandomAccessFile(part, "rw").use { out ->
                val start = if (resume) offset else 0L
                out.setLength(start)
                out.seek(start)
                var written = start
                onBytes(written)
                val buffer = ByteArray(BUFFER_SIZE)
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        onBytes(written)
                    }
                }
                if (written < size) throw IOException("Download ended at $written of $size bytes")
            }
        }
    }

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Remembers (or with null clears) why the download stopped, in `book.json`. */
    suspend fun recordFailure(bookId: String, failure: DownloadFailure?) {
        withContext(ioDispatcher) { writeFailure(bookId, failure) }
    }

    private fun writeFailure(bookId: String, failure: DownloadFailure?) {
        val manifest = runCatching { BookManifestCodec.read(storage.manifestFile(bookId)) }.getOrNull() ?: return
        if (manifest.downloadFailure == failure) return
        BookManifestCodec.write(manifest.copy(downloadFailure = failure), storage.manifestFile(bookId))
    }

    /** After a failure that will not be retried: keeps what is complete and tells the library. */
    suspend fun markFailed(bookId: String) {
        val manifest = withContext(ioDispatcher) {
            runCatching { BookManifestCodec.read(storage.manifestFile(bookId)) }.getOrNull()
        }
        val bookDir = storage.bookDir(bookId)
        val anyComplete = manifest?.files?.filterNot { it.skipped }?.any { File(bookDir, it.path).let { f -> f.isFile && f.length() == it.sizeBytes } } == true
        library.setDownloadState(bookId, if (anyComplete) DownloadState.PARTIAL else DownloadState.IN_CLOUD)
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024

        /** One book at a time (download spec: limited number of concurrent downloads). */
        val gate = Semaphore(1)
    }
}
