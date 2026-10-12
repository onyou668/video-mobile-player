package io.github.mobileplayer.compose

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mobileplayer.core.PlaybackState
import io.github.mobileplayer.core.PlaybackStatus
import io.github.mobileplayer.core.PlayerEngineType
import io.github.mobileplayer.core.PlayerPresentationController
import io.github.mobileplayer.core.PlayerPresentationMode
import io.github.mobileplayer.core.PlayerPresentationState
import io.github.mobileplayer.core.VideoPlayerController
import io.github.mobileplayer.core.VideoSource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Complete Compose video surface and controls. The caller owns layout sizing;
 * a 16:9 aspect ratio is a sensible default at the call site.
 */
@Composable
fun VideoPlayer(
    controller: VideoPlayerController,
    modifier: Modifier = Modifier,
    source: VideoSource? = null,
    autoPlay: Boolean = true,
    uiConfig: VideoPlayerUiConfig = VideoPlayerUiConfig(),
    strings: VideoPlayerStrings = VideoPlayerStrings(),
    colors: VideoPlayerColors = VideoPlayerColors(),
    presentationController: PlayerPresentationController? = null,
    onFullscreenRequest: (() -> Unit)? = null,
    onFullscreenChangeRequest: ((Boolean) -> Unit)? = null,
    isFullscreen: Boolean = false,
    overlayContent: @Composable BoxScope.(PlaybackState) -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val presentationState = presentationController
        ?.state
        ?.collectAsStateWithLifecycle()
        ?.value
    val lifecycleOwner = LocalLifecycleOwner.current
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }

    fun revealControls() {
        controlsVisible = true
        controlsInteraction++
    }

    LaunchedEffect(source, autoPlay) {
        source?.let { controller.setMediaIfChanged(it, autoPlay) }
    }

    DisposableEffect(controller, lifecycleOwner, presentationController) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.onHostStart()
                Lifecycle.Event.ON_STOP -> controller.onHostStop(
                    keepPlaying = presentationController
                        ?.state
                        ?.value
                        ?.displayedMode == PlayerPresentationMode.FLOATING,
                )
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(controlsVisible, state.isPlaying, controlsInteraction) {
        if (controlsVisible && state.isPlaying) {
            delay(uiConfig.controlsAutoHideMs)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .background(colors.background)
            .pointerInput(Unit) {
                detectTapGestures { controlsVisible = !controlsVisible }
            },
    ) {
        VideoSurface(
            controller = controller,
            aspectRatio = state.videoAspectRatio,
            modifier = Modifier.align(Alignment.Center),
        )

        when (state.status) {
            PlaybackStatus.PREPARING,
            PlaybackStatus.BUFFERING,
            -> LoadingOverlay(
                state = state,
                uiConfig = uiConfig,
                strings = strings,
                colors = colors,
                modifier = Modifier
                    .align(Alignment.Center)
            )

            PlaybackStatus.ERROR -> ErrorOverlay(
                message = state.error?.message ?: strings.unknownError,
                strings = strings,
                colors = colors,
                onRetry = controller::retry,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> Unit
        }

        overlayContent(state)

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.fillMaxSize(),
        ) {
            PlayerControls(
                state = state,
                uiConfig = uiConfig,
                strings = strings,
                colors = colors,
                presentationState = presentationState,
                onInteraction = ::revealControls,
                onTogglePlay = controller::togglePlayPause,
                onSeek = controller::seekTo,
                onSeekBy = controller::seekBy,
                onVolume = controller::setVolume,
                onSpeed = controller::setPlaybackSpeed,
                onSwitchEngine = controller::switchEngine,
                onRequestPresentationMode = { mode ->
                    presentationController?.requestMode(mode)
                },
                onClose = { presentationController?.requestClose() },
                onFullscreenRequest = onFullscreenRequest,
                onFullscreenChangeRequest = onFullscreenChangeRequest,
                isFullscreen = isFullscreen,
            )
        }
    }
}

