package io.github.mobileplayer.compose

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mobileplayer.core.PlaybackPhase
import io.github.mobileplayer.core.PlayerController
import io.github.mobileplayer.core.PlayerState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Raw Compose playback host. It renders video, owns host interaction behavior,
 * and exposes read-only state plus Controller-backed commands through [PlayerScope].
 */
@Composable
fun VideoPlayer(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    url: String? = null,
    headers: Map<String, String> = emptyMap(),
    autoPlay: Boolean = true,
    interactionConfig: PlayerInteractionConfig = PlayerInteractionConfig(),
    interactionEnabled: Boolean = true,
    content: @Composable BoxScope.(PlayerScope) -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionKey by remember { mutableIntStateOf(0) }
    var interactionDepth by remember { mutableIntStateOf(0) }
    var gestureFeedback by remember { mutableStateOf<PlayerGestureFeedback?>(null) }

    fun revealControls() {
        controlsVisible = true
        interactionKey++
    }

    val actions = PlayerActions(
        play = { if (interactionEnabled) controller.play() },
        pause = { if (interactionEnabled) controller.pause() },
        togglePlay = { if (interactionEnabled) controller.togglePlayPause() },
        seekTo = { revealControls(); if (interactionEnabled) controller.seekTo(it) },
        seekBy = { if (interactionEnabled) controller.seekBy(it) },
        setPlayerVolume = { revealControls(); if (interactionEnabled) controller.setPlayerVolume(it) },
        setSystemVolume = { if (interactionEnabled) controller.setSystemVolume(it) },
        setScreenBrightness = { if (interactionEnabled) controller.setScreenBrightness(it) },
        setPlaybackSpeed = { revealControls(); if (interactionEnabled) controller.setPlaybackSpeed(it) },
        selectTrack = { type, id ->
            revealControls()
            if (interactionEnabled) controller.selectTrack(type, id)
        },
        setSubtitlesEnabled = {
            revealControls()
            if (interactionEnabled) controller.setSubtitlesEnabled(it)
        },
        switchEngine = {
            revealControls()
            if (interactionEnabled) controller.switchEngine(it)
        },
        goToLiveEdge = {
            revealControls()
            if (interactionEnabled) controller.goToLiveEdge()
        },
        retry = {
            revealControls()
            if (interactionEnabled) controller.retry()
        },
        beginInteraction = {
            interactionDepth++
            revealControls()
        },
        endInteraction = {
            interactionDepth = (interactionDepth - 1).coerceAtLeast(0)
            revealControls()
        },
        showControls = ::revealControls,
        hideControls = { controlsVisible = false },
    )
    val playerScope = PlayerScope(
        state = state,
        availableEngines = controller.availableEngines,
        actions = actions,
        controlsVisible = controlsVisible,
        gestureFeedback = gestureFeedback,
    )

    LaunchedEffect(url, headers, autoPlay) {
        controlsVisible = true
        url?.let { controller.setVideoUrl(it, headers, autoPlay) }
    }

    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.onHostStart()
                Lifecycle.Event.ON_STOP -> controller.onHostStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        controlsVisible,
        interactionKey,
        interactionDepth,
        state.phase,
        interactionEnabled,
    ) {
        if (
            controlsVisible && interactionDepth == 0 &&
            state.phase != PlaybackPhase.ERROR && interactionConfig.controlsAutoHideMs > 0L
        ) {
            delay(interactionConfig.controlsAutoHideMs)
            controlsVisible = false
        }
    }

    LaunchedEffect(gestureFeedback) {
        val current = gestureFeedback ?: return@LaunchedEffect
        delay(GESTURE_FEEDBACK_DURATION_MS)
        if (gestureFeedback == current) gestureFeedback = null
    }

    val wideMedia = state.videoWidth > state.videoHeight && state.videoHeight > 0
    val enableEnvironmentGestures = wideMedia && interactionEnabled

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(interactionEnabled, state.phase, interactionConfig.tapToToggleControls) {
                if (!interactionConfig.tapToToggleControls) return@pointerInput
                detectTapGestures {
                    if (state.phase != PlaybackPhase.ERROR) {
                        controlsVisible = !controlsVisible
                        interactionKey++
                    }
                }
            }
            .playerEnvironmentGestures(
                enabled = enableEnvironmentGestures,
                enableBrightness = interactionConfig.enableBrightnessGesture,
                enableVolume = interactionConfig.enableVolumeGesture,
                unmuteOnVolumeGesture = interactionConfig.unmuteOnVolumeGesture,
                state = state,
                actions = actions,
                onFeedback = { gestureFeedback = it },
            ),
    ) {
        PlayerSurface(
            controller = controller,
            aspectRatio = state.videoAspectRatio,
            modifier = Modifier.align(Alignment.Center),
        )
        content(playerScope)
    }
}

