package com.efisuby.abl.ui.book

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.disk.BookDownloader
import com.efisuby.abl.data.disk.DownloadScheduler
import com.efisuby.abl.data.disk.FakeDiskServer
import com.efisuby.abl.data.disk.FileDownloadException
import com.efisuby.abl.data.importer.BookManifest
import com.efisuby.abl.data.importer.BookManifestCodec
import com.efisuby.abl.data.importer.DownloadFailure
import com.efisuby.abl.data.importer.ManifestFile
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.ui.waitForView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * File 2 of 3 answers 500 on the fake Disk: the book screen names it, and "Skip file" lets the
 * other two finish.
 */
@RunWith(AndroidJUnit4::class)
class DownloadFailureScreenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = context.appContainer
    private val bookId = "download-failure-ui-test"
    private val root = "disk:/books/Failing"
    private val server = FakeDiskServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var originalScheduler: DownloadScheduler
    private lateinit var downloader: BookDownloader

    @Before
    fun setUp() {
        val contents = (1..3).map { ByteArray(2_000 * it) { b -> (b + it).toByte() } }
        contents.forEachIndexed { i, bytes -> server.put("$root/0${i + 1}.mp3", bytes) }
        server.failing += "$root/02.mp3"

        downloader = BookDownloader(
            links = server.api(context),
            http = OkHttpClient(),
            storage = container.bookStorage,
            library = container.libraryRepository,
            finalizer = { _, _, _ -> },
        )
        originalScheduler = container.downloadScheduler
        // What the worker does, without WorkManager: run, and on a file error record it and give up.
        container.downloadScheduler = object : DownloadScheduler {
            override fun enqueue(bookId: String, wifiOnly: Boolean) {
                scope.launch { runDownload() }
            }

            override fun cancel(bookId: String) = Unit
        }
        runBlocking {
            container.libraryRepository.saveBook(
                Book(
                    id = bookId, title = "Книга с дефектом", source = BookSource.YANDEX_DISK,
                    remotePath = root, downloadState = DownloadState.IN_CLOUD, addedAt = 1,
                ),
                listOf(Chapter(id = "c1", bookId = bookId, chapterIndex = 0, title = "Глава", filePath = "/none/1.mp3")),
            )
            container.bookStorage.bookDir(bookId).mkdirs()
            BookManifestCodec.write(
                BookManifest(
                    id = bookId, title = "Книга с дефектом", source = "YANDEX_DISK", remotePath = root,
                    totalDurationMs = 0, addedAt = 1, chapters = emptyList(),
                    files = contents.mapIndexed { i, bytes ->
                        ManifestFile("audio/0${i + 1}.mp3", bytes.size.toLong(), FakeDiskServer.md5(bytes), remote = "$root/0${i + 1}.mp3")
                    },
                ),
                container.bookStorage.manifestFile(bookId),
            )
            // The first attempt, as the worker would have made it.
            container.libraryRepository.setDownloadState(bookId, DownloadState.DOWNLOADING)
            runDownload()
        }
    }

    private suspend fun runDownload() {
        try {
            downloader.run(bookId)
        } catch (e: FileDownloadException) {
            downloader.recordFailure(bookId, DownloadFailure(e.file, e.code))
            downloader.markFailed(bookId)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        container.downloadScheduler = originalScheduler
        runBlocking { container.bookActions.delete(bookId) }
        server.close()
    }

    @Test
    fun theScreenNamesTheFileAndSkippingLetsTheRestDownload() {
        ActivityScenario.launch<BookActivity>(BookActivity.intent(context, bookId)).use {
            waitForView(withText(context.getString(R.string.download_failed, "02.mp3", context.getString(R.string.download_failed_http, 500))))
            onView(withId(R.id.downloadError)).check(matches(isDisplayed()))

            onView(withId(R.id.download)).perform(click())
            waitForView(withText(R.string.download_failed_title))
            onView(withText(R.string.download_skip_file)).inRoot(isDialog()).perform(click())

            val deadline = System.currentTimeMillis() + 15_000
            while (runBlocking { container.libraryRepository.getBook(bookId)!!.downloadState } != DownloadState.DOWNLOADED &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(100)
            }
            assertEquals(DownloadState.DOWNLOADED, runBlocking { container.libraryRepository.getBook(bookId)!!.downloadState })
            val audio = container.bookStorage.bookDir(bookId).resolve("audio")
            assertTrue(File(audio, "01.mp3").isFile)
            assertTrue(File(audio, "03.mp3").isFile)
            assertTrue(!File(audio, "02.mp3").exists())
        }
    }
}
