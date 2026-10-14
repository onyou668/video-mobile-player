package io.github.mobileplayer.core

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/** State emitted by one engine adapter and normalized by the controller. */
data class EnginePlaybackState(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val isPlaying: Boolean = false,
    val hasRenderedFirstFrame: Boolean = false,
    val timeline: PlayerTimeline = PlayerTimeline(),
    val media: MediaInfo = MediaInfo(),
    val tracks: PlayerTracks = PlayerTracks(),
    val subtitleCues: List<PlayerSubtitleCue> = emptyList(),
    val network: PlayerNetworkState = PlayerNetworkState(),
    val capabilities: PlayerCapabilities = PlayerCapabilities(),
    val metrics: PlayerMetrics = PlayerMetrics(),
    val endReason: PlaybackEndReason? = null,
    val error: PlaybackError? = null,
)

/** Adapter seam implemented once for every playback SDK. */
interface PlaybackEngine {
    val state: StateFlow<EnginePlaybackState>

    fun setSurface(surface: Surface?)

    /** Prepares media only. Playback starts exclusively through [play]. */
    fun prepare(source: VideoSource, startPositionMs: Long = 0L)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setPlayerVolume(volume: Float)

    fun setPlaybackSpeed(speed: Float)

    fun selectTrack(type: TrackType, trackId: String?) = Unit

    fun setSubtitlesEnabled(enabled: Boolean) = Unit

    fun goToLiveEdge() = Unit

    fun release()
}
