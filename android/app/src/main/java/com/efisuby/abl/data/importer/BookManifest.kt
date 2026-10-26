package com.efisuby.abl.data.importer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * `book.json`: everything needed to rebuild a book's database rows from its directory alone.
 * Paths are relative to the book directory so the manifest survives a change of `filesDir`.
 */
@Serializable
data class BookManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    val series: String? = null,
    val seriesIndex: Int? = null,
    val source: String,
    val remotePath: String? = null,
    /** `cover.jpg` when a cover was saved, otherwise null. */
    val cover: String? = null,
    val totalDurationMs: Long,
    val addedAt: Long,
    val chapters: List<ManifestChapter>,
    val files: List<ManifestFile>,
    /** Why the last download stopped; cleared when it succeeds. */
    val downloadFailure: DownloadFailure? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class ManifestChapter(
    val id: String,
    val index: Int,
    val title: String,
    /** Relative to the book directory, e.g. `audio/part-1/01.mp3`. */
    val file: String,
    val startMs: Long,
    val durationMs: Long,
)

@Serializable
data class ManifestFile(
    val path: String,
    val sizeBytes: Long,
    val md5: String,
    /** Path of the file on Yandex Disk; null for locally imported books. */
    val remote: String? = null,
    /** The user chose to go on without this file after it failed to download. */
    val skipped: Boolean = false,
)

/** A download that stopped on [file] (path relative to the book directory); [code] is the HTTP status, if any. */
@Serializable
data class DownloadFailure(val file: String, val code: Int? = null)

object BookManifestCodec {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // A newer app version may add fields; an older one must still read the book.
        ignoreUnknownKeys = true
    }

    fun encode(manifest: BookManifest): String = json.encodeToString(BookManifest.serializer(), manifest)

    fun decode(text: String): BookManifest = json.decodeFromString(BookManifest.serializer(), text)

    /** Writes through a `.part` file so a crash never leaves a half-written manifest. */
    fun write(manifest: BookManifest, target: File) {
        val part = File(target.path + BookStorage.PART_SUFFIX)
        try {
            part.writeText(encode(manifest))
            if (!part.renameTo(target)) throw IOException("Cannot rename $part to $target")
        } finally {
            part.delete()
        }
    }

    fun read(file: File): BookManifest = decode(file.readText())
}
