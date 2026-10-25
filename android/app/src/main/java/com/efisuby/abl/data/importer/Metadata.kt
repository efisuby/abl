package com.efisuby.abl.data.importer

/** Tags read from one audio file. Blank values are normalized to null by [of]. */
data class FileTags(
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val author: String? = null,
    /** Audiobook files conventionally put the narrator into the composer tag. */
    val composer: String? = null,
    val durationMs: Long = 0L,
    val series: String? = null,
    /** Position in [series] as written in the tag: `3`, `03`, `3/10`. */
    val seriesPart: String? = null,
) {
    companion object {
        val EMPTY = FileTags()

        fun of(
            title: String?,
            album: String?,
            artist: String?,
            albumArtist: String?,
            author: String?,
            composer: String?,
            durationMs: Long,
            series: String? = null,
            seriesPart: String? = null,
        ) = FileTags(
            title = title.clean(),
            album = album.clean(),
            artist = artist.clean(),
            albumArtist = albumArtist.clean(),
            author = author.clean(),
            composer = composer.clean(),
            durationMs = durationMs.coerceAtLeast(0L),
            series = series.clean(),
            seriesPart = seriesPart.clean(),
        )

        private fun String?.clean(): String? = TtsMarkup.strip(this)
    }
}

data class BookMetadata(
    val title: String,
    val author: String?,
    val narrator: String?,
    val series: String? = null,
    val seriesIndex: Int? = null,
)

/** Splits `Author - Title` folder names; anything else is taken as the title alone. */
object FolderNameParser {

    private val separator = Regex("\\s+[-–—]\\s+")

    fun parse(name: String): Pair<String?, String> {
        val cleaned = name.trim()
        val parts = cleaned.split(separator, limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[0].trim() to parts[1].trim()
        } else {
            null to cleaned
        }
    }
}

object MetadataResolver {

    /**
     * Book-level metadata from the tags of its first file, falling back to the picked folder or
     * file name. For a folder the per-file title tag names a chapter, so only the album tag can
     * name the book; for a single file the title tag names the book itself.
     */
    fun resolveBook(sourceName: String, tags: FileTags, isFolder: Boolean): BookMetadata {
        val baseName = if (isFolder) sourceName else sourceName.substringBeforeLast('.')
        val (nameAuthor, nameTitle) = FolderNameParser.parse(baseName)
        val title = tags.album
            ?: tags.title.takeIf { !isFolder }
            ?: nameTitle.ifBlank { baseName }
        return BookMetadata(
            title = title,
            author = tags.albumArtist ?: tags.artist ?: tags.author ?: nameAuthor,
            narrator = tags.composer,
            series = tags.series,
            seriesIndex = tags.series?.let { seriesIndex(tags.seriesPart) },
        )
    }

    /** The first number of a series part tag: `3`, `03`, `3/10` and `Book 3` all give 3. */
    fun seriesIndex(part: String?): Int? =
        part?.let { Regex("""\d{1,6}""").find(it)?.value?.toInt() }

    fun chapterTitle(fileName: String, tags: FileTags): String =
        tags.title ?: fileName.substringBeforeLast('.')
}

/** A chapter marker embedded in one file: its start and an optional title. */
data class EmbeddedChapter(val startMs: Long, val title: String?)

/** A chapter span inside one file. */
data class ChapterSpan(val startMs: Long, val durationMs: Long, val title: String?)

object ChapterBoundaries {

    /**
     * Turns embedded markers into contiguous spans covering the whole file: markers are sorted,
     * duplicates and markers past the end are dropped, the first span always starts at 0 and
     * each span ends where the next begins. No usable markers means one span for the file.
     */
    fun build(markers: List<EmbeddedChapter>, fileDurationMs: Long): List<ChapterSpan> {
        val usable = markers
            .filter { it.startMs >= 0 && (fileDurationMs <= 0 || it.startMs < fileDurationMs) }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
        if (usable.size <= 1) {
            return listOf(ChapterSpan(0L, fileDurationMs.coerceAtLeast(0L), usable.firstOrNull()?.title))
        }
        return usable.mapIndexed { index, marker ->
            val start = if (index == 0) 0L else marker.startMs
            val end = usable.getOrNull(index + 1)?.startMs ?: fileDurationMs
            ChapterSpan(start, (end - start).coerceAtLeast(0L), marker.title)
        }
    }
}
