package com.efisuby.abl.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.domain.model.BookStatus
import com.efisuby.abl.ui.player.PlayerActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays books in the background: ExoPlayer behind a media session, so the notification, the
 * lock screen, headsets and the app's own screens all control the same player.
 *
 * Controllers ask for a book with a [PlaybackIds.request] media id; the service turns it into
 * one queue item per chapter (design D3) and restores the saved position.
 *
 * Media button preferences (±15 s in the notification) are an unstable Media3 API.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val scope: CoroutineScope = MainScope()
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var tracker: PositionTracker
    private lateinit var sleepTimer: SleepTimer

    /** Progress writes in order: a late position write must not undo "finished". */
    private val writes = Channel<ProgressWrite>(Channel.UNLIMITED)

    override fun onCreate() {
        super.onCreate()
        val container = appContainer
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, PlayerActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setMediaButtonPreferences(mediaButtons())
            .build()

        sleepTimer = SleepTimer(player, SystemClock::elapsedRealtime) { session?.setSessionExtras(sleepTimer.toExtras()) }
        tracker = PositionTracker(clock = SystemClock::elapsedRealtime, save = { writes.trySend(ProgressWrite.Position(it)) })
        player.addListener(PlayerEvents())

        container.applicationScope.launch {
            for (write in writes) {
                when (write) {
                    is ProgressWrite.Position -> with(write.snapshot) {
                        container.progressRepository.savePosition(bookId, chapterId, positionMs, totalListenedMs)
                    }
                    is ProgressWrite.Finished -> {
                        container.progressRepository.markFinished(write.bookId, true)
                        container.libraryRepository.setStatus(write.bookId, BookStatus.FINISHED)
                    }
                }
            }
        }

        scope.launch {
            container.settingsRepository.settings.map { it.playbackSpeed }.distinctUntilChanged().collect {
                player.setPlaybackSpeed(it)
            }
        }
        // A deleted book must not keep playing from files that are gone.
        scope.launch {
            container.libraryRepository.observeBooks().map { books -> books.mapTo(HashSet()) { it.id } }.collect { ids ->
                val bookId = current()?.first ?: return@collect
                if (bookId !in ids) {
                    player.stop()
                    player.clearMediaItems()
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                sleepTimer.tick()
                if (player.isPlaying) current()?.let { (book, chapter) -> tracker.onTick(book, chapter, player.currentPosition) }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        saveCurrentPosition()
        writes.close()
        scope.cancel()
        sleepTimer.release()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    /** Book and chapter of the current queue item. */
    private fun current(): Pair<String, String>? =
        player.currentMediaItem?.mediaId?.let(PlaybackIds::parseChapter)

    private fun saveCurrentPosition() {
        val (book, chapter) = current() ?: return
        tracker.saveNow(book, chapter, player.currentPosition)
    }

    /** −15 s and +15 s next to play/pause; chapter skips go to the overflow. */
    private fun mediaButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setDisplayName(getString(R.string.player_back_15))
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_15)
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setDisplayName(getString(R.string.player_forward_15))
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .setDisplayName(getString(R.string.player_previous_chapter))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setDisplayName(getString(R.string.player_next_chapter))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private suspend fun loadBook(request: PlaybackRequest): MediaItemsWithStartPosition {
        val bookId = request.bookId
        val container = appContainer
        val book = requireNotNull(container.libraryRepository.getBook(bookId)) { "No book $bookId" }
        val queue = ChapterQueue.build(bookId, container.libraryRepository.getChapters(bookId))
        require(queue.isNotEmpty()) { "Book $bookId has no chapters" }
        val progress = container.progressRepository.get(bookId)
        val start = ChapterQueue.startPoint(queue, progress, request.chapterId, request.positionMs)

        // The previous book keeps its position before the queue is replaced.
        saveCurrentPosition()
        tracker.startBook(bookId, progress?.totalListenedMs ?: 0L)
        if (book.status != BookStatus.IN_PROGRESS) {
            container.libraryRepository.setStatus(bookId, BookStatus.IN_PROGRESS)
        }
        return MediaItemsWithStartPosition(queue.map { it.toMediaItem(book) }, start.index, start.positionMs)
    }

    private fun resolve(block: suspend () -> MediaItemsWithStartPosition): ListenableFuture<MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaItemsWithStartPosition>()
        scope.launch {
            try {
                future.set(block())
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(SleepTimer.COMMAND, Bundle.EMPTY))
                        .build(),
                )
                .setSessionExtras(sleepTimer.toExtras())
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != SleepTimer.COMMAND) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            when (val minutes = args.getInt(SleepTimer.ARG_MINUTES, 0)) {
                0 -> sleepTimer.cancel()
                SleepTimer.END_OF_CHAPTER -> sleepTimer.start(SleepTimerMode.EndOfChapter)
                else -> sleepTimer.start(SleepTimerMode.Minutes(minutes))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val request = mediaItems.singleOrNull()?.mediaId?.let(PlaybackIds::parseRequest)
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Only book requests are played"))
            return resolve { loadBook(request) }
        }

        /** "Play" on a headset or the system media controls after the service was stopped. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = resolve {
            val last = appContainer.libraryRepository.observeBooks().first()
                .filter { it.lastListenedAt != null }
                .maxByOrNull { it.lastListenedAt ?: 0L }
                ?: throw IllegalStateException("Nothing was played yet")
            loadBook(PlaybackRequest(last.id))
        }
    }

    private inner class PlayerEvents : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            tracker.onPlayingChanged(isPlaying)
            if (!isPlaying) saveCurrentPosition()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // At a chapter end the new chapter is saved at its start.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) saveCurrentPosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            saveCurrentPosition()
            val bookId = current()?.first ?: return
            writes.trySend(ProgressWrite.Finished(bookId))
        }
    }

    private sealed interface ProgressWrite {
        data class Position(val snapshot: PositionSnapshot) : ProgressWrite
        data class Finished(val bookId: String) : ProgressWrite
    }

    private companion object {
        const val SEEK_INCREMENT_MS = 15_000L
        const val TICK_MS = 1_000L
    }
}
