package com.efisuby.abl.domain.repository

import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.Chapter
import kotlinx.coroutines.flow.Flow

/** Read and write access to the book library. The database is the single source of truth. */
interface LibraryRepository {

    fun observeBooks(): Flow<List<Book>>

    fun observeBook(id: String): Flow<Book?>

    fun observeChapters(bookId: String): Flow<List<Chapter>>

    suspend fun getBook(id: String): Book?

    suspend fun getChapters(bookId: String): List<Chapter>

    /** Inserts or replaces a book together with its full chapter list. */
    suspend fun saveBook(book: Book, chapters: List<Chapter>)

    suspend fun updateMetadata(book: Book)

    suspend fun setStatus(id: String, status: BookStatus)

    /** Removes the book and, through foreign keys, its chapters, progress and bookmarks. */
    suspend fun deleteBook(id: String)
}
