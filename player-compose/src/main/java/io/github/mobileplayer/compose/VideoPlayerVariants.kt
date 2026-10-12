package io.github.mobileplayer.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.mobileplayer.core.PlaybackError
import io.github.mobileplayer.core.PlaybackState
import io.github.mobileplayer.core.VideoPlayerController
import io.github.mobileplayer.core.VideoSource

/** Orientation applied to the host Activity while [LandscapeVideoPlayer] is fullscreen. */
enum class PlayerFullscreenOrientation {
    /** Allow either landscape direction according to the device sensor. */
    SENSOR_LANDSCAPE,

    /** Lock to the device's primary landscape direction. */
    LANDSCAPE,

    /** Do not change the host Activity orientation. */
    UNCHANGED,
}

/** A portrait-only player for media whose display height is greater than width. */
@Composable
fun PortraitVideoPlayer(
    controller: VideoPlayerController,
    source: VideoSource? = null,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(9f / 16f),
    autoPlay: Boolean = true,
    startPositionMs: Long = 0L,
    uiConfig: VideoPlayerUiConfig = VideoPlayerUiConfig(),
    strings: VideoPlayerStrings = VideoPlayerStrings(),
    colors: VideoPlayerColors = VideoPlayerColors(),
    overlayContent: @Composable BoxScope.(PlaybackState) -> Unit = {},
    controlsContent: (@Composable BoxScope.(PlaybackState, PlayerUiActions) -> Unit)? = null,
    loadingContent: (@Composable BoxScope.(PlaybackState) -> Unit)? = null,
    errorContent: (@Composable BoxScope.(PlaybackError, PlayerUiActions) -> Unit)? = null,
) {
    VideoPlayer(
        controller = controller,
        source = source,
        modifier = modifier,
        autoPlay = autoPlay,
        startPositionMs = startPositionMs,
        uiConfig = uiConfig,
        strings = strings,
        colors = colors,
        onFullscreenChangeRequest = null,
        isFullscreen = false,
        overlayContent = overlayContent,
        controlsContent = controlsContent,
        loadingContent = loadingContent,
        errorContent = errorContent,
    )
}

/**
 * A controlled player for landscape media. The caller owns [isFullscreen]; this
 * component performs the Activity orientation and fullscreen window operation.
 */
@Composable
fun LandscapeVideoPlayer(
    controller: VideoPlayerController,
    source: VideoSource? = null,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f),
    autoPlay: Boolean = true,
    startPositionMs: Long = 0L,
    uiConfig: VideoPlayerUiConfig = VideoPlayerUiConfig(),
    strings: VideoPlayerStrings = VideoPlayerStrings(),
    colors: VideoPlayerColors = VideoPlayerColors(),
    orientation: PlayerFullscreenOrientation = PlayerFullscreenOrientation.SENSOR_LANDSCAPE,
    immersive: Boolean = true,
    overlayContent: @Composable BoxScope.(PlaybackState) -> Unit = {},
    controlsContent: (@Composable BoxScope.(PlaybackState, PlayerUiActions) -> Unit)? = null,
    loadingContent: (@Composable BoxScope.(PlaybackState) -> Unit)? = null,
    errorContent: (@Composable BoxScope.(PlaybackError, PlayerUiActions) -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var orientationBeforeFullscreen by rememberSaveable {
        mutableIntStateOf(ORIENTATION_NOT_CAPTURED)
    }

    if (isFullscreen) {
        ControlledLockActivityOrientation(
            activity = activity,
            orientation = orientation,
            orientationBeforeFullscreen = orientationBeforeFullscreen,
            onOrientationCaptured = { orientationBeforeFullscreen = it },
            onOrientationRestored = { orientationBeforeFullscreen = ORIENTATION_NOT_CAPTURED },
        )
        Dialog(
            onDismissRequest = { onFullscreenChange(false) },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            ControlledConfigureFullscreenWindow(immersive)
            VideoPlayer(
                controller = controller,
                source = source,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                autoPlay = autoPlay,
                startPositionMs = startPositionMs,
                uiConfig = uiConfig,
                strings = strings,
                colors = colors,
                onFullscreenChangeRequest = onFullscreenChange,
                isFullscreen = true,
                overlayContent = overlayContent,
                controlsContent = controlsContent,
                loadingContent = loadingContent,
                errorContent = errorContent,
            )
        }
    } else {
        VideoPlayer(
            controller = controller,
            source = source,
            modifier = modifier,
            autoPlay = autoPlay,
            startPositionMs = startPositionMs,
            uiConfig = uiConfig,
            strings = strings,
            colors = colors,
            onFullscreenChangeRequest = onFullscreenChange,
            isFullscreen = false,
            overlayContent = overlayContent,
            controlsContent = controlsContent,
            loadingContent = loadingContent,
            errorContent = errorContent,
        )
    }
}

@Composable
private fun ControlledLockActivityOrientation(
    activity: Activity?,
    orientation: PlayerFullscreenOrientation,
    orientationBeforeFullscreen: Int,
    onOrientationCaptured: (Int) -> Unit,
    onOrientationRestored: () -> Unit,
) {
    DisposableEffect(activity, orientation) {
        if (activity == null || orientation == PlayerFullscreenOrientation.UNCHANGED) {
            return@DisposableEffect onDispose {}
        }

        val previousOrientation = if (orientationBeforeFullscreen == ORIENTATION_NOT_CAPTURED) {
            activity.requestedOrientation.also(onOrientationCaptured)
        } else {
            orientationBeforeFullscreen
        }
        val requestedOrientation = when (orientation) {
            PlayerFullscreenOrientation.SENSOR_LANDSCAPE ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

            PlayerFullscreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            PlayerFullscreenOrientation.UNCHANGED -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        activity.requestedOrientation = requestedOrientation

        onDispose {
            if (!activity.isChangingConfigurations &&
                activity.requestedOrientation == requestedOrientation
            ) {
                activity.requestedOrientation = previousOrientation
                onOrientationRestored()
            }
        }
    }
}

@Composable
private fun ControlledConfigureFullscreenWindow(immersive: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, immersive) {
        val window = (view.parent as? DialogWindowProvider)?.window
            ?: return@DisposableEffect onDispose {}
        WindowCompat.setDecorFitsSystemWindows(window, !immersive)
        if (immersive) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {}
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val ORIENTATION_NOT_CAPTURED = Int.MIN_VALUE
