package io.github.mobileplayer.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.mobileplayer.core.AndroidPlayerEnvironment
import io.github.mobileplayer.core.AppPrivateVideoPlayerCache
import io.github.mobileplayer.core.PlaybackEngineProvider
import io.github.mobileplayer.core.PlayerController
import io.github.mobileplayer.core.PlayerEngineId
import io.github.mobileplayer.core.VideoCacheConfig
import io.github.mobileplayer.core.VideoContentCache
import io.github.mobileplayer.core.createPlayerController
import io.github.mobileplayer.ijk.IjkPlaybackEngineProvider
import io.github.mobileplayer.media3.Media3PlaybackEngineProvider
import kotlinx.coroutines.CoroutineScope
import java.io.File

fun createDefaultPlayerController(
    context: Context,
    scope: CoroutineScope,
    initialEngineId: PlayerEngineId = PlayerEngineId.MEDIA3,
    additionalEngineProviders: List<PlaybackEngineProvider> = emptyList(),
    cache: VideoContentCache? = null,
    cacheConfig: VideoCacheConfig = VideoCacheConfig(),
    cacheDirectory: File? = null,
): PlayerController {
    val appContext = context.applicationContext
    val effectiveCache = cache ?: AppPrivateVideoPlayerCache(
        context = appContext,
        config = cacheConfig,
        cacheDirectory = cacheDirectory,
    )
    val providers = listOf(
        Media3PlaybackEngineProvider(appContext),
        IjkPlaybackEngineProvider(appContext),
    ) + additionalEngineProviders
    return createPlayerController(
        initialEngineId = initialEngineId,
        engineProviders = providers,
        scope = scope,
        cache = effectiveCache,
        environment = AndroidPlayerEnvironment(context),
        ownedResources = if (cache == null) listOf(effectiveCache as AutoCloseable) else emptyList(),
    )
}

@Composable
fun rememberPlayerController(
    initialEngineId: PlayerEngineId = PlayerEngineId.MEDIA3,
    additionalEngineProviders: List<PlaybackEngineProvider> = emptyList(),
    cache: VideoContentCache? = null,
    cacheConfig: VideoCacheConfig = VideoCacheConfig(),
    cacheDirectory: File? = null,
): PlayerController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(
        initialEngineId,
        context,
        scope,
        additionalEngineProviders,
        cache,
        cacheConfig,
        cacheDirectory,
    ) {
        createDefaultPlayerController(
            context = context,
            scope = scope,
            initialEngineId = initialEngineId,
            additionalEngineProviders = additionalEngineProviders,
            cache = cache,
            cacheConfig = cacheConfig,
            cacheDirectory = cacheDirectory,
        )
    }
    DisposableEffect(controller) { onDispose(controller::close) }
    return controller
}
