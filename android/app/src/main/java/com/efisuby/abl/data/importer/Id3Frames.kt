package com.efisuby.abl.data.importer

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * Text frames of an ID3v2.3/2.4 tag at the start of a file. The platform retriever exposes only
 * a fixed set of frames, while audiobook series live in user frames (`TXXX:SERIES`) or in the
 * iTunes movement frames (`MVNM`, `MVIN`).
 *
 * Keys are lowercase: a `TXXX` frame is keyed by its description, any other text frame by its
 * id. Compressed, encrypted or unsynchronised frames are skipped, as are ID3v2.2 tags.
 */
object Id3Frames {

    private const val HEADER_SIZE = 10
    private const val MAX_TEXT_FRAME_BYTES = 64 * 1024

    /** Text frames despite their names: iTunes movement name and number. */
    private val MOVEMENT_FRAMES = setOf("MVNM", "MVIN")

    fun read(file: File): Map<String, String> = try {
        RandomAccessFile(file, "r").use(::readTag)
    } catch (e: IOException) {
        emptyMap()
    }

    private fun readTag(raf: RandomAccessFile): Map<String, String> {
        if (raf.length() < HEADER_SIZE) return emptyMap()
        val header = ByteArray(HEADER_SIZE).also { raf.readFully(it) }
        if (String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") return emptyMap()
        val major = header[3].toInt()
        if (major != 3 && major != 4) return emptyMap()
        val flags = header[5].toInt()
        // Tag-wide unsynchronisation in 2.3 changes every frame's bytes; such tags are rare.
        if (major == 3 && flags and 0x80 != 0) return emptyMap()
        val end = minOf(HEADER_SIZE + syncSafe(header, 6).toLong(), raf.length())

        var position = HEADER_SIZE.toLong()
        if (flags and 0x40 != 0) {
            raf.seek(position)
            val extended = ByteArray(4).also { raf.readFully(it) }
            // 2.3 counts the size without its own four bytes, 2.4 includes them.
            position += if (major == 3) 4L + int(extended, 0) else syncSafe(extended, 0).toLong()
        }

        val result = mutableMapOf<String, String>()
        val frameHeader = ByteArray(HEADER_SIZE)
        while (position + HEADER_SIZE <= end) {
            raf.seek(position)
            raf.readFully(frameHeader)
            if (frameHeader[0].toInt() == 0) break // padding
            val id = String(frameHeader, 0, 4, Charsets.ISO_8859_1)
            val size = if (major == 4) syncSafe(frameHeader, 4).toLong() else int(frameHeader, 4).toLong() and 0xFFFFFFFFL
            val formatFlags = frameHeader[9].toInt()
            position += HEADER_SIZE
            if (size <= 0 || position + size > end) break

            val encoded = if (major == 4) formatFlags and 0x0F != 0 else formatFlags and 0xC0 != 0
            if ((id[0] == 'T' || id in MOVEMENT_FRAMES) && !encoded && size <= MAX_TEXT_FRAME_BYTES) {
                val body = ByteArray(size.toInt()).also { raf.readFully(it) }
                textFrame(id, body)?.let { (key, value) -> result.putIfAbsent(key, value) }
            }
            position += size
        }
        return result
    }

    private fun textFrame(id: String, body: ByteArray): Pair<String, String>? {
        if (body.isEmpty()) return null
        val charset = charset(body[0].toInt()) ?: return null
        val wide = body[0].toInt() == 1 || body[0].toInt() == 2
        return if (id == "TXXX") {
            val split = terminator(body, 1, wide) ?: return null
            val description = String(body, 1, split - 1, charset).trim()
            val valueStart = split + if (wide) 2 else 1
            val value = firstValue(String(body, valueStart, body.size - valueStart, charset))
            if (description.isEmpty() || value == null) null else description.lowercase() to value
        } else {
            firstValue(String(body, 1, body.size - 1, charset))?.let { id.lowercase() to it }
        }
    }

    /** 2.4 separates several values with NUL; audiobook fields need only the first. */
    private fun firstValue(text: String): String? =
        text.substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }

    private fun charset(encoding: Int): Charset? = when (encoding) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16 // with a byte order mark
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> null
    }

    /** Index of the NUL (two aligned NULs for UTF-16) that ends the description. */
    private fun terminator(body: ByteArray, from: Int, wide: Boolean): Int? {
        if (!wide) return (from until body.size).firstOrNull { body[it].toInt() == 0 }
        var i = from
        while (i + 1 < body.size) {
            if (body[i].toInt() == 0 && body[i + 1].toInt() == 0) return i
            i += 2
        }
        return null
    }

    private fun syncSafe(bytes: ByteArray, offset: Int): Int =
        (0 until 4).fold(0) { acc, i -> (acc shl 7) or (bytes[offset + i].toInt() and 0x7F) }

    private fun int(bytes: ByteArray, offset: Int): Int =
        (0 until 4).fold(0) { acc, i -> (acc shl 8) or (bytes[offset + i].toInt() and 0xFF) }
}