/** Video rendering surface for fully custom UIs that still use only [PlayerController]. */
@Composable
fun PlayerSurface(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) =
                        controller.attachVideoSurface(holder.surface)

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                        controller.attachVideoSurface(holder.surface)

                    override fun surfaceDestroyed(holder: SurfaceHolder) =
                        controller.detachVideoSurface(holder.surface)
                })
            }
        },
        modifier = modifier.fitInside(aspectRatio),
    )
}

private fun Modifier.playerEnvironmentGestures(
    enabled: Boolean,
    enableBrightness: Boolean,
    enableVolume: Boolean,
    unmuteOnVolumeGesture: Boolean,
    state: PlayerState,
    actions: PlayerActions,
    onFeedback: (PlayerGestureFeedback) -> Unit,
): Modifier = pointerInput(
    enabled,
    enableBrightness,
    enableVolume,
    unmuteOnVolumeGesture,
    state.environment,
    state.playerVolume,
) {
    if (!enabled) return@pointerInput
    var gestureType: PlayerGestureType? = null
    var startValue = 0f
    var drag = 0f
    detectVerticalDragGestures(
        onDragStart = { offset ->
            val insideVideoGestureArea = offset.y >= GESTURE_TOP_EXCLUSION.toPx() &&
                offset.y <= size.height - GESTURE_BOTTOM_EXCLUSION.toPx()
            gestureType = when {
                !insideVideoGestureArea -> null
                offset.x <= size.width * BRIGHTNESS_ZONE_END && enableBrightness -> PlayerGestureType.BRIGHTNESS
                offset.x >= size.width * VOLUME_ZONE_START && enableVolume -> PlayerGestureType.VOLUME
                else -> null
            }
            startValue = when (gestureType) {
                PlayerGestureType.BRIGHTNESS -> state.environment.screenBrightness ?: 0.5f
                PlayerGestureType.VOLUME -> {
                    if (unmuteOnVolumeGesture && state.playerVolume <= 0f) actions.setPlayerVolume(1f)
                    state.environment.systemVolume ?: 0.5f
                }
                null -> 0f
            }
            drag = 0f
        },
        onVerticalDrag = { change, amount ->
            val type = gestureType ?: return@detectVerticalDragGestures
            change.consume()
            drag += amount
            val value = (startValue - drag / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
            when (type) {
                PlayerGestureType.BRIGHTNESS -> actions.setScreenBrightness(value)
                PlayerGestureType.VOLUME -> actions.setSystemVolume(value)
            }
            onFeedback(PlayerGestureFeedback(type, value))
        },
    )
}

private fun Modifier.fitInside(aspectRatio: Float): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight || aspectRatio <= 0f) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val maxWidth = constraints.maxWidth
    val maxHeight = constraints.maxHeight
    val widthFromHeight = (maxHeight * aspectRatio).roundToInt()
    val width: Int
    val height: Int
    if (widthFromHeight <= maxWidth) {
        width = widthFromHeight
        height = maxHeight
    } else {
        width = maxWidth
        height = (maxWidth / aspectRatio).roundToInt()
    }
    val placeable = measurable.measure(
        constraints.copy(minWidth = width, maxWidth = width, minHeight = height, maxHeight = height),
    )
    layout(maxWidth, maxHeight) {
        placeable.place((maxWidth - width) / 2, (maxHeight - height) / 2)
    }
}

private val GESTURE_TOP_EXCLUSION = 48.dp
private val GESTURE_BOTTOM_EXCLUSION = 76.dp
private const val BRIGHTNESS_ZONE_END = 0.32f
private const val VOLUME_ZONE_START = 0.88f
private const val GESTURE_FEEDBACK_DURATION_MS = 900L
