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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.mobileplayer.core.EnginePlaybackState
import io.github.mobileplayer.core.AudioInfo
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
import io.github.mobileplayer.core.PlayerSubtitleCue
import io.github.mobileplayer.core.PlayerTrack
import io.github.mobileplayer.core.PlayerTimeline
import io.github.mobileplayer.core.PlayerTracks
import io.github.mobileplayer.core.RetryPolicy
import io.github.mobileplayer.core.TrackType
import io.github.mobileplayer.core.VideoInfo
import io.github.mobileplayer.core.VideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class Media3PlaybackEngine(context: Context) : PlaybackEngine {
    private val appContext = context.applicationContext
    private val player = ExoPlayer.Builder(appContext).build()
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(EnginePlaybackState(capabilities = capabilities()))
    private val networkSpeedMeter = NetworkSpeedMeter()
    private var currentProtocol = MediaProtocol.UNKNOWN
    private var subtitlesEnabled = false
    private var subtitleCues: List<PlayerSubtitleCue> = emptyList()
    private var hasRenderedFirstFrame = false
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
        override fun onVideoSizeChanged(videoSize: VideoSize) = publishState()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = publishState()
        override fun onTracksChanged(tracks: Tracks) {
            if (tracks.groups.none { it.type == C.TRACK_TYPE_TEXT }) subtitleCues = emptyList()
            if (!ensureSubtitleTrackSelected(tracks)) publishState()
        }
        override fun onCues(cueGroup: CueGroup) {
            subtitleCues = if (subtitlesEnabled) {
                cueGroup.cues.mapNotNull { cue ->
                    cue.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(::PlayerSubtitleCue)
                }
            } else emptyList()
            publishState()
        }
        override fun onRenderedFirstFrame() {
            hasRenderedFirstFrame = true
            publishState()
        }

        override fun onPlayerError(error: PlaybackException) {
            mutableState.value = snapshot().copy(
                phase = PlaybackPhase.ERROR,
                isPlaying = false,
                error = error.toPlayerError(),
            )
        }
    }

    init {
        player.addListener(listener)
        handler.post(progressTicker)
    }

    override fun setSurface(surface: Surface?) {
        if (!released) player.setVideoSurface(surface)
    }

    override fun prepare(source: VideoSource, startPositionMs: Long) {
        if (released) return
        player.pause()
        currentProtocol = source.protocolFromUri()
        hasRenderedFirstFrame = false
        subtitleCues = emptyList()
        mutableState.value = EnginePlaybackState(
            phase = PlaybackPhase.PREPARING,
            timeline = PlayerTimeline(positionMs = startPositionMs.coerceAtLeast(0L)),
            media = MediaInfo(protocol = currentProtocol),
            network = PlayerNetworkState(isLoading = source.type == VideoSource.Type.NETWORK),
            capabilities = capabilities(),
        )
        networkSpeedMeter.reset()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(source.headers)
        val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
            .setTransferListener(networkSpeedMeter)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val mediaItem = MediaItem.fromUri(Uri.parse(source.uri))
        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem), startPositionMs.coerceAtLeast(0L))
        player.prepare()
    }

    override fun play() {
        if (!released) player.play()
    }

    override fun pause() {
        if (!released) player.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        player.seekTo(positionMs.coerceAtLeast(0L))
        publishState()
    }

    override fun setPlayerVolume(volume: Float) {
        if (!released) player.volume = volume.coerceIn(0f, 1f)
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (!released) player.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 5f))
    }

    override fun setSubtitlesEnabled(enabled: Boolean) {
        if (released) return
        subtitlesEnabled = enabled
        if (!enabled) subtitleCues = emptyList()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        if (!ensureSubtitleTrackSelected(player.currentTracks)) publishState()
    }

    override fun selectTrack(type: TrackType, trackId: String?) {
        if (released || type != TrackType.SUBTITLE) return
        if (trackId == null) {
            setSubtitlesEnabled(false)
            return
        }
        val (groupIndex, trackIndex) = trackId.split(':', limit = 2)
            .mapNotNull(String::toIntOrNull)
            .takeIf { it.size == 2 }
            ?: return
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
        if (group.type != C.TRACK_TYPE_TEXT || trackIndex !in 0 until group.length) return
        subtitlesEnabled = true
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)))
            .build()
        publishState()
    }

    override fun goToLiveEdge() {
        if (released || !player.isCurrentMediaItemLive) return
        player.seekToDefaultPosition()
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
        if (released || mutableState.value.phase == PlaybackPhase.ERROR) return
        mutableState.value = snapshot()
    }

    private fun snapshot(): EnginePlaybackState {
        val duration = player.duration.validTimeOrNull()
        val position = player.currentPosition.coerceAtLeast(0L)
        val buffered = player.bufferedPosition.coerceAtLeast(0L)
        val isLive = player.isCurrentMediaItemLive
        val liveOffset = player.currentLiveOffset.validTimeOrNull()
        val phase = when (player.playbackState) {
            Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
            Player.STATE_READY -> PlaybackPhase.READY
            Player.STATE_ENDED -> PlaybackPhase.ENDED
            else -> if (player.currentMediaItem == null) PlaybackPhase.IDLE else PlaybackPhase.PREPARING
        }
        val videoSize = player.videoSize
        val videoFormat = player.videoFormat
        val audioFormat = player.audioFormat
        val playbackType = when {
            player.currentMediaItem == null -> PlaybackType.UNKNOWN
            isLive -> PlaybackType.LIVE
            else -> PlaybackType.VOD
        }
        val tracks = currentTracks()
        return EnginePlaybackState(
            phase = phase,
            isPlaying = player.isPlaying,
            hasRenderedFirstFrame = hasRenderedFirstFrame,
            timeline = PlayerTimeline(
                positionMs = position,
                durationMs = duration,
                bufferedPositionMs = buffered,
                seekableStartMs = if (player.isCurrentMediaItemSeekable) 0L else null,
                seekableEndMs = if (player.isCurrentMediaItemSeekable) duration else null,
                liveEdgePositionMs = if (isLive) duration else null,
                liveOffsetMs = liveOffset,
                isAtLiveEdge = isLive && liveOffset != null && liveOffset <= LIVE_EDGE_TOLERANCE_MS,
            ),
            media = MediaInfo(
                protocol = currentProtocol,
                playbackType = playbackType,
                mimeType = videoFormat?.sampleMimeType,
                bitrate = videoFormat?.averageBitrate?.takeIf { it > 0 }?.toLong(),
                video = VideoInfo(
                    width = videoFormat?.width?.takeIf { it > 0 } ?: videoSize.width.takeIf { it > 0 },
                    height = videoFormat?.height?.takeIf { it > 0 } ?: videoSize.height.takeIf { it > 0 },
                    bitrate = videoFormat?.averageBitrate?.takeIf { it > 0 }?.toLong(),
                    frameRate = videoFormat?.frameRate?.takeIf { it > 0f },
                    codec = videoFormat?.codecs,
                ),
                audio = AudioInfo(
                    bitrate = audioFormat?.averageBitrate?.takeIf { it > 0 }?.toLong(),
                    sampleRate = audioFormat?.sampleRate?.takeIf { it > 0 },
                    channelCount = audioFormat?.channelCount?.takeIf { it > 0 },
                    codec = audioFormat?.codecs,
                ),
            ),
            tracks = tracks,
            subtitleCues = subtitleCues,
            network = PlayerNetworkState(
                bytesPerSecond = networkSpeedMeter.bytesPerSecond(),
                isLoading = player.isLoading,
            ),
            capabilities = capabilities(
                canSeek = player.isCurrentMediaItemSeekable,
                isLive = isLive,
                hasSubtitles = tracks.subtitles.isNotEmpty(),
            ),
            endReason = if (phase == PlaybackPhase.ENDED) {
                if (isLive) PlaybackEndReason.LIVE_STREAM_ENDED else PlaybackEndReason.VOD_COMPLETED
            } else null,
        )
    }

    private fun currentTracks(): PlayerTracks {
        val video = mutableListOf<PlayerTrack>()
        val audio = mutableListOf<PlayerTrack>()
        val subtitles = mutableListOf<PlayerTrack>()
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            val type = when (group.type) {
                C.TRACK_TYPE_VIDEO -> TrackType.VIDEO
                C.TRACK_TYPE_AUDIO -> TrackType.AUDIO
                C.TRACK_TYPE_TEXT -> TrackType.SUBTITLE
                else -> null
            } ?: return@forEachIndexed
            repeat(group.length) { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                val track = PlayerTrack(
                    id = "$groupIndex:$trackIndex",
                    type = type,
                    label = format.label,
                    language = format.language,
                    mimeType = format.sampleMimeType,
                    bitrate = format.bitrate.takeIf { it > 0 },
                    selected = group.isTrackSelected(trackIndex),
                )
                when (type) {
                    TrackType.VIDEO -> video += track
                    TrackType.AUDIO -> audio += track
                    TrackType.SUBTITLE -> subtitles += track
                }
            }
        }
        return PlayerTracks(
            video = video,
            audio = audio,
            subtitles = subtitles,
            subtitlesEnabled = subtitlesEnabled && subtitles.any(PlayerTrack::selected),
        )
    }

    private fun ensureSubtitleTrackSelected(tracks: Tracks): Boolean {
        if (!subtitlesEnabled) return false
        if (tracks.groups.any { group ->
                group.type == C.TRACK_TYPE_TEXT &&
                    (0 until group.length).any(group::isTrackSelected)
            }
        ) return false
        val groups = tracks.groups.filter { candidate ->
            candidate.type == C.TRACK_TYPE_TEXT &&
                (0 until candidate.length).any(candidate::isTrackSupported)
        }
        val preferredLanguage = Locale.getDefault().language
        val group = groups.firstOrNull { candidate ->
            (0 until candidate.length).any { index ->
                candidate.isTrackSupported(index) &&
                    candidate.getTrackFormat(index).language
                        ?.substringBefore('-')
                        ?.equals(preferredLanguage, ignoreCase = true) == true
            }
        } ?: groups.firstOrNull() ?: return false
        val trackIndex = (0 until group.length).first(group::isTrackSupported)
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)))
            .build()
        return true
    }

    private fun capabilities(
        canSeek: Boolean = false,
        isLive: Boolean = false,
        hasSubtitles: Boolean = false,
    ) = PlayerCapabilities(
        canPlay = true,
        canPause = true,
        canSeek = canSeek,
        canSetPlaybackSpeed = true,
        canSetPlayerVolume = true,
        canSelectTracks = hasSubtitles,
        canToggleSubtitles = hasSubtitles,
        canGoToLiveEdge = isLive,
    )

    private fun Long.validTimeOrNull(): Long? = if (this == C.TIME_UNSET || this < 0L) null else this

    companion object {
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val LIVE_EDGE_TOLERANCE_MS = 3_000L
    }
}

