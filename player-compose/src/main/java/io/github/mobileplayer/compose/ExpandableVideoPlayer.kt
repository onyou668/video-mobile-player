package io.github.mobileplayer.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import io.github.mobileplayer.core.PlayerController

enum class PlayerFullscreenOrientation {
    SENSOR_LANDSCAPE,
    LANDSCAPE,
    UNCHANGED,
}

/** Expands from caller-provided bounds into a landscape fullscreen dialog. */
@Composable
fun ExpandableVideoPlayer(
    controller: PlayerController,
    url: String? = null,
    headers: Map<String, String> = emptyMap(),
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    autoPlay: Boolean = true,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    uiConfig: VideoPlayerUiConfig = VideoPlayerUiConfig(),
    interactionConfig: PlayerInteractionConfig = expandablePlayerInteractionConfig(),
    strings: VideoPlayerStrings = VideoPlayerStrings(),
    colors: VideoPlayerColors = VideoPlayerColors(),
    icons: VideoPlayerIcons = VideoPlayerIcons(),
    customControls: Map<String, PlayerControlContent> = emptyMap(),
    orientation: PlayerFullscreenOrientation = PlayerFullscreenOrientation.SENSOR_LANDSCAPE,
    immersive: Boolean = true,
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
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var orientationBeforeFullscreen by rememberSaveable { mutableIntStateOf(ORIENTATION_NOT_CAPTURED) }
    var locked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isFullscreen) {
        if (!isFullscreen) locked = false
    }

    val content: @Composable (Modifier, Boolean) -> Unit = { playerModifier, fullscreen ->
        val navigateBack: (() -> Unit)? = if (fullscreen) {
            { if (!locked) onFullscreenChange(false) }
        } else {
            onBack
        }
        VideoPlayer(
            controller = controller,
            url = url,
            headers = headers,
            modifier = playerModifier,
            autoPlay = autoPlay,
            interactionConfig = interactionConfig,
            interactionEnabled = !locked,
        ) { player ->
            BuiltInPlayerPresentation(
                player = player,
                preset = BuiltInPlayerPreset.EXPANDABLE,
                title = title,
                onBack = navigateBack,
                onFullscreenChangeRequest = onFullscreenChange,
                isFullscreen = fullscreen,
                isLocked = locked,
                onLockedChange = { locked = it },
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

    if (isFullscreen) {
        ControlledLockActivityOrientation(
            activity = activity,
            orientation = orientation,
            orientationBeforeFullscreen = orientationBeforeFullscreen,
            onOrientationCaptured = { orientationBeforeFullscreen = it },
            onOrientationRestored = { orientationBeforeFullscreen = ORIENTATION_NOT_CAPTURED },
        )
        Dialog(
            onDismissRequest = { if (!locked) onFullscreenChange(false) },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            ControlledConfigureFullscreenWindow(immersive)
            content(Modifier.fillMaxSize().background(Color.Black), true)
        }
    } else {
        content(modifier, false)
    }
}

fun expandablePlayerInteractionConfig(): PlayerInteractionConfig = PlayerInteractionConfig()

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
        val previous = if (orientationBeforeFullscreen == ORIENTATION_NOT_CAPTURED) {
            activity.requestedOrientation.also(onOrientationCaptured)
        } else {
            orientationBeforeFullscreen
        }
        val requested = when (orientation) {
            PlayerFullscreenOrientation.SENSOR_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PlayerFullscreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            PlayerFullscreenOrientation.UNCHANGED -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        activity.requestedOrientation = requested
        onDispose {
            if (!activity.isChangingConfigurations && activity.requestedOrientation == requested) {
                activity.requestedOrientation = previous
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
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            if (immersive) controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val ORIENTATION_NOT_CAPTURED = Int.MIN_VALUE
