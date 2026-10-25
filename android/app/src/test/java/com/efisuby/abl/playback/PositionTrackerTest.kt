package com.efisuby.abl.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionTrackerTest {

    private var now = 0L
    private val saved = mutableListOf<PositionSnapshot>()
    private val tracker = PositionTracker(clock = { now }, save = { saved += it })

    @Test
    fun `a process killed at any moment loses at most five seconds`() {
        tracker.startBook("book", totalListenedMs = 0)
        tracker.onPlayingChanged(true)

        // One tick per second, as the service does; the process may die after any of them.
        for (second in 0..120) {
            now = second * 1_000L
            tracker.onTick("book", "chapter", positionMs = now)
            val lastSaved = saved.last().positionMs
            assertTrue("at ${now}ms the last save was $lastSaved", now - lastSaved <= 5_000)
        }
        assertTrue("saves are throttled, got ${saved.size}", saved.size <= 26)
    }

    @Test
    fun `pause and chapter change save immediately`() {
        tracker.startBook("book", 0)
        tracker.onPlayingChanged(true)
        tracker.onTick("book", "c1", 0)

        now = 1_500
        tracker.onPlayingChanged(false)
        tracker.saveNow("book", "c1", 1_500)
        now = 1_700
        tracker.saveNow("book", "c2", 0)

        assertEquals(listOf("c1" to 0L, "c1" to 1_500L, "c2" to 0L), saved.map { it.chapterId to it.positionMs })
    }

    @Test
    fun `listening time counts only playback and continues the saved total`() {
        tracker.startBook("book", totalListenedMs = 10_000)
        tracker.onPlayingChanged(true)
        now = 4_000
        tracker.onPlayingChanged(false)
        now = 60_000 // paused for a minute
        tracker.onPlayingChanged(true)
        now = 61_000
        tracker.saveNow("book", "c1", 5_000)

        assertEquals(15_000L, saved.last().totalListenedMs)
    }

    @Test
    fun `positions of another book are ignored`() {
        tracker.startBook("book", 0)
        tracker.saveNow("previous", "c9", 9_000)

        assertTrue(saved.isEmpty())
    }

    @Test
    fun `nothing is saved before a book starts`() {
        tracker.onTick("book", "c1", 1_000)
        tracker.saveNow("book", "c1", 1_000)

        assertTrue(saved.isEmpty())
    }
}
