package io.github.mobileplayer.ijk

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import io.github.mobileplayer.core.EnginePlaybackState
import io.github.mobileplayer.core.MediaInfo
import io.github.mobileplayer.core.MediaProtocol
import io.github.mobileplayer.core.PlaybackEndReason
import io.github.mobileplayer.core.PlaybackEngine
import io.github.mobileplayer.core.PlaybackEngineProvider
import io.github.mobileplayer.core.PlaybackError
import io.github.mobileplayer.core.PlaybackPhase
import io.github.mobileplayer.core.PlaybackType
import io.github.mobileplayer.core.PlayerCapabilities
import io.github.mobileplayer.core.PlayerEngineId
import io.github.mobileplayer.core.PlayerEngineInfo
import io.github.mobileplayer.core.PlayerErrorCategory
import io.github.mobileplayer.core.PlayerNetworkState
import io.github.mobileplayer.core.PlayerTimeline
import io.github.mobileplayer.core.RetryPolicy
import io.github.mobileplayer.core.VideoInfo
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
    private val mutableState = MutableStateFlow(EnginePlaybackState(capabilities = capabilities()))
    private var surface: Surface? = null
    private var prepared = false
    private var buffering = false
    private var playWhenReady = false
    private var previewFramePending = false
    private var hasRenderedFirstFrame = false
    private var pendingSeekMs = 0L
    private var bufferedPercent = 0
    private var volume = 1f
    private var speed = 1f
    private var isNetworkSource = false
    private var protocol = MediaProtocol.UNKNOWN
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

    override fun prepare(source: VideoSource, startPositionMs: Long) {
        if (released) return
        playWhenReady = false
        isNetworkSource = source.type == VideoSource.Type.NETWORK
        protocol = source.protocolFromUri()
        pendingSeekMs = startPositionMs.coerceAtLeast(0L)
        prepared = false
        previewFramePending = false
        hasRenderedFirstFrame = false
        buffering = false
        bufferedPercent = 0
        mutableState.value = EnginePlaybackState(
            phase = PlaybackPhase.PREPARING,
            timeline = PlayerTimeline(positionMs = pendingSeekMs),
            media = MediaInfo(protocol = protocol),
            network = PlayerNetworkState(isLoading = isNetworkSource),
            capabilities = capabilities(),
        )
        try {
            player.reset()
            configurePlayer()
            installListeners()
            player.setSurface(surface)
            player.setDataSource(appContext, Uri.parse(source.uri), source.headers)
            player.prepareAsync()
        } catch (error: Exception) {
            publishError("ijk_prepare", "Unable to prepare IjkPlayer source", error)
        }
    }

    override fun play() {
        if (released) return
        playWhenReady = true
        previewFramePending = false
        if (prepared) {
            runCatching { player.start() }
                .onSuccess { publishState() }
                .onFailure { publishError("ijk_start", "IjkPlayer could not start", it) }
        }
    }

    override fun pause() {
        if (released) return
        playWhenReady = false
        if (prepared) {
            runCatching { player.pause() }
                .onSuccess { publishState() }
                .onFailure { publishError("ijk_pause", "IjkPlayer could not pause", it) }
        }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        pendingSeekMs = positionMs.coerceAtLeast(0L)
        if (prepared) {
            runCatching { player.seekTo(pendingSeekMs) }
                .onSuccess { publishState(pendingSeekMs) }
                .onFailure { publishError("ijk_seek", "IjkPlayer could not seek", it) }
        } else {
            mutableState.value = mutableState.value.copy(
                timeline = mutableState.value.timeline.copy(positionMs = pendingSeekMs),
            )
        }
    }

    override fun setPlayerVolume(volume: Float) {
        if (released) return
        this.volume = volume.coerceIn(0f, 1f)
        player.setVolume(this.volume, this.volume)
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (released) return
        this.speed = speed.coerceIn(0.25f, 5f)
        runCatching { player.setSpeed(this.speed) }
    }

    override fun goToLiveEdge() {
        val duration = runCatching { player.duration }.getOrDefault(0L)
        if (prepared && duration > 0L) player.seekTo(duration)
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
            if (playWhenReady) {
                player.start()
            } else {
                // Ijk does not render a paused first frame until decoding starts.
                // Start only until the first frame is rendered, then pause again.
                previewFramePending = true
                buffering = true
                player.start()
            }
            publishState()
        }
        player.setOnCompletionListener {
            playWhenReady = false
            val playbackType = playbackType()
            mutableState.value = snapshot().copy(
                phase = PlaybackPhase.ENDED,
                isPlaying = false,
                endReason = if (playbackType == PlaybackType.LIVE) {
                    PlaybackEndReason.LIVE_STREAM_ENDED
                } else PlaybackEndReason.VOD_COMPLETED,
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
                IMediaPlayer.MEDIA_INFO_BUFFERING_END -> buffering = false
                IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                    buffering = false
                    hasRenderedFirstFrame = true
                    if (previewFramePending && !playWhenReady) {
                        previewFramePending = false
                        player.pause()
                        if (player.currentPosition != pendingSeekMs) player.seekTo(pendingSeekMs)
                    }
                }
            }
            publishState()
            false
        }
        player.setOnErrorListener { _, what, extra ->
            prepared = false
            playWhenReady = false
            val unsupported = what == IMediaPlayer.MEDIA_ERROR_UNSUPPORTED ||
                what == IMediaPlayer.MEDIA_ERROR_MALFORMED
            publishError(
                code = "ijk_${what}_$extra",
                message = "IjkPlayer error (what=$what, extra=$extra)",
                category = if (unsupported) PlayerErrorCategory.UNSUPPORTED else PlayerErrorCategory.NETWORK,
                retryPolicy = if (unsupported) RetryPolicy.NEVER else RetryPolicy.BACKOFF,
            )
            true
        }
    }

    private fun publishState(positionOverride: Long? = null) {
        if (released || mutableState.value.phase == PlaybackPhase.ERROR) return
        mutableState.value = snapshot(positionOverride)
    }

    private fun snapshot(positionOverride: Long? = null): EnginePlaybackState {
        val duration = if (prepared) runCatching { player.duration }.getOrDefault(0L) else 0L
        val position = positionOverride ?: if (prepared) {
            runCatching { player.currentPosition }.getOrDefault(pendingSeekMs)
        } else pendingSeekMs
        val isPlaying = prepared && runCatching { player.isPlaying }.getOrDefault(false)
        val phase = when {
            !prepared -> PlaybackPhase.PREPARING
            buffering -> PlaybackPhase.BUFFERING
            else -> PlaybackPhase.READY
        }
        val playbackType = playbackType(duration)
        val canSeek = prepared && duration > 0L
        return EnginePlaybackState(
            phase = phase,
            isPlaying = isPlaying,
            hasRenderedFirstFrame = hasRenderedFirstFrame,
            timeline = PlayerTimeline(
                positionMs = position.coerceAtLeast(0L),
                durationMs = duration.takeIf { it > 0L },
                bufferedPositionMs = if (duration > 0L) duration * bufferedPercent / 100L else 0L,
                seekableStartMs = if (canSeek) 0L else null,
                seekableEndMs = duration.takeIf { canSeek },
                liveEdgePositionMs = duration.takeIf { playbackType == PlaybackType.LIVE && it > 0L },
            ),
            media = MediaInfo(
                protocol = protocol,
                playbackType = playbackType,
                video = VideoInfo(
                    width = player.videoWidth.takeIf { prepared && it > 0 },
                    height = player.videoHeight.takeIf { prepared && it > 0 },
                ),
            ),
            network = PlayerNetworkState(
                bytesPerSecond = if (isNetworkSource) {
                    runCatching { player.tcpSpeed }.getOrDefault(0L).coerceAtLeast(0L)
                } else 0L,
                isLoading = isNetworkSource && (!prepared || buffering),
            ),
            capabilities = capabilities(canSeek, playbackType == PlaybackType.LIVE),
        )
    }

    private fun playbackType(duration: Long = runCatching { player.duration }.getOrDefault(0L)): PlaybackType = when {
        !prepared -> PlaybackType.UNKNOWN
        duration > 0L -> PlaybackType.VOD
        protocol == MediaProtocol.HLS -> PlaybackType.LIVE
        else -> PlaybackType.UNKNOWN
    }

    private fun capabilities(canSeek: Boolean = false, isLive: Boolean = false) = PlayerCapabilities(
        canPlay = true,
        canPause = true,
        canSeek = canSeek,
        canSetPlaybackSpeed = true,
        canSetPlayerVolume = true,
        canGoToLiveEdge = isLive,
    )

    private fun publishError(
        code: String,
        message: String,
        cause: Throwable? = null,
        category: PlayerErrorCategory = PlayerErrorCategory.INTERNAL,
        retryPolicy: RetryPolicy = RetryPolicy.NEVER,
    ) {
        buffering = false
        mutableState.value = mutableState.value.copy(
            phase = PlaybackPhase.ERROR,
            isPlaying = false,
            error = PlaybackError(code, message, category, retryPolicy, cause),
        )
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 250L
    }
}

class IjkPlaybackEngineProvider(context: Context) : PlaybackEngineProvider {
    private val appContext = context.applicationContext
    override val info = PlayerEngineInfo(
        id = PlayerEngineId.IJK,
        displayName = "IjkPlayer",
        supportedProtocols = setOf(MediaProtocol.PROGRESSIVE, MediaProtocol.HLS),
    )

    override fun supports(source: VideoSource): Boolean =
        !runCatching { Uri.parse(source.uri).path.orEmpty().lowercase().endsWith(".mpd") }.getOrDefault(false)

    override fun create(): PlaybackEngine = IjkPlaybackEngine(appContext)
}

private fun VideoSource.protocolFromUri(): MediaProtocol {
    val path = runCatching { Uri.parse(uri).path }.getOrNull().orEmpty().lowercase()
    return when {
        path.endsWith(".m3u8") -> MediaProtocol.HLS
        path.endsWith(".mpd") -> MediaProtocol.DASH
        else -> MediaProtocol.PROGRESSIVE
    }
}
