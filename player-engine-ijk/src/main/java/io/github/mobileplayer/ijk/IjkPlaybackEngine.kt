package io.github.mobileplayer.ijk

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import io.github.mobileplayer.core.EnginePlaybackState
import io.github.mobileplayer.core.PlaybackEngine
import io.github.mobileplayer.core.PlaybackEngineFactory
import io.github.mobileplayer.core.PlaybackError
import io.github.mobileplayer.core.PlaybackStatus
import io.github.mobileplayer.core.VideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.IMediaPlayer

class IjkPlaybackEngine(context: Context) : PlaybackEngine {
    private val appContext = context.applicationContext
    private val player = IjkMediaPlayer()
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(EnginePlaybackState())

    private var surface: Surface? = null
    private var prepared = false
    private var buffering = false
    private var playWhenReady = false
    private var pendingSeekMs = 0L
    private var bufferedPercent = 0
    private var volume = 1f
    private var speed = 1f
    private var isNetworkSource = false
    private var released = false

    override val state: StateFlow<EnginePlaybackState> = mutableState.asStateFlow()

    private val progressTicker = object : Runnable {
        override fun run() {
            if (released) return
            publishState()
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    init {
        configurePlayer()
        installListeners()
        handler.post(progressTicker)
    }

    override fun setSurface(surface: Surface?) {
        if (released) return
        this.surface = surface
        player.setSurface(surface)
    }

    override fun prepare(source: VideoSource, playWhenReady: Boolean, startPositionMs: Long) {
        if (released) return
        this.playWhenReady = playWhenReady
        isNetworkSource = source.type == VideoSource.Type.NETWORK
        pendingSeekMs = startPositionMs.coerceAtLeast(0L)
        prepared = false
        buffering = false
        bufferedPercent = 0
        mutableState.value = EnginePlaybackState(
            status = PlaybackStatus.PREPARING,
            positionMs = pendingSeekMs,
        )

        try {
            player.reset()
            configurePlayer()
            installListeners()
            player.setSurface(surface)
            player.setDataSource(appContext, Uri.parse(source.uri), source.headers)
            player.prepareAsync()
        } catch (error: Exception) {
            publishError("Unable to prepare IjkPlayer source", error)
        }
    }

    override fun play() {
        if (released) return
        playWhenReady = true
        if (prepared) {
            runCatching { player.start() }
                .onSuccess { publishState() }
                .onFailure { publishError("IjkPlayer could not start", it) }
        }
    }

    override fun pause() {
        if (released) return
        playWhenReady = false
        if (prepared) {
            runCatching { player.pause() }
                .onSuccess { publishState() }
                .onFailure { publishError("IjkPlayer could not pause", it) }
        }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        pendingSeekMs = positionMs.coerceAtLeast(0L)
        if (prepared) {
            runCatching { player.seekTo(pendingSeekMs) }
                .onSuccess { publishState(positionOverride = pendingSeekMs) }
                .onFailure { publishError("IjkPlayer could not seek", it) }
        } else {
            mutableState.value = mutableState.value.copy(positionMs = pendingSeekMs)
        }
    }

    override fun setVolume(volume: Float) {
        if (released) return
        this.volume = volume.coerceIn(0f, 1f)
        player.setVolume(this.volume, this.volume)
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (released) return
        this.speed = speed.coerceIn(0.25f, 3f)
        runCatching { player.setSpeed(this.speed) }
    }

    override fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        player.setSurface(null)
        player.release()
        surface = null
    }

    private fun configurePlayer() {
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L)
        player.setVolume(volume, volume)
    }

    private fun installListeners() {
        player.setOnPreparedListener {
            prepared = true
            buffering = false
            player.setVolume(volume, volume)
            player.setSpeed(speed)
            if (pendingSeekMs > 0L) player.seekTo(pendingSeekMs)
            if (playWhenReady) player.start()
            publishState()
        }
        player.setOnCompletionListener {
            playWhenReady = false
            mutableState.value = snapshot().copy(
                status = PlaybackStatus.ENDED,
                isPlaying = false,
            )
        }
        player.setOnBufferingUpdateListener { _, percent ->
            bufferedPercent = percent.coerceIn(0, 100)
            publishState()
        }
        player.setOnSeekCompleteListener { publishState() }
        player.setOnVideoSizeChangedListener { _, _, _, _, _ -> publishState() }
        player.setOnInfoListener { _, what, _ ->
            when (what) {
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> buffering = true
                IMediaPlayer.MEDIA_INFO_BUFFERING_END,
                IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START,
                -> buffering = false
            }
            publishState()
            false
        }
        player.setOnErrorListener { _, what, extra ->
            prepared = false
            playWhenReady = false
            publishError("IjkPlayer error (what=$what, extra=$extra)")
            true
        }
    }

    private fun publishState(positionOverride: Long? = null) {
        if (released || mutableState.value.status == PlaybackStatus.ERROR) return
        mutableState.value = snapshot(positionOverride)
    }

    private fun snapshot(positionOverride: Long? = null): EnginePlaybackState {
        val duration = if (prepared) runCatching { player.duration }.getOrDefault(0L) else 0L
        val position = positionOverride ?: if (prepared) {
            runCatching { player.currentPosition }.getOrDefault(pendingSeekMs)
        } else {
            pendingSeekMs
        }
        val isPlaying = prepared && runCatching { player.isPlaying }.getOrDefault(false)
        val status = when {
            !prepared -> PlaybackStatus.PREPARING
            buffering -> PlaybackStatus.BUFFERING
            else -> PlaybackStatus.READY
        }
        return EnginePlaybackState(
            status = status,
            isPlaying = isPlaying,
            positionMs = position.coerceAtLeast(0L),
            durationMs = duration.coerceAtLeast(0L),
            bufferedPositionMs = if (duration > 0L) duration * bufferedPercent / 100L else 0L,
            networkSpeedBytesPerSecond = if (isNetworkSource) {
                runCatching { player.tcpSpeed }.getOrDefault(0L).coerceAtLeast(0L)
            } else {
                0L
            },
            videoWidth = if (prepared) player.videoWidth else 0,
            videoHeight = if (prepared) player.videoHeight else 0,
        )
    }

    private fun publishError(message: String, cause: Throwable? = null) {
        buffering = false
        mutableState.value = mutableState.value.copy(
            status = PlaybackStatus.ERROR,
            isPlaying = false,
            error = PlaybackError(message, cause),
        )
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 250L
    }
}

class IjkPlaybackEngineFactory(
    context: Context,
) : PlaybackEngineFactory {
    private val appContext = context.applicationContext

    override fun create(): PlaybackEngine = IjkPlaybackEngine(appContext)
}
