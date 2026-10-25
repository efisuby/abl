package com.efisuby.abl.domain.model

/**
 * Listening position of one book on one device. [updatedAt] is used to merge positions from
 * several devices: the newest record wins.
 */
data class Progress(
    val bookId: String,
    val chapterId: String?,
    val positionMs: Long,
    val totalListenedMs: Long = 0L,
    val finished: Boolean = false,
    val updatedAt: Long,
    val deviceId: String,
)
