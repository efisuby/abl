package com.efisuby.abl.data.importer

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Reads QuickTime chapters: a `text` track referenced from the audio track's `tref/chap`, one
 * sample per chapter holding its title. Media3's `MetadataRetriever` only surfaces Nero `chpl`
 * chapters (the chapter track is read lazily during playback), so files that carry only this
 * track need their own reader — see `docs/spikes/m4b-chapters.md`.
 */
class Mp4ChapterTrackReader : ChapterReader {

    override fun read(file: File): List<EmbeddedChapter> =
        Mp4Parser.use(file, emptyList()) { it.chapters() }
}

/**
 * MP4 metadata the platform retriever does not expose, as lowercase keys:
 * - QuickTime `keys` + `ilst` (the `mdta` handler), without the `com.apple.quicktime.` prefix.
 *   Neither `MediaMetadataRetriever` nor Media3 map these to title and artist, yet audiobook
 *   tools such as ebook2audiobook write m4b tags this way (`docs/spikes/m4b-chapters.md`);
 * - iTunes free-form items (`----:com.apple.iTunes:SERIES`) by their name;
 * - the iTunes movement items `©mvn` and `©mvi` as `mvnm` and `mvin`, the ID3 frame names.
 */
object Mp4Tags {

    fun read(file: File): Map<String, String> = Mp4Parser.use(file, emptyMap()) { it.tags() }
}

private class Box(val type: String, val start: Long, val end: Long)

private class Mp4Parser(private val raf: RandomAccessFile) {

    companion object {
        const val MAX_ENTRIES = 1_000_000
        const val MAX_TITLE_BYTES = 4096
        const val MAX_VALUE_BYTES = 64 * 1024
        private const val QUICKTIME_PREFIX = "com.apple.quicktime."
        private const val FREE_FORM = "----"
        private const val MOVEMENT_NAME = "\u00A9mvn"
        private const val MOVEMENT_NUMBER = "\u00A9mvi"
        private const val TYPE_UTF8 = 1
        private const val TYPE_INTEGER = 21

        /** Malformed or non-MP4 files yield [fallback] instead of an error. */
        fun <T> use(file: File, fallback: T, block: (Mp4Parser) -> T): T = try {
            RandomAccessFile(file, "r").use { block(Mp4Parser(it)) }
        } catch (e: IOException) {
            fallback
        } catch (e: IllegalArgumentException) {
            fallback
        }
    }

    /** iTunes tags live in `moov/udta/meta`, QuickTime keys usually in `moov/meta`; read both. */
    fun tags(): Map<String, String> {
        val moov = children(0, raf.length()).firstOrNull { it.type == "moov" } ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (meta in listOfNotNull(find(moov, "udta", "meta"), find(moov, "meta"))) {
            val items = metaChildren(meta)
            val ilst = items.firstOrNull { it.type == "ilst" } ?: continue
            val keys = items.firstOrNull { it.type == "keys" }
            val found = if (keys != null) quickTimeKeys(keys, ilst) else iTunesItems(ilst)
            found.forEach { (key, value) -> result.putIfAbsent(key, value) }
        }
        return result
    }

    private fun quickTimeKeys(keys: Box, ilst: Box): Map<String, String> {
        raf.seek(keys.start + 4)
        val names = List(count(raf.readInt())) {
            val size = raf.readInt()
            require(size in 8..MAX_TITLE_BYTES) { "key size $size" }
            raf.readInt() // namespace, normally 'mdta'
            String(ByteArray(size - 8).also { raf.readFully(it) }, Charsets.UTF_8)
        }
        val result = mutableMapOf<String, String>()
        for (item in children(ilst)) {
            // Items are typed by their 1-based index into the key list.
            val index = item.type.toByteArray(Charsets.ISO_8859_1)
                .fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            val name = names.getOrNull(index - 1) ?: continue
            val value = textData(item) ?: continue
            result[name.lowercase().removePrefix(QUICKTIME_PREFIX)] = value
        }
        return result
    }