@Composable
private fun LoadingOverlay(
    state: PlaybackState,
    uiConfig: VideoPlayerUiConfig,
    strings: VideoPlayerStrings,
    colors: VideoPlayerColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(42.dp),
            color = colors.accent,
            trackColor = Color.White.copy(alpha = 0.2f),
        )
        if (
            uiConfig.showNetworkSpeedWhileLoading &&
            state.source?.type == VideoSource.Type.NETWORK
        ) {
            Text(
                text = "${strings.loading}  ${formatNetworkSpeed(state.networkSpeedBytesPerSecond)}",
                color = colors.controlsContent,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun VideoSurface(
    controller: VideoPlayerController,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        controller.setSurface(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        controller.setSurface(holder.surface)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        controller.clearSurface(holder.surface)
                    }
                })
            }
        },
        modifier = modifier.fitInside(aspectRatio),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerControls(
    state: PlaybackState,
    uiConfig: VideoPlayerUiConfig,
    strings: VideoPlayerStrings,
    colors: VideoPlayerColors,
    presentationState: PlayerPresentationState?,
    onInteraction: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onSwitchEngine: (PlayerEngineType) -> Unit,
    onRequestPresentationMode: (PlayerPresentationMode) -> Unit,
    onClose: () -> Unit,
    onFullscreenRequest: (() -> Unit)?,
    onFullscreenChangeRequest: ((Boolean) -> Unit)?,
    isFullscreen: Boolean,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.controlsBackground)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.source?.title.orEmpty(),
                modifier = Modifier.weight(1f),
                color = colors.controlsContent,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (uiConfig.showEngineSwitcher) {
                SingleChoiceSegmentedButtonRow {
                    PlayerEngineType.entries.forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = state.engineType == type,
                            onClick = {
                                onInteraction()
                                onSwitchEngine(type)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, PlayerEngineType.entries.size),
                            label = { Text(if (type == PlayerEngineType.MEDIA3) "Exo" else "Ijk") },
                        )
                    }
                }
            }
            if (
                uiConfig.showPresentationControls &&
                presentationState?.capabilities?.close == true
            ) {
                IconButton(
                    onClick = {
                        onInteraction()
                        onClose()
                    },
                    enabled = !presentationState.hasPendingRequest && !presentationState.isClosed,
                ) {
                    Icon(Icons.Default.Close, strings.close, tint = colors.controlsContent)
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    onInteraction()
                    onSeekBy(-uiConfig.skipDurationMs)
                },
            ) {
                Icon(Icons.Default.Replay10, strings.rewind, tint = colors.controlsContent)
            }
            IconButton(
                onClick = {
                    onInteraction()
                    onTogglePlay()
                },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) strings.pause else strings.play,
                    tint = colors.controlsContent,
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(
                onClick = {
                    onInteraction()
                    onSeekBy(uiConfig.skipDurationMs)
                },
            ) {
                Icon(Icons.Default.Forward10, strings.forward, tint = colors.controlsContent)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.controlsBackground)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .align(Alignment.BottomCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    onInteraction()
                    onVolume(if (state.volume > 0f) 0f else 1f)
                },
            ) {
                Icon(
                    imageVector = if (state.volume > 0f) {
                        Icons.AutoMirrored.Filled.VolumeUp
                    } else {
                        Icons.AutoMirrored.Filled.VolumeOff
                    },
                    contentDescription = if (state.volume > 0f) strings.mute else strings.unmute,
                    tint = colors.controlsContent,
                )
            }
            Text(
                text = formatTime(if (scrubbing) scrubPosition.toLong() else state.positionMs),
                color = colors.controlsContent,
                style = MaterialTheme.typography.labelSmall,
            )
            Box(modifier = Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { state.bufferedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.Center),
                    color = colors.buffered,
                    trackColor = Color.White.copy(alpha = 0.18f),
                )
                Slider(
                    value = if (scrubbing) scrubPosition else state.positionMs.toFloat(),
                    onValueChange = {
                        scrubbing = true
                        scrubPosition = it
                        onInteraction()
                    },
                    onValueChangeFinished = {
                        onSeek(scrubPosition.toLong())
                        scrubbing = false
                        onInteraction()
                    },
                    valueRange = 0f..state.durationMs.coerceAtLeast(1L).toFloat(),
                    enabled = state.durationMs > 0L,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = Color.Transparent,
                        disabledThumbColor = colors.buffered,
                        disabledActiveTrackColor = colors.buffered,
                        disabledInactiveTrackColor = Color.Transparent,
                    ),
                )
            }
            Text(
                text = formatTime(state.durationMs),
                color = colors.controlsContent,
                style = MaterialTheme.typography.labelSmall,
            )
            PlaybackSpeedMenu(
                speed = state.playbackSpeed,
                speeds = uiConfig.availableSpeeds,
                strings = strings,
                color = colors.controlsContent,
                onInteraction = onInteraction,
                onSpeed = onSpeed,
            )
            if (uiConfig.showPresentationControls && presentationState != null) {
                PresentationControls(
                    state = presentationState,
                    strings = strings,
                    color = colors.controlsContent,
                    onInteraction = onInteraction,
                    onRequestMode = onRequestPresentationMode,
                )
            } else if (onFullscreenChangeRequest != null || (!isFullscreen && onFullscreenRequest != null)) {
                IconButton(
                    onClick = {
                        onInteraction()
                        onFullscreenChangeRequest?.invoke(!isFullscreen)
                            ?: onFullscreenRequest?.invoke()
                    },
                ) {
                    Icon(
                        imageVector = if (isFullscreen) {
                            Icons.Default.FullscreenExit
                        } else {
                            Icons.Default.Fullscreen
                        },
                        contentDescription = if (isFullscreen) strings.portrait else strings.fullscreen,
                        tint = colors.controlsContent,
                    )
                }
            }
        }
    }
}

