package io.github.mobileplayer.compose

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.mobileplayer.core.PlaybackError
import io.github.mobileplayer.core.PlayerController

/** Stays inside the caller-provided bounds and never changes orientation. */
@Composable
fun ContainedVideoPlayer(
    controller: PlayerController,
    url: String? = null,
    headers: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier.fillMaxSize(),
    autoPlay: Boolean = true,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    uiConfig: VideoPlayerUiConfig = VideoPlayerUiConfig(),
    interactionConfig: PlayerInteractionConfig = containedPlayerInteractionConfig(),
    strings: VideoPlayerStrings = VideoPlayerStrings(),
    colors: VideoPlayerColors = VideoPlayerColors(),
    icons: VideoPlayerIcons = VideoPlayerIcons(),
    customControls: Map<String, PlayerControlContent> = emptyMap(),
    overlayContent: @Composable BoxScope.(PlayerUiScope) -> Unit = {},
    subtitleContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)? = null,
    controlsContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)? = null,
    loadingContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)? = null,
    errorContent: (@Composable BoxScope.(PlaybackError, PlayerUiScope) -> Unit)? = null,
    persistentContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)? = null,
    topControlsContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)? = null,
    centerControlsContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)? = null,
    sideControlsContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)? = null,
    bottomControlsContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)? = null,
) {
    VideoPlayer(
        controller = controller,
        url = url,
        headers = headers,
        modifier = modifier,
        autoPlay = autoPlay,
        interactionConfig = interactionConfig,
    ) { player ->
        BuiltInPlayerPresentation(
            player = player,
            preset = BuiltInPlayerPreset.CONTAINED,
            title = title,
            onBack = onBack,
            onFullscreenChangeRequest = null,
            isFullscreen = false,
            isLocked = false,
            onLockedChange = {},
            uiConfig = uiConfig,
            strings = strings,
            colors = colors,
            icons = icons,
            customControls = customControls,
            overlayContent = overlayContent,
            subtitleContent = subtitleContent,
            controlsContent = controlsContent,
            loadingContent = loadingContent,
            errorContent = errorContent,
            persistentContent = persistentContent,
            topControlsContent = topControlsContent,
            centerControlsContent = centerControlsContent,
            sideControlsContent = sideControlsContent,
            bottomControlsContent = bottomControlsContent,
        )
    }
}

fun containedPlayerInteractionConfig(): PlayerInteractionConfig = PlayerInteractionConfig(
    enableBrightnessGesture = false,
    enableVolumeGesture = false,
)
