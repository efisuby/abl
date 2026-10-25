package com.efisuby.abl.data.importer

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.efisuby.abl.data.db.AblDatabase
import com.efisuby.abl.data.repository.RoomLibraryRepository
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Imports the fixtures in `androidTest/assets/fixtures` through the documents contract with
 * the real tag reader, Media3 chapter reader, file copy and Room.
 */
@RunWith(AndroidJUnit4::class)
class SafImportTest {

    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private lateinit var database: AblDatabase
    private lateinit var library: LibraryRepository
    private lateinit var storage: BookStorage
    private lateinit var importer: BookImporter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        resolver = FixtureDocumentsProvider.install(testContext, context, File(context.cacheDir, "fixtures"))
        database = Room.inMemoryDatabaseBuilder(context, AblDatabase::class.java).build()
        library = RoomLibraryRepository(database)
        storage = BookStorage(File(context.cacheDir, "books-test").apply { deleteRecursively() })
        val chapters = CascadingChapterReader(listOf(Media3ChapterReader(context), Mp4ChapterTrackReader()))
        importer = BookImporter(storage, library, MediaMetadataTagReader(), chapters)
    }

    @After
    fun tearDown() {
        database.close()
        storage.root.deleteRecursively()
    }

    @Test
    fun pickedFolderWithThreeMp3FilesBecomesOneBookWithThreeChapters() = runBlocking {
        val progress = mutableListOf<ImportProgress>()

        val book = importer.import(SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("tagged"))) {
            progress += it
        }

        val chapters = library.getChapters(book.id)
        assertEquals(1, library.observeBooksOnce().size)
        assertEquals(3, chapters.size)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), chapters.map { it.title })
        assertEquals("The Bull's Hour", book.title)
        assertEquals("Ivan Yefremov", book.author)
        assertEquals("Sergey Larionov", book.narrator)
        assertEquals(BookSource.LOCAL, book.source)
        chapters.forEach { assertTrue("duration of ${it.title}", abs(it.durationMs - 1_000L) < 150L) }
        chapters.forEach { assertTrue(File(it.filePath).isFile) }
        assertTrue(File(requireNotNull(book.coverPath)).length() > 0)
        assertTrue(storage.manifestFile(book.id).isFile)
        val total = progress.last().totalBytes
        assertTrue(total > 0)
        assertEquals(total, progress.last().copiedBytes)
    }

    @Test
    fun folderWithoutTagsIsNamedAfterTheFolder() = runBlocking {
        val book = importer.import(
            SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("Ivan Yefremov - The Bull's Hour")),
        )

        assertEquals("The Bull's Hour", book.title)
        assertEquals("Ivan Yefremov", book.author)
        assertNull(book.narrator)
        assertNull(book.coverPath)
        assertEquals(listOf("01", "02"), library.getChapters(book.id).map { it.title })
    }

    @Test
    fun partialTagsNameTheChapterButNotTheBook() = runBlocking {
        val book = importer.import(SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("partial")))

        assertEquals("partial", book.title)
        assertNull(book.author)
        assertEquals(listOf("Only a title"), library.getChapters(book.id).map { it.title })
    }

    @Test
    fun nestedFoldersAreOrderedByPath() = runBlocking {
        val book = importer.import(SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("nested")))

        val relative = library.getChapters(book.id).map {
            File(it.filePath).relativeTo(storage.audioDir(book.id)).invariantSeparatorsPath
        }
        assertEquals(listOf("part-1/01.mp3", "part-1/02.mp3", "part-2/01.mp3"), relative)
    }

    @Test
    fun folderWithoutAudioCreatesNoBook() = runBlocking {
        try {
            importer.import(SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("empty")))
            fail("import should have failed")
        } catch (e: ImportException) {
            assertEquals(ImportException.Reason.NO_AUDIO_FILES, e.reason)
        }
        assertTrue(library.observeBooksOnce().isEmpty())
    }

    @Test
    fun m4bWithEmbeddedChaptersKeepsItsBoundaries() = assertThreeChapters("chapters.m4b")

    @Test
    fun m4bWithOnlyQuickTimeChapterTrack() = assertThreeChapters("chapters-qt-only.m4b")

    @Test
    fun m4bWithOnlyNeroChpl() = assertThreeChapters("chapters-chpl-only.m4b")

    @Test
    fun mp3WithoutChaptersBecomesOneChapter() = runBlocking {
        val book = importer.import(SafFileSource(resolver, FixtureDocumentsProvider.documentUri("partial/01.mp3")))

        val chapters = library.getChapters(book.id)
        assertEquals("Only a title", book.title)
        assertEquals(1, chapters.size)
        assertEquals(0L, chapters.single().startMs)
    }

    @Test
    fun m4bWithQuickTimeKeysTagsIsNamedFromThem() = runBlocking {
        // The tag layout ebook2audiobook writes: MediaMetadataRetriever alone sees no title.
        val book = importer.import(SafFileSource(resolver, FixtureDocumentsProvider.documentUri("mdta-tags.m4b")))

        assertEquals("Возвышение Хоруса", book.title)
        assertEquals("Дэн Абнетт", book.author)
    }

    @Test
    fun seriesIsReadFromEveryTagConvention() = runBlocking {
        val sources = listOf(
            SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("series-id3")) to 1, // ID3 TXXX
            SafFileSource(resolver, FixtureDocumentsProvider.documentUri("series-mvnm.mp3")) to 2, // ID3 MVNM
            SafFileSource(resolver, FixtureDocumentsProvider.documentUri("series.m4b")) to 3, // MP4 ----
            SafFileSource(resolver, FixtureDocumentsProvider.documentUri("series-mvn.m4b")) to 4, // MP4 ©mvn
        )
        for ((source, index) in sources) {
            val stored = requireNotNull(library.getBook(importer.import(source).id))
            assertEquals("${source.displayName} series", "Ересь Хоруса", stored.series)
            assertEquals("${source.displayName} index", index, stored.seriesIndex)
        }
    }

    @Test
    fun bookWithoutSeriesTagsHasNoSeries() = runBlocking {
        val book = importer.import(SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("tagged")))

        assertNull(book.series)
        assertNull(book.seriesIndex)
    }

    private fun assertThreeChapters(file: String) = runBlocking {
        val book = importer.import(SafFileSource(resolver, FixtureDocumentsProvider.documentUri(file)))

        val chapters = library.getChapters(book.id)
        assertNotNull(book)
        assertEquals("Chaptered Book", book.title)
        assertEquals("Test Author", book.author)
        assertEquals(listOf("Opening", "Middle", "Ending"), chapters.map { it.title })
        assertEquals(listOf(0L, 2_000L, 4_500L), chapters.map { it.startMs })
        assertEquals(listOf(2_000L, 2_500L), chapters.take(2).map { it.durationMs })
        assertTrue("last chapter ${chapters[2].durationMs}", abs(chapters[2].durationMs - 1_500L) < 150L)
        assertEquals(1, chapters.map { it.filePath }.distinct().size)
    }

    private suspend fun LibraryRepository.observeBooksOnce() = observeBooks().first()
}
