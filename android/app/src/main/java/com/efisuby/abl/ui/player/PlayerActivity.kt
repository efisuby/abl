package com.efisuby.abl.ui.player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.settings.AppSettings
import com.efisuby.abl.databinding.ActivityPlayerBinding
import com.efisuby.abl.databinding.DialogSpeedBinding
import com.efisuby.abl.playback.PlaybackIds
import com.efisuby.abl.playback.PlaybackService
import com.efisuby.abl.playback.SleepTimer
import com.efisuby.abl.ui.common.applySystemBarInsets
import com.efisuby.abl.ui.common.formatDuration
import com.efisuby.abl.ui.common.loadCover
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The player screen: a [MediaController] of [PlaybackService]. Started with a book id it plays
 * that book (from the picked chapter or the saved position); without one it shows whatever the
 * service is playing, which is how the notification opens it.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var draggingPosition = false
    private var coverUri: String? = null
    private val bookmarks = Bookmarks(this, lifecycleScope) { controller }

    /** The book this screen was opened for; cleared once handed to the player. */
    private var pendingRequest: String? = null

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Denial is fine: playback works, only the notification stays hidden (app-shell spec).
        startPendingRequest()
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        if (savedInstanceState?.getBoolean(STATE_REQUEST_HANDLED) != true) {
            intent.getStringExtra(EXTRA_BOOK_ID)?.let {
                pendingRequest = requestFrom(intent, it)
            }
        }

        binding.playPause.setOnClickListener { controller?.let(::togglePlayPause) }
        binding.back15.setOnClickListener { controller?.seekBack() }
        binding.forward15.setOnClickListener { controller?.seekForward() }
        binding.previousChapter.setOnClickListener { controller?.seekToPreviousMediaItem() }
        binding.nextChapter.setOnClickListener { controller?.seekToNextMediaItem() }
        binding.speed.setOnClickListener { showSpeedDialog() }
        binding.sleepTimer.setOnClickListener { showSleepTimerDialog() }
        binding.addBookmark.setOnClickListener { bookmarks.showAdd() }
        binding.bookmarks.setOnClickListener { bookmarks.showList() }
        binding.position.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                draggingPosition = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                draggingPosition = false
                controller?.seekTo(slider.value.toLong())
            }
        })
        binding.position.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.elapsed.text = formatDuration(value.toLong())
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    appContainer.settingsRepository.settings.map { it.playbackSpeed }.distinctUntilChanged().collect {
                        binding.speed.text = getString(R.string.player_speed, formatSpeed(it))
                    }
                }
                // Position moves without player events; refresh it while the screen is visible.
                while (true) {
                    renderPosition()
                    delay(POSITION_REFRESH_MS)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return
        pendingRequest = requestFrom(intent, bookId)
        if (controller != null) requestNotificationsThenStart()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future || future.isCancelled) return@addListener
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(listener)
            if (pendingRequest != null) {
                requestNotificationsThenStart()
            } else if (connected.mediaItemCount == 0) {
                finish() // opened from a stale notification after the service stopped
                return@addListener
            }
            render()
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        // Leaving the player: publish the position (a rotation is not leaving).
        if (!isChangingConfigurations) appContainer.syncScheduler.request()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_REQUEST_HANDLED, pendingRequest == null)
    }

    private fun requestNotificationsThenStart() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startPendingRequest()
        }
    }

    private fun startPendingRequest() {
        val player = controller ?: return
        val request = pendingRequest ?: return
        pendingRequest = null
        val parsed = PlaybackIds.parseRequest(request) ?: return
        val playingBook = player.currentMediaItem?.mediaId?.let(PlaybackIds::parseChapter)?.first
        // Reopening the book that is already loaded keeps its position and play state.
        if (parsed.chapterId == null && playingBook == parsed.bookId) return
        player.setMediaItems(listOf(MediaItem.Builder().setMediaId(request).build()))
        player.prepare()
        player.play()
    }

    private fun togglePlayPause(player: Player) {
        if (player.isPlaying) {
            player.pause()
            return
        }
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0L)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    private fun render() {
        val player = controller ?: return
        val metadata = player.mediaMetadata
        binding.bookTitle.text = metadata.albumTitle
        binding.chapterTitle.text = metadata.title
        binding.chapterNumber.text = if (player.mediaItemCount > 0) {
            getString(R.string.player_chapter_of, player.currentMediaItemIndex + 1, player.mediaItemCount)
        } else {
            null
        }
        val playing = player.isPlaying
        binding.playPause.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPause.contentDescription = getString(if (playing) R.string.player_pause else R.string.player_play)
        binding.previousChapter.isEnabled = player.hasPreviousMediaItem()
        binding.nextChapter.isEnabled = player.hasNextMediaItem()

        val artwork = metadata.artworkUri?.toString()
        if (artwork != coverUri) {
            coverUri = artwork
            val size = resources.displayMetrics.widthPixels
            lifecycleScope.launch { binding.cover.setImageBitmap(loadCover(metadata.artworkUri?.path, size)) }
        }
        renderPosition()
    }

    private fun renderPosition() {
        val player = controller ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val position = player.currentPosition.coerceAtLeast(0L)
        renderSleepTimer(player)
        binding.position.isEnabled = duration != null
        if (duration == null) {
            binding.elapsed.text = formatDuration(position)
            binding.remaining.text = null
            return
        }
        if (!draggingPosition) {
            binding.position.valueTo = duration.toFloat()
            binding.position.value = position.coerceAtMost(duration).toFloat()
            binding.elapsed.text = formatDuration(position)
        }
        binding.remaining.text = getString(R.string.player_remaining, formatDuration(duration - position))
    }

    private fun renderSleepTimer(player: Player) {
        val extras = (player as? MediaController)?.sessionExtras ?: return
        val endsAt = extras.getLong(SleepTimer.EXTRA_ENDS_AT, SleepTimer.NOT_SET)
        binding.sleepTimer.text = when {
            extras.getBoolean(SleepTimer.EXTRA_END_OF_CHAPTER) -> getString(R.string.sleep_timer_end_of_chapter)
            endsAt != SleepTimer.NOT_SET -> getString(
                R.string.sleep_timer_remaining,
                formatDuration((endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)),
            )
            else -> getString(R.string.sleep_timer)
        }
    }

    private fun showSleepTimerDialog() {
        val player = controller ?: return
        val active = player.sessionExtras.getLong(SleepTimer.EXTRA_ENDS_AT, SleepTimer.NOT_SET) != SleepTimer.NOT_SET ||
            player.sessionExtras.getBoolean(SleepTimer.EXTRA_END_OF_CHAPTER)
        val choices = SleepTimer.PRESET_MINUTES + SleepTimer.END_OF_CHAPTER + if (active) listOf(0) else emptyList()
        val labels = choices.map {
            when (it) {
                SleepTimer.END_OF_CHAPTER -> getString(R.string.sleep_timer_end_of_chapter)
                0 -> getString(R.string.sleep_timer_off)
                else -> resources.getQuantityString(R.plurals.sleep_timer_minutes, it, it)
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sleep_timer_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val args = Bundle().apply { putInt(SleepTimer.ARG_MINUTES, choices[which]) }
                controller?.sendCustomCommand(SessionCommand(SleepTimer.COMMAND, Bundle.EMPTY), args)
            }
            .show()
    }

    private fun showSpeedDialog() {
        val dialog = DialogSpeedBinding.inflate(layoutInflater)
        val settings = appContainer.settingsRepository
        dialog.slider.addOnChangeListener { _, value, fromUser ->
            dialog.value.text = getString(R.string.player_speed, formatSpeed(value))
            if (fromUser) appContainer.applicationScope.launch { settings.setPlaybackSpeed(value) }
        }
        lifecycleScope.launch {
            val current = AppSettings.normalizeSpeed(settings.settings.first().playbackSpeed)
            dialog.slider.value = current
            dialog.value.text = getString(R.string.player_speed, formatSpeed(current))
            MaterialAlertDialogBuilder(this@PlayerActivity)
                .setTitle(R.string.player_speed_title)
                .setView(dialog.root)
                .setPositiveButton(R.string.player_speed_done, null)
                .show()
        }
    }

    private fun formatSpeed(speed: Float): String = String.format(Locale.getDefault(), "%.1f", speed)

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_CHAPTER_ID = "chapterId"
        private const val EXTRA_POSITION_MS = "positionMs"
        private const val STATE_REQUEST_HANDLED = "requestHandled"
        private const val POSITION_REFRESH_MS = 500L

        /** Plays [bookId] from [chapterId], or from the saved position when it is null. */
        /** [positionMs] inside [chapterId] is for bookmarks; without a chapter the saved position is used. */
        fun intent(context: Context, bookId: String, chapterId: String? = null, positionMs: Long = 0L): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_CHAPTER_ID, chapterId)
                .putExtra(EXTRA_POSITION_MS, positionMs)

        private fun requestFrom(intent: Intent, bookId: String): String = PlaybackIds.request(
            bookId,
            intent.getStringExtra(EXTRA_CHAPTER_ID),
            intent.getLongExtra(EXTRA_POSITION_MS, 0L),
        )
    }
}
