package io.github.mobileplayer.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mobileplayer.core.PlaybackError
import io.github.mobileplayer.core.PlaybackPhase
import io.github.mobileplayer.core.PlaybackType
import io.github.mobileplayer.core.PlayerState
import kotlin.math.roundToInt

/**
 * Pure layer assembler for player presentations. Full [controlsContent] replacement
 * takes precedence over the individual persistent and positional control slots.
 */
@Composable
fun BoxScope.PlayerScaffold(
    scope: PlayerScope,
    modifier: Modifier = Modifier.fillMaxSize(),
    loadingContent: @Composable BoxScope.(PlayerScope) -> Unit = {},
    errorContent: @Composable BoxScope.(PlaybackError?, PlayerScope) -> Unit = { _, _ -> },
    subtitleContent: @Composable BoxScope.(PlayerScope) -> Unit = {},
    overlayContent: @Composable BoxScope.(PlayerScope) -> Unit = {},
    persistentContent: @Composable BoxScope.(PlayerScope) -> Unit = {},
    topControlsContent: @Composable BoxScope.(PlayerScope) -> Unit = {},
    centerControlsContent: @Composable BoxScope.(PlayerScope) -> Unit = {},
    sideControlsContent: @Composable BoxScope.(PlayerScope) -> Unit = {},
    bottomControlsContent: @Composable BoxScope.(PlayerScope) -> Unit = {},
    controlsContent: (@Composable BoxScope.(PlayerScope) -> Unit)? = null,
) {
    Box(modifier) {
        val waitingForFirstFrame = scope.state.source != null && !scope.state.hasRenderedFirstFrame
        when {
            scope.state.phase == PlaybackPhase.ERROR -> errorContent(scope.state.error, scope)
            waitingForFirstFrame -> loadingContent(scope)
            scope.state.phase == PlaybackPhase.PREPARING || scope.state.phase == PlaybackPhase.BUFFERING -> {
                loadingContent(scope)
            }
            else -> Unit
        }

        subtitleContent(scope)
        overlayContent(scope)

        if (controlsContent != null) {
            AnimatedVisibility(visible = scope.controlsVisible, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) { controlsContent(scope) }
            }
        } else {
            persistentContent(scope)
            AnimatedVisibility(visible = scope.controlsVisible, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    topControlsContent(scope)
                    centerControlsContent(scope)
                    sideControlsContent(scope)
                    bottomControlsContent(scope)
                }
            }
        }
    }
}

internal enum class BuiltInPlayerPreset {
    CONTAINED,
    EXPANDABLE,
}

