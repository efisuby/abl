package com.efisuby.abl.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.efisuby.abl.data.importer.FixtureDocumentsProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** [SleepTimer] on a real player; the countdown runs on a fake clock instead of minutes of audio. */
@RunWith(AndroidJUnit4::class)
class SleepTimerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var fixtures: File
    private lateinit var player: ExoPlayer
    private lateinit var timer: SleepTimer
    private var now = 1_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixtures = File(context.cacheDir, "fixtures")
        FixtureDocumentsProvider.install(instrumentation.context, context, fixtures)
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build()
            timer = SleepTimer(player, clock = { now })
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            timer.release()
            player.release()
        }
    }

    @Test
    fun countdownPausesPlaybackWhenItExpires() {
        play("long/01.mp3")
        onMain { timer.start(SleepTimerMode.Minutes(5)) }

        now += 4 * 60_000L + 59_000L
        onMain { timer.tick() }
        assertTrue("still playing one second before the end", onMain { player.playWhenReady })

        now += 1_000L
        onMain { timer.tick() }
        assertFalse("paused when the timer expired", onMain { player.playWhenReady })
        assertFalse(onMain { timer.isActive })
    }

    @Test
    fun cancelledTimerDoesNotPause() {
        play("long/01.mp3")
        onMain {
            timer.start(SleepTimerMode.Minutes(5))
            timer.cancel()
        }
        now += 10 * 60_000L
        onMain { timer.tick() }

        assertTrue(onMain { player.playWhenReady })
    }

    @Test
    fun endOfChapterStopsAtTheBoundaryWithoutAdvancing() {
        play("tagged/01.mp3", "tagged/02.mp3", "tagged/03.mp3")
        onMain { timer.start(SleepTimerMode.EndOfChapter) }

        val deadline = System.currentTimeMillis() + 10_000
        while (onMain { player.playWhenReady } && System.currentTimeMillis() < deadline) Thread.sleep(100)

        assertFalse("paused at the end of chapter 1", onMain { player.playWhenReady })
        assertEquals("did not advance", 0, onMain { player.currentMediaItemIndex })
        assertFalse("the timer is used up", onMain { timer.isActive })
    }

    private fun play(vararg paths: String) = onMain {
        player.setMediaItems(paths.map { MediaItem.fromUri(Uri.fromFile(File(fixtures, it))) })
        player.prepare()
        player.play()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
