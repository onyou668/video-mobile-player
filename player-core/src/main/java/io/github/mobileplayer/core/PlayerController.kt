package io.github.mobileplayer.core

import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import java.net.URI

/**
 * The only playback interface consumed by application and UI code.
 *
 * State is immutable. Every mutation is expressed as a controller capability.
 */
interface PlayerController : AutoCloseable {
    val state: StateFlow<PlayerState>
    val availableEngines: List<PlayerEngineInfo>

    fun setVideoUrl(
        url: String,
        headers: Map<String, String> = emptyMap(),
        autoPlay: Boolean = true,
    )

    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun setPlayerVolume(volume: Float)
    fun setSystemVolume(volume: Float)
    fun setScreenBrightness(brightness: Float)
    fun selectTrack(type: TrackType, trackId: String?)
    fun setSubtitlesEnabled(enabled: Boolean)
    fun goToLiveEdge()
    fun retry()
    fun switchEngine(engineId: PlayerEngineId)

    fun attachVideoSurface(surface: Surface)
    fun detachVideoSurface(surface: Surface)
    fun onHostStart()
    fun onHostStop(keepPlaying: Boolean = false)
}

fun createPlayerController(
    initialEngineId: PlayerEngineId,
    engineProviders: List<PlaybackEngineProvider>,
    scope: CoroutineScope,
    cache: VideoContentCache? = null,
    environment: PlayerEnvironmentAdapter? = null,
    ownedResources: List<AutoCloseable> = emptyList(),
): PlayerController = DefaultPlayerController(
    initialEngineId = initialEngineId,
    engineProviders = engineProviders,
    scope = scope,
    cache = cache,
    environment = environment ?: NoOpPlayerEnvironment,
    ownedResources = ownedResources,
)

