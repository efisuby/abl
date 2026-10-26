package com.efisuby.abl.data.importer

import com.efisuby.abl.domain.model.Chapter
import java.io.File
import java.util.UUID

/** An audio file of a book that is already in private storage; [name] is its original file name. */
class AssembleFile(val target: File, val name: String)

/**
 * Builds the chapter list of a book from its audio files: one chapter per mp3, the embedded
 * chapters of an m4b. Shared by the local import and by the end of a cloud download.
 */
class ChapterAssembler(
    private val tagReader: TagReader,
    private val chapterReader: ChapterReader,
) {

    /** [files] in book order; [firstTags] are the tags of the first one, already read by the caller. */
    fun assemble(bookId: String, bookDir: File, files: List<AssembleFile>, firstTags: FileTags): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        for (file in files) {
            val tags = if (file === files.first()) firstTags else tagReader.read(file.target)
            val spans = ChapterBoundaries.build(chapterReader.read(file.target), tags.durationMs)
            val relative = file.target.relativeTo(bookDir).invariantSeparatorsPath
            for (span in spans) {
                val title = when {
                    spans.size > 1 -> TtsMarkup.strip(span.title) ?: "${chapters.size + 1}"
                    else -> MetadataResolver.chapterTitle(file.name, tags)
                }
                chapters += Chapter(
                    id = chapterId(bookId, relative, span.startMs),
                    bookId = bookId,
                    chapterIndex = chapters.size,
                    title = title,
                    filePath = file.target.absolutePath,
                    startMs = span.startMs,
                    durationMs = span.durationMs,
                )
            }
        }
        return chapters
    }

    companion object {
        /**
         * Deterministic, so progress and bookmarks keep pointing at the same chapter when a book
         * is rebuilt from its manifest, re-imported, or finished downloading.
         */
        fun chapterId(bookId: String, relativeFile: String, startMs: Long): String =
            UUID.nameUUIDFromBytes("$bookId/$relativeFile#$startMs".toByteArray()).toString()
    }
}
