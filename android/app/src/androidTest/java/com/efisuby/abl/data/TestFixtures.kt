package com.efisuby.abl.data

import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.Chapter

object TestFixtures {

    fun book(id: String, title: String = "Book $id"): Book = Book(
        id = id,
        title = title,
        author = "Author",
        source = BookSource.LOCAL,
        addedAt = 1_000L,
    )

    fun chapter(bookId: String, index: Int): Chapter = Chapter(
        id = "$bookId-$index",
        bookId = bookId,
        chapterIndex = index,
        title = "Chapter ${index + 1}",
        filePath = "/books/$bookId/${index + 1}.mp3",
        durationMs = 60_000L,
    )
}