internal class DefaultPlayerController(
    initialEngineId: PlayerEngineId,
    engineProviders: List<PlaybackEngineProvider>,
    private val scope: CoroutineScope,
    private val cache: VideoContentCache?,
    private val environment: PlayerEnvironmentAdapter,
    private val ownedResources: List<AutoCloseable>,
) : PlayerController {
    private val providers = engineProviders.associateBy { it.info.id }
    private val mutableState: MutableStateFlow<PlayerState>
    private var engine: PlaybackEngine? = null
    private var engineStateJob: Job? = null
    private var environmentStateJob: Job? = null
    private var surface: Surface? = null
    private var source: VideoSource? = null
    private var playWhenReady = false
    private var resumeAfterHostStart = false
    private var hostStopped = false
    private var closed = false

    override val state: StateFlow<PlayerState>
    override val availableEngines: List<PlayerEngineInfo> = engineProviders.map { it.info }

    init {
        require(engineProviders.isNotEmpty()) { "At least one playback engine must be registered" }
        require(providers.size == engineProviders.size) { "Playback engine ids must be unique" }
        val initial = requireNotNull(providers[initialEngineId]) {
            "No playback engine registered for $initialEngineId"
        }
        mutableState = MutableStateFlow(
            PlayerState(
                engine = initial.info,
                capabilities = baseCapabilities(),
                environment = environment.state.value,
            ),
        )
        state = mutableState.asStateFlow()
        replaceEngine(initialEngineId, restorePositionMs = 0L, restorePlayback = false)
        environmentStateJob = scope.launch {
            environment.state.collectLatest { value ->
                if (!closed) mutableState.value = mutableState.value.copy(environment = value)
            }
        }
    }

    override fun setVideoUrl(url: String, headers: Map<String, String>, autoPlay: Boolean) {
        checkOpen()
        require(url.isNotBlank()) { "Video URL must not be blank" }
        val scheme = runCatching { URI(url).scheme }.getOrNull()
        val isLocal = scheme.equals("content", true) ||
            scheme.equals("file", true) || scheme.isNullOrBlank()
        setSource(
            source = if (isLocal) VideoSource.local(url) else VideoSource.network(url, headers),
            autoPlay = autoPlay,
            startPositionMs = 0L,
        )
    }

    override fun play() {
        checkOpen()
        val current = mutableState.value
        if (current.source == null || current.phase == PlaybackPhase.RELEASED) return
        playWhenReady = true
        mutableState.value = current.copy(playWhenReady = true)
        if (hostStopped) {
            resumeAfterHostStart = true
            return
        }
        if (current.phase == PlaybackPhase.ENDED) {
            if (current.media.playbackType == PlaybackType.LIVE) engine?.goToLiveEdge()
            else engine?.seekTo(0L)
        }
        engine?.play()
    }

    override fun pause() {
        checkOpen()
        playWhenReady = false
        resumeAfterHostStart = false
        mutableState.value = mutableState.value.copy(playWhenReady = false)
        engine?.pause()
    }

    override fun togglePlayPause() {
        if (state.value.playWhenReady) pause() else play()
    }

    override fun seekTo(positionMs: Long) {
        checkOpen()
        if (state.value.phase == PlaybackPhase.READY && !state.value.capabilities.canSeek) return
        val timeline = state.value.timeline
        val max = timeline.seekableEndMs ?: timeline.durationMs
        val min = timeline.seekableStartMs ?: 0L
        val target = max?.let { positionMs.coerceIn(min, it) } ?: positionMs.coerceAtLeast(min)
        engine?.seekTo(target)
        mutableState.value = mutableState.value.copy(timeline = timeline.copy(positionMs = target))
    }

    override fun seekBy(offsetMs: Long) = seekTo(state.value.positionMs + offsetMs)

    override fun setPlaybackSpeed(speed: Float) {
        checkOpen()
        val normalized = speed.coerceIn(0.25f, 5f)
        mutableState.value = mutableState.value.copy(playbackSpeed = normalized)
        engine?.setPlaybackSpeed(normalized)
    }

    override fun setPlayerVolume(volume: Float) {
        checkOpen()
        val normalized = volume.coerceIn(0f, 1f)
        mutableState.value = mutableState.value.copy(playerVolume = normalized)
        engine?.setPlayerVolume(normalized)
    }

    override fun setSystemVolume(volume: Float) {
        checkOpen()
        if (environment.canSetSystemVolume) environment.setSystemVolume(volume.coerceIn(0f, 1f))
    }

    override fun setScreenBrightness(brightness: Float) {
        checkOpen()
        if (environment.canSetScreenBrightness) environment.setScreenBrightness(brightness.coerceIn(0f, 1f))
    }

    override fun selectTrack(type: TrackType, trackId: String?) {
        checkOpen()
        if (state.value.capabilities.canSelectTracks) engine?.selectTrack(type, trackId)
    }

    override fun setSubtitlesEnabled(enabled: Boolean) {
        checkOpen()
        if (state.value.capabilities.canToggleSubtitles) engine?.setSubtitlesEnabled(enabled)
    }

    override fun goToLiveEdge() {
        checkOpen()
        if (state.value.capabilities.canGoToLiveEdge) engine?.goToLiveEdge()
    }

    override fun retry() {
        checkOpen()
        val error = state.value.error ?: return
        if (!error.isRetryable) return
        val currentSource = source ?: return
        prepare(currentSource, state.value.positionMs)
        if (playWhenReady && !hostStopped) play()
    }

    override fun switchEngine(engineId: PlayerEngineId) {
        checkOpen()
        if (engineId == state.value.engineId) return
        val provider = requireNotNull(providers[engineId]) { "No playback engine registered for $engineId" }
        val currentSource = source
        if (currentSource != null && !provider.supports(currentSource)) {
            mutableState.value = state.value.copy(
                phase = PlaybackPhase.ERROR,
                playWhenReady = false,
                isPlaying = false,
                error = unsupportedFormatError(currentSource),
            )
            playWhenReady = false
            return
        }
        replaceEngine(engineId, state.value.positionMs, playWhenReady && !hostStopped)
    }

    override fun attachVideoSurface(surface: Surface) {
        if (closed) return
        this.surface = surface
        engine?.setSurface(surface)
    }

    override fun detachVideoSurface(surface: Surface) {
        if (closed || this.surface !== surface) return
        this.surface = null
        engine?.setSurface(null)
    }

    override fun onHostStop(keepPlaying: Boolean) {
        if (closed) return
        if (keepPlaying) {
            hostStopped = false
            resumeAfterHostStart = false
            return
        }
        hostStopped = true
        resumeAfterHostStart = playWhenReady && source != null && state.value.phase != PlaybackPhase.ENDED
        if (resumeAfterHostStart) engine?.pause()
    }

    override fun onHostStart() {
        if (closed) return
        hostStopped = false
        if (resumeAfterHostStart) {
            resumeAfterHostStart = false
            engine?.play()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        engineStateJob?.cancel()
        environmentStateJob?.cancel()
        engine?.release()
        engine = null
        surface = null
        mutableState.value = mutableState.value.copy(
            phase = PlaybackPhase.RELEASED,
            playWhenReady = false,
            isPlaying = false,
        )
        ownedResources.forEach { runCatching { it.close() } }
    }

    private fun setSource(source: VideoSource, autoPlay: Boolean, startPositionMs: Long) {
        if (this.source == source && state.value.phase != PlaybackPhase.ERROR) {
            if (autoPlay && !playWhenReady) play()
            return
        }
        playWhenReady = false
        val compatibleProvider = providers[state.value.engineId]
            ?.takeIf { it.supports(source) }
            ?: providers.values.firstOrNull { it.supports(source) }
        if (compatibleProvider == null) {
            this.source = source
            mutableState.value = state.value.copy(
                source = source,
                media = inferMediaInfo(source),
                phase = PlaybackPhase.ERROR,
                playWhenReady = false,
                isPlaying = false,
                hasRenderedFirstFrame = false,
                tracks = PlayerTracks(),
                subtitleCues = emptyList(),
                error = unsupportedFormatError(source),
                capabilities = baseCapabilities(),
            )
            return
        }
        if (compatibleProvider.info.id != state.value.engineId) {
            this.source = null
            replaceEngine(compatibleProvider.info.id, startPositionMs, restorePlayback = false)
        }
        this.source = source
        prepare(source, startPositionMs)
        if (autoPlay) play()
    }

    private fun prepare(source: VideoSource, startPositionMs: Long) {
        val position = startPositionMs.coerceAtLeast(0L)
        mutableState.value = state.value.copy(
            phase = PlaybackPhase.PREPARING,
            hasRenderedFirstFrame = false,
            source = source,
            media = inferMediaInfo(source),
            timeline = PlayerTimeline(positionMs = position),
            tracks = PlayerTracks(),
            subtitleCues = emptyList(),
            network = PlayerNetworkState(isLoading = source.type == VideoSource.Type.NETWORK),
            endReason = null,
            error = null,
            capabilities = baseCapabilities(),
        )
        engine?.prepare(resolvePlaybackSource(source), position)
    }

    private fun replaceEngine(
        engineId: PlayerEngineId,
        restorePositionMs: Long,
        restorePlayback: Boolean,
    ) {
        engineStateJob?.cancel()
        engine?.release()
        val provider = requireNotNull(providers[engineId])
        val next = provider.create()
        engine = next
        val previous = mutableState.value
        mutableState.value = previous.copy(
            engine = provider.info,
            phase = if (source == null) PlaybackPhase.IDLE else PlaybackPhase.PREPARING,
            isPlaying = false,
            hasRenderedFirstFrame = false,
            tracks = PlayerTracks(),
            subtitleCues = emptyList(),
            timeline = previous.timeline.copy(positionMs = restorePositionMs),
            error = null,
            capabilities = baseCapabilities(),
        )
        next.setSurface(surface)
        next.setPlayerVolume(previous.playerVolume)
        next.setPlaybackSpeed(previous.playbackSpeed)
        engineStateJob = scope.launch {
            next.state.collectLatest { engineState ->
                if (engine !== next || closed) return@collectLatest
                if (engineState.phase == PlaybackPhase.ENDED) {
                    playWhenReady = false
                }
                mutableState.value = mutableState.value.copy(
                    phase = engineState.phase,
                    playWhenReady = playWhenReady,
                    isPlaying = engineState.isPlaying,
                    hasRenderedFirstFrame = engineState.hasRenderedFirstFrame,
                    media = mergeMedia(mutableState.value.media, engineState.media),
                    timeline = engineState.timeline,
                    tracks = engineState.tracks,
                    subtitleCues = engineState.subtitleCues,
                    network = engineState.network,
                    capabilities = engineState.capabilities.copy(
                        canRetry = engineState.error?.isRetryable == true,
                        canSwitchEngine = providers.size > 1,
                        canSetSystemVolume = environment.canSetSystemVolume,
                        canSetScreenBrightness = environment.canSetScreenBrightness,
                    ),
                    metrics = engineState.metrics,
                    endReason = engineState.endReason,
                    error = engineState.error,
                )
            }
        }
        source?.let { current ->
            next.prepare(resolvePlaybackSource(current), restorePositionMs)
            if (restorePlayback) {
                playWhenReady = true
                mutableState.value = mutableState.value.copy(playWhenReady = true)
                next.play()
            }
        }
    }

    private fun baseCapabilities() = PlayerCapabilities(
        canSwitchEngine = providers.size > 1,
        canSetSystemVolume = environment.canSetSystemVolume,
        canSetScreenBrightness = environment.canSetScreenBrightness,
    )

    private fun resolvePlaybackSource(source: VideoSource): VideoSource =
        runCatching { cache?.getCachedSource(source) }.getOrNull() ?: source

    private fun checkOpen() = check(!closed) { "PlayerController has already been closed" }
}

private fun inferMediaInfo(source: VideoSource): MediaInfo {
    val path = runCatching { URI(source.uri).path }.getOrNull().orEmpty()
    val fileName = path.substringAfterLast('/').takeIf { it.isNotBlank() }
    val extension = fileName?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
    val protocol = when (extension) {
        "m3u8" -> MediaProtocol.HLS
        "mpd" -> MediaProtocol.DASH
        in setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "ts") -> MediaProtocol.PROGRESSIVE
        else -> MediaProtocol.UNKNOWN
    }
    return MediaInfo(
        protocol = protocol,
        playbackType = if (protocol == MediaProtocol.PROGRESSIVE) PlaybackType.VOD else PlaybackType.UNKNOWN,
        fileName = fileName,
        containerFormat = extension.takeIf { it.isNotBlank() },
    )
}

private fun mergeMedia(parsed: MediaInfo, engine: MediaInfo): MediaInfo = engine.copy(
    protocol = engine.protocol.takeUnless { it == MediaProtocol.UNKNOWN } ?: parsed.protocol,
    playbackType = engine.playbackType.takeUnless { it == PlaybackType.UNKNOWN } ?: parsed.playbackType,
    fileName = engine.fileName ?: parsed.fileName,
    containerFormat = engine.containerFormat ?: parsed.containerFormat,
)

private fun unsupportedFormatError(source: VideoSource) = PlaybackError(
    code = "unsupported_format",
    message = "No registered playback engine supports ${source.uri}",
    category = PlayerErrorCategory.UNSUPPORTED,
    retryPolicy = RetryPolicy.NEVER,
)
