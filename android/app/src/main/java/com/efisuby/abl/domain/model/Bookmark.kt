package com.efisuby.abl.domain.model

/**
 * A saved position with an optional note. [deleted] is a tombstone: bookmarks are merged by
 * union across devices, so a deletion must travel as a record as well.
 */
data class Bookmark(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val positionMs: Long,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)
