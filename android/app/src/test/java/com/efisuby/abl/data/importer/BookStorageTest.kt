package com.efisuby.abl.data.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class BookStorageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val storage by lazy { BookStorage(temp.newFolder("books")) }

    @Test
    fun `copy writes the file under its final name and reports md5`() = runBlocking {
        val data = "a".toByteArray()
        val target = storage.audioFile("b", "part-1/01.mp3")
        var reported = 0L

        val md5 = storage.copy(SourceFile("part-1/01.mp3", 1L) { ByteArrayInputStream(data) }, target) {
            reported += it
        }

        assertArrayEquals(data, target.readBytes())
        assertEquals("0cc175b9c0f1b6a831c399e269772661", md5)
        assertEquals(1L, reported)
        assertTrue(partFiles().isEmpty())
    }

    @Test
    fun `cancelled copy leaves neither the part file nor the target`() = runBlocking {
        val target = storage.audioFile("b", "01.mp3")
        lateinit var job: Job
        val source = SourceFile("01.mp3", -1L) {
            // Cancels the copy after the first chunk has been written to the part file.
            ChunkedStream(chunks = 1_000) { chunk -> if (chunk == 1) job.cancel() }
        }

        val copy = async(Dispatchers.IO, start = CoroutineStart.LAZY) { storage.copy(source, target) }
        job = copy
        copy.start()
        try {
            copy.await()
            fail("copy should have been cancelled")
        } catch (expected: CancellationException) {
        }

        assertFalse(target.exists())
        assertTrue(partFiles().isEmpty())
    }

    @Test
    fun `failed read leaves neither the part file nor the target`() = runBlocking {
        val target = storage.audioFile("b", "01.mp3")
        val source = SourceFile("01.mp3", -1L) {
            ChunkedStream(chunks = 10) { chunk -> if (chunk == 3) throw IOException("disk gone") }
        }

        try {
            storage.copy(source, target)
            fail("copy should have failed")
        } catch (expected: IOException) {
        }

        assertFalse(target.exists())
        assertTrue(partFiles().isEmpty())
    }

    @Test
    fun `paths cannot escape the audio directory`() {
        val file = storage.audioFile("b", "../../etc/./passwd")

        assertEquals(File(storage.audioDir("b"), "etc/passwd").path, file.path)
    }

    private fun partFiles(): List<File> =
        storage.root.walkTopDown().filter { it.name.endsWith(BookStorage.PART_SUFFIX) }.toList()

    /** Produces [chunks] chunks of 1 KiB, calling [onChunk] before each one. */
    private class ChunkedStream(private val chunks: Int, private val onChunk: (Int) -> Unit) : InputStream() {
        private var served = 0

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served == chunks) return -1
            onChunk(served)
            served++
            val n = minOf(len, 1024)
            b.fill(1, off, off + n)
            return n
        }
    }
}
