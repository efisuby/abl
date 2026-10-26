package com.efisuby.abl.data.disk

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.settings.SettingsRepository
import com.efisuby.abl.data.importer.DownloadFailure
import com.efisuby.abl.domain.model.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class BookDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val library = FakeLibrary()
    private val storage by lazy { BookStorage(folder.root.resolve("books")) }
    private val data = ByteArray(1_000_000) { (it * 31 + it / 7).toByte() }
    private val md5 = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
    private val finalized = mutableListOf<String>()

    private val ranges = mutableListOf<String?>()

    /** Serves [data] with `Range` support; the first [interruptions] full-size answers drop mid-body. */
    private fun serve(interruptions: Int = 0, slow: Boolean = false) {
        var left = interruptions
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                ranges += range
                val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toLong() ?: 0L
                val body = Buffer().write(data, start.toInt(), data.size - start.toInt())
                val response = MockResponse().setBody(body)
                if (range != null) {
                    response.setResponseCode(206).setHeader("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
                }
                if (left > 0) {
                    left--
                    response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }
                if (slow) response.throttleBody(100_000, 100, TimeUnit.MILLISECONDS)
                return response
            }
        }
    }

    private val links = object : DiskLinks {
        override suspend fun downloadLink(path: String) = DiskLink(href = server.url("/file").toString())
    }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun downloader(finalizer: BookFinalizer = BookFinalizer { _, _, _ -> finalized += "done" }) =
        BookDownloader(links, OkHttpClient(), storage, library, finalizer, Dispatchers.IO)

    private fun addBook(md5: String = this.md5, state: DownloadState = DownloadState.DOWNLOADING) =
        library.addCloudBook(storage, state = state, files = listOf(Triple("book.mp3", data.size.toLong(), md5)))

    private fun controller(): DownloadController {
        val store = PreferenceDataStoreFactory.create(produceFile = { folder.root.resolve("settings.preferences_pb") })
        val scheduler = object : DownloadScheduler {
            override fun enqueue(bookId: String, wifiOnly: Boolean) = Unit
            override fun cancel(bookId: String) = Unit
        }
        return DownloadController(library, storage, SettingsRepository(store), scheduler, freeSpace = { Long.MAX_VALUE })
    }

    private val target get() = storage.audioFile("cloud-book", "book.mp3")
    private val part get() = storage.audioFile("cloud-book", "book.mp3.part")

    @Test
    fun interruptedDownloadContinuesFromTheSameByte() = runBlocking {
        serve(interruptions = 1)
        addBook()
        val downloader = downloader()

        assertThrows(IOException::class.java) { runBlocking { downloader.run("cloud-book") } }
        val partial = part.length()
        assertTrue("some bytes arrived", partial in 1 until data.size)
        assertFalse(target.exists())

        downloader.run("cloud-book")

        assertEquals(listOf(null, "bytes=$partial-"), ranges)
        assertArrayEquals(data, target.readBytes())
        assertFalse(part.exists())
        assertEquals(DownloadState.DOWNLOADED, library.books.getValue("cloud-book").downloadState)
        assertEquals(listOf("done"), finalized)
    }

    @Test
    fun serverThatIgnoresRangeRestartsTheFile() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(data))
        }
        addBook()
        part.apply { parentFile!!.mkdirs(); writeBytes(data.copyOf(1000)) }

        downloader().run("cloud-book")

        assertArrayEquals(data, target.readBytes())
    }

    @Test
    fun wrongChecksumDiscardsTheFile() {
        serve()
        addBook(md5 = "0".repeat(32))

        val error = assertThrows(FileDownloadException::class.java) { runBlocking { downloader().run("cloud-book") } }

        assertTrue(error.cause is ChecksumMismatchException)
        assertFalse(target.exists())
        assertFalse(part.exists())
    }

    @Test
    fun cancelledByTheUserRemovesThePartFile() = runBlocking {
        serve(slow = true)
        addBook()
        val job = launch(Dispatchers.IO) { downloader().run("cloud-book") }
        val deadline = System.currentTimeMillis() + 10_000
        while (part.length() == 0L && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("download started", part.length() > 0)

        // What DownloadController.cancel does first.
        library.setDownloadState("cloud-book", DownloadState.IN_CLOUD)
        job.cancelAndJoin()

        assertFalse(part.exists())
        assertFalse(storage.audioDir("cloud-book").exists())
    }

    @Test
    fun stoppedBySystemKeepsThePartFileForResume() = runBlocking {
        serve(slow = true)
        addBook()
        val job = launch(Dispatchers.IO) { downloader().run("cloud-book") }
        val deadline = System.currentTimeMillis() + 10_000
        while (part.length() == 0L && System.currentTimeMillis() < deadline) Thread.sleep(20)

        job.cancelAndJoin() // state is still DOWNLOADING

        assertTrue(part.exists())
    }

    @Test
    fun markFailedKeepsCompleteFilesAsPartial() = runBlocking {
        library.addCloudBook(storage, files = listOf(Triple("a.mp3", 3, ""), Triple("b.mp3", 3, "")))
        val downloader = downloader()

        downloader.markFailed("cloud-book")
        assertEquals(DownloadState.IN_CLOUD, library.books.getValue("cloud-book").downloadState)

        storage.audioFile("cloud-book", "a.mp3").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        downloader.markFailed("cloud-book")
        assertEquals(DownloadState.PARTIAL, library.books.getValue("cloud-book").downloadState)
    }

    @Test
    fun serverErrorNamesTheFileAndItsCode() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.getHeader("X-Test") == null && server.requestCount == 2) MockResponse().setResponseCode(500)
                else MockResponse().setBody(Buffer().write(data))
        }
        library.addCloudBook(storage, files = listOf(Triple("a.mp3", data.size.toLong(), md5), Triple("b.mp3", data.size.toLong(), md5)))

        val error = assertThrows(FileDownloadException::class.java) { runBlocking { downloader().run("cloud-book") } }

        assertEquals("audio/b.mp3", error.file)
        assertEquals(500, error.code)
        assertFalse("a failed file is not retried as an I/O error", RetryPolicy.isStuck(null, DownloadFailure(error.file, error.code)))
        assertTrue(storage.audioFile("cloud-book", "a.mp3").exists())
    }

    @Test
    fun skippedFileIsLeftOutAndTheBookIsFinalized() = runBlocking {
        serve()
        library.addCloudBook(storage, files = listOf(Triple("a.mp3", data.size.toLong(), md5), Triple("b.mp3", data.size.toLong(), md5)))
        val controller = controller()
        controller.skipFile("cloud-book", "audio/b.mp3")

        downloader().run("cloud-book")

        assertTrue(storage.audioFile("cloud-book", "a.mp3").exists())
        assertFalse(storage.audioFile("cloud-book", "b.mp3").exists())
        assertEquals(1, server.requestCount)
        assertEquals(DownloadState.DOWNLOADED, library.books.getValue("cloud-book").downloadState)
        assertEquals(0L, controller.remainingBytes("cloud-book"))
    }

    @Test
    fun successClearsTheRecordedFailure() = runBlocking {
        serve()
        addBook()
        val downloader = downloader()
        downloader.recordFailure("cloud-book", DownloadFailure("audio/book.mp3", 500))
        val controller = controller()
        assertEquals(500, controller.failure("cloud-book")?.code)

        downloader.run("cloud-book")

        assertEquals(null, controller.failure("cloud-book"))
    }
}