@Composable
private fun PresentationControls(
    state: PlayerPresentationState,
    strings: VideoPlayerStrings,
    color: Color,
    onInteraction: () -> Unit,
    onRequestMode: (PlayerPresentationMode) -> Unit,
) {
    val mode = state.displayedMode
    val enabled = !state.hasPendingRequest && !state.isClosed

    if (state.capabilities.floating && mode != PlayerPresentationMode.FLOATING) {
        IconButton(
            onClick = {
                onInteraction()
                onRequestMode(PlayerPresentationMode.FLOATING)
            },
            enabled = enabled,
        ) {
            Icon(Icons.Default.PictureInPictureAlt, strings.minimize, tint = color)
        }
    }
    if (mode != PlayerPresentationMode.INLINE) {
        IconButton(
            onClick = {
                onInteraction()
                onRequestMode(PlayerPresentationMode.INLINE)
            },
            enabled = enabled,
        ) {
            Icon(Icons.Default.StayCurrentPortrait, strings.portrait, tint = color)
        }
    }
    if (state.capabilities.fullscreen && mode != PlayerPresentationMode.FULLSCREEN) {
        IconButton(
            onClick = {
                onInteraction()
                onRequestMode(PlayerPresentationMode.FULLSCREEN)
            },
            enabled = enabled,
        ) {
            Icon(Icons.Default.Fullscreen, strings.fullscreen, tint = color)
        }
    }
}

@Composable
private fun PlaybackSpeedMenu(
    speed: Float,
    speeds: List<Float>,
    strings: VideoPlayerStrings,
    color: Color,
    onInteraction: () -> Unit,
    onSpeed: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = {
                onInteraction()
                expanded = true
            },
        ) {
            Text(text = "${speed.formatSpeed()}x", color = color)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            speeds.distinct().sorted().forEach { item ->
                DropdownMenuItem(
                    text = { Text("${item.formatSpeed()}x") },
                    onClick = {
                        expanded = false
                        onInteraction()
                        onSpeed(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorOverlay(
    message: String,
    strings: VideoPlayerStrings,
    colors: VideoPlayerColors,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = colors.error,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text(strings.retry)
        }
    }
}

private val PlaybackState.videoAspectRatio: Float
    get() = if (videoWidth > 0 && videoHeight > 0) {
        videoWidth.toFloat() / videoHeight.toFloat()
    } else {
        DEFAULT_VIDEO_ASPECT_RATIO
    }

private fun Modifier.fitInside(aspectRatio: Float): Modifier = layout { measurable, constraints ->
    val maxWidth = constraints.maxWidth
    val maxHeight = constraints.maxHeight
    val safeRatio = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_VIDEO_ASPECT_RATIO
    val width: Int
    val height: Int
    if (maxWidth.toFloat() / maxHeight.toFloat() > safeRatio) {
        height = maxHeight
        width = (height * safeRatio).roundToInt().coerceAtMost(maxWidth)
    } else {
        width = maxWidth
        height = (width / safeRatio).roundToInt().coerceAtMost(maxHeight)
    }
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun formatNetworkSpeed(bytesPerSecond: Long): String {
    val speed = bytesPerSecond.coerceAtLeast(0L)
    return when {
        speed >= BYTES_PER_MEGABYTE -> "%.1f MB/s".format(speed.toDouble() / BYTES_PER_MEGABYTE)
        speed >= BYTES_PER_KILOBYTE -> "%.1f KB/s".format(speed.toDouble() / BYTES_PER_KILOBYTE)
        else -> "$speed B/s"
    }
}

private fun Float.formatSpeed(): String = if (this % 1f == 0f) {
    toInt().toString()
} else {
    toString()
}

private const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f
private const val BYTES_PER_KILOBYTE = 1_024L
private const val BYTES_PER_MEGABYTE = 1_024L * 1_024L