@Composable
internal fun BoxScope.BuiltInPlayerPresentation(
    player: PlayerScope,
    preset: BuiltInPlayerPreset,
    title: String?,
    onBack: (() -> Unit)?,
    onFullscreenChangeRequest: ((Boolean) -> Unit)?,
    isFullscreen: Boolean,
    isLocked: Boolean,
    onLockedChange: (Boolean) -> Unit,
    uiConfig: VideoPlayerUiConfig,
    strings: VideoPlayerStrings,
    colors: VideoPlayerColors,
    icons: VideoPlayerIcons,
    customControls: Map<String, PlayerControlContent>,
    overlayContent: @Composable BoxScope.(PlayerUiScope) -> Unit,
    subtitleContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)?,
    controlsContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)?,
    loadingContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)?,
    errorContent: (@Composable BoxScope.(PlaybackError, PlayerUiScope) -> Unit)?,
    persistentContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)?,
    topControlsContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)?,
    centerControlsContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)?,
    sideControlsContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)?,
    bottomControlsContent: (@Composable BoxScope.(PlayerUiScope) -> Unit)?,
) {
    val actions = PlayerUiActions(
        player = player.actions,
        back = {
            player.actions.showControls()
            if (!isLocked) onBack?.invoke()
        },
        requestFullscreen = {
            player.actions.showControls()
            if (!isLocked) onFullscreenChangeRequest?.invoke(it)
        },
        setLocked = {
            onLockedChange(it)
            player.actions.showControls()
        },
    )
    val scope = PlayerUiScope(
        player = player,
        title = title,
        actions = actions,
        strings = strings,
        colors = colors,
        icons = icons,
        isFullscreen = isFullscreen,
        canNavigateBack = onBack != null,
        canRequestFullscreen = onFullscreenChangeRequest != null,
        isLocked = isLocked,
        canLock = preset == BuiltInPlayerPreset.EXPANDABLE && isFullscreen,
    )
    val usesConfiguredLayout = uiConfig.controls != VideoPlayerControlLayout()

    PlayerScaffold(
        scope = player,
        loadingContent = {
            if (loadingContent != null) loadingContent(scope) else DefaultLoading(scope, uiConfig)
        },
        errorContent = { error, _ ->
            if (errorContent != null && error != null) errorContent(error, scope) else DefaultError(scope)
        },
        subtitleContent = {
            if (subtitleContent != null) subtitleContent(scope) else DefaultSubtitles(scope)
        },
        overlayContent = {
            overlayContent(scope)
            if (player.gestureFeedback != null && !isLocked) {
                GestureFeedbackOverlay(player.gestureFeedback, colors)
            }
        },
        controlsContent = controlsContent?.let { replacement ->
            { replacement(scope) }
        },
        persistentContent = {
            when {
                controlsContent != null -> Unit
                isLocked -> PersistentTimeline(
                    scope = scope,
                    controlsVisible = false,
                    enabled = false,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                persistentContent != null -> persistentContent(scope)
                !usesConfiguredLayout -> {
                    CenterPlaybackControl(scope, uiConfig)
                    PersistentTimeline(
                        scope = scope,
                        controlsVisible = player.controlsVisible,
                        enabled = true,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        },
        topControlsContent = {
            when {
                isLocked -> UnlockControl(scope)
                topControlsContent != null -> topControlsContent(scope)
                usesConfiguredLayout -> ConfiguredControlRow(
                    controls = uiConfig.controls.top,
                    scope = scope,
                    config = uiConfig,
                    customControls = customControls,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(scope.colors.controlsBackground)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                else -> TopControls(scope)
            }
        },
        centerControlsContent = {
            when {
                isLocked -> Unit
                centerControlsContent != null -> centerControlsContent(scope)
                usesConfiguredLayout -> ConfiguredControlRow(
                    controls = uiConfig.controls.center,
                    scope = scope,
                    config = uiConfig,
                    customControls = customControls,
                    modifier = Modifier.align(Alignment.Center),
                    arrangement = Arrangement.spacedBy(18.dp),
                )
            }
        },
        sideControlsContent = {
            when {
                isLocked -> Unit
                sideControlsContent != null -> sideControlsContent(scope)
                !usesConfiguredLayout && shouldShowSkip(scope.state, uiConfig) -> SkipControls(
                    scope = scope,
                    config = uiConfig,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            end = if (preset == BuiltInPlayerPreset.EXPANDABLE) {
                                if (scope.isFullscreen) 112.dp else 56.dp
                            } else {
                                12.dp
                            },
                        ),
                )
            }
        },
        bottomControlsContent = {
            when {
                isLocked -> Unit
                bottomControlsContent != null -> bottomControlsContent(scope)
                usesConfiguredLayout -> ConfiguredControlRow(
                    controls = uiConfig.controls.bottom,
                    scope = scope,
                    config = uiConfig,
                    customControls = customControls,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(scope.colors.controlsBackground)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                else -> BottomActionControls(
                    scope = scope,
                    config = uiConfig,
                    showSpeed = true,
                    showFullscreen = preset == BuiltInPlayerPreset.EXPANDABLE,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = timelineBottomPadding(scope.state)),
                )
            }
        },
    )
}

@Composable
private fun BoxScope.DefaultLoading(scope: PlayerUiScope, config: VideoPlayerUiConfig) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (!scope.state.hasRenderedFirstFrame) Color.Black else Color.Transparent),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(6.dp))
                .background(scope.colors.controlsBackground)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp),
            )
            Text(scope.strings.loading, color = scope.colors.controlsContent)
            if (config.showNetworkSpeedWhileLoading && scope.state.networkSpeedBytesPerSecond > 0L) {
                Text(
                    formatNetworkSpeed(scope.state.networkSpeedBytesPerSecond),
                    color = scope.colors.secondaryContent,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.DefaultError(scope: PlayerUiScope) {
    val error = scope.state.error
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .clip(RoundedCornerShape(6.dp))
            .background(scope.colors.controlsBackground)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = error?.message ?: scope.strings.unknownError,
            color = scope.colors.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (error?.isRetryable == true && !scope.isLocked) {
            TextButton(onClick = scope.actions.retry) {
                Icon(scope.icons.retry, contentDescription = null)
                Text(scope.strings.retry)
            }
        }
    }
}

@Composable
private fun BoxScope.DefaultSubtitles(scope: PlayerUiScope) {
    if (!scope.state.tracks.subtitlesEnabled || scope.state.subtitleCues.isEmpty()) return
    Text(
        text = scope.state.subtitleCues.joinToString("\n") { it.text },
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 24.dp, vertical = 82.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.68f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun BoxScope.UnlockControl(scope: PlayerUiScope) {
    PlayerIconButton(
        icon = scope.icons.unlock,
        description = scope.strings.unlock,
        color = scope.colors.controlsContent,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(12.dp)
            .clip(CircleShape)
            .background(scope.colors.controlsBackground),
        onClick = { scope.actions.setLocked(false) },
    )
}

@Composable
private fun BoxScope.TopControls(scope: PlayerUiScope) {
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .background(scope.colors.controlsBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (scope.canNavigateBack) {
            PlayerIconButton(
                icon = scope.icons.back,
                description = scope.strings.back,
                color = scope.colors.controlsContent,
                onClick = scope.actions.back,
            )
        }
        (scope.title ?: scope.state.media.fileName)?.let { title ->
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = scope.colors.controlsContent,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } ?: Box(Modifier.weight(1f))
        if (scope.canLock) {
            PlayerIconButton(
                icon = scope.icons.lock,
                description = scope.strings.lock,
                color = scope.colors.controlsContent,
                onClick = { scope.actions.setLocked(true) },
            )
        }
    }
}

@Composable
private fun BoxScope.CenterPlaybackControl(scope: PlayerUiScope, config: VideoPlayerUiConfig) {
    val showStatusControl = scope.state.source != null && !scope.state.isPlaying &&
        scope.state.phase !in setOf(PlaybackPhase.PREPARING, PlaybackPhase.BUFFERING, PlaybackPhase.ERROR)
    if (!showStatusControl) return

    val ended = scope.state.phase == PlaybackPhase.ENDED
    IconButton(
        onClick = scope.actions.play,
        modifier = Modifier
            .align(Alignment.Center)
            .size(config.primaryControlSize)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.92f)),
    ) {
        Icon(
            imageVector = if (ended) scope.icons.replay else scope.icons.play,
            contentDescription = if (ended) scope.strings.replay else scope.strings.play,
            tint = Color.Black,
            modifier = Modifier.size(config.controlIconSize + 10.dp),
        )
    }
}

@Composable
private fun SkipControls(scope: PlayerUiScope, config: VideoPlayerUiConfig, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkipButton(scope, config, forward = false)
        SkipButton(scope, config, forward = true)
    }
}

