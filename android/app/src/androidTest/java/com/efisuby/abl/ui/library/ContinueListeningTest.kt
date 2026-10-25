package com.efisuby.abl.ui.library

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.view.View
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.efisuby.abl.MainActivity
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.importer.FixtureDocumentsProvider
import com.efisuby.abl.data.importer.SafTreeSource
import com.efisuby.abl.di.AppContainer
import com.efisuby.abl.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** The home screen card for the last played book, against the real service and database. */
@RunWith(AndroidJUnit4::class)
class ContinueListeningTest {

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
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val resolver = FixtureDocumentsProvider.install(instrumentation.context, context, File(context.cacheDir, "fixtures"))
        runBlocking {
            bookId = container.bookImporter.import(SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("long"))).id
            val chapter = container.libraryRepository.getChapters(bookId).single()
            container.progressRepository.savePosition(bookId, chapter.id, positionMs = 30_000, totalListenedMs = 30_000)
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
    fun cardShowsTheLastBookAndResumesItAtTheSavedPosition() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(inCard(withText("Long Book")))
            onView(inCard(withText("Long chapter"))).check(matches(isDisplayed()))
            onView(inCard(withText("0:30 из 1:00"))).check(matches(isDisplayed()))

            onView(withId(R.id.continuePlay)).perform(click())

            val controller = connectProbe()
            waitUntil("playing from 0:30") {
                onMain { controller.currentMediaItem?.mediaId?.startsWith(bookId) == true && controller.currentPosition in 29_500..34_000 }
            }
        }
    }

    private fun inCard(matcher: Matcher<View>): Matcher<View> = allOf(matcher, isDescendantOfA(withId(R.id.continueCard)))

    private fun connectProbe(): MediaController {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = onMain { MediaController.Builder(context, token).buildAsync() }
        return future.get(10, TimeUnit.SECONDS).also { probe = it }
    }

    private fun waitFor(matcher: Matcher<View>) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            try {
                onView(matcher).check(matches(isDisplayed()))
                return
            } catch (e: RuntimeException) {
                if (System.currentTimeMillis() > deadline) throw e
            } catch (e: AssertionError) {
                if (System.currentTimeMillis() > deadline) throw e
            }
            Thread.sleep(100)
        }
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
