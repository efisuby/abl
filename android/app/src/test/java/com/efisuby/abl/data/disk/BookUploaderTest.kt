package com.efisuby.abl.data.disk

import com.efisuby.abl.data.importer.BookManifest
import com.efisuby.abl.data.importer.BookManifestCodec
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.importer.ManifestFile
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class BookUploaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val library = FakeLibrary()
    private val storage by lazy { BookStorage(folder.root.resolve("books")) }
    private val bookId = "local-book"

    /** Disk in memory: what was uploaded, the folders made and the PUTs the upload host received. */
    private inner class FakeUploads : DiskUploads {
        val stored = mutableMapOf<String, ByteArray>()
        val folders = mutableListOf<String>()
        val puts = mutableListOf<String>()
        var failOn: String? = null

        override suspend fun ensureFolder(path: String) {
            if (path !in folders) folders += path
        }

        override suspend fun uploadLink(path: String, overwrite: Boolean) =
            DiskLink(href = server.url("/up").newBuilder().addQueryParameter("p", path).build().toString(), method = "PUT")

        override suspend fun find(path: String): DiskResource? {
            stored[path]?.let { return DiskResource(name = path.substringAfterLast('/'), path = path, type = "file", size = it.size.toLong(), md5 = md5(it)) }
            return if (path in folders) DiskResource(name = path.substringAfterLast('/'), path = path, type = "dir") else null
        }
    }

    private val disk = FakeUploads()
    private val contents = listOf("01.mp3", "02.mp3", "part-2/03.mp3").mapIndexed { i, name -> name to ByteArray(3_000 + i) { (it + i).toByte() } }

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.queryParameter("p")!!
                disk.puts += path
                if (path == disk.failOn) return MockResponse().setResponseCode(500)
                disk.stored[path] = request.body.readByteArray()
                return MockResponse().setResponseCode(201)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun localBook(title: String = "Автор: Книга?") {
        val book = Book(id = bookId, title = title, source = BookSource.LOCAL, addedAt = 1, downloadState = DownloadState.DOWNLOADED)
        library.books[bookId] = book
        library.chapters[bookId] = contents.mapIndexed { i, (name, _) ->
            Chapter(id = "ch-$i", bookId = bookId, chapterIndex = i, title = name, filePath = "/x/audio/$name")
        }
        contents.forEach { (name, bytes) -> storage.audioFile(bookId, name).apply { parentFile!!.mkdirs() }.writeBytes(bytes) }
        BookManifestCodec.write(
            BookManifest(
                id = bookId, title = title, source = "LOCAL", totalDurationMs = 0, addedAt = 1, chapters = emptyList(),
                files = contents.map { (name, bytes) -> ManifestFile("audio/$name", bytes.size.toLong(), md5(bytes)) },
            ),
            storage.manifestFile(bookId),
        )
    }

    private fun uploader(root: String = "disk:/app/books") =
        BookUploader(disk, OkHttpClient(), storage, library, root, ioDispatcher = Dispatchers.Unconfined)

    @Test
    fun filesLandWithMatchingMd5AndTheBookBecomesACloudBook() = runBlocking {
        localBook()
        uploader().run(bookId)

        val target = "disk:/app/books/Автор_ Книга_"
        assertEquals(contents.map { "$target/${it.first}" }, disk.puts)
        contents.forEach { (name, bytes) -> assertEquals(md5(bytes), md5(disk.stored.getValue("$target/$name"))) }
        assertTrue("$target/part-2" in disk.folders)

        val book = library.books.getValue(bookId)
        assertEquals(BookSource.YANDEX_DISK, book.source)
        assertEquals(target, book.remotePath)
        assertEquals(DownloadState.DOWNLOADED, book.downloadState)
        assertEquals(listOf("ch-0", "ch-1", "ch-2"), library.chapters.getValue(bookId).map { it.id })
        val manifest = BookManifestCodec.read(storage.manifestFile(bookId))
        assertEquals("YANDEX_DISK", manifest.source)
        assertEquals(target, manifest.remotePath)
        assertEquals(contents.map { "$target/${it.first}" }, manifest.files.map { it.remote })
    }

    @Test
    fun anInterruptedRunResumesAtTheFileThatFailed() = runBlocking {
        localBook()
        val target = "disk:/app/books/Автор_ Книга_"
        disk.failOn = "$target/part-2/03.mp3"
        assertThrows(UploadHttpException::class.java) { runBlocking { uploader().run(bookId) } }
        assertEquals(BookSource.LOCAL, library.books.getValue(bookId).source)

        disk.failOn = null
        disk.puts.clear()
        uploader().run(bookId)

        assertEquals(listOf("$target/part-2/03.mp3"), disk.puts) // files 1 and 2 are already there
        assertEquals(BookSource.YANDEX_DISK, library.books.getValue(bookId).source)
        assertEquals(target, library.books.getValue(bookId).remotePath) // the same folder, not "(2)"
    }

    @Test
    fun aTakenFolderNameGetsANumberInsteadOfBeingMixedIn() = runBlocking {
        localBook(title = "Книга")
        disk.folders += "disk:/app/books/Книга"
        uploader().run(bookId)

        assertEquals("disk:/app/books/Книга (2)", library.books.getValue(bookId).remotePath)
    }

    @Test
    fun aBookAlreadyInTheCloudSendsNothing() = runBlocking {
        localBook()
        library.books[bookId] = library.books.getValue(bookId).copy(source = BookSource.YANDEX_DISK, remotePath = "disk:/x")
        uploader().run(bookId)
        assertTrue(disk.puts.isEmpty())
    }

    @Test
    fun errorsMapToWhatTheUserCanDo() {
        assertEquals(UploadError.SIGN_IN, DiskException.NotSignedIn().toUploadError())
        assertEquals(UploadError.SIGN_IN, DiskException.Unauthorized().toUploadError())
        assertEquals(UploadError.QUOTA, DiskException.Http(507).toUploadError())
        assertEquals(UploadError.QUOTA, UploadHttpException(507).toUploadError())
        assertEquals(UploadError.SERVER, DiskException.Http(500).toUploadError())
        assertEquals(UploadError.SERVER, UploadHttpException(503).toUploadError())
        assertEquals(UploadError.NETWORK, java.io.IOException("x").toUploadError())
        assertEquals(UploadError.OTHER, IllegalStateException().toUploadError())
        assertTrue(!RetryPolicy.isTransient(DiskException.Http(507)))
        assertTrue(!RetryPolicy.isTransient(UploadHttpException(403)))
        assertTrue(RetryPolicy.isTransient(UploadHttpException(500)))
        assertTrue(RetryPolicy.isTransient(java.io.IOException("reset")))
    }

    @Test
    fun folderNamesAreSafeForDisk() {
        assertEquals("a_b_c", BookUploader.folderName("a/b:c"))
        assertEquals("Book", BookUploader.folderName("  ..  "))
        assertEquals(100, BookUploader.folderName("x".repeat(300)).length)
    }
}
