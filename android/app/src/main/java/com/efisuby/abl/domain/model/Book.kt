package com.efisuby.abl.domain.model

/** Where the audio for a book came from. */
enum class BookSource {
    LOCAL,
    YANDEX_DISK,
}

enum class BookStatus {
    NOT_STARTED,
    IN_PROGRESS,
    FINISHED,
}

/** Availability of the audio files on this device. */
enum class DownloadState {
    /** Only in the cloud; playback is not possible yet. */
    IN_CLOUD,
    DOWNLOADING,
    /** Some files are present, the rest are not. */
    PARTIAL,
    DOWNLOADED,
}

/**
 * A book is an ordered list of [Chapter]s. The audio format (a folder of mp3 files or a single
 * m4b) is an implementation detail of the chapters and never reaches the UI or the player.
 */
data class Book(
    val id: String,
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    val series: String? = null,
    val seriesIndex: Int? = null,
    val coverPath: String? = null,
    val source: BookSource,
    /** Path in the app folder on Yandex Disk; null for locally imported books. */
    val remotePath: String? = null,
    val totalDurationMs: Long = 0L,
    val status: BookStatus = BookStatus.NOT_STARTED,
    val downloadState: DownloadState = DownloadState.DOWNLOADED,
    val addedAt: Long,
    val lastListenedAt: Long? = null,
    val metadataUpdatedAt: Long = 0L,
)
