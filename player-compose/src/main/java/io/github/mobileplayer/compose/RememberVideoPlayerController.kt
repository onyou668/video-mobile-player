package io.github.mobileplayer.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.mobileplayer.core.PlaybackEngineFactory
import io.github.mobileplayer.core.PlayerEngineType
import io.github.mobileplayer.core.PlaybackProgressPolicy
import io.github.mobileplayer.core.AppPrivateVideoPlayerCache
import io.github.mobileplayer.core.VideoPlayerCache
import io.github.mobileplayer.core.VideoCacheConfig
import io.github.mobileplayer.core.VideoPlayerController
import io.github.mobileplayer.ijk.IjkPlaybackEngineFactory
import io.github.mobileplayer.media3.Media3PlaybackEngineFactory
import kotlinx.coroutines.CoroutineScope

/**
 * Creates a controller whose lifetime is owned by the caller. Use this from a
 * ViewModel or foreground service when playback must survive UI migration.
 */
fun createDefaultVideoPlayerController(
    context: Context,
    scope: CoroutineScope,
    initialEngine: PlayerEngineType = PlayerEngineType.MEDIA3,
    cache: VideoPlayerCache? = null,
    cacheConfig: VideoCacheConfig = VideoCacheConfig(),
    progressPolicy: PlaybackProgressPolicy = PlaybackProgressPolicy(),
): VideoPlayerController {
    val appContext = context.applicationContext
    val effectiveCache = cache ?: AppPrivateVideoPlayerCache(
        context = appContext,
        config = cacheConfig,
    )
    return VideoPlayerController(
        initialEngine = initialEngine,
        factories = mapOf<PlayerEngineType, PlaybackEngineFactory>(
            PlayerEngineType.MEDIA3 to Media3PlaybackEngineFactory(appContext),
            PlayerEngineType.IJK to IjkPlaybackEngineFactory(appContext),
        ),
        scope = scope,
        cache = effectiveCache,
        progressPolicy = progressPolicy,
    )
}

/** Creates and owns a controller configured with both bundled playback engines. */
@Composable
fun rememberVideoPlayerController(
    initialEngine: PlayerEngineType = PlayerEngineType.MEDIA3,
    cache: VideoPlayerCache? = null,
    cacheConfig: VideoCacheConfig = VideoCacheConfig(),
    progressPolicy: PlaybackProgressPolicy = PlaybackProgressPolicy(),
): VideoPlayerController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(initialEngine, context, scope, cache, cacheConfig, progressPolicy) {
        createDefaultVideoPlayerController(
            context = context,
            scope = scope,
            initialEngine = initialEngine,
            cache = cache,
            cacheConfig = cacheConfig,
            progressPolicy = progressPolicy,
        )
    }

    DisposableEffect(controller) {
        onDispose(controller::close)
    }
    return controller
}
