package com.efisuby.abl.ui.importer

import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.importer.FixtureDocumentsProvider
import com.efisuby.abl.data.importer.ImportSource
import com.efisuby.abl.data.importer.ImportSources
import com.efisuby.abl.data.importer.SafImportSources
import com.efisuby.abl.data.importer.SourceFile
import com.efisuby.abl.di.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.google.android.material.progressindicator.BaseProgressIndicator
import org.hamcrest.Matcher
import org.junit.Assert.assertTrue
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives [ImportActivity] the way the picker result does, with the fixture folder served
 * through a mock documents provider. Copying is held at the first file so the progress screen
 * can be observed before the import finishes.
 */
@RunWith(AndroidJUnit4::class)
class ImportScreenTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var originalSources: ImportSources
    private lateinit var fixtures: SafImportSources
    private lateinit var booksBefore: Set<String>
    private val gate = CountDownLatch(1)
    private val mode = FixtureDocumentsProvider.Mode()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = context.appContainer
        originalSources = container.importSources
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val resolver = FixtureDocumentsProvider.install(testContext, context, File(context.cacheDir, "fixtures"), mode)
        fixtures = SafImportSources(resolver)
        booksBefore = runBlocking { container.libraryRepository.observeBooks().first().map { it.id }.toSet() }
    }

    @After
    fun tearDown() {
        gate.countDown()
        container.importSources = originalSources
        // The screen writes to the real library; remove only what this test imported.
        runBlocking {
            container.libraryRepository.observeBooks().first()
                .filter { it.id !in booksBefore }
                .forEach {
                    container.libraryRepository.deleteBook(it.id)
                    container.bookStorage.delete(it.id)
                }
        }
    }

    @Test
    fun importingAFolderShowsProgressAndOpensTheBookScreen() {
        container.importSources = GatedSources(fixtures, gate)

        ActivityScenario.launch<ImportActivity>(importIntent("tagged")).use {
            waitFor(allOf(withId(R.id.file), withText(context.getString(R.string.import_file, 1, 3, "01.mp3"))))
            onView(withId(R.id.progress)).check(matches(isDisplayed()))
            onView(withText(R.string.import_copying)).check(matches(isDisplayed()))

            gate.countDown()

            waitFor(withText("The Bull's Hour"))
            onView(withText("Ivan Yefremov")).check(matches(isDisplayed()))
            onView(withText("Chapter 1")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun anUnknownSizeShowsAnIndeterminateBar() {
        mode.sizeUnknown = true
        container.importSources = GatedSources(fixtures, gate)

        ActivityScenario.launch<ImportActivity>(importIntent("tagged")).use { scenario ->
            // Espresso waits for the main thread to go idle, which an indeterminate bar never lets it
            // do, so the views are read directly.
            val expected = context.getString(R.string.import_file, 1, 3, "01.mp3")
            val deadline = System.currentTimeMillis() + 10_000
            var shown = false
            while (!shown && System.currentTimeMillis() < deadline) {
                scenario.onActivity { shown = it.findViewById<TextView>(R.id.file).text.toString() == expected }
                if (!shown) Thread.sleep(100)
            }
            assertTrue("the first file is not shown", shown)
            scenario.onActivity {
                assertTrue(
                    "the bar must be indeterminate",
                    it.findViewById<BaseProgressIndicator<*>>(R.id.progress).isIndeterminate,
                )
            }
            gate.countDown()
            waitFor(withText("The Bull's Hour"))
        }
    }

    @Test
    fun aDroppedConnectionShowsTheErrorAndLeavesNothing() {
        mode.failAfterBytes = 1_000
        container.importSources = fixtures

        ActivityScenario.launch<ImportActivity>(importIntent("tagged")).use {
            waitFor(withText(R.string.import_error_io))
            onView(withText(R.string.import_close)).check(matches(isDisplayed()))
        }
        runBlocking { check(container.libraryRepository.observeBooks().first().all { it.id in booksBefore }) }
    }

    @Test
    fun folderWithoutAudioShowsAnError() {
        container.importSources = fixtures

        ActivityScenario.launch<ImportActivity>(importIntent("empty")).use {
            waitFor(withText(R.string.import_error_no_audio))
            onView(withText(R.string.import_close)).check(matches(isDisplayed()))
        }
        runBlocking {
            check(container.libraryRepository.observeBooks().first().all { it.id in booksBefore })
        }
    }

    private fun importIntent(folder: String) =
        ImportActivity.intent(context, FixtureDocumentsProvider.treeUri(folder), isFolder = true)

    /** Espresso does not wait for background copying, so poll until the view shows up. */
    private fun waitFor(matcher: Matcher<View>, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
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

    /** Opening any file waits for [gate]; progress for the first file is reported before that. */
    private class GatedSources(private val delegate: ImportSources, private val gate: CountDownLatch) : ImportSources {

        override fun folder(treeUri: Uri): ImportSource = gated(delegate.folder(treeUri))

        override fun file(uri: Uri): ImportSource = gated(delegate.file(uri))

        private fun gated(source: ImportSource) = object : ImportSource by source {
            override fun listFiles() = source.listFiles().map { file ->
                SourceFile(file.relativePath, file.sizeBytes) {
                    check(gate.await(30, TimeUnit.SECONDS)) { "gate was not released" }
                    file.open()
                }
            }
        }
    }
}
