package com.efisuby.abl.data.repository

import androidx.room.withTransaction
import com.efisuby.abl.data.db.AblDatabase
import com.efisuby.abl.data.db.toDomain
import com.efisuby.abl.data.db.toEntity
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLibraryRepository(private val database: AblDatabase) : LibraryRepository {

    private val bookDao = database.bookDao()
    private val chapterDao = database.chapterDao()

    override fun observeBooks(): Flow<List<Book>> =
        bookDao.observeAll().map { books -> books.map { it.toDomain() } }

    override fun observeBook(id: String): Flow<Book?> =
        bookDao.observeById(id).map { it?.toDomain() }

    override fun observeChapters(bookId: String): Flow<List<Chapter>> =
        chapterDao.observeByBook(bookId).map { chapters -> chapters.map { it.toDomain() } }

    override suspend fun getBook(id: String): Book? = bookDao.getById(id)?.toDomain()

    override suspend fun getChapters(bookId: String): List<Chapter> =
        chapterDao.getByBook(bookId).map { it.toDomain() }

    override suspend fun saveBook(book: Book, chapters: List<Chapter>) {
        database.withTransaction {
            bookDao.upsert(book.toEntity())
            chapterDao.deleteByBook(book.id)
            chapterDao.upsertAll(chapters.map { it.toEntity() })
        }
    }

    override suspend fun updateMetadata(book: Book) {
        bookDao.updateMetadata(
            id = book.id,
            title = book.title,
            author = book.author,
            narrator = book.narrator,
            series = book.series,
            seriesIndex = book.seriesIndex,
            coverPath = book.coverPath,
        )
    }

    override suspend fun setStatus(id: String, status: BookStatus) {
        bookDao.updateStatus(id, status.name)
    }

    override suspend fun setDownloadState(id: String, state: DownloadState) {
        bookDao.updateDownloadState(id, state.name)
    }

    override suspend fun deleteBook(id: String) {
        // Chapters, progress and bookmarks are removed by ON DELETE CASCADE.
        bookDao.deleteById(id)
    }
}
