package com.efisuby.abl.data.importer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.inspector.MetadataRetriever
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** Reads tags, duration and the embedded cover of a local file. */
interface TagReader {
    fun read(file: File): FileTags

    fun embeddedPicture(file: File): ByteArray?
}

/** Embedded chapter markers of a local file; empty when the file has none. */
interface ChapterReader {
    fun read(file: File): List<EmbeddedChapter>
}

class MediaMetadataTagReader : TagReader {

    override fun read(file: File): FileTags {
        val tags = withRetriever(file) { retriever ->
            FileTags.of(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
                composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
            )
        } ?: FileTags.EMPTY
        val extra = if (file.extension.lowercase() in MP4_EXTENSIONS) Mp4Tags.read(file) else Id3Frames.read(file)
        return tags.withExtra(extra)
    }

    /**
     * Fills tags the platform left empty from what it does not read itself: QuickTime `keys`
     * metadata, and the series, which no platform key covers (`Mp4Tags`, `Id3Frames`).
     */
    private fun FileTags.withExtra(keys: Map<String, String>): FileTags {
        if (keys.isEmpty()) return this
        return FileTags.of(
            title = title ?: keys["title"] ?: keys["displayname"],
            album = album ?: keys["album"],
            artist = artist ?: keys["artist"],
            albumArtist = albumArtist ?: keys["album_artist"] ?: keys["albumartist"],
            author = author ?: keys["author"],
            composer = composer ?: keys["composer"],
            durationMs = durationMs,
            series = SERIES_KEYS.firstNotNullOfOrNull { keys[it] },
            seriesPart = SERIES_PART_KEYS.firstNotNullOfOrNull { keys[it] },
        )
    }

    override fun embeddedPicture(file: File): ByteArray? = withRetriever(file) { it.embeddedPicture }

    /** A file the platform cannot parse is imported without tags rather than rejected. */
    private fun <T> withRetriever(file: File, block: (MediaMetadataRetriever) -> T?): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            // A descriptor opened by this process: the path variant opens the file in the media
            // server, which cannot read every location this app can.
            FileInputStream(file).use { input ->
                retriever.setDataSource(input.fd)
                block(retriever)
            }
        } catch (e: RuntimeException) {
            null
        } catch (e: IOException) {
            null
        } finally {
            retriever.release()
        }
    }

    private companion object {
        val MP4_EXTENSIONS = setOf("m4a", "m4b", "mp4")

        // Conventions of Mp3tag, Audiobookshelf and iTunes (movement name and number).
        val SERIES_KEYS = listOf("series", "mvnm")
        val SERIES_PART_KEYS = listOf("series-part", "series_part", "seriespart", "series-index", "series_index", "mvin")
    }
}

/**
 * Chapters Media3 exposes on the track formats without playing the file. For MP4 that is the
 * Nero `chpl` atom; QuickTime chapter tracks are read only during playback and need
 * [Mp4ChapterTrackReader] (see `docs/spikes/m4b-chapters.md`).
 * Must not be called on the main thread: it blocks until the file is prepared.
 */
@OptIn(UnstableApi::class)
class Media3ChapterReader(private val context: Context) : ChapterReader {

    override fun read(file: File): List<EmbeddedChapter> {
        val item = MediaItem.fromUri(Uri.fromFile(file))
        return try {
            MetadataRetriever.Builder(context, item).build().use { retriever ->
                val groups = retriever.retrieveTrackGroups().get()
                buildList {
                    for (g in 0 until groups.length) {
                        val group = groups[g]
                        for (t in 0 until group.length) {
                            val metadata = group.getFormat(t).metadata ?: continue
                            for (e in 0 until metadata.length()) {
                                val chapter = metadata[e] as? Chapter ?: continue
                                if (chapter.isHidden) continue
                                add(EmbeddedChapter(chapter.startTimeMs, chapter.title?.value?.trim()?.ifEmpty { null }))
                            }
                        }
                    }
                }.distinctBy { it.startMs }
            }
        } catch (e: Exception) {
            // Unreadable container: the importer degrades to one chapter for the whole file.
            emptyList()
        }
    }
}
