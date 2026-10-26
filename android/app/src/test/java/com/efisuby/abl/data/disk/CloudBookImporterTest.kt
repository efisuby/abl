package com.efisuby.abl.data.disk

import com.efisuby.abl.data.importer.BookManifestCodec
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.importer.ImportException
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.ui.cloud.FakeDisk
import com.efisuby.abl.ui.cloud.dir
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudBookImporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val library = FakeLibrary()
    private val storage get() = BookStorage(folder.root.resolve("books"))

    private fun audio(path: String, size: Long = 10) =
        DiskResource(name = path.substringAfterLast('/'), path = path, type = "file", size = size, md5 = "md5-$size")

    private val disk = FakeDisk(
        mapOf(
            "disk:/b/Автор - Книга" to listOf(dir("disk:/b/Автор - Книга/part-2"), dir("disk:/b/Автор - Книга/part-10"), audio("disk:/b/Автор - Книга/cover.jpg")),
            "disk:/b/Автор - Книга/part-2" to listOf(audio("disk:/b/Автор - Книга/part-2/01.mp3", 20)),
            "disk:/b/Автор - Книга/part-10" to listOf(audio("disk:/b/Автор - Книга/part-10/01.mp3", 30), audio("disk:/b/Автор - Книга/part-10/readme.txt")),
            "disk:/b/empty" to emptyList(),
        ),
    )

    private fun importer() = CloudBookImporter(disk, storage, library, clock = { 123L }, ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)

    @Test
    fun importingTheSamePathTwiceGivesTheSameBook() = runBlocking {
        val resource = dir("disk:/b/Автор - Книга")
        val first = importer().import(resource)
        val second = importer().import(resource)

        assertEquals(first.book.id, second.book.id)
        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(1, library.books.size)
        assertEquals(CloudBookImporter.bookId("disk:/b/Автор - Книга"), first.book.id)
    }

    @Test
    fun aBookUploadedFromAnotherDeviceKeepsItsId() = runBlocking {
        val book = importer().import(dir("disk:/b/Автор - Книга"), bookId = "uploaded-id").book

        assertEquals("uploaded-id", book.id)
        assertEquals("uploaded-id", library.chapters.getValue("uploaded-id").first().bookId)
        assertEquals("uploaded-id", BookManifestCodec.read(storage.manifestFile("uploaded-id")).id)
    }

    @Test
    fun folderBecomesACloudBookWithAChapterPerFileInNaturalOrder() = runBlocking {
        val book = importer().import(dir("disk:/b/Автор - Книга")).book

        assertEquals(BookSource.YANDEX_DISK, book.source)
        assertEquals(DownloadState.IN_CLOUD, book.downloadState)
        assertEquals("Книга", book.title)
        assertEquals("Автор", book.author)
        assertEquals("disk:/b/Автор - Книга", book.remotePath)
        val chapters = library.chapters.getValue(book.id)
        assertEquals(listOf("01", "01"), chapters.map { it.title })
        assertEquals(listOf(0, 1), chapters.map { it.chapterIndex })
        assertTrue(chapters[0].filePath.endsWith("audio/part-2/01.mp3")) // part-2 before part-10

        val manifest = BookManifestCodec.read(storage.manifestFile(book.id))
        assertEquals(listOf("audio/part-2/01.mp3", "audio/part-10/01.mp3"), manifest.files.map { it.path })
        assertEquals(listOf("disk:/b/Автор - Книга/part-2/01.mp3", "disk:/b/Автор - Книга/part-10/01.mp3"), manifest.files.map { it.remote })
        assertEquals(listOf(20L, 30L), manifest.files.map { it.sizeBytes })
        assertEquals("md5-20", manifest.files[0].md5)
    }

    @Test
    fun singleM4bFileIsOneBook() = runBlocking {
        val result = importer().import(audio("disk:/b/Solo.m4b", 99))
        assertEquals("Solo", result.book.title)
        assertEquals(1, library.chapters.getValue(result.book.id).size)
    }

    @Test
    fun folderWithoutAudioIsRejectedAndLeavesNothing() {
        val e = assertThrows(ImportException::class.java) { runBlocking { importer().import(dir("disk:/b/empty")) } }
        assertEquals(ImportException.Reason.NO_AUDIO_FILES, e.reason)
        assertTrue(library.books.isEmpty())
    }
}
