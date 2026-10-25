package com.efisuby.abl.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** What the user picked in the sleep timer menu. */
sealed interface SleepTimerMode {
    data class Minutes(val minutes: Int) : SleepTimerMode
    data object EndOfChapter : SleepTimerMode
}

/**
 * Pauses [player] when the sleep timer runs out (playback spec, "Sleep timer"). A countdown is
 * checked by [tick], which the service calls about once a second; "until end of chapter" uses
 * the player's own pause at the end of the current item, so it never advances to the next one.
 *
 * Call from the player's thread. [clock] is elapsed real time, faked in tests.
 * `pauseAtEndOfMediaItems` is an unstable Media3 API.
 */
@OptIn(UnstableApi::class)
class SleepTimer(
    private val player: ExoPlayer,
    private val clock: () -> Long,
    private val onChanged: () -> Unit = {},
) {
    /** Elapsed time at which the countdown ends, or null. */
    var endsAt: Long? = null
        private set

    val untilEndOfChapter: Boolean get() = player.pauseAtEndOfMediaItems

    val isActive: Boolean get() = endsAt != null || untilEndOfChapter

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && untilEndOfChapter) {
                // Fired once at the boundary; later chapters must not pause by themselves.
                cancel()
            }
        }
    }

    init {
        player.addListener(listener)
    }

    fun start(mode: SleepTimerMode) {
        when (mode) {
            is SleepTimerMode.Minutes -> {
                player.pauseAtEndOfMediaItems = false
                endsAt = clock() + mode.minutes * 60_000L
            }
            SleepTimerMode.EndOfChapter -> {
                endsAt = null
                player.pauseAtEndOfMediaItems = true
            }
        }
        onChanged()
    }

    fun cancel() {
        val wasActive = isActive
        endsAt = null
        player.pauseAtEndOfMediaItems = false
        if (wasActive) onChanged()
    }

    fun tick() {
        val end = endsAt ?: return
        if (clock() >= end) {
            player.pause()
            cancel()
        }
    }

    fun release() {
        player.removeListener(listener)
    }

    /** The state for controllers, published in the session extras. */
    fun toExtras(): Bundle = Bundle().apply {
        putLong(EXTRA_ENDS_AT, endsAt ?: NOT_SET)
        putBoolean(EXTRA_END_OF_CHAPTER, untilEndOfChapter)
    }

    companion object {
        const val COMMAND = "com.efisuby.abl.SLEEP_TIMER"
        /** Minutes as an int; 0 cancels; [END_OF_CHAPTER] stops at the chapter end. */
        const val ARG_MINUTES = "minutes"
        const val END_OF_CHAPTER = -1
        const val EXTRA_ENDS_AT = "sleepEndsAt"
        const val EXTRA_END_OF_CHAPTER = "sleepEndOfChapter"
        const val NOT_SET = -1L

        val PRESET_MINUTES = listOf(5, 10, 15, 30, 45, 60)
    }
}
