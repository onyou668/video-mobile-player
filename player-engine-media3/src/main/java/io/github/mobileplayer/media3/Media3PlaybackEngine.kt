package io.github.mobileplayer.media3

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.util.UnstableApi
import io.github.mobileplayer.core.EnginePlaybackState
import io.github.mobileplayer.core.PlaybackEngine
import io.github.mobileplayer.core.PlaybackEngineFactory
import io.github.mobileplayer.core.PlaybackError
import io.github.mobileplayer.core.PlaybackStatus
import io.github.mobileplayer.core.VideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class Media3PlaybackEngine(context: Context) : PlaybackEngine {
    private val appContext = context.applicationContext
    private val player = ExoPlayer.Builder(appContext).build()
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(EnginePlaybackState())
    private val networkSpeedMeter = NetworkSpeedMeter()
    private var released = false

    override val state: StateFlow<EnginePlaybackState> = mutableState.asStateFlow()

    private val progressTicker = object : Runnable {
        override fun run() {
            if (released) return
            publishState()
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = publishState()

        override fun onIsPlayingChanged(isPlaying: Boolean) = publishState()

        override fun onPlayerError(error: PlaybackException) {
            mutableState.value = snapshot().copy(
                status = PlaybackStatus.ERROR,
                isPlaying = false,
                error = PlaybackError(error.message ?: "Media3 playback failed", error),
            )
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = publishState()
    }

    init {
        player.addListener(listener)
        handler.post(progressTicker)
    }

    override fun setSurface(surface: Surface?) {
        if (released) return
        player.setVideoSurface(surface)
    }

    override fun prepare(source: VideoSource, playWhenReady: Boolean, startPositionMs: Long) {
        if (released) return
        mutableState.value = EnginePlaybackState(
            status = PlaybackStatus.PREPARING,
            positionMs = startPositionMs,
        )
        networkSpeedMeter.reset()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(source.headers)
        val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
            .setTransferListener(networkSpeedMeter)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val mediaItem = MediaItem.fromUri(Uri.parse(source.uri))

        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem), startPositionMs)
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    override fun play() {
        if (released) return
        player.play()
    }

    override fun pause() {
        if (released) return
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        player.seekTo(positionMs.coerceAtLeast(0L))
        publishState()
    }

    override fun setVolume(volume: Float) {
        if (released) return
        player.volume = volume.coerceIn(0f, 1f)
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (released) return
        player.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 3f))
    }

    override fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        player.removeListener(listener)
        player.clearVideoSurface()
        player.release()
    }

    private fun publishState() {
        if (released || mutableState.value.status == PlaybackStatus.ERROR) return
        mutableState.value = snapshot()
    }

    private fun snapshot(): EnginePlaybackState {
        val playbackStatus = when (player.playbackState) {
            Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
            Player.STATE_READY -> PlaybackStatus.READY
            Player.STATE_ENDED -> PlaybackStatus.ENDED
            else -> if (player.currentMediaItem == null) PlaybackStatus.IDLE else PlaybackStatus.PREPARING
        }
        val videoSize = player.videoSize
        return EnginePlaybackState(
            status = playbackStatus,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.validTime(),
            bufferedPositionMs = player.bufferedPosition.validTime(),
            networkSpeedBytesPerSecond = networkSpeedMeter.bytesPerSecond(),
            videoWidth = videoSize.width,
            videoHeight = videoSize.height,
        )
    }

    private fun Long.validTime(): Long = if (this == C.TIME_UNSET || this < 0L) 0L else this

    companion object {
        private const val PROGRESS_INTERVAL_MS = 250L
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class NetworkSpeedMeter : TransferListener {
    private var windowStartedAtMs = 0L
    private var bytesInWindow = 0L
    private var lastTransferAtMs = 0L
    private var measuredBytesPerSecond = 0L
    private var activeTransfers = 0

    @Synchronized
    fun reset() {
        windowStartedAtMs = 0L
        bytesInWindow = 0L
        lastTransferAtMs = 0L
        measuredBytesPerSecond = 0L
        activeTransfers = 0
    }

    @Synchronized
    fun bytesPerSecond(): Long {
        val now = SystemClock.elapsedRealtime()
        if (lastTransferAtMs == 0L || now - lastTransferAtMs > STALE_AFTER_MS) return 0L
        val elapsedMs = now - windowStartedAtMs
        return if (bytesInWindow > 0L && elapsedMs > 0L) {
            bytesInWindow * 1_000L / elapsedMs
        } else {
            measuredBytesPerSecond
        }
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    @Synchronized
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val now = SystemClock.elapsedRealtime()
        if (activeTransfers == 0) {
            windowStartedAtMs = now
            bytesInWindow = 0L
        }
        activeTransfers++
        lastTransferAtMs = now
    }

    @Synchronized
    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        if (!isNetwork || bytesTransferred <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (windowStartedAtMs == 0L) windowStartedAtMs = now
        bytesInWindow += bytesTransferred
        lastTransferAtMs = now
        val elapsedMs = now - windowStartedAtMs
        if (elapsedMs >= SAMPLE_WINDOW_MS) {
            measuredBytesPerSecond = bytesInWindow * 1_000L / elapsedMs
            windowStartedAtMs = now
            bytesInWindow = 0L
        }
    }

    @Synchronized
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - windowStartedAtMs
        if (bytesInWindow > 0L && elapsedMs > 0L) {
            measuredBytesPerSecond = bytesInWindow * 1_000L / elapsedMs
        }
        activeTransfers = (activeTransfers - 1).coerceAtLeast(0)
        if (activeTransfers == 0) {
            windowStartedAtMs = 0L
            bytesInWindow = 0L
        }
        lastTransferAtMs = now
    }

    private companion object {
        const val SAMPLE_WINDOW_MS = 500L
        const val STALE_AFTER_MS = 1_500L
    }
}

class Media3PlaybackEngineFactory(
    context: Context,
) : PlaybackEngineFactory {
    private val appContext = context.applicationContext

    override fun create(): PlaybackEngine = Media3PlaybackEngine(appContext)
}
