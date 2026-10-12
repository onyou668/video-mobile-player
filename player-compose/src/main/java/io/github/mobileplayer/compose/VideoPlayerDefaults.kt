package io.github.mobileplayer.compose

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import io.github.mobileplayer.core.PlayerEngineType

@Immutable
data class VideoPlayerUiConfig(
    val showEngineSwitcher: Boolean = true,
    /** Shows measured throughput below the spinner for network sources. */
    val showNetworkSpeedWhileLoading: Boolean = true,
    val skipDurationMs: Long = 10_000L,
    val controlsAutoHideMs: Long = 3_000L,
    val availableSpeeds: List<Float> = listOf(0.5f, 1f, 1.25f, 1.5f, 2f),
)

@Immutable
data class VideoPlayerStrings(
    val play: String = "Play",
    val pause: String = "Pause",
    val rewind: String = "Rewind 10 seconds",
    val forward: String = "Forward 10 seconds",
    val mute: String = "Mute",
    val unmute: String = "Unmute",
    val retry: String = "Retry",
    val fullscreen: String = "Fullscreen",
    val portrait: String = "Return to portrait player",
    val playbackSpeed: String = "Playback speed",
    val switchEngine: String = "Switch playback engine",
    val loading: String = "Loading",
    val unknownError: String = "Unable to play this video",
)

@Immutable
data class VideoPlayerColors(
    val background: Color = Color.Black,
    val controlsBackground: Color = Color(0xB3000000),
    val controlsContent: Color = Color.White,
    val accent: Color = Color(0xFF36C98F),
    val buffered: Color = Color(0x80FFFFFF),
    val error: Color = Color(0xFFFFB4AB),
)

/** Commands exposed to replaceable player UI slots. */
@Stable
data class PlayerUiActions(
    val togglePlay: () -> Unit,
    val seekTo: (Long) -> Unit,
    val seekBy: (Long) -> Unit,
    val setVolume: (Float) -> Unit,
    val setPlaybackSpeed: (Float) -> Unit,
    val switchEngine: (PlayerEngineType) -> Unit,
    val retry: () -> Unit,
    val requestFullscreen: (Boolean) -> Unit,
)
