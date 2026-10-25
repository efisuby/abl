package com.efisuby.abl.domain.model

/**
 * One playable unit.
 *
 * For a folder of mp3 files [filePath] is a separate file and [startMs] is 0. For an m4b all
 * chapters share [filePath] and are addressed by [startMs] inside it.
 */
data class Chapter(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val title: String,
    val filePath: String,
    val startMs: Long = 0L,
    val durationMs: Long = 0L,
)
