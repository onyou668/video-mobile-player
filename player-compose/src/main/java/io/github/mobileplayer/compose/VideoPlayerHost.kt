package io.github.mobileplayer.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.mobileplayer.core.PlaybackState
import io.github.mobileplayer.core.PlayerPresentationCapabilities
import io.github.mobileplayer.core.PlayerPresentationController
import io.github.mobileplayer.core.PlayerPresentationMode
import io.github.mobileplayer.core.PlayerPresentationRequest
import io.github.mobileplayer.core.VideoPlayerController
import io.github.mobileplayer.core.VideoSource
import kotlinx.coroutines.flow.collect

/** Orientation applied while [VideoPlayerHost] presents its fullscreen dialog. */
enum class PlayerFullscreenOrientation {
    /** Allow either landscape direction according to the device sensor. */
    SENSOR_LANDSCAPE,

    /** Lock to the device's primary landscape direction. */
    LANDSCAPE,

    /** Do not change the host Activity orientation. */
    UNCHANGED,
}

/**
 * Hosts one playback session in either a caller-sized page region or a
 * fullscreen dialog. Details, episode lists and other business UI stay in the
 * caller; only the video surface and controls move between presentation modes.
 *
 * The same [controller] is used in both modes, so playback position, play/pause
 * intent, selected engine, volume and speed survive every UI migration.
 */
@Composable
fun VideoPlayerHost(
    controller: VideoPlayerController,
    source: VideoSource? = null,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f),
    presentationController: PlayerPresentationController = rememberPlayerPresentationController(
        capabilities = PlayerPresentationCapabilities(
            fullscreen = true,
            floating = false,
            close = false,
        ),
    ),
    autoPlay: Boolean = true,
    uiConfig: VideoPlayerUiConfig = VideoPlayerUiConfig(),
    strings: VideoPlayerStrings = VideoPlayerStrings(),
    colors: VideoPlayerColors = VideoPlayerColors(),
    fullscreenOrientation: PlayerFullscreenOrientation = PlayerFullscreenOrientation.SENSOR_LANDSCAPE,
    immersiveFullscreen: Boolean = true,
    onExternalPresentationRequest: ((PlayerPresentationRequest) -> Unit)? = null,
    overlayContent: @Composable BoxScope.(PlaybackState) -> Unit = {},
) {
    val presentation by presentationController.state.collectAsStateWithLifecycle()
    val currentExternalRequestHandler by rememberUpdatedState(onExternalPresentationRequest)
    var orientationBeforeFullscreen by rememberSaveable {
        mutableIntStateOf(ORIENTATION_NOT_CAPTURED)
    }

    LaunchedEffect(presentationController) {
        presentationController.requests.collect { request ->
            when (request) {
                is PlayerPresentationRequest.ChangeMode -> when (request.to) {
                    PlayerPresentationMode.INLINE,
                    PlayerPresentationMode.FULLSCREEN,
                    -> presentationController.confirmMode(request.to)

                    PlayerPresentationMode.FLOATING -> {
                        val handler = currentExternalRequestHandler
                        if (handler == null) {
                            presentationController.rejectPendingRequest(
                                "No floating presentation adapter was configured",
                            )
                        } else {
                            handler(request)
                        }
                    }
                }

                PlayerPresentationRequest.Close -> {
                    val handler = currentExternalRequestHandler
                    if (handler == null) {
                        presentationController.rejectPendingRequest(
                            "No close presentation adapter was configured",
                        )
                    } else {
                        handler(request)
                    }
                }
            }
        }
    }

    when (presentation.currentMode) {
        PlayerPresentationMode.INLINE -> VideoPlayer(
            controller = controller,
            source = source,
            modifier = modifier,
            autoPlay = autoPlay,
            uiConfig = uiConfig,
            strings = strings,
            colors = colors,
            presentationController = presentationController,
            overlayContent = overlayContent,
        )

        PlayerPresentationMode.FULLSCREEN -> FullscreenVideoPlayer(
            controller = controller,
            source = source,
            presentationController = presentationController,
            autoPlay = autoPlay,
            uiConfig = uiConfig,
            strings = strings,
            colors = colors,
            orientation = fullscreenOrientation,
            immersive = immersiveFullscreen,
            orientationBeforeFullscreen = orientationBeforeFullscreen,
            onOrientationCaptured = { orientationBeforeFullscreen = it },
            onOrientationRestored = { orientationBeforeFullscreen = ORIENTATION_NOT_CAPTURED },
            overlayContent = overlayContent,
        )

        PlayerPresentationMode.FLOATING -> Box(
            modifier = modifier.background(colors.background),
        )
    }
}