@Composable
private fun SkipButton(scope: PlayerUiScope, config: VideoPlayerUiConfig, forward: Boolean) {
    IconButton(
        onClick = { scope.actions.seekBy(if (forward) config.skipDurationMs else -config.skipDurationMs) },
        modifier = Modifier
            .size(SKIP_TOUCH_SIZE)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.18f)),
    ) {
        Icon(
            imageVector = if (forward) scope.icons.forward else scope.icons.rewind,
            contentDescription = if (forward) scope.strings.forward else scope.strings.rewind,
            tint = scope.colors.controlsContent.copy(alpha = 0.68f),
            modifier = Modifier.size(SKIP_ICON_SIZE),
        )
    }
}

@Composable
private fun BoxScope.BottomActionControls(
    scope: PlayerUiScope,
    config: VideoPlayerUiConfig,
    showSpeed: Boolean,
    showFullscreen: Boolean,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scope.colors.controlsBackground)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallPlayPause(scope)
            VolumeControl(scope)
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showSpeed && scope.state.capabilities.canSetPlaybackSpeed) {
                SpeedMenu(scope, config.availableSpeeds)
            }
            if (scope.state.tracks.subtitles.isNotEmpty()) SubtitleControl(scope)
            if (showFullscreen && scope.canRequestFullscreen) FullscreenControl(scope)
        }
    }
}

