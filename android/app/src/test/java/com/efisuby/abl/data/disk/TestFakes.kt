package com.efisuby.abl.data.disk

import com.efisuby.abl.data.importer.BookManifest
import com.efisuby.abl.data.importer.BookManifestCodec
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.importer.ManifestFile
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

class FakeLibrary : LibraryRepository {
    val books = mutableMapOf<String, Book>()
    val chapters = mutableMapOf<String, List<Chapter>>()
    override fun observeBooks(): Flow<List<Book>> = flow { emit(books.values.toList()) }
    override fun observeBook(id: String): Flow<Book?> = emptyFlow()
    override fun observeChapters(bookId: String): Flow<List<Chapter>> = emptyFlow()
    override suspend fun getBook(id: String) = books[id]
    override suspend fun getChapters(bookId: String) = chapters[bookId].orEmpty()
    override suspend fun saveBook(book: Book, chapters: List<Chapter>) {
        books[book.id] = book
        this.chapters[book.id] = chapters
    }
    override suspend fun updateMetadata(book: Book) {
        books[book.id] = book
    }
    override suspend fun setStatus(id: String, status: BookStatus) {
        books[id] = books.getValue(id).copy(status = status)
    }
    override suspend fun setDownloadState(id: String, state: DownloadState) {
        books[id] = books.getValue(id).copy(downloadState = state)
    }
    override suspend fun deleteBook(id: String) {
        books.remove(id)
    }
}

/** A cloud book in the library with a manifest listing [files] as (relative path, size, md5). */
fun FakeLibrary.addCloudBook(
    storage: BookStorage,
    id: String = "cloud-book",
    state: DownloadState = DownloadState.IN_CLOUD,
    files: List<Triple<String, Long, String>>,
): Book {
    val book = Book(
        id = id, title = "Cloud", source = BookSource.YANDEX_DISK,
        remotePath = "disk:/books/Cloud", downloadState = state, addedAt = 1,
    )
    books[id] = book
    storage.bookDir(id).mkdirs()
    BookManifestCodec.write(
        BookManifest(
            id = id, title = "Cloud", source = "YANDEX_DISK", remotePath = book.remotePath,
            totalDurationMs = 0, addedAt = 1, chapters = emptyList(),
            files = files.map { (path, size, md5) ->
                ManifestFile("audio/$path", size, md5, remote = "disk:/books/Cloud/$path")
            },
        ),
        storage.manifestFile(id),
    )
    return book
}
