package io.github.mobileplayer.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mobileplayer.core.PlayerEngineId
import io.github.mobileplayer.core.PlayerEngineInfo
import io.github.mobileplayer.core.PlayerState
import io.github.mobileplayer.core.TrackType

sealed interface PlayerControl {
    data object Back : PlayerControl
    data object Title : PlayerControl
    data object Engine : PlayerControl
    data object Rewind : PlayerControl
    data object PlayPause : PlayerControl
    data object Forward : PlayerControl
    data object Volume : PlayerControl
    data object Time : PlayerControl
    data object Timeline : PlayerControl
    data object LiveEdge : PlayerControl
    data object Speed : PlayerControl
    data object Subtitles : PlayerControl
    data object Fullscreen : PlayerControl
    data object Lock : PlayerControl
    data class Custom(val key: String) : PlayerControl
}

@Immutable
data class VideoPlayerControlLayout(
    val top: List<PlayerControl> = listOf(PlayerControl.Back, PlayerControl.Title, PlayerControl.Engine),
    val center: List<PlayerControl> = listOf(
        PlayerControl.Rewind,
        PlayerControl.PlayPause,
        PlayerControl.Forward,
    ),
    val bottom: List<PlayerControl> = listOf(
        PlayerControl.Volume,
        PlayerControl.Time,
        PlayerControl.Timeline,
        PlayerControl.LiveEdge,
        PlayerControl.Speed,
        PlayerControl.Subtitles,
        PlayerControl.Fullscreen,
    ),
)

@Immutable
data class VideoPlayerUiConfig(
    val controls: VideoPlayerControlLayout = VideoPlayerControlLayout(),
    val showNetworkSpeedWhileLoading: Boolean = true,
    val skipDurationMs: Long = 5_000L,
    val minimumDurationForSkipMs: Long = 60_000L,
    val availableSpeeds: List<Float> = listOf(0.5f, 1f, 1.5f, 2f, 5f),
    val controlIconSize: Dp = 20.dp,
    val primaryControlSize: Dp = 56.dp,
)

/** Shared player interaction behavior, independent from the visual control layer. */
@Immutable
data class PlayerInteractionConfig(
    val tapToToggleControls: Boolean = true,
    val controlsAutoHideMs: Long = 5_000L,
    val enableBrightnessGesture: Boolean = true,
    val enableVolumeGesture: Boolean = true,
    val unmuteOnVolumeGesture: Boolean = true,
)

@Immutable
data class VideoPlayerStrings(
    val back: String = "Back",
    val play: String = "Play",
    val pause: String = "Pause",
    val rewind: String = "Rewind",
    val forward: String = "Forward",
    val mute: String = "Mute",
    val unmute: String = "Unmute",
    val retry: String = "Retry",
    val fullscreen: String = "Fullscreen",
    val portrait: String = "Exit fullscreen",
    val playbackSpeed: String = "Playback speed",
    val switchEngine: String = "Playback engine",
    val subtitles: String = "Subtitles",
    val lock: String = "Lock controls",
    val unlock: String = "Unlock controls",
    val replay: String = "Replay",
    val live: String = "LIVE",
    val goLive: String = "Go live",
    val loading: String = "Loading",
    val unknownError: String = "Unable to play this video",
)

@Immutable
data class VideoPlayerColors(
    val background: Color = Color(0xFF080B0D),
    val controlsBackground: Color = Color(0xC912171A),
    val controlsContent: Color = Color(0xFFF7FAF9),
    val secondaryContent: Color = Color(0xFFB8C2BE),
    val accent: Color = Color(0xFF2DD4A7),
    val live: Color = Color(0xFFFF4D67),
    val buffered: Color = Color(0x80FFFFFF),
    val error: Color = Color(0xFFFF8A80),
    val timelinePlayed: Color = Color.White,
    val timelineUnplayed: Color = Color(0x2EFFFFFF),
)

@Immutable
data class VideoPlayerIcons(
    val back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    val play: ImageVector = Icons.Default.PlayArrow,
    val pause: ImageVector = Icons.Default.Pause,
    val rewind: ImageVector = Icons.Default.FastRewind,
    val forward: ImageVector = Icons.Default.FastForward,
    val volumeOn: ImageVector = Icons.AutoMirrored.Filled.VolumeUp,
    val volumeOff: ImageVector = Icons.AutoMirrored.Filled.VolumeOff,
    val retry: ImageVector = Icons.Default.Refresh,
    val subtitles: ImageVector = Icons.Default.ClosedCaption,
    val fullscreen: ImageVector = Icons.Default.Fullscreen,
    val exitFullscreen: ImageVector = Icons.Default.FullscreenExit,
    val lock: ImageVector = Icons.Default.Lock,
    val unlock: ImageVector = Icons.Default.LockOpen,
    val replay: ImageVector = Icons.Default.Replay,
)

