package com.efisuby.abl.data.importer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Private on-device layout of downloaded and imported books:
 *
 * ```
 * <root>/<bookId>/
 *   book.json
 *   cover.jpg
 *   audio/<relative path>
 * ```
 *
 * `<root>` is `filesDir/books`, which is excluded from backup.
 */
class BookStorage(val root: File) {

    fun bookDir(bookId: String): File = File(root, bookId)

    fun audioDir(bookId: String): File = File(bookDir(bookId), AUDIO_DIR)

    fun manifestFile(bookId: String): File = File(bookDir(bookId), MANIFEST_FILE)

    fun coverFile(bookId: String): File = File(bookDir(bookId), COVER_FILE)

    /**
     * Resolves [relativePath] inside the book's audio directory. Path segments come from file
     * names chosen by the user or a document provider, so anything that could climb out of the
     * directory is neutralized.
     */
    fun audioFile(bookId: String, relativePath: String): File {
        val segments = relativePath.split('/', '\\')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { it.replace('\u0000', '_') }
        require(segments.isNotEmpty()) { "Empty path: $relativePath" }
        return File(audioDir(bookId), segments.joinToString(File.separator))
    }

    /**
     * Copies [source] to [target] through `<target>.part` and renames it only once the data is
     * complete, so a crash or a cancellation never leaves a truncated file under the real name.
     * Returns the md5 of the copied bytes as lowercase hex.
     */
    suspend fun copy(source: SourceFile, target: File, onBytes: (Long) -> Unit = {}): String {
        target.parentFile?.mkdirs()
        val part = File(target.path + PART_SUFFIX)
        val digest = MessageDigest.getInstance("MD5")
        try {
            source.open().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        onBytes(read.toLong())
                    }
                    output.fd.sync()
                }
            }
            if (!part.renameTo(target)) throw IOException("Cannot rename $part to $target")
        } finally {
            part.delete()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Stores a cover picked by the user under a new name and removes the previous ones. A new
     * name changes `Book.coverPath`, which is what tells screens and the notification to reload.
     */
    fun replaceCover(bookId: String, jpeg: ByteArray, stamp: Long): File {
        val dir = bookDir(bookId).apply { mkdirs() }
        val target = File(dir, "cover-$stamp.jpg")
        val part = File(target.path + PART_SUFFIX)
        try {
            part.writeBytes(jpeg)
            if (!part.renameTo(target)) throw IOException("Cannot rename $part to $target")
        } finally {
            part.delete()
        }
        dir.listFiles { file -> file != target && file.name.startsWith("cover") && file.name.endsWith(".jpg") }
            ?.forEach { it.delete() }
        return target
    }

    /** Bytes taken by all books on the device. */
    fun usedBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Removes everything stored for the book; used for failed imports and deletion. */
    fun delete(bookId: String) {
        bookDir(bookId).deleteRecursively()
    }

    companion object {
        const val AUDIO_DIR = "audio"
        const val MANIFEST_FILE = "book.json"
        const val COVER_FILE = "cover.jpg"
        const val PART_SUFFIX = ".part"
        private const val BUFFER_SIZE = 64 * 1024
    }
}
