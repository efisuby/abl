package com.efisuby.abl.ui.library

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.efisuby.abl.MainActivity
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.di.AppContainer
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.ui.waitForGone
import com.efisuby.abl.ui.waitForView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The library list against the real database; test books are records only, without audio. */
@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private val ids = listOf("ui-test-new", "ui-test-listening", "ui-test-done")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = context.appContainer
    }

    @After
    fun tearDown() = runBlocking {
        ids.forEach { container.libraryRepository.deleteBook(it) }
    }

    @Test
    fun rowsShowAuthorStatusAndListenedShare() {
        insertBooks()

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForView(row("Тест: новая", "Не начата"))
            waitForView(row("Тест: слушаю", "Слушаю · 50%"))
            waitForView(row("Тест: прослушана", "Прослушана"))
            waitForView(allOf(withId(R.id.bookRow), hasDescendant(withText("Тест: новая")), hasDescendant(withText("Автор Тестов · 2:00"))))
        }
    }

    @Test
    fun searchWithoutMatchesOffersToResetFilters() {
        insertBooks()

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForView(withText("Тест: новая"))
            onView(withId(R.id.search)).perform(click())
            onView(withId(androidx.appcompat.R.id.search_src_text)).perform(replaceText("нет такой книги"))
            waitForView(withText(R.string.library_nothing_found))
            waitForGone(withText("Тест: новая"))

            onView(withId(R.id.resetFilters)).perform(click())
            waitForView(withText("Тест: новая"))
        }
    }

    @Test
    fun emptyLibraryShowsTheImportHint() {
        // Never wipes real data: runs only where the library is empty anyway.
        assumeTrue(runBlocking { container.libraryRepository.observeBooks().first().isEmpty() })

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForView(withText(R.string.library_empty))
            waitForView(withId(R.id.importFolder))
        }
    }

    private fun row(title: String, status: String) =
        allOf(withId(R.id.bookRow), hasDescendant(withText(title)), hasDescendant(withText(status)))

    private fun insertBooks() = runBlocking {
        val statuses = listOf(BookStatus.NOT_STARTED, BookStatus.IN_PROGRESS, BookStatus.FINISHED)
        val titles = listOf("Тест: новая", "Тест: слушаю", "Тест: прослушана")
        ids.forEachIndexed { i, id ->
            val book = Book(
                id = id,
                title = titles[i],
                author = "Автор Тестов",
                source = BookSource.LOCAL,
                totalDurationMs = 120_000,
                status = statuses[i],
                addedAt = 1,
            )
            container.libraryRepository.saveBook(
                book,
                listOf(Chapter(id = "$id-c1", bookId = id, chapterIndex = 0, title = "Глава", filePath = "/nonexistent", durationMs = 120_000)),
            )
        }
        container.progressRepository.savePosition("ui-test-listening", "ui-test-listening-c1", positionMs = 60_000, totalListenedMs = 60_000)
    }
}
