package io.github.mobileplayer.core

import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns one engine at a time and exposes stable commands/state to the UI.
 *
 * Switching engines preserves the current source, position, play/pause intent,
 * volume, speed and output surface.
 */
class VideoPlayerController(
    initialEngine: PlayerEngineType,
    factories: Map<PlayerEngineType, PlaybackEngineFactory>,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val factories = factories.toMap()
    private val mutableState = MutableStateFlow(PlaybackState(engineType = initialEngine))

    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var engine: PlaybackEngine? = null
    private var stateCollection: Job? = null
    private var surface: Surface? = null
    private var source: VideoSource? = null
    private var playWhenReady = false
    private var resumeAfterHostStart = false
    private var hostStopped = false
    private var closed = false

    init {
        require(factories.containsKey(initialEngine)) {
            "No engine factory registered for $initialEngine"
        }
        replaceEngine(initialEngine, restorePositionMs = 0L, restorePlayback = false)
    }

    fun setMedia(source: VideoSource, playWhenReady: Boolean = true) {
        checkOpen()
        this.source = source
        this.playWhenReady = playWhenReady
        if (hostStopped && playWhenReady) resumeAfterHostStart = true
        mutableState.value = mutableState.value.copy(
            source = source,
            status = PlaybackStatus.PREPARING,
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            bufferedPositionMs = 0L,
            networkSpeedBytesPerSecond = 0L,
            error = null,
        )
        engine?.prepare(source, playWhenReady && !hostStopped, startPositionMs = 0L)
    }

    /**
     * Loads [source] only when it differs from the active source. This is the
     * appropriate entry point for UI containers that may be recreated while a
     * shared playback session remains alive.
     *
     * @return true when a new source was prepared.
     */
    fun setMediaIfChanged(source: VideoSource, playWhenReady: Boolean = true): Boolean {
        checkOpen()
        if (this.source == source) return false
        setMedia(source, playWhenReady)
        return true
    }

    fun switchEngine(type: PlayerEngineType) {
        checkOpen()
        if (type == mutableState.value.engineType) return
        require(factories.containsKey(type)) { "No engine factory registered for $type" }

        val snapshot = mutableState.value
        replaceEngine(
            type = type,
            restorePositionMs = snapshot.positionMs,
            restorePlayback = playWhenReady && !hostStopped,
        )
    }

    fun play() {
        checkOpen()
        playWhenReady = true
        if (hostStopped) {
            resumeAfterHostStart = true
            return
        }
        if (mutableState.value.status == PlaybackStatus.ENDED) {
            engine?.seekTo(0L)
        }
        engine?.play()
    }

    fun pause() {
        checkOpen()
        playWhenReady = false
        resumeAfterHostStart = false
        engine?.pause()
    }

    fun togglePlayPause() {
        if (mutableState.value.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        checkOpen()
        val duration = mutableState.value.durationMs
        val target = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        engine?.seekTo(target)
        mutableState.value = mutableState.value.copy(positionMs = target)
    }

    fun seekBy(offsetMs: Long) {
        seekTo(mutableState.value.positionMs + offsetMs)
    }

    fun setVolume(volume: Float) {
        checkOpen()
        val normalized = volume.coerceIn(0f, 1f)
        mutableState.value = mutableState.value.copy(volume = normalized)
        engine?.setVolume(normalized)
    }

    fun setPlaybackSpeed(speed: Float) {
        checkOpen()
        val normalized = speed.coerceIn(0.25f, 3f)
        mutableState.value = mutableState.value.copy(playbackSpeed = normalized)
        engine?.setPlaybackSpeed(normalized)
    }

    fun retry() {
        checkOpen()
        val currentSource = source ?: return
        engine?.prepare(
            source = currentSource,
            playWhenReady = playWhenReady && !hostStopped,
            startPositionMs = mutableState.value.positionMs,
        )
    }

    fun setSurface(surface: Surface?) {
        if (closed) return
        this.surface = surface
        engine?.setSurface(surface)
    }

    /** Detaches [surface] only if it is still the active video output. */
    fun clearSurface(surface: Surface) {
        if (closed || this.surface !== surface) return
        this.surface = null
        engine?.setSurface(null)
    }

    /**
     * Pauses page-scoped playback when the host stops. Floating players pass
     * [keepPlaying] so an Activity-to-PiP/overlay transition does not alter
     * the playback session.
     */
    fun onHostStop(keepPlaying: Boolean = false) {
        if (closed) return
        if (keepPlaying) {
            hostStopped = false
            resumeAfterHostStart = false
            return
        }
        hostStopped = true
        resumeAfterHostStart = playWhenReady &&
            source != null &&
            mutableState.value.status != PlaybackStatus.ENDED
        if (resumeAfterHostStart) engine?.pause()
    }

    fun onHostStart() {
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
        stateCollection?.cancel()
        stateCollection = null
        engine?.release()
        engine = null
        surface = null
    }

    private fun replaceEngine(
        type: PlayerEngineType,
        restorePositionMs: Long,
        restorePlayback: Boolean,
    ) {
        stateCollection?.cancel()
        engine?.release()

        val nextEngine = requireNotNull(factories[type]) {
            "No engine factory registered for $type"
        }.create()
        engine = nextEngine

        val previous = mutableState.value
        mutableState.value = previous.copy(
            engineType = type,
            status = if (source == null) PlaybackStatus.IDLE else PlaybackStatus.PREPARING,
            isPlaying = false,
            positionMs = restorePositionMs,
            error = null,
        )

        nextEngine.setSurface(surface)
        nextEngine.setVolume(previous.volume)
        nextEngine.setPlaybackSpeed(previous.playbackSpeed)

        stateCollection = scope.launch {
            nextEngine.state.collectLatest { engineState ->
                if (engine !== nextEngine || closed) return@collectLatest
                if (engineState.status == PlaybackStatus.ENDED) playWhenReady = false
                mutableState.value = mutableState.value.copy(
                    status = engineState.status,
                    isPlaying = engineState.isPlaying,
                    positionMs = engineState.positionMs,
                    durationMs = engineState.durationMs,
                    bufferedPositionMs = engineState.bufferedPositionMs,
                    networkSpeedBytesPerSecond = engineState.networkSpeedBytesPerSecond,
                    videoWidth = engineState.videoWidth,
                    videoHeight = engineState.videoHeight,
                    error = engineState.error,
                )
            }
        }

        source?.let { currentSource ->
            nextEngine.prepare(currentSource, restorePlayback, restorePositionMs)
        }
    }

    private fun checkOpen() {
        check(!closed) { "VideoPlayerController has already been closed" }
    }
}
