package com.efisuby.abl.data.repository

import com.efisuby.abl.data.importer.BookManifest
import com.efisuby.abl.data.importer.BookManifestCodec
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.model.Progress
import com.efisuby.abl.domain.repository.LibraryRepository
import com.efisuby.abl.domain.repository.ProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookActionsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val library = FakeLibrary()
    private val progress = FakeProgress()
    private lateinit var storage: BookStorage
    private lateinit var actions: BookActions
    private var now = 1_000L

    private val book = Book(id = "b1", title = "Old", author = "Author", source = BookSource.LOCAL, addedAt = 1)

    @Before
    fun setUp() {
        storage = BookStorage(temp.newFolder("books"))
        actions = BookActions(library, progress, storage, clock = { now }, ioDispatcher = Dispatchers.Unconfined)
        library.books[book.id] = book
        storage.audioFile(book.id, "01.mp3").apply { parentFile!!.mkdirs(); writeText("audio") }
        BookManifestCodec.write(
            BookManifest(id = book.id, title = book.title, source = "LOCAL", totalDurationMs = 0, addedAt = 1, chapters = emptyList(), files = emptyList()),
            storage.manifestFile(book.id),
        )
    }

    @Test
    fun `deleting a book removes its directory and its record`() = runBlocking {
        actions.delete(book.id)

        assertFalse(storage.bookDir(book.id).exists())
        assertNull(library.getBook(book.id))
    }

    @Test
    fun `manual status change keeps the saved position`() = runBlocking {
        progress.saved[book.id] = Progress(book.id, "c1", 37 * 60_000L, finished = true, updatedAt = 1, deviceId = "d")

        actions.setStatus(book.id, BookStatus.IN_PROGRESS)

        assertEquals(BookStatus.IN_PROGRESS, library.books.getValue(book.id).status)
        val saved = progress.saved.getValue(book.id)
        assertEquals(37 * 60_000L, saved.positionMs)
        assertFalse(saved.finished)
    }

    @Test
    fun `metadata edit updates the record, the manifest and the cover`() = runBlocking {
        val first = actions.updateMetadata(book.id, MetadataEdit(" New title ", " ", "Reader", "Saga", 2), coverJpeg = byteArrayOf(1))
        now = 2_000L
        val second = actions.updateMetadata(book.id, MetadataEdit("New title", null, "Reader", "Saga", 2), coverJpeg = byteArrayOf(2))

        assertEquals("New title", first.title)
        assertNull(first.author)
        assertTrue("a new cover gets a new path", first.coverPath != second.coverPath)
        assertFalse(File(first.coverPath!!).exists())
        assertEquals(listOf<Byte>(2), File(second.coverPath!!).readBytes().toList())
        assertEquals(second, library.books.getValue(book.id))

        val manifest = BookManifestCodec.read(storage.manifestFile(book.id))
        assertEquals("New title", manifest.title)
        assertEquals("Saga", manifest.series)
        assertEquals(2, manifest.seriesIndex)
        assertEquals(File(second.coverPath!!).name, manifest.cover)
    }

    @Test
    fun `series number without a series is dropped`() = runBlocking {
        val updated = actions.updateMetadata(book.id, MetadataEdit("Title", null, null, "  ", 3), coverJpeg = null)

        assertNull(updated.series)
        assertNull(updated.seriesIndex)
        assertEquals(book.coverPath, updated.coverPath)
    }

    @Test
    fun `removing downloads keeps cloud books in the library and leaves imported books alone`() = runBlocking {
        val cloud = Book(id = "c1", title = "Cloud", source = BookSource.YANDEX_DISK, remotePath = "/Books/Cloud", addedAt = 1)
        library.books[cloud.id] = cloud
        storage.audioFile(cloud.id, "01.mp3").apply { parentFile!!.mkdirs(); writeText("audio") }
        BookManifestCodec.write(
            BookManifest(id = cloud.id, title = cloud.title, source = "YANDEX_DISK", totalDurationMs = 0, addedAt = 1, chapters = emptyList(), files = emptyList()),
            storage.manifestFile(cloud.id),
        )

        assertEquals(1, actions.removeDownloads())

        assertEquals(DownloadState.IN_CLOUD, library.books.getValue(cloud.id).downloadState)
        assertFalse(storage.audioDir(cloud.id).exists())
        assertTrue("the manifest and cover stay for the library", storage.manifestFile(cloud.id).isFile)
        assertEquals(DownloadState.DOWNLOADED, library.books.getValue(book.id).downloadState)
        assertTrue(storage.audioFile(book.id, "01.mp3").isFile)
        assertEquals(0, actions.removeDownloads())
    }

    @Test
    fun `removing one download frees its audio, keeps the cloud book and ignores imported books`() = runBlocking {
        val cloud = Book(id = "c1", title = "Cloud", source = BookSource.YANDEX_DISK, remotePath = "/Books/Cloud", addedAt = 1)
        library.books[cloud.id] = cloud
        storage.audioFile(cloud.id, "01.mp3").apply { parentFile!!.mkdirs(); writeText("audio") }

        actions.removeDownload(book.id) // an imported book has no cloud copy
        assertTrue(storage.audioFile(book.id, "01.mp3").isFile)

        actions.removeDownload(cloud.id)

        assertEquals(DownloadState.IN_CLOUD, library.books.getValue(cloud.id).downloadState)
        assertFalse(storage.audioDir(cloud.id).exists())
    }

    private class FakeLibrary : LibraryRepository {
        val books = mutableMapOf<String, Book>()
        override fun observeBooks(): Flow<List<Book>> = flow { emit(books.values.toList()) }
        override fun observeBook(id: String): Flow<Book?> = emptyFlow()
        override fun observeChapters(bookId: String): Flow<List<Chapter>> = emptyFlow()
        override suspend fun getBook(id: String) = books[id]
        override suspend fun getChapters(bookId: String) = emptyList<Chapter>()
        override suspend fun saveBook(book: Book, chapters: List<Chapter>) {
            books[book.id] = book
        }
        override suspend fun updateMetadata(book: Book) {
            books[book.id] = book
        }
        override suspend fun setStatus(id: String, status: BookStatus) {
            books[id] = books.getValue(id).copy(status = status)
        }
        override suspend fun setDownloadState(id: String, state: DownloadState) {
            books[id] = books.getValue(id).copy(downloadState = state)
        }
        override suspend fun deleteBook(id: String) {
            books.remove(id)
        }
    }

    private class FakeProgress : ProgressRepository {
        val saved = mutableMapOf<String, Progress>()
        override fun observe(bookId: String): Flow<Progress?> = emptyFlow()
        override fun observeBookPositions(): Flow<Map<String, Long>> = emptyFlow()
        override suspend fun get(bookId: String) = saved[bookId]
        override suspend fun savePosition(bookId: String, chapterId: String?, positionMs: Long, totalListenedMs: Long) = Unit
        override suspend fun markFinished(bookId: String, finished: Boolean) {
            saved[bookId]?.let { saved[bookId] = it.copy(finished = finished) }
        }
        override suspend fun delete(bookId: String) {
            saved.remove(bookId)
        }
    }
}
