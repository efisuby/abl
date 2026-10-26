package com.efisuby.abl.ui.book

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.importer.BookManifest
import com.efisuby.abl.data.importer.BookManifestCodec
import com.efisuby.abl.data.importer.ManifestFile
import com.efisuby.abl.data.disk.DownloadScheduler
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.ui.waitForView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A book that only lives in the cloud cannot be played: the screen offers to download it. */
@RunWith(AndroidJUnit4::class)
class CloudBookScreenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = context.appContainer
    private val bookId = "cloud-ui-test"
    private val queued = mutableListOf<String>()
    private lateinit var originalScheduler: DownloadScheduler

    @Before
    fun setUp() {
        originalScheduler = container.downloadScheduler
        container.downloadScheduler = object : DownloadScheduler {
            override fun enqueue(bookId: String, wifiOnly: Boolean) {
                queued += bookId
            }
            override fun cancel(bookId: String) = Unit
        }
        runBlocking {
            container.libraryRepository.saveBook(
                Book(
                    id = bookId, title = "Облачная книга", source = BookSource.YANDEX_DISK,
                    remotePath = "disk:/books/x", downloadState = DownloadState.IN_CLOUD, addedAt = 1,
                ),
                listOf(Chapter(id = "c1", bookId = bookId, chapterIndex = 0, title = "Глава облака", filePath = "/none/1.mp3")),
            )
            container.bookStorage.bookDir(bookId).mkdirs()
            BookManifestCodec.write(
                BookManifest(
                    id = bookId, title = "Облачная книга", source = "YANDEX_DISK", remotePath = "disk:/books/x",
                    totalDurationMs = 0, addedAt = 1, chapters = emptyList(),
                    files = listOf(ManifestFile("audio/1.mp3", 1024, "", remote = "disk:/books/x/1.mp3")),
                ),
                container.bookStorage.manifestFile(bookId),
            )
        }
    }

    @After
    fun tearDown() {
        container.downloadScheduler = originalScheduler
        runBlocking { container.bookActions.delete(bookId) }
    }

    @Test
    fun playOnACloudBookOffersTheDownloadInsteadOfStartingPlayback() {
        ActivityScenario.launch<BookActivity>(BookActivity.intent(context, bookId)).use {
            waitForView(withText("Облачная книга"))
            onView(withId(R.id.listen)).perform(click())

            waitForView(withText(R.string.download_offer_title))
            onView(withText(R.string.download_offer_title)).check(matches(isDisplayed()))

            onView(withText(R.string.download_start)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            val deadline = System.currentTimeMillis() + 10_000
            while (queued.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals(listOf(bookId), queued)
            assertTrue(runBlocking { container.libraryRepository.getBook(bookId)!!.downloadState } == DownloadState.DOWNLOADING)
        }
    }
}
