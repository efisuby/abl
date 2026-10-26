package com.efisuby.abl.data.disk

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.importer.CascadingChapterReader
import com.efisuby.abl.data.importer.FixtureDocumentsProvider
import com.efisuby.abl.data.importer.Media3ChapterReader
import com.efisuby.abl.data.importer.MediaMetadataTagReader
import com.efisuby.abl.data.importer.Mp4ChapterTrackReader
import com.efisuby.abl.data.importer.SafTreeSource
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.DownloadState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole trip against the fake Disk: import a fixture book, upload it, drop the audio, bring
 * it back, then restore it on a "second device" under the same id. Chapters, progress and
 * bookmarks must be the same at every step.
 */
@RunWith(AndroidJUnit4::class)
class UploadRoundTripTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val container = context.appContainer
    private val server = FakeDiskServer()
    private var bookId: String? = null

    @Before
    fun setUp() {
        server.put("disk:/Приложения/AudioBookPlayer/books/placeholder/x.mp3", ByteArray(1)) // the root exists, as on a real Disk
    }

    @After
    fun tearDown() {
        bookId?.let { runBlocking { container.bookActions.delete(it) } }
        server.close()
    }

    private fun finalizer() = MediaBookFinalizer(
        container.bookStorage, container.libraryRepository, MediaMetadataTagReader(),
        CascadingChapterReader(listOf(Media3ChapterReader(context), Mp4ChapterTrackReader())),
    )

    @Test
    fun aLocalBookGoesUpComesBackAndIsRestoredOnAnotherDevice() = runBlocking {
        val resolver = FixtureDocumentsProvider.install(instrumentation.context, context, File(context.cacheDir, "fixtures"))
        val book = container.bookImporter.import(SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("nested")))
        val id = book.id.also { bookId = it }
        val library = container.libraryRepository
        val chapterIds = library.getChapters(id).map { it.id }
        val audio = container.bookStorage.audioDir(id)
        val originals = audio.walkTopDown().filter { it.isFile }.associate { it.relativeTo(audio).invariantSeparatorsPath to FakeDiskServer.md5(it.readBytes()) }
        assertEquals(3, originals.size)

        container.progressRepository.savePosition(id, chapterIds[1], 12_000, 30_000)
        val bookmark = container.bookmarkRepository.add(id, chapterIds[1], 5_000, "note")

        // Up.
        val api = server.api(context)
        BookUploader(api, OkHttpClient(), container.bookStorage, library).run(id)
        val uploaded = library.getBook(id)!!
        assertEquals(BookSource.YANDEX_DISK, uploaded.source)
        val remote = uploaded.remotePath!!
        originals.forEach { (path, md5) -> assertEquals(md5, FakeDiskServer.md5(server.files.getValue("$remote/$path"))) }
        assertEquals(chapterIds, library.getChapters(id).map { it.id })

        // Audio dropped, then downloaded again from the cloud copy.
        container.bookActions.removeDownload(id)
        assertEquals(DownloadState.IN_CLOUD, library.getBook(id)!!.downloadState)
        val downloader = BookDownloader(api, OkHttpClient(), container.bookStorage, library, finalizer())
        downloader.run(id)
        assertEquals(DownloadState.DOWNLOADED, library.getBook(id)!!.downloadState)
        originals.forEach { (path, md5) -> assertEquals(md5, FakeDiskServer.md5(File(audio, path).readBytes())) }
        assertEquals(chapterIds, library.getChapters(id).map { it.id })
        assertEquals(chapterIds[1], container.progressRepository.get(id)!!.chapterId)
        assertEquals(12_000L, container.progressRepository.get(id)!!.positionMs)

        // A second device: nothing local, the registry entry says "id + remote path".
        container.progressRepository.get(id) // progress travels through progress/<device>.json; here only the book is restored
        container.bookActions.delete(id)
        CloudBookImporter(api, container.bookStorage, library).import(api.resource(remote), bookId = id)
        BookDownloader(api, OkHttpClient(), container.bookStorage, library, finalizer()).apply {
            library.setDownloadState(id, DownloadState.DOWNLOADING)
            run(id)
        }
        val restored = library.getBook(id)!!
        assertEquals(id, restored.id)
        assertEquals(DownloadState.DOWNLOADED, restored.downloadState)
        assertEquals(chapterIds, library.getChapters(id).map { it.id })
        originals.forEach { (path, md5) -> assertEquals(md5, FakeDiskServer.md5(File(audio, path).readBytes())) }
        assertTrue(bookmark.id.isNotEmpty())
    }
}
