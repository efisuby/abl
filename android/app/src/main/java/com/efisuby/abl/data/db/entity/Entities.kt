package com.efisuby.abl.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [Index("title"), Index("series")],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val narrator: String?,
    val series: String?,
    val seriesIndex: Int?,
    val coverPath: String?,
    val source: String,
    val remotePath: String?,
    val totalDurationMs: Long,
    val status: String,
    val downloadState: String,
    val addedAt: Long,
    val lastListenedAt: Long?,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bookId", "chapterIndex"], unique = true)],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val title: String,
    val filePath: String,
    val startMs: Long,
    val durationMs: Long,
)

@Entity(
    tableName = "progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProgressEntity(
    @PrimaryKey val bookId: String,
    val chapterId: String?,
    val positionMs: Long,
    val totalListenedMs: Long,
    val finished: Boolean,
    val updatedAt: Long,
    val deviceId: String,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterId: String,
    val positionMs: Long,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
)
