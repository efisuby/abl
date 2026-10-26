package com.efisuby.abl.data.disk

import com.efisuby.abl.data.importer.AudioFormats
import com.efisuby.abl.data.importer.BookManifest
import com.efisuby.abl.data.importer.BookManifestCodec
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.importer.ChapterAssembler
import com.efisuby.abl.data.importer.FileTags
import com.efisuby.abl.data.importer.ImportException
import com.efisuby.abl.data.importer.ManifestChapter
import com.efisuby.abl.data.importer.ManifestFile
import com.efisuby.abl.data.importer.MetadataResolver
import com.efisuby.abl.data.importer.PathOrder
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Adds a cloud folder (or a single file) to the library as a book "in the cloud": the record,
 * one placeholder chapter per file and `book.json` with the remote paths, sizes and md5 the
 * download needs. Nothing is downloaded here, and no audio has been read yet, so durations are
 * unknown until [BookDownloader] finishes.
 */
class CloudBookImporter(
    private val disk: DiskSource,
    private val storage: BookStorage,
    private val library: LibraryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    class Result(val book: Book, /** False when the same cloud path was imported before. */ val created: Boolean)

    /**
     * [bookId] is the hash of the path unless the book is known under another id: a book that
     * another device uploaded keeps the id it had there, so its progress and bookmarks match.
     */
    suspend fun import(resource: DiskResource, bookId: String = bookId(resource.path)): Result {
        library.getBook(bookId)?.let { return Result(it, created = false) }

        val files = remoteFiles(resource)
        if (files.isEmpty()) throw ImportException(ImportException.Reason.NO_AUDIO_FILES)

        val metadata = MetadataResolver.resolveBook(resource.name, FileTags.EMPTY, resource.isFolder)
        val bookDir = storage.bookDir(bookId)
        val targets = files.map { (relative, _) -> storage.audioFile(bookId, relative) }
        val chapters = files.mapIndexed { index, (_, file) ->
            val relative = targets[index].relativeTo(bookDir).invariantSeparatorsPath
            Chapter(
                id = ChapterAssembler.chapterId(bookId, relative, 0L),
                bookId = bookId,
                chapterIndex = index,
                title = MetadataResolver.chapterTitle(file.name, FileTags.EMPTY),
                filePath = targets[index].absolutePath,
            )
        }
        val book = Book(
            id = bookId,
            title = metadata.title,
            author = metadata.author,
            source = BookSource.YANDEX_DISK,
            remotePath = resource.path,
            downloadState = DownloadState.IN_CLOUD,
            addedAt = clock(),
        )
        val manifest = BookManifest(
            id = book.id,
            title = book.title,
            author = book.author,
            source = book.source.name,
            remotePath = book.remotePath,
            totalDurationMs = 0,
            addedAt = book.addedAt,
            chapters = chapters.map {
                ManifestChapter(it.id, it.chapterIndex, it.title, it.filePath.toRelative(bookDir), 0, 0)
            },
            files = files.mapIndexed { index, (_, file) ->
                ManifestFile(
                    path = targets[index].relativeTo(bookDir).invariantSeparatorsPath,
                    sizeBytes = file.size,
                    md5 = file.md5.orEmpty(),
                    remote = file.path,
                )
            },
        )
        withContext(ioDispatcher) {
            bookDir.mkdirs()
            BookManifestCodec.write(manifest, storage.manifestFile(bookId))
        }
        try {
            library.saveBook(book, chapters)
        } catch (e: Throwable) {
            withContext(ioDispatcher) { storage.delete(bookId) }
            throw e
        }
        return Result(book, created = true)
    }

    private fun String.toRelative(bookDir: java.io.File) = java.io.File(this).relativeTo(bookDir).invariantSeparatorsPath

    /** Supported audio files with their path inside the picked folder, in chapter order. */
    private suspend fun remoteFiles(resource: DiskResource): List<Pair<String, DiskResource>> {
        if (!resource.isFolder) {
            return if (AudioFormats.isSupported(resource.name)) listOf(resource.name to resource) else emptyList()
        }
        val found = mutableListOf<Pair<String, DiskResource>>()
        val pending = ArrayDeque(listOf(resource.path to ""))
        while (pending.isNotEmpty()) {
            val (path, prefix) = pending.removeFirst()
            for (item in disk.listAll(path)) {
                when {
                    item.isFolder -> pending.addLast(item.path to prefix + item.name + "/")
                    AudioFormats.isSupported(item.name) -> found += (prefix + item.name) to item
                }
            }
        }
        return found.sortedWith(compareBy(PathOrder) { it.first })
    }

    companion object {
        /** The same cloud path always gives the same book, so a repeated import cannot duplicate it. */
        fun bookId(remotePath: String): String =
            UUID.nameUUIDFromBytes("yandex-disk:$remotePath".toByteArray()).toString()
    }
}
