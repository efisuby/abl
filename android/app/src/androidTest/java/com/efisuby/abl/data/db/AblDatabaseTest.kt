package com.efisuby.abl.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.efisuby.abl.data.TestFixtures
import com.efisuby.abl.data.db.toEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AblDatabaseTest {

    private lateinit var database: AblDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AblDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun chaptersAreReturnedInIndexOrder() = runBlocking {
        val book = TestFixtures.book("book-1")
        database.bookDao().upsert(book.toEntity())
        database.chapterDao().upsertAll(
            listOf(
                TestFixtures.chapter(book.id, index = 2).toEntity(),
                TestFixtures.chapter(book.id, index = 0).toEntity(),
                TestFixtures.chapter(book.id, index = 1).toEntity(),
            ),
        )

        val chapters = database.chapterDao().getByBook(book.id)

        assertEquals(listOf(0, 1, 2), chapters.map { it.chapterIndex })
        assertEquals(listOf("book-1-0", "book-1-1", "book-1-2"), chapters.map { it.id })
    }
}
