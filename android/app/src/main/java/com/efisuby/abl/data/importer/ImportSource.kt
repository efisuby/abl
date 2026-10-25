package com.efisuby.abl.data.importer

import java.io.InputStream

/** One audio file offered for import, addressed by its path inside the picked folder. */
class SourceFile(
    /** `/`-separated path relative to the picked folder; just the file name for a single file. */
    val relativePath: String,
    val sizeBytes: Long,
    private val opener: () -> InputStream,
) {
    val name: String get() = relativePath.substringAfterLast('/')

    fun open(): InputStream = opener()
}

/** What the user picked: a folder (one book) or a single file (one book). */
interface ImportSource {

    /** Folder name or file name; the fallback for title and author when tags are absent. */
    val displayName: String

    val isFolder: Boolean

    /** All files, recursively for a folder, in no particular order. */
    fun listFiles(): List<SourceFile>
}

object AudioFormats {

    val extensions: Set<String> = setOf("mp3", "m4a", "m4b", "aac")

    fun isSupported(fileName: String): Boolean =
        fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase() in extensions
}
