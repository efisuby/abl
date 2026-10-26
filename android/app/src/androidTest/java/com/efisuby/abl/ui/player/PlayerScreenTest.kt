package com.efisuby.abl.ui.player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.efisuby.abl.MainActivity
import com.efisuby.abl.R
import com.efisuby.abl.ui.waitForView
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.importer.FixtureDocumentsProvider
import com.efisuby.abl.data.importer.SafTreeSource
import com.efisuby.abl.di.AppContainer
import com.efisuby.abl.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** The player screen against the real playback service and a 60-second fixture book. */
@RunWith(AndroidJUnit4::class)
class PlayerScreenTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var bookId: String
    private var probe: MediaController? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = context.appContainer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Keeps the permission dialog from covering the screen under test.
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val resolver = FixtureDocumentsProvider.install(instrumentation.context, context, File(context.cacheDir, "fixtures"))
        bookId = runBlocking {
            container.bookImporter.import(SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("long"))).id
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            probe?.run {
                stop()
                clearMediaItems()
                release()
            }
        }
        runBlocking {
            container.libraryRepository.deleteBook(bookId)
            container.bookStorage.delete(bookId)
        }
    }

    @Test
    fun back15MovesThePositionBackByFifteenSeconds() {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, bookId)).use {
            val controller = connectProbe()
            waitUntil("the book is loaded") { onMain { controller.currentMediaItem?.mediaId?.startsWith(bookId) == true } }

            onMain {
                controller.pause()
                controller.seekTo(40_000)
            }
            waitUntil("seeked to 0:40") { onMain { controller.currentPosition in 39_500..40_500 } }

            onView(withId(R.id.back15)).perform(click())

            waitUntil("moved to 0:25") { onMain { controller.currentPosition in 24_500..25_500 } }
        }
    }

    @Test
    fun leavingThePlayerRequestsSynchronizationExactlyOnce() {
        val original = container.syncScheduler
        val requests = java.util.concurrent.atomic.AtomicInteger()
        container.syncScheduler = object : com.efisuby.abl.data.sync.SyncScheduler {
            override fun request(immediate: Boolean) {
                requests.incrementAndGet()
            }

            override val active = kotlinx.coroutines.flow.flowOf(false)
        }
        try {
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, bookId)).use { scenario ->
                val controller = connectProbe()
                waitUntil("the book is loaded") { onMain { controller.currentMediaItem?.mediaId?.startsWith(bookId) == true } }
                assertEquals("nothing is requested while the player is open", 0, requests.get())

                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED) // the screen is left: onStop
                waitUntil("the request arrives") { requests.get() >= 1 }
            }
            Thread.sleep(500)
            assertEquals(1, requests.get())
        } finally {
            container.syncScheduler = original
        }
    }

    @Test
    fun libraryMiniPlayerShowsThePlayingBookAndOpensThePlayer() {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, bookId)).use {
            val controller = connectProbe()
            waitUntil("the book is loaded") { onMain { controller.currentMediaItem?.mediaId?.startsWith(bookId) == true } }

            ActivityScenario.launch(MainActivity::class.java).use {
                waitForView(allOf(withId(R.id.miniPlayer), isDisplayed()))
                onView(withId(R.id.miniPlayer)).perform(click())
                waitForView(allOf(withId(R.id.playPause), isDisplayed()))
            }
        }
    }

    @Test
    fun tappingABookmarkJumpsToItsPosition() {
        val chapterId = runBlocking { container.libraryRepository.getChapters(bookId).single().id }
        runBlocking { container.bookmarkRepository.add(bookId, chapterId, 42_000, "Important") }

        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, bookId)).use {
            val controller = connectProbe()
            waitUntil("the book is loaded") { onMain { controller.currentMediaItem?.mediaId?.startsWith(bookId) == true } }
            onMain { controller.pause() }

            onView(withId(R.id.bookmarks)).perform(click())
            onView(withText("Important")).perform(click())

            waitUntil("jumped to 0:42") { onMain { controller.currentPosition in 41_500..43_500 } }
        }
    }

    private fun connectProbe(): MediaController {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = onMain { MediaController.Builder(context, token).buildAsync() }
        return future.get(10, TimeUnit.SECONDS).also { probe = it }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("timed out waiting until $what", System.currentTimeMillis() < deadline)
            Thread.sleep(100)
        }
    }
}
