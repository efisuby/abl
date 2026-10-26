package com.efisuby.abl.data.importer

import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Copy progress over all files of the book being imported. */
data class ImportProgress(
    val copiedBytes: Long,
    /** -1 when a document provider does not report sizes. */
    val totalBytes: Long,
    val currentFile: String,
    val fileIndex: Int,
    val fileCount: Int,
)

class ImportException(val reason: Reason, cause: Throwable? = null) :
    Exception(reason.name, cause) {

    enum class Reason {
        /** The picked folder or file contains no mp3, m4a, m4b or aac. */
        NO_AUDIO_FILES,

        /** Reading the source or writing private storage failed. */
        IO,
    }
}

/**
 * Imports a picked folder or file as one local book: copies the audio into [BookStorage],
 * builds chapters (one per file, or the embedded chapters of an m4b), writes `book.json` and
 * saves the book. On failure or cancellation nothing of the book is left behind.
 */
class BookImporter(
    private val storage: BookStorage,
    private val library: LibraryRepository,
    private val tagReader: TagReader,
    private val chapterReader: ChapterReader,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun import(source: ImportSource, onProgress: (ImportProgress) -> Unit = {}): Book =
        withContext(ioDispatcher) {
            val files = try {
                source.listFiles()
            } catch (e: Exception) {
                throw ImportException(ImportException.Reason.IO, e)
            }
                .filter { AudioFormats.isSupported(it.name) }
                .sortedWith(compareBy(PathOrder) { it.relativePath })
            if (files.isEmpty()) throw ImportException(ImportException.Reason.NO_AUDIO_FILES)

            val bookId = UUID.randomUUID().toString()
            try {
                importFiles(bookId, source, files, onProgress)
            } catch (e: Throwable) {
                storage.delete(bookId)
                throw when (e) {
                    // Checked first: CancellationException is an IllegalStateException.
                    is CancellationException, is ImportException -> e
                    is java.io.IOException, is SecurityException, is IllegalStateException ->
                        ImportException(ImportException.Reason.IO, e)
                    else -> e
                }
            }
        }

    private suspend fun importFiles(
        bookId: String,
        source: ImportSource,
        files: List<SourceFile>,
        onProgress: (ImportProgress) -> Unit,
    ): Book {
        val totalBytes = if (files.any { it.sizeBytes < 0 }) -1L else files.sumOf { it.sizeBytes }
        var copied = 0L
        val bookDir = storage.bookDir(bookId)
        val copiedFiles = files.mapIndexed { index, file ->
            val target = storage.audioFile(bookId, file.relativePath)
            onProgress(ImportProgress(copied, totalBytes, file.relativePath, index, files.size))
            val md5 = storage.copy(file, target) { bytes ->
                copied += bytes
                onProgress(ImportProgress(copied, totalBytes, file.relativePath, index, files.size))
            }
            CopiedFile(file, target, md5)
        }

        val firstTags = tagReader.read(copiedFiles.first().target)
        val metadata = MetadataResolver.resolveBook(source.displayName, firstTags, source.isFolder)
        val cover = saveCover(bookId, copiedFiles)

        val chapters = ChapterAssembler(tagReader, chapterReader).assemble(
            bookId,
            bookDir,
            copiedFiles.map { AssembleFile(it.target, it.source.name) },
            firstTags,
        )

        val book = Book(
            id = bookId,
            title = metadata.title,
            author = metadata.author,
            narrator = metadata.narrator,
            series = metadata.series,
            seriesIndex = metadata.seriesIndex,
            coverPath = cover?.absolutePath,
            source = BookSource.LOCAL,
            totalDurationMs = chapters.sumOf { it.durationMs },
            downloadState = DownloadState.DOWNLOADED,
            addedAt = clock(),
        )
        BookManifestCodec.write(manifestOf(book, chapters, copiedFiles, bookDir), storage.manifestFile(bookId))
        library.saveBook(book, chapters)
        return book
    }

    private fun saveCover(bookId: String, files: List<CopiedFile>): File? {
        val bytes = files.asSequence()
            .take(MAX_FILES_PROBED_FOR_COVER)
            .firstNotNullOfOrNull { tagReader.embeddedPicture(it.target) }
            ?: return null
        val target = storage.coverFile(bookId)
        target.writeBytes(bytes)
        return target
    }

    private fun manifestOf(
        book: Book,
        chapters: List<Chapter>,
        files: List<CopiedFile>,
        bookDir: File,
    ) = BookManifest(
        id = book.id,
        title = book.title,
        author = book.author,
        narrator = book.narrator,
        series = book.series,
        seriesIndex = book.seriesIndex,
        source = book.source.name,
        remotePath = book.remotePath,
        cover = book.coverPath?.let { File(it).name },
        totalDurationMs = book.totalDurationMs,
        addedAt = book.addedAt,
        chapters = chapters.map {
            ManifestChapter(
                id = it.id,
                index = it.chapterIndex,
                title = it.title,
                file = File(it.filePath).relativeTo(bookDir).invariantSeparatorsPath,
                startMs = it.startMs,
                durationMs = it.durationMs,
            )
        },
        files = files.map {
            ManifestFile(it.target.relativeTo(bookDir).invariantSeparatorsPath, it.target.length(), it.md5)
        },
    )

    private class CopiedFile(val source: SourceFile, val target: File, val md5: String)

    companion object {
        private const val MAX_FILES_PROBED_FOR_COVER = 3

        /**
         * Deterministic, so progress and bookmarks keep pointing at the same chapter when a book
         * is rebuilt from its manifest or re-imported under the same id.
         */
        fun chapterId(bookId: String, relativeFile: String, startMs: Long): String =
            ChapterAssembler.chapterId(bookId, relativeFile, startMs)
    }
}