    private fun iTunesItems(ilst: Box): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (item in children(ilst)) {
            val key = when (item.type) {
                FREE_FORM -> freeFormName(item) ?: continue
                MOVEMENT_NAME -> "mvnm"
                MOVEMENT_NUMBER -> "mvin"
                else -> continue
            }
            textData(item)?.let { result.putIfAbsent(key, it) }
        }
        return result
    }

    /** The `name` box of a `----` item: a full box holding the bare name, e.g. `SERIES`. */
    private fun freeFormName(item: Box): String? {
        val name = children(item).firstOrNull { it.type == "name" } ?: return null
        val length = (name.end - name.start - 4).toInt()
        if (length !in 1..MAX_TITLE_BYTES) return null
        raf.seek(name.start + 4)
        return String(ByteArray(length).also { raf.readFully(it) }, Charsets.UTF_8).trim().lowercase()
            .takeIf { it.isNotEmpty() }
    }

    /** A `meta` box is a full box in MP4 but a plain container in QuickTime files. */
    private fun metaChildren(meta: Box): List<Box> {
        val asFullBox = children(meta.start + 4, meta.end)
        return if (asFullBox.firstOrNull()?.type == "hdlr") asFullBox else children(meta)
    }

    /**
     * The item's `data` box as text: UTF-8 (well-known type 1) as is, a big-endian signed
     * integer (type 21, used by `©mvi`) as its decimal value; other types are ignored.
     */
    private fun textData(item: Box): String? {
        val data = children(item).firstOrNull { it.type == "data" } ?: return null
        if (data.end - data.start < 8) return null
        raf.seek(data.start)
        val type = raf.readInt()
        raf.readInt() // locale
        val length = (data.end - data.start - 8).toInt()
        require(length in 0..MAX_VALUE_BYTES) { "value size $length" }
        val bytes = ByteArray(length).also { raf.readFully(it) }
        return when (type) {
            TYPE_UTF8 -> String(bytes, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
            TYPE_INTEGER -> if (length in 1..8) {
                bytes.fold(if (bytes[0] < 0) -1L else 0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }.toString()
            } else {
                null
            }
            else -> null
        }
    }

    fun chapters(): List<EmbeddedChapter> {
        val moov = children(0, raf.length()).firstOrNull { it.type == "moov" } ?: return emptyList()
        val tracks = children(moov).filter { it.type == "trak" }
        // Only a referenced track counts: an unreferenced text track may be subtitles.
        val referenced = tracks.firstNotNullOfOrNull { chapterReference(it) } ?: return emptyList()
        val textTrack = tracks.firstOrNull { trackId(it) == referenced && handler(it) == "text" }
            ?: return emptyList()
        return readSamples(textTrack)
    }

    private fun readSamples(track: Box): List<EmbeddedChapter> {
        val mdia = find(track, "mdia") ?: return emptyList()
        val timescale = timescale(find(mdia, "mdhd") ?: return emptyList())
        require(timescale > 0) { "timescale" }
        val stbl = find(mdia, "minf", "stbl") ?: return emptyList()

        val durations = stts(find(stbl, "stts") ?: return emptyList())
        val sizes = stsz(find(stbl, "stsz") ?: return emptyList())
        val offsets = sampleOffsets(stbl, sizes) ?: return emptyList()

        val result = ArrayList<EmbeddedChapter>(offsets.size)
        var time = 0L
        for (i in offsets.indices) {
            result += EmbeddedChapter(time * 1000 / timescale, title(offsets[i], sizes[i]))
            time += durations.getOrElse(i) { 0L }
        }
        return result
    }

    /** Sample offsets from the chunk table (`stco`/`co64`) and sample-to-chunk runs (`stsc`). */
    private fun sampleOffsets(stbl: Box, sizes: IntArray): LongArray? {
        val chunkOffsets = find(stbl, "stco")?.let { offsets(it, wide = false) }
            ?: find(stbl, "co64")?.let { offsets(it, wide = true) }
            ?: return null
        val runs = stsc(find(stbl, "stsc") ?: return null)
        val result = LongArray(sizes.size)
        var sample = 0
        for (chunk in chunkOffsets.indices) {
            val perChunk = runs.lastOrNull { it.first <= chunk + 1 }?.second ?: return null
            var offset = chunkOffsets[chunk]
            repeat(perChunk) {
                if (sample == sizes.size) return result
                result[sample] = offset
                offset += sizes[sample]
                sample++
            }
        }
        return if (sample == sizes.size) result else null
    }

    /** A chapter sample: a 16-bit length, then UTF-8 or BOM-marked UTF-16 text. */
    private fun title(offset: Long, size: Int): String? {
        if (size < 2) return null
        raf.seek(offset)
        val length = minOf(raf.readUnsignedShort(), size - 2, MAX_TITLE_BYTES)
        if (length == 0) return null
        val bytes = ByteArray(length).also { raf.readFully(it) }
        val isUtf16 = length >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
        val text = if (isUtf16) String(bytes, Charsets.UTF_16) else String(bytes, Charsets.UTF_8)
        return text.trim().takeIf { it.isNotEmpty() }
    }

    private fun chapterReference(track: Box): Int? {
        val chap = find(track, "tref", "chap") ?: return null
        if (chap.end - chap.start < 4) return null
        raf.seek(chap.start)
        return raf.readInt()
    }

    private fun trackId(track: Box): Int? {
        val tkhd = find(track, "tkhd") ?: return null
        raf.seek(tkhd.start)
        val version = raf.readUnsignedByte()
        raf.seek(tkhd.start + if (version == 1) 20 else 12)
        return raf.readInt()
    }

    private fun handler(track: Box): String? {
        val hdlr = find(track, "mdia", "hdlr") ?: return null
        raf.seek(hdlr.start + 8)
        return fourCc()
    }

    private fun timescale(mdhd: Box): Long {
        raf.seek(mdhd.start)
        val version = raf.readUnsignedByte()
        raf.seek(mdhd.start + if (version == 1) 20 else 12)
        return raf.readInt().toLong() and 0xFFFFFFFFL
    }

    private fun stts(box: Box): LongArray {
        raf.seek(box.start + 4)
        val entries = count(raf.readInt())
        val result = ArrayList<Long>()
        repeat(entries) {
            val samples = count(raf.readInt())
            val delta = raf.readInt().toLong() and 0xFFFFFFFFL
            repeat(samples) { result += delta }
        }
        return result.toLongArray()
    }

    private fun stsz(box: Box): IntArray {
        raf.seek(box.start + 4)
        val fixed = raf.readInt()
        val samples = count(raf.readInt())
        return IntArray(samples) { if (fixed != 0) fixed else raf.readInt() }
    }

    private fun stsc(box: Box): List<Pair<Int, Int>> {
        raf.seek(box.start + 4)
        return List(count(raf.readInt())) {
            val firstChunk = raf.readInt()
            val samplesPerChunk = count(raf.readInt())
            raf.readInt() // sample description index
            firstChunk to samplesPerChunk
        }
    }

    private fun offsets(box: Box, wide: Boolean): LongArray {
        raf.seek(box.start + 4)
        return LongArray(count(raf.readInt())) {
            if (wide) raf.readLong() else raf.readInt().toLong() and 0xFFFFFFFFL
        }
    }

    /** Rejects counts a corrupt file could use to make us allocate gigabytes. */
    private fun count(value: Int): Int {
        require(value in 0..MAX_ENTRIES) { "count $value" }
        return value
    }

    private fun find(parent: Box, vararg path: String): Box? {
        var current = parent
        for (type in path) current = children(current).firstOrNull { it.type == type } ?: return null
        return current
    }

    private fun children(parent: Box): List<Box> = children(parent.start, parent.end)

    private fun children(start: Long, end: Long): List<Box> {
        val result = mutableListOf<Box>()
        var position = start
        while (position + 8 <= end) {
            raf.seek(position)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = fourCc()
            var header = 8L
            when (size) {
                1L -> { size = raf.readLong(); header = 16L }
                0L -> size = end - position
            }
            if (size < header || position + size > end) break
            result += Box(type, position + header, position + size)
            position += size
        }
        return result
    }

    private fun fourCc(): String {
        val bytes = ByteArray(4).also { raf.readFully(it) }
        return String(bytes, Charsets.ISO_8859_1)
    }
}

/**
 * The chapter cascade from design D5: the first reader that finds real chapters wins; a single
 * marker is kept only if no reader finds more. Media3 covers Nero `chpl`, the track reader covers
 * QuickTime chapter tracks; without either the importer makes one chapter per file.
 */
class CascadingChapterReader(private val readers: List<ChapterReader>) : ChapterReader {

    override fun read(file: File): List<EmbeddedChapter> {
        var fallback = emptyList<EmbeddedChapter>()
        for (reader in readers) {
            val markers = reader.read(file)
            if (markers.size > 1) return markers
            if (fallback.isEmpty()) fallback = markers
        }
        return fallback
    }
}
