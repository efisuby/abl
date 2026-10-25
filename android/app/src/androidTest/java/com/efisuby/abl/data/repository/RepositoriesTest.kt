package com.efisuby.abl.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.efisuby.abl.data.TestFixtures
import com.efisuby.abl.data.db.AblDatabase
import com.efisuby.abl.data.db.toEntity
import com.efisuby.abl.domain.model.Bookmark
import com.efisuby.abl.domain.repository.LibraryRepository
import com.efisuby.abl.domain.repository.ProgressRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoriesTest {

    private lateinit var database: AblDatabase
    private lateinit var library: LibraryRepository
    private lateinit var progress: ProgressRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AblDatabase::class.java).build()
        library = RoomLibraryRepository(database)
        progress = RoomProgressRepository(database, DeviceIdProvider(context))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun savingABookKeepsItsChaptersAndProgress() = runBlocking {
        val book = TestFixtures.book("book-1")
        library.saveBook(book, listOf(TestFixtures.chapter(book.id, 0), TestFixtures.chapter(book.id, 1)))

        assertEquals(2, library.getChapters(book.id).size)
        assertNotNull(library.getBook(book.id))
    }

    @Test
    fun savingPositionAdvancesUpdatedAt() = runBlocking {
        val book = TestFixtures.book("book-1")
        library.saveBook(book, listOf(TestFixtures.chapter(book.id, 0)))

        progress.savePosition(book.id, "${book.id}-0", positionMs = 1_000L, totalListenedMs = 1_000L)
        val first = requireNotNull(progress.get(book.id))

        progress.savePosition(book.id, "${book.id}-0", positionMs = 2_000L, totalListenedMs = 2_000L)
        val second = requireNotNull(progress.get(book.id))

        assertTrue(
            "updatedAt must grow monotonically, was ${first.updatedAt} then ${second.updatedAt}",
            second.updatedAt > first.updatedAt,
        )
        assertEquals(2_000L, second.positionMs)
        assertEquals(book.id, second.bookId)
    }

    @Test
    fun deletingABookRemovesChaptersAndBookmarks() = runBlocking {
        val book = TestFixtures.book("book-1")
        library.saveBook(book, listOf(TestFixtures.chapter(book.id, 0)))
        database.bookmarkDao().upsert(
            Bookmark(
                id = "bookmark-1",
                bookId = book.id,
                chapterId = "${book.id}-0",
                positionMs = 500L,
                createdAt = 1L,
                updatedAt = 1L,
            ).toEntity(),
        )
        progress.savePosition(book.id, "${book.id}-0", 500L, 500L)

        library.deleteBook(book.id)

        assertNull(library.getBook(book.id))
        assertTrue(library.getChapters(book.id).isEmpty())
        assertTrue(database.bookmarkDao().getAll().isEmpty())
        assertNull(progress.get(book.id))
    }
}