/** Convenience overload for the common network-video + title use case. */
@Composable
fun VideoPlayerHost(
    controller: VideoPlayerController,
    url: String,
    title: String? = null,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f),
    headers: Map<String, String> = emptyMap(),
    contentCachePolicy: VideoSource.ContentCachePolicy = VideoSource.ContentCachePolicy.AUTO,
    presentationController: PlayerPresentationController = rememberPlayerPresentationController(
        capabilities = PlayerPresentationCapabilities(
            fullscreen = true,
            floating = false,
            close = false,
        ),
    ),
    autoPlay: Boolean = true,
    uiConfig: VideoPlayerUiConfig = VideoPlayerUiConfig(),
    strings: VideoPlayerStrings = VideoPlayerStrings(),
    colors: VideoPlayerColors = VideoPlayerColors(),
    fullscreenOrientation: PlayerFullscreenOrientation = PlayerFullscreenOrientation.SENSOR_LANDSCAPE,
    immersiveFullscreen: Boolean = true,
    onExternalPresentationRequest: ((PlayerPresentationRequest) -> Unit)? = null,
    overlayContent: @Composable BoxScope.(PlaybackState) -> Unit = {},
) {
    val source = remember(url, title, headers, contentCachePolicy) {
        VideoSource.network(
            url = url,
            title = title,
            headers = headers,
            contentCachePolicy = contentCachePolicy,
        )
    }
    VideoPlayerHost(
        controller = controller,
        source = source,
        modifier = modifier,
        presentationController = presentationController,
        autoPlay = autoPlay,
        uiConfig = uiConfig,
        strings = strings,
        colors = colors,
        fullscreenOrientation = fullscreenOrientation,
        immersiveFullscreen = immersiveFullscreen,
        onExternalPresentationRequest = onExternalPresentationRequest,
        overlayContent = overlayContent,
    )
}

@Composable
private fun FullscreenVideoPlayer(
    controller: VideoPlayerController,
    source: VideoSource?,
    presentationController: PlayerPresentationController,
    autoPlay: Boolean,
    uiConfig: VideoPlayerUiConfig,
    strings: VideoPlayerStrings,
    colors: VideoPlayerColors,
    orientation: PlayerFullscreenOrientation,
    immersive: Boolean,
    orientationBeforeFullscreen: Int,
    onOrientationCaptured: (Int) -> Unit,
    onOrientationRestored: () -> Unit,
    overlayContent: @Composable BoxScope.(PlaybackState) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    LockActivityOrientation(
        activity = activity,
        orientation = orientation,
        orientationBeforeFullscreen = orientationBeforeFullscreen,
        onOrientationCaptured = onOrientationCaptured,
        onOrientationRestored = onOrientationRestored,
    )

    Dialog(
        onDismissRequest = { presentationController.requestInline() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ConfigureFullscreenWindow(immersive)
        VideoPlayer(
            controller = controller,
            source = source,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            autoPlay = autoPlay,
            uiConfig = uiConfig,
            strings = strings,
            colors = colors,
            presentationController = presentationController,
            overlayContent = overlayContent,
        )
    }
}

@Composable
private fun LockActivityOrientation(
    activity: Activity?,
    orientation: PlayerFullscreenOrientation,
    orientationBeforeFullscreen: Int,
    onOrientationCaptured: (Int) -> Unit,
    onOrientationRestored: () -> Unit,
) {
    val currentOnOrientationCaptured by rememberUpdatedState(onOrientationCaptured)
    val currentOnOrientationRestored by rememberUpdatedState(onOrientationRestored)

    DisposableEffect(activity, orientation) {
        if (activity == null || orientation == PlayerFullscreenOrientation.UNCHANGED) {
            return@DisposableEffect onDispose {}
        }

        val previousOrientation = if (orientationBeforeFullscreen == ORIENTATION_NOT_CAPTURED) {
            activity.requestedOrientation.also(currentOnOrientationCaptured)
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
                currentOnOrientationRestored()
            }
        }
    }
}

@Composable
private fun ConfigureFullscreenWindow(immersive: Boolean) {
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
