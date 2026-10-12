package io.github.mobileplayer.core

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/** State emitted by an individual engine adapter. */
data class EnginePlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val networkSpeedBytesPerSecond: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val error: PlaybackError? = null,
)

/** Small adapter boundary implemented once for every playback SDK. */
interface PlaybackEngine {
    val state: StateFlow<EnginePlaybackState>

    fun setSurface(surface: Surface?)

    fun prepare(source: VideoSource, playWhenReady: Boolean, startPositionMs: Long = 0L)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setVolume(volume: Float)

    fun setPlaybackSpeed(speed: Float)

    fun release()
}

fun interface PlaybackEngineFactory {
    fun create(): PlaybackEngine
}
