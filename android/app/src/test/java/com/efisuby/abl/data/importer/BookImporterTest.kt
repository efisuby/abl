package com.efisuby.abl.data.importer

import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class BookImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val library = FakeLibrary()
    private val storage by lazy { BookStorage(temp.newFolder("books")) }

    private fun importer(
        tags: (File) -> FileTags = { FileTags(durationMs = 1_000L) },
        chapters: (File) -> List<EmbeddedChapter> = { emptyList() },
    ) = BookImporter(
        storage = storage,
        library = library,
        tagReader = object : TagReader {
            override fun read(file: File) = tags(file)
            override fun embeddedPicture(file: File): ByteArray? = null
        },
        chapterReader = object : ChapterReader {
            override fun read(file: File) = chapters(file)
        },
        clock = { 42L },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `nested folder becomes one book with chapters in path order`() = runBlocking {
        val source = FolderSource(
            "Ivan Yefremov - The Bull's Hour",
            "part-2/01.mp3", "part-1/02.mp3", "cover.jpg", "part-1/01.mp3",
        )

        val book = importer().import(source)

        val chapters = library.chapters.getValue(book.id)
        assertEquals("The Bull's Hour", book.title)
        assertEquals("Ivan Yefremov", book.author)
        assertEquals(3_000L, book.totalDurationMs)
        assertEquals(listOf(0, 1, 2), chapters.map { it.chapterIndex })
        assertEquals(
            listOf("audio/part-1/01.mp3", "audio/part-1/02.mp3", "audio/part-2/01.mp3"),
            chapters.map { File(it.filePath).relativeTo(storage.bookDir(book.id)).invariantSeparatorsPath },
        )
        val manifest = BookManifestCodec.read(storage.manifestFile(book.id))
        assertEquals(chapters.map { it.id }, manifest.chapters.map { it.id })
        assertEquals(3, manifest.files.size)
    }

    @Test
    fun `m4b with embedded chapters is split into them`() = runBlocking {
        val markers = listOf(EmbeddedChapter(0, "Opening"), EmbeddedChapter(2_000, "Middle"), EmbeddedChapter(4_500, "Ending"))
        val importer = importer(
            tags = { FileTags(title = "Chaptered Book", artist = "Test Author", durationMs = 6_000L) },
            chapters = { markers },
        )

        val book = importer.import(FileSource("chapters.m4b"))

        val chapters = library.chapters.getValue(book.id)
        assertEquals("Chaptered Book", book.title)
        assertEquals(listOf("Opening", "Middle", "Ending"), chapters.map { it.title })
        assertEquals(listOf(0L, 2_000L, 4_500L), chapters.map { it.startMs })
        assertEquals(listOf(2_000L, 2_500L, 1_500L), chapters.map { it.durationMs })
        assertEquals(1, chapters.map { it.filePath }.distinct().size)
    }

    @Test
    fun `speech-synthesis markup is stripped from chapter titles`() = runBlocking {
        val markers = listOf(
            EmbeddedChapter(0, "Д+эн Абн+етт Возвыш+ение Х+оруса; "),
            EmbeddedChapter(2_000, "ПРИМ+АРХИ Х+орус[break]. прим+арх"),
            EmbeddedChapter(4_500, "[voice:/tmp/proc-1/voice.wav"),
        )
        val importer = importer(tags = { FileTags(durationMs = 6_000L) }, chapters = { markers })

        val book = importer.import(FileSource("tts.m4b"))

        assertEquals(
            listOf("Дэн Абнетт Возвышение Хоруса", "ПРИМАРХИ Хорус. примарх", "3"),
            library.chapters.getValue(book.id).map { it.title },
        )
    }

    @Test
    fun `chapter ids are deterministic`() {
        assertEquals(
            BookImporter.chapterId("b", "audio/01.mp3", 0),
            BookImporter.chapterId("b", "audio/01.mp3", 0),
        )
        assertTrue(BookImporter.chapterId("b", "audio/01.mp3", 0) != BookImporter.chapterId("b", "audio/02.mp3", 0))
    }

    @Test
    fun `folder without audio is rejected and leaves nothing`() = runBlocking {
        try {
            importer().import(FolderSource("docs", "readme.txt", "cover.jpg"))
            fail("import should have failed")
        } catch (e: ImportException) {
            assertEquals(ImportException.Reason.NO_AUDIO_FILES, e.reason)
        }

        assertTrue(library.books.isEmpty())
        assertTrue(storage.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun `cancelled import removes the partially copied book`() = runBlocking {
        val importer = importer()
        val deferred = async(Dispatchers.IO) {
            importer.import(FolderSource("book", "01.mp3", "02.mp3")) { progress ->
                if (progress.fileIndex == 1) throw CancellationException("user cancelled")
            }
        }

        try {
            deferred.await()
            fail("import should have been cancelled")
        } catch (expected: CancellationException) {
        }

        assertTrue(library.books.isEmpty())
        assertTrue(storage.root.listFiles().isNullOrEmpty())
    }

    private class FolderSource(override val displayName: String, vararg paths: String) : ImportSource {
        private val files = paths.toList()
        override val isFolder = true
        override fun listFiles() = files.map { SourceFile(it, 4L) { bytes() } }
    }

    private class FileSource(override val displayName: String) : ImportSource {
        override val isFolder = false
        override fun listFiles() = listOf(SourceFile(displayName, 4L) { bytes() })
    }

    private class FakeLibrary : LibraryRepository {
        val books = mutableMapOf<String, Book>()
        val chapters = mutableMapOf<String, List<Chapter>>()

        override suspend fun saveBook(book: Book, chapters: List<Chapter>) {
            books[book.id] = book
            this.chapters[book.id] = chapters
        }

        override fun observeBooks(): Flow<List<Book>> = emptyFlow()
        override fun observeBook(id: String): Flow<Book?> = emptyFlow()
        override fun observeChapters(bookId: String): Flow<List<Chapter>> = emptyFlow()
        override suspend fun getBook(id: String) = books[id]
        override suspend fun getChapters(bookId: String) = chapters[bookId].orEmpty()
        override suspend fun updateMetadata(book: Book) = Unit
        override suspend fun setStatus(id: String, status: BookStatus) = Unit
        override suspend fun deleteBook(id: String) = Unit
    }

    private companion object {
        fun bytes(): InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
    }
}