@Composable
private fun SmallPlayPause(scope: PlayerUiScope) {
    val playing = scope.state.playWhenReady && scope.state.phase != PlaybackPhase.ENDED
    PlayerIconButton(
        icon = if (playing) scope.icons.pause else scope.icons.play,
        description = if (playing) scope.strings.pause else scope.strings.play,
        color = scope.colors.controlsContent,
        onClick = scope.actions.togglePlay,
    )
}

@Composable
private fun VolumeControl(scope: PlayerUiScope) {
    val muted = scope.state.playerVolume <= 0f || scope.state.environment.systemVolume == 0f
    PlayerIconButton(
        icon = if (muted) scope.icons.volumeOff else scope.icons.volumeOn,
        description = if (muted) scope.strings.unmute else scope.strings.mute,
        color = scope.colors.controlsContent,
        onClick = { scope.actions.setPlayerVolume(if (scope.state.playerVolume > 0f) 0f else 1f) },
    )
}

@Composable
private fun SubtitleControl(scope: PlayerUiScope) {
    PlayerIconButton(
        icon = scope.icons.subtitles,
        description = scope.strings.subtitles,
        color = if (scope.state.tracks.subtitlesEnabled) scope.colors.accent else scope.colors.secondaryContent,
        onClick = { scope.actions.setSubtitlesEnabled(!scope.state.tracks.subtitlesEnabled) },
    )
}

@Composable
private fun FullscreenControl(scope: PlayerUiScope) {
    PlayerIconButton(
        icon = if (scope.isFullscreen) scope.icons.exitFullscreen else scope.icons.fullscreen,
        description = if (scope.isFullscreen) scope.strings.portrait else scope.strings.fullscreen,
        color = scope.colors.controlsContent,
        onClick = { scope.actions.requestFullscreen(!scope.isFullscreen) },
    )
}

@Composable
private fun PersistentTimeline(
    scope: PlayerUiScope,
    controlsVisible: Boolean,
    enabled: Boolean,
    modifier: Modifier,
) {
    val state = scope.state
    if (!shouldShowTimeline(state)) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TIMELINE_HEIGHT)
            .background(if (controlsVisible) scope.colors.controlsBackground else Color.Black.copy(alpha = 0.2f))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}",
            color = Color.White.copy(alpha = if (controlsVisible) 0.9f else 0.72f),
            style = MaterialTheme.typography.labelSmall,
        )
        ThinTimeline(scope, controlsVisible, enabled, Modifier.weight(1f))
    }
}