/** Playback and interaction commands supplied by the raw [VideoPlayer] host. */
@Stable
data class PlayerActions(
    val play: () -> Unit,
    val pause: () -> Unit,
    val togglePlay: () -> Unit,
    val seekTo: (Long) -> Unit,
    val seekBy: (Long) -> Unit,
    val setPlayerVolume: (Float) -> Unit,
    val setSystemVolume: (Float) -> Unit,
    val setScreenBrightness: (Float) -> Unit,
    val setPlaybackSpeed: (Float) -> Unit,
    val selectTrack: (TrackType, String?) -> Unit,
    val setSubtitlesEnabled: (Boolean) -> Unit,
    val switchEngine: (PlayerEngineId) -> Unit,
    val goToLiveEdge: () -> Unit,
    val retry: () -> Unit,
    val beginInteraction: () -> Unit,
    val endInteraction: () -> Unit,
    val showControls: () -> Unit,
    val hideControls: () -> Unit,
)

enum class PlayerGestureType { BRIGHTNESS, VOLUME }

@Immutable
data class PlayerGestureFeedback(
    val type: PlayerGestureType,
    val value: Float,
)

/** Read-only state and commands exposed by the raw player host to presentation code. */
@Stable
class PlayerScope internal constructor(
    val state: PlayerState,
    val availableEngines: List<PlayerEngineInfo>,
    val actions: PlayerActions,
    val controlsVisible: Boolean,
    val gestureFeedback: PlayerGestureFeedback?,
)

/** Presentation scope used by the built-in player presets and their custom controls. */
@Stable
class PlayerUiScope internal constructor(
    val player: PlayerScope,
    val title: String?,
    val actions: PlayerUiActions,
    val strings: VideoPlayerStrings,
    val colors: VideoPlayerColors,
    val icons: VideoPlayerIcons,
    val isFullscreen: Boolean,
    val canNavigateBack: Boolean,
    val canRequestFullscreen: Boolean,
    val isLocked: Boolean,
    val canLock: Boolean,
) {
    val state: PlayerState get() = player.state
    val availableEngines: List<PlayerEngineInfo> get() = player.availableEngines
    val controlsVisible: Boolean get() = player.controlsVisible
    val gestureFeedback: PlayerGestureFeedback? get() = player.gestureFeedback
}

/** Adds preset-owned navigation, fullscreen and lock commands to raw player commands. */
@Stable
class PlayerUiActions internal constructor(
    private val player: PlayerActions,
    val back: () -> Unit,
    val requestFullscreen: (Boolean) -> Unit,
    val setLocked: (Boolean) -> Unit,
) {
    val play: () -> Unit get() = player.play
    val pause: () -> Unit get() = player.pause
    val togglePlay: () -> Unit get() = player.togglePlay
    val seekTo: (Long) -> Unit get() = player.seekTo
    val seekBy: (Long) -> Unit get() = player.seekBy
    val setPlayerVolume: (Float) -> Unit get() = player.setPlayerVolume
    val setSystemVolume: (Float) -> Unit get() = player.setSystemVolume
    val setScreenBrightness: (Float) -> Unit get() = player.setScreenBrightness
    val setPlaybackSpeed: (Float) -> Unit get() = player.setPlaybackSpeed
    val selectTrack: (TrackType, String?) -> Unit get() = player.selectTrack
    val setSubtitlesEnabled: (Boolean) -> Unit get() = player.setSubtitlesEnabled
    val switchEngine: (PlayerEngineId) -> Unit get() = player.switchEngine
    val goToLiveEdge: () -> Unit get() = player.goToLiveEdge
    val retry: () -> Unit get() = player.retry
    val beginInteraction: () -> Unit get() = player.beginInteraction
    val endInteraction: () -> Unit get() = player.endInteraction
    val showControls: () -> Unit get() = player.showControls
    val hideControls: () -> Unit get() = player.hideControls
}

fun interface PlayerControlContent {
    @Composable
    fun Content(scope: PlayerUiScope)
}
