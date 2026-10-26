package com.efisuby.abl.ui.book

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.disk.BookUploader
import com.efisuby.abl.data.disk.FakeDiskServer
import com.efisuby.abl.data.disk.UploadScheduler
import com.efisuby.abl.data.disk.UploadStatus
import com.efisuby.abl.data.disk.toUploadError
import com.efisuby.abl.data.importer.FixtureDocumentsProvider
import com.efisuby.abl.data.importer.SafTreeSource
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.ui.waitForView
import com.efisuby.abl.ui.waitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** "Upload to cloud" in the book menu: progress is shown, then the book is a cloud book that still plays. */
@RunWith(AndroidJUnit4::class)
class UploadScreenTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = context.appContainer
    private val server = FakeDiskServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var originalScheduler: UploadScheduler
    private lateinit var bookId: String

    @Before
    fun setUp() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val resolver = FixtureDocumentsProvider.install(instrumentation.context, context, File(context.cacheDir, "fixtures"))
        bookId = runBlocking { container.bookImporter.import(SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("nested"))).id }
        originalScheduler = container.uploadScheduler
        // What UploadWorker does, without WorkManager.
        container.uploadScheduler = object : UploadScheduler {
            override fun enqueue(bookId: String, wifiOnly: Boolean) {
                scope.launch {
                    val uploader = BookUploader(server.api(context), OkHttpClient(), container.bookStorage, container.libraryRepository)
                    try {
                        uploader.run(bookId) { done, total -> container.uploadTracker.set(bookId, UploadStatus.Running(done, total)) }
                        container.uploadTracker.set(bookId, null)
                    } catch (e: Exception) {
                        container.uploadTracker.set(bookId, UploadStatus.Failed(e.toUploadError()))
                    }
                }
            }

            override fun cancel(bookId: String) = Unit
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        container.uploadScheduler = originalScheduler
        container.uploadTracker.set(bookId, null)
        runBlocking { container.bookActions.delete(bookId) }
        server.close()
    }

    @Test
    fun theBookGoesToTheCloudAndStillPlays() {
        ActivityScenario.launch<BookActivity>(BookActivity.intent(context, bookId)).use {
            waitForView(withId(R.id.listen))
            onView(withId(R.id.download)).check(matches(androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE)))

            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(R.string.upload_book)).perform(click())

            waitUntil("the book is a cloud book") {
                runBlocking { container.libraryRepository.getBook(bookId)!!.source } == BookSource.YANDEX_DISK
            }
            // The cloud button appears; the upload entry is gone from the menu.
            waitForView(withId(R.id.download))
            onView(withId(R.id.download)).check(matches(withText(R.string.download_remove_button)))
            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(R.string.upload_book)).check(doesNotExist())
            androidx.test.espresso.Espresso.pressBack()

            assertEquals(3, server.files.keys.count { it.startsWith("disk:/Приложения/AudioBookPlayer/books/") })
            onView(withId(R.id.listen)).perform(click())
            waitForView(withId(R.id.playerToolbar))
            onView(withId(R.id.playerToolbar)).check(matches(isDisplayed()))
        }
    }
}
