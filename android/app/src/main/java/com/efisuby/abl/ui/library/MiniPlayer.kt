package com.efisuby.abl.ui.library

import android.content.ComponentName
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.efisuby.abl.R
import com.efisuby.abl.databinding.ActivityMainBinding
import com.efisuby.abl.playback.PlaybackIds
import com.efisuby.abl.playback.PlaybackService
import com.efisuby.abl.ui.common.loadCover
import com.efisuby.abl.ui.common.setCover
import com.efisuby.abl.ui.player.PlayerActivity
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch

/**
 * The bar at the bottom of the library: whatever [PlaybackService] holds right now, with play/pause
 * and a tap that opens the player. Connected between `onStart` and `onStop`; hidden while nothing is loaded.
 */
class MiniPlayer(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val onVisibilityChanged: () -> Unit,
) {

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var coverUri: String? = null

    val showing: Boolean get() = binding.miniPlayer.isVisible

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = render()
    }

    init {
        binding.miniPlayer.setOnClickListener { openPlayer() }
        binding.miniPlayPause.setOnClickListener {
            val player = controller ?: return@setOnClickListener
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0L)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        }
    }

    fun start() {
        val token = SessionToken(activity, ComponentName(activity, PlaybackService::class.java))
        val pending = MediaController.Builder(activity, token).buildAsync()
        future = pending
        pending.addListener({
            if (future !== pending || pending.isCancelled) return@addListener
            val connected = runCatching { pending.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(listener)
            render()
        }, MoreExecutors.directExecutor())
    }

    fun stop() {
        controller?.removeListener(listener)
        controller = null
        future?.let(MediaController::releaseFuture)
        future = null
        setShowing(false)
    }

    /** Called about once a second while the library is visible; the position moves without player events. */
    fun renderProgress() {
        val player = controller ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        binding.miniProgress.progress = (player.currentPosition.coerceIn(0L, duration) * PROGRESS_MAX / duration).toInt()
    }

    private fun render() {
        val player = controller
        if (player == null || player.mediaItemCount == 0) {
            setShowing(false)
            return
        }
        val metadata = player.mediaMetadata
        binding.miniTitle.text = metadata.albumTitle
        binding.miniChapter.text = metadata.title
        val playing = player.isPlaying
        binding.miniPlayPause.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.miniPlayPause.contentDescription = activity.getString(if (playing) R.string.player_pause else R.string.player_play)
        val artwork = metadata.artworkUri?.toString()
        if (artwork != coverUri) {
            coverUri = artwork
            val size = activity.resources.getDimensionPixelSize(R.dimen.mini_cover_size)
            activity.lifecycleScope.launch { binding.miniCover.setCover(loadCover(metadata.artworkUri?.path, size)) }
        }
        renderProgress()
        setShowing(true)
    }

    private fun setShowing(value: Boolean) {
        if (binding.miniPlayer.isVisible == value) return
        binding.miniPlayer.isVisible = value
        onVisibilityChanged()
    }

    private fun openPlayer() {
        val bookId = controller?.currentMediaItem?.mediaId?.let(PlaybackIds::parseChapter)?.first ?: return
        activity.startActivity(PlayerActivity.intent(activity, bookId))
    }

    private companion object {
        const val PROGRESS_MAX = 1000L
    }
}