@Composable
private fun ThinTimeline(
    scope: PlayerUiScope,
    controlsVisible: Boolean,
    enabled: Boolean,
    modifier: Modifier,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    val duration = scope.state.durationMs
    val canSeek = enabled && scope.state.capabilities.canSeek && duration > 0L
    val played = if (scrubbing) scrubFraction else scope.state.progress
    val buffered = scope.state.bufferedProgress.coerceAtLeast(played)

    Canvas(
        modifier = modifier
            .height(24.dp)
            .pointerInput(canSeek, duration) {
                if (!canSeek) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    scope.actions.beginInteraction()
                    scrubbing = true
                    fun update(positionX: Float) {
                        scrubFraction = (positionX / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                    update(down.position.x)
                    try {
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            update(change.position.x)
                            if (change.position != change.previousPosition) change.consume()
                            pressed = change.pressed
                        }
                        scope.actions.seekTo((duration * scrubFraction).toLong())
                    } finally {
                        scrubbing = false
                        scope.actions.endInteraction()
                    }
                }
            },
    ) {
        val y = size.height / 2f
        val stroke = 2.dp.toPx()
        drawLine(scope.colors.timelineUnplayed, Offset(0f, y), Offset(size.width, y), stroke)
        drawLine(scope.colors.buffered, Offset(0f, y), Offset(size.width * buffered, y), stroke)
        drawLine(scope.colors.timelinePlayed, Offset(0f, y), Offset(size.width * played, y), stroke)
        drawCircle(
            color = scope.colors.timelinePlayed,
            radius = if (controlsVisible || scrubbing) 4.dp.toPx() else 2.5.dp.toPx(),
            center = Offset(size.width * played, y),
        )
    }
}

@Composable
private fun ConfiguredControlRow(
    controls: List<PlayerControl>,
    scope: PlayerUiScope,
    config: VideoPlayerUiConfig,
    customControls: Map<String, PlayerControlContent>,
    modifier: Modifier,
    arrangement: Arrangement.Horizontal = Arrangement.Start,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = arrangement,
    ) {
        controls.forEach { RenderControl(it, scope, config, customControls) }
    }
}

@Composable
private fun RowScope.RenderControl(
    control: PlayerControl,
    scope: PlayerUiScope,
    config: VideoPlayerUiConfig,
    customControls: Map<String, PlayerControlContent>,
) {
    val state = scope.state
    when (control) {
        PlayerControl.Back -> if (scope.canNavigateBack) PlayerIconButton(
            scope.icons.back,
            scope.strings.back,
            scope.colors.controlsContent,
            onClick = scope.actions.back,
        )
        PlayerControl.Title -> (scope.title ?: state.media.fileName)?.let {
            Text(
                it,
                Modifier.weight(1f),
                scope.colors.controlsContent,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PlayerControl.Engine -> EngineMenu(scope)
        PlayerControl.Rewind -> if (shouldShowSkip(state, config)) SkipButton(scope, config, false)
        PlayerControl.PlayPause -> SmallPlayPause(scope)
        PlayerControl.Forward -> if (shouldShowSkip(state, config)) SkipButton(scope, config, true)
        PlayerControl.Volume -> VolumeControl(scope)
        PlayerControl.Time -> if (shouldShowTimeline(state)) Text(
            "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}",
            color = scope.colors.secondaryContent,
            style = MaterialTheme.typography.labelSmall,
        )
        PlayerControl.Timeline -> if (shouldShowTimeline(state)) {
            ThinTimeline(scope, true, true, Modifier.weight(1f))
        }
        PlayerControl.LiveEdge -> if (state.media.playbackType == PlaybackType.LIVE) LiveControl(scope)
        PlayerControl.Speed -> if (state.capabilities.canSetPlaybackSpeed) {
            SpeedMenu(scope, config.availableSpeeds)
        }
        PlayerControl.Subtitles -> if (state.tracks.subtitles.isNotEmpty()) SubtitleControl(scope)
        PlayerControl.Fullscreen -> if (scope.canRequestFullscreen) FullscreenControl(scope)
        PlayerControl.Lock -> if (scope.canLock) PlayerIconButton(
            scope.icons.lock,
            scope.strings.lock,
            scope.colors.controlsContent,
            onClick = { scope.actions.setLocked(true) },
        )
        is PlayerControl.Custom -> customControls[control.key]?.Content(scope)
    }
}

@Composable
private fun PlayerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = CONTROL_ICON_SIZE,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(CONTROL_TOUCH_SIZE),
    ) {
        Icon(
            icon,
            description,
            tint = if (enabled) color else color.copy(alpha = 0.35f),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun EngineMenu(scope: PlayerUiScope) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = {
                expanded = true
                scope.actions.beginInteraction()
            },
        ) {
            Text(scope.state.engine.displayName, color = scope.colors.secondaryContent)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                scope.actions.endInteraction()
            },
        ) {
            scope.availableEngines.forEach { engine ->
                DropdownMenuItem(
                    text = { Text(engine.displayName) },
                    onClick = {
                        expanded = false
                        scope.actions.switchEngine(engine.id)
                        scope.actions.endInteraction()
                    },
                )
            }
        }
    }
}

