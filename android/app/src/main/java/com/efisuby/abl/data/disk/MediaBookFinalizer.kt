package com.efisuby.abl.data.disk

import com.efisuby.abl.data.importer.AssembleFile
import com.efisuby.abl.data.importer.BookManifest
import com.efisuby.abl.data.importer.BookManifestCodec
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.importer.ChapterAssembler
import com.efisuby.abl.data.importer.ChapterReader
import com.efisuby.abl.data.importer.ManifestChapter
import com.efisuby.abl.data.importer.MetadataResolver
import com.efisuby.abl.data.importer.TagReader
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.repository.LibraryRepository
import java.io.File

/**
 * Reads the downloaded files the way a local import does: durations, embedded chapters and a
 * cover. The record keeps what the user may already have edited (title) and only fills in what
 * the placeholder lacked.
 */
class MediaBookFinalizer(
    private val storage: BookStorage,
    private val library: LibraryRepository,
    private val tagReader: TagReader,
    private val chapterReader: ChapterReader,
) : BookFinalizer {

    override suspend fun finalize(book: Book, manifest: BookManifest, bookDir: File) {
        val files = manifest.files.map { File(bookDir, it.path) }
        val firstTags = tagReader.read(files.first())
        val chapters = ChapterAssembler(tagReader, chapterReader).assemble(
            book.id,
            bookDir,
            // The name of a placeholder chapter is the original file name; the target keeps it.
            files.map { AssembleFile(it, it.name) },
            firstTags,
        )
        val resolved = MetadataResolver.resolveBook(File(book.remotePath.orEmpty()).name, firstTags, isFolder = files.size > 1)
        val cover = book.coverPath?.let(::File)?.takeIf { it.isFile } ?: saveCover(book.id, files)

        // Reload: the user may have edited the book while it was downloading.
        val current = library.getBook(book.id) ?: return
        val updated = current.copy(
            author = current.author ?: resolved.author,
            narrator = current.narrator ?: resolved.narrator,
            series = current.series ?: resolved.series,
            seriesIndex = current.seriesIndex ?: resolved.seriesIndex.takeIf { current.series == null },
            coverPath = cover?.absolutePath ?: current.coverPath,
            totalDurationMs = chapters.sumOf { it.durationMs },
            downloadState = DownloadState.DOWNLOADED,
        )
        BookManifestCodec.write(
            manifest.copy(
                author = updated.author,
                narrator = updated.narrator,
                series = updated.series,
                seriesIndex = updated.seriesIndex,
                cover = updated.coverPath?.let { File(it).name },
                totalDurationMs = updated.totalDurationMs,
                chapters = chapters.map {
                    ManifestChapter(
                        it.id, it.chapterIndex, it.title,
                        File(it.filePath).relativeTo(bookDir).invariantSeparatorsPath,
                        it.startMs, it.durationMs,
                    )
                },
            ),
            storage.manifestFile(book.id),
        )
        library.saveBook(updated, chapters)
    }

    private fun saveCover(bookId: String, files: List<File>): File? {
        val bytes = files.asSequence().take(3).firstNotNullOfOrNull { tagReader.embeddedPicture(it) } ?: return null
        return storage.coverFile(bookId).also { it.writeBytes(bytes) }
    }
}
