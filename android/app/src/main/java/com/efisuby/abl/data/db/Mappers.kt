package com.efisuby.abl.data.db

import com.efisuby.abl.data.db.entity.BookEntity
import com.efisuby.abl.data.db.entity.BookmarkEntity
import com.efisuby.abl.data.db.entity.ChapterEntity
import com.efisuby.abl.data.db.entity.ProgressEntity
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.domain.model.Bookmark
import com.efisuby.abl.domain.model.Chapter
import com.efisuby.abl.domain.model.DownloadState
import com.efisuby.abl.domain.model.Progress

fun BookEntity.toDomain(): Book = Book(
    id = id,
    title = title,
    author = author,
    narrator = narrator,
    series = series,
    seriesIndex = seriesIndex,
    coverPath = coverPath,
    source = BookSource.valueOf(source),
    remotePath = remotePath,
    totalDurationMs = totalDurationMs,
    status = BookStatus.valueOf(status),
    downloadState = DownloadState.valueOf(downloadState),
    addedAt = addedAt,
    lastListenedAt = lastListenedAt,
    metadataUpdatedAt = metadataUpdatedAt,
)

fun Book.toEntity(): BookEntity = BookEntity(
    id = id,
    title = title,
    author = author,
    narrator = narrator,
    series = series,
    seriesIndex = seriesIndex,
    coverPath = coverPath,
    source = source.name,
    remotePath = remotePath,
    totalDurationMs = totalDurationMs,
    status = status.name,
    downloadState = downloadState.name,
    addedAt = addedAt,
    lastListenedAt = lastListenedAt,
    metadataUpdatedAt = metadataUpdatedAt,
)

fun ChapterEntity.toDomain(): Chapter = Chapter(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    title = title,
    filePath = filePath,
    startMs = startMs,
    durationMs = durationMs,
)

fun Chapter.toEntity(): ChapterEntity = ChapterEntity(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    title = title,
    filePath = filePath,
    startMs = startMs,
    durationMs = durationMs,
)

fun ProgressEntity.toDomain(): Progress = Progress(
    bookId = bookId,
    chapterId = chapterId,
    positionMs = positionMs,
    totalListenedMs = totalListenedMs,
    finished = finished,
    updatedAt = updatedAt,
    deviceId = deviceId,
)

fun Progress.toEntity(): ProgressEntity = ProgressEntity(
    bookId = bookId,
    chapterId = chapterId,
    positionMs = positionMs,
    totalListenedMs = totalListenedMs,
    finished = finished,
    updatedAt = updatedAt,
    deviceId = deviceId,
)

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id,
    bookId = bookId,
    chapterId = chapterId,
    positionMs = positionMs,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted,
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    bookId = bookId,
    chapterId = chapterId,
    positionMs = positionMs,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted,
)
