package io.github.mobileplayer.core

enum class PlaybackPhase { IDLE, PREPARING, READY, BUFFERING, ENDED, ERROR, RELEASED }

enum class PlaybackEndReason { VOD_COMPLETED, LIVE_STREAM_ENDED }

enum class MediaProtocol { UNKNOWN, PROGRESSIVE, HLS, DASH }

enum class PlaybackType { UNKNOWN, VOD, LIVE }

enum class TrackType { VIDEO, AUDIO, SUBTITLE }

enum class PlayerErrorCategory { SOURCE, NETWORK, DECODER, UNSUPPORTED, INTERNAL }

enum class RetryPolicy { NEVER, IMMEDIATE, BACKOFF }

data class PlaybackError(
    val code: String,
    val message: String,
    val category: PlayerErrorCategory = PlayerErrorCategory.INTERNAL,
    val retryPolicy: RetryPolicy = RetryPolicy.NEVER,
    val cause: Throwable? = null,
) {
    val isRetryable: Boolean get() = retryPolicy != RetryPolicy.NEVER
}

data class PlayerTimeline(
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val bufferedPositionMs: Long = 0L,
    val seekableStartMs: Long? = null,
    val seekableEndMs: Long? = null,
    val liveEdgePositionMs: Long? = null,
    val liveOffsetMs: Long? = null,
    val isAtLiveEdge: Boolean = false,
) {
    val progress: Float
        get() = durationMs?.takeIf { it > 0L }?.let {
            (positionMs.toFloat() / it).coerceIn(0f, 1f)
        } ?: 0f

    val bufferedProgress: Float
        get() = durationMs?.takeIf { it > 0L }?.let {
            (bufferedPositionMs.toFloat() / it).coerceIn(0f, 1f)
        } ?: 0f
}

data class VideoInfo(
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val frameRate: Float? = null,
    val codec: String? = null,
)

data class AudioInfo(
    val bitrate: Long? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    val codec: String? = null,
)

data class MediaInfo(
    val protocol: MediaProtocol = MediaProtocol.UNKNOWN,
    val playbackType: PlaybackType = PlaybackType.UNKNOWN,
    val fileName: String? = null,
    val mimeType: String? = null,
    val containerFormat: String? = null,
    val contentLengthBytes: Long? = null,
    val bitrate: Long? = null,
    val video: VideoInfo = VideoInfo(),
    val audio: AudioInfo = AudioInfo(),
)

data class PlayerTrack(
    val id: String,
    val type: TrackType,
    val label: String? = null,
    val language: String? = null,
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val selected: Boolean = false,
)

data class PlayerTracks(
    val video: List<PlayerTrack> = emptyList(),
    val audio: List<PlayerTrack> = emptyList(),
    val subtitles: List<PlayerTrack> = emptyList(),
    val subtitlesEnabled: Boolean = true,
)

/** Engine-independent text currently presented by the selected subtitle track. */
data class PlayerSubtitleCue(
    val text: String,
)

data class PlayerNetworkState(
    val bytesPerSecond: Long = 0L,
    val isLoading: Boolean = false,
)

data class PlayerCapabilities(
    val canPlay: Boolean = true,
    val canPause: Boolean = true,
    val canSeek: Boolean = false,
    val canSetPlaybackSpeed: Boolean = false,
    val canSetPlayerVolume: Boolean = true,
    val canSelectTracks: Boolean = false,
    val canToggleSubtitles: Boolean = false,
    val canGoToLiveEdge: Boolean = false,
    val canRetry: Boolean = false,
    val canSwitchEngine: Boolean = false,
    val canSetSystemVolume: Boolean = false,
    val canSetScreenBrightness: Boolean = false,
)

data class PlayerMetrics(
    val droppedVideoFrames: Long = 0L,
    val renderedVideoFrames: Long = 0L,
)

data class PlayerEnvironmentState(
    val systemVolume: Float? = null,
    val screenBrightness: Float? = null,
)

/** Immutable, engine-independent snapshot consumed by any UI implementation. */
data class PlayerState(
    val engine: PlayerEngineInfo,
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val hasRenderedFirstFrame: Boolean = false,
    val source: VideoSource? = null,
    val media: MediaInfo = MediaInfo(),
    val timeline: PlayerTimeline = PlayerTimeline(),
    val tracks: PlayerTracks = PlayerTracks(),
    val subtitleCues: List<PlayerSubtitleCue> = emptyList(),
    val network: PlayerNetworkState = PlayerNetworkState(),
    val capabilities: PlayerCapabilities = PlayerCapabilities(),
    val metrics: PlayerMetrics = PlayerMetrics(),
    val environment: PlayerEnvironmentState = PlayerEnvironmentState(),
    val playbackSpeed: Float = 1f,
    val playerVolume: Float = 1f,
    val endReason: PlaybackEndReason? = null,
    val error: PlaybackError? = null,
) {
    val engineId: PlayerEngineId get() = engine.id
    val positionMs: Long get() = timeline.positionMs
    val durationMs: Long get() = timeline.durationMs ?: 0L
    val bufferedPositionMs: Long get() = timeline.bufferedPositionMs
    val progress: Float get() = timeline.progress
    val bufferedProgress: Float get() = timeline.bufferedProgress
    val networkSpeedBytesPerSecond: Long get() = network.bytesPerSecond
    val videoWidth: Int get() = media.video.width ?: 0
    val videoHeight: Int get() = media.video.height ?: 0
    val videoAspectRatio: Float
        get() = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else 16f / 9f
}
