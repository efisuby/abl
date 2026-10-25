package com.efisuby.abl.playback

/** A position to persist: chapter, offset inside it and the listening time accumulated so far. */
data class PositionSnapshot(
    val bookId: String,
    val chapterId: String,
    val positionMs: Long,
    val totalListenedMs: Long,
)

/**
 * Decides when the listening position is written (playback spec, "Position saving"): at least
 * every [intervalMs] of playback through [onTick], and immediately on pause, at a chapter
 * change and when the service stops through [saveNow]. A killed process therefore loses at
 * most [intervalMs] of position.
 *
 * Not thread-safe: call it from the player's thread.
 */
class PositionTracker(
    private val clock: () -> Long,
    private val save: (PositionSnapshot) -> Unit,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private var lastSavedAt = Long.MIN_VALUE
    private var playingSince: Long? = null
    private var listenedMs = 0L
    private var bookId: String? = null

    /** Starts a book; [totalListenedMs] continues the listening time saved earlier. */
    fun startBook(bookId: String, totalListenedMs: Long) {
        this.bookId = bookId
        listenedMs = totalListenedMs
        playingSince = null
        lastSavedAt = Long.MIN_VALUE
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        val now = clock()
        if (isPlaying) {
            if (playingSince == null) playingSince = now
        } else {
            accumulate(now)
            playingSince = null
        }
    }

    /** Called about once a second while playing; saves when [intervalMs] has passed. */
    fun onTick(bookId: String, chapterId: String, positionMs: Long) {
        val now = clock()
        if (lastSavedAt == Long.MIN_VALUE || now - lastSavedAt >= intervalMs) saveNow(bookId, chapterId, positionMs)
    }

    /**
     * Saves [positionMs] in [chapterId]. Ignored for any book but the started one: while the
     * player swaps queues it may still report a chapter of the previous book.
     */
    fun saveNow(bookId: String, chapterId: String, positionMs: Long) {
        if (bookId != this.bookId) return
        val now = clock()
        accumulate(now)
        if (playingSince != null) playingSince = now
        lastSavedAt = now
        save(PositionSnapshot(bookId, chapterId, positionMs.coerceAtLeast(0L), listenedMs))
    }

    private fun accumulate(now: Long) {
        playingSince?.let { listenedMs += (now - it).coerceAtLeast(0L) }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
