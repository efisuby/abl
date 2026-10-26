package com.efisuby.abl.ui.settings

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.efisuby.abl.MainActivity
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.di.AppContainer
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.ui.waitForView
import com.efisuby.abl.ui.waitUntil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private var wifiOnlyBefore = true
    private val cloudBook = Book(
        id = "settings-test-cloud",
        title = "Cloud Book",
        source = BookSource.YANDEX_DISK,
        remotePath = "/Books/Cloud Book",
        addedAt = 1,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = context.appContainer
        wifiOnlyBefore = runBlocking { container.settingsRepository.settings.first().wifiOnly }
    }

    @After
    fun tearDown() = runBlocking {
        container.settingsRepository.setWifiOnly(wifiOnlyBefore)
        container.libraryRepository.deleteBook(cloudBook.id)
        container.bookStorage.delete(cloudBook.id)
    }

    @Test
    fun allSectionsAreReachableFromTheLibrary() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.settings)).perform(click())

            waitForView(withId(R.id.wifiOnly))
            onView(withId(R.id.storageUsed)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.removeDownloads)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.lastSync)).perform(scrollTo()).check(matches(withText(R.string.settings_sync_never)))
            onView(withId(R.id.account)).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun wifiOnlySwitchIsStored() {
        ActivityScenario.launch<SettingsActivity>(SettingsActivity.intent(context)).use {
            waitForView(withId(R.id.wifiOnly))
            onView(withId(R.id.wifiOnly)).perform(click())

            waitUntil("the setting flips") {
                runBlocking { container.settingsRepository.settings.first().wifiOnly } != wifiOnlyBefore
            }
        }
    }

    @Test
    fun removingDownloadsKeepsTheCloudBookInTheLibrary() {
        val audio = container.bookStorage.audioFile(cloudBook.id, "01.mp3").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(1024))
        }
        runBlocking {
            container.libraryRepository.saveBook(
                cloudBook,
                listOf(Chapter(id = "${cloudBook.id}/1", bookId = cloudBook.id, chapterIndex = 0, title = "One", filePath = audio.path)),
            )
        }

        ActivityScenario.launch<SettingsActivity>(SettingsActivity.intent(context)).use {
            waitForView(withId(R.id.removeDownloads))
            onView(withId(R.id.removeDownloads)).perform(scrollTo()).check(matches(isEnabled())).perform(click())
            onView(withId(android.R.id.button1)).perform(click())

            waitUntil("the book is back in the cloud") {
                runBlocking { container.libraryRepository.getBook(cloudBook.id)?.downloadState } == DownloadState.IN_CLOUD
            }
            waitUntil("the audio is gone") { !container.bookStorage.audioDir(cloudBook.id).exists() }
            assertNotNull(runBlocking { container.libraryRepository.getBook(cloudBook.id) })
            assertTrue(runBlocking { container.libraryRepository.getChapters(cloudBook.id) }.isNotEmpty())
        }
    }
}