class Media3PlaybackEngineProvider(context: Context) : PlaybackEngineProvider {
    private val appContext = context.applicationContext
    override val info = PlayerEngineInfo(
        id = PlayerEngineId.MEDIA3,
        displayName = "Media3",
        supportedProtocols = setOf(MediaProtocol.PROGRESSIVE, MediaProtocol.HLS, MediaProtocol.DASH),
    )

    override fun create(): PlaybackEngine = Media3PlaybackEngine(appContext)
}

private fun VideoSource.protocolFromUri(): MediaProtocol {
    val path = runCatching { Uri.parse(uri).path }.getOrNull().orEmpty().lowercase()
    return when {
        path.endsWith(".m3u8") -> MediaProtocol.HLS
        path.endsWith(".mpd") -> MediaProtocol.DASH
        else -> MediaProtocol.PROGRESSIVE
    }
}

private fun PlaybackException.toPlayerError(): PlaybackError {
    val unsupported = errorCode in setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    )
    val network = errorCode in PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED..
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
    val category = when {
        unsupported -> PlayerErrorCategory.UNSUPPORTED
        network -> PlayerErrorCategory.NETWORK
        errorCode >= PlaybackException.ERROR_CODE_DECODING_FAILED -> PlayerErrorCategory.DECODER
        else -> PlayerErrorCategory.SOURCE
    }
    return PlaybackError(
        code = "media3_$errorCode",
        message = message ?: "Media3 playback failed",
        category = category,
        retryPolicy = if (category == PlayerErrorCategory.NETWORK) RetryPolicy.BACKOFF else RetryPolicy.NEVER,
        cause = this,
    )
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
        } else measuredBytesPerSecond
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
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
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
        if (bytesInWindow > 0L && elapsedMs > 0L) measuredBytesPerSecond = bytesInWindow * 1_000L / elapsedMs
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
