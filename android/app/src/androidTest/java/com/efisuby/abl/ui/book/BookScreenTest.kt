package com.efisuby.abl.ui.book

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
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
import com.efisuby.abl.ui.onMain
import com.efisuby.abl.ui.waitForView
import com.efisuby.abl.ui.waitUntil
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Book screen and metadata editing against the real database, storage and player service. */
@RunWith(AndroidJUnit4::class)
class BookScreenTest {

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
        runBlocking { container.bookActions.delete(bookId) }
    }

    @Test
    fun tappingAChapterStartsItsPlayback() {
        ActivityScenario.launch<BookActivity>(BookActivity.intent(context, bookId)).use {
            waitForView(withText("Long chapter"))
            onView(withText("Long chapter")).perform(click())

            val controller = connectProbe()
            waitUntil("the chapter is playing") {
                onMain { controller.currentMediaItem?.mediaId?.startsWith(bookId) == true && controller.playWhenReady }
            }
        }
    }

    @Test
    fun editedTitleIsShownOnTheBookScreenAndInTheList() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForView(allOf(withText("Long Book"), isDescendantOfA(withId(R.id.books))))
            onView(allOf(withText("Long Book"), isDescendantOfA(withId(R.id.books)))).perform(click())

            waitForView(withId(R.id.edit))
            onView(withId(R.id.edit)).perform(click())
            // The form is filled from the database; editing before that would be overwritten.
            waitForView(allOf(withId(R.id.titleField), withText("Long Book")))
            onView(withId(R.id.titleField)).perform(replaceText("Отредактированная книга"))
            onView(withId(R.id.save)).perform(click())

            waitForView(allOf(withId(R.id.title), withText("Отредактированная книга")))
            pressBack()
            waitForView(allOf(withText("Отредактированная книга"), isDescendantOfA(withId(R.id.books))))
        }
    }

    @Test
    fun deletingAfterConfirmationRemovesTheBookAndItsFiles() {
        ActivityScenario.launch<BookActivity>(BookActivity.intent(context, bookId)).use {
            waitForView(withText("Long chapter"))
            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(R.string.book_delete)).perform(click())
            onView(withText(R.string.book_delete_confirm)).perform(click())

            waitUntil("the record is gone") { runBlocking { container.libraryRepository.getBook(bookId) == null } }
            waitUntil("the files are gone") { !container.bookStorage.bookDir(bookId).exists() }
        }
        assertFalse(container.bookStorage.bookDir(bookId).exists())
    }

    private fun connectProbe(): MediaController {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = onMain { MediaController.Builder(context, token).buildAsync() }
        return future.get(10, TimeUnit.SECONDS).also { probe = it }
    }
}
