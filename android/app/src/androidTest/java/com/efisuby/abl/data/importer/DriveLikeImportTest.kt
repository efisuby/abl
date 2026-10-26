package com.efisuby.abl.data.importer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.efisuby.abl.appContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Imports through a provider that behaves like Google Drive on a bad day: unknown sizes,
 * dropped connections, streams that end early, revoked access. Whatever happens, a failed
 * import leaves no `.part`, no book directory and no database row.
 */
@RunWith(AndroidJUnit4::class)
class DriveLikeImportTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val container = context.appContainer
    private val mode = FixtureDocumentsProvider.Mode()
    private lateinit var source: SafTreeSource
    private lateinit var booksBefore: Set<String>
    private val bookDirsBefore = container.bookStorage.root.list().orEmpty().toSet()

    @Before
    fun setUp() {
        val resolver = FixtureDocumentsProvider.install(
            InstrumentationRegistry.getInstrumentation().context, context, File(context.cacheDir, "fixtures"), mode,
        )
        source = SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("nested"))
        booksBefore = runBlocking { container.libraryRepository.observeBooks().first().map { it.id }.toSet() }
    }

    @After
    fun tearDown() = runBlocking {
        container.libraryRepository.observeBooks().first().filter { it.id !in booksBefore }.forEach { container.bookActions.delete(it.id) }
    }

    private fun assertNothingLeft() {
        runBlocking { assertEquals(booksBefore, container.libraryRepository.observeBooks().first().map { it.id }.toSet()) }
        assertEquals(bookDirsBefore, container.bookStorage.root.list().orEmpty().toSet())
    }

    @Test
    fun unknownSizesImportFineAndTheTotalIsReportedAsUnknown() = runBlocking {
        mode.sizeUnknown = true
        val totals = mutableListOf<Long>()
        val book = container.bookImporter.import(source) { totals += it.totalBytes }

        assertEquals(3, container.libraryRepository.getChapters(book.id).size)
        assertTrue(totals.isNotEmpty())
        assertTrue("the total stays unknown: $totals", totals.all { it < 0 })
    }

    @Test
    fun aConnectionDroppedMidFileCleansEverything() {
        mode.failAfterBytes = 2_000
        val e = assertThrows(ImportException::class.java) { runBlocking { container.bookImporter.import(source) } }
        assertEquals(ImportException.Reason.IO, e.reason)
        assertNothingLeft()
    }

    @Test
    fun aStreamThatEndsEarlyIsNotAcceptedAsTheWholeFile() {
        mode.endEarlyAfterBytes = 2_000
        val e = assertThrows(ImportException::class.java) { runBlocking { container.bookImporter.import(source) } }
        assertEquals(ImportException.Reason.IO, e.reason)
        assertNothingLeft()
    }

    @Test
    fun revokedAccessCleansEverything() {
        mode.denyOpen = true
        val e = assertThrows(ImportException::class.java) { runBlocking { container.bookImporter.import(source) } }
        assertEquals(ImportException.Reason.IO, e.reason)
        assertNothingLeft()
    }

    @Test
    fun slowReadsStillImport() = runBlocking {
        mode.chunkDelayMs = 2
        val book = container.bookImporter.import(source)
        assertEquals(3, container.libraryRepository.getChapters(book.id).size)
        assertFalse(container.bookStorage.audioDir(book.id).walkTopDown().any { it.name.endsWith(BookStorage.PART_SUFFIX) })
    }
}