@Composable
private fun SpeedMenu(scope: PlayerUiScope, availableSpeeds: List<Float>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(CONTROL_TOUCH_SIZE)
                .clip(CircleShape)
                .clickable {
                    expanded = true
                    scope.actions.beginInteraction()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${scope.state.playbackSpeed.formatSpeed()}x",
                color = scope.colors.controlsContent,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                scope.actions.endInteraction()
            },
        ) {
            availableSpeeds.forEach { speed ->
                DropdownMenuItem(
                    text = { Text("${speed.formatSpeed()}x") },
                    onClick = {
                        expanded = false
                        scope.actions.setPlaybackSpeed(speed)
                        scope.actions.endInteraction()
                    },
                )
            }
        }
    }
}

@Composable
private fun LiveControl(scope: PlayerUiScope) {
    val atEdge = scope.state.timeline.isAtLiveEdge
    TextButton(
        onClick = scope.actions.goToLiveEdge,
        enabled = scope.state.capabilities.canGoToLiveEdge && !atEdge,
    ) {
        Text(
            text = if (atEdge) scope.strings.live else scope.strings.goLive,
            color = if (atEdge) scope.colors.live else scope.colors.controlsContent,
        )
    }
}

@Composable
private fun BoxScope.GestureFeedbackOverlay(feedback: PlayerGestureFeedback, colors: VideoPlayerColors) {
    val label = when (feedback.type) {
        PlayerGestureType.BRIGHTNESS -> "Brightness"
        PlayerGestureType.VOLUME -> "Volume"
    }
    Text(
        text = "$label ${(feedback.value * 100).roundToInt()}%",
        color = colors.controlsContent,
        modifier = Modifier
            .align(Alignment.Center)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.controlsBackground)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private fun shouldShowSkip(state: PlayerState, config: VideoPlayerUiConfig): Boolean =
    state.media.playbackType == PlaybackType.VOD &&
        state.capabilities.canSeek &&
        state.durationMs > config.minimumDurationForSkipMs

private fun shouldShowTimeline(state: PlayerState): Boolean =
    state.media.playbackType == PlaybackType.VOD &&
        state.capabilities.canSeek &&
        state.durationMs > 0L

private fun timelineBottomPadding(state: PlayerState) =
    if (shouldShowTimeline(state)) TIMELINE_HEIGHT else 0.dp

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private fun formatNetworkSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_048_576L -> "%.1f MB/s".format(bytesPerSecond / 1_048_576f)
    bytesPerSecond >= 1_024L -> "%.0f KB/s".format(bytesPerSecond / 1_024f)
    else -> "$bytesPerSecond B/s"
}

private fun Float.formatSpeed(): String = if (this % 1f == 0f) toInt().toString() else toString()

private val TIMELINE_HEIGHT = 34.dp
private val CONTROL_TOUCH_SIZE = 40.dp
private val CONTROL_ICON_SIZE = 20.dp
private val SKIP_TOUCH_SIZE = 40.dp
private val SKIP_ICON_SIZE = 18.dp
