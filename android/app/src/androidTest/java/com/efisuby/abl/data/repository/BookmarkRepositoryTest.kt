package com.efisuby.abl.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.efisuby.abl.data.TestFixtures
import com.efisuby.abl.data.db.AblDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkRepositoryTest {

    private lateinit var database: AblDatabase
    private lateinit var bookmarks: RoomBookmarkRepository
    private var now = 1_000L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AblDatabase::class.java).build()
        bookmarks = RoomBookmarkRepository(database, clock = { now })
        val book = TestFixtures.book("book")
        RoomLibraryRepository(database).saveBook(book, (0..2).map { TestFixtures.chapter(book.id, it) })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun bookmarksAreListedInChapterOrderThenByPosition() = runBlocking {
        bookmarks.add("book", "book-2", 5_000, null)
        bookmarks.add("book", "book-0", 50_000, "  late in chapter one ")
        bookmarks.add("book", "book-0", 10_000, "")

        val listed = bookmarks.observe("book").first()

        assertEquals(listOf("book-0" to 10_000L, "book-0" to 50_000L, "book-2" to 5_000L), listed.map { it.chapterId to it.positionMs })
        assertEquals("late in chapter one", listed[1].note)
        assertNull(listed[0].note)
    }

    @Test
    fun deletionLeavesATombstoneForSync() = runBlocking {
        val kept = bookmarks.add("book", "book-0", 1_000, null)
        val deleted = bookmarks.add("book", "book-1", 2_000, null)

        now = 5_000L
        bookmarks.delete(deleted.id)

        assertEquals(listOf(kept.id), bookmarks.observe("book").first().map { it.id })
        val tombstone = database.bookmarkDao().getAll().single { it.id == deleted.id }
        assertTrue(tombstone.deleted)
        assertEquals(5_000L, tombstone.updatedAt)
    }
}
