package io.github.mobileplayer.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persists progressive network byte ranges in app-private storage.
 *
 * Content is stored under [Context.getFilesDir], so Android does not reclaim it
 * as disposable cache data. It remains until LRU eviction, an explicit remove
 * or clear, app-data clearing, or uninstall. One instance should normally be
 * shared by every controller in the application process.
 */
class AppPrivateVideoPlayerCache(
    context: Context,
    maxMediaCacheBytes: Long = DEFAULT_MAX_MEDIA_CACHE_BYTES,
    private val config: VideoCacheConfig = VideoCacheConfig(),
    cacheDirectory: File? = null,
) : VideoContentCache, AutoCloseable {
    private val appContext = context.applicationContext
    /** Caller-provided directories can isolate cache data without changing media IDs. */
    private val mediaDirectory = cacheDirectory ?: File(appContext.filesDir, MEDIA_DIRECTORY)
    private val mediaProxy = ProgressiveMediaProxy(
        baseDirectory = mediaDirectory,
        maxCacheBytes = maxMediaCacheBytes,
        config = config,
    )

    init {
        require(maxMediaCacheBytes > 0L) { "Media cache size must be positive" }
    }

    override fun getCachedSource(source: VideoSource): VideoSource? =
        mediaProxy.playbackSource(source)

    override suspend fun cache(
        source: VideoSource,
        onProgress: (MediaCacheProgress) -> Unit,
    ): VideoSource {
        if (source.type == VideoSource.Type.LOCAL) return source
        if (!mediaProxy.supportsContentCaching(source)) return source
        return withContext(Dispatchers.IO) {
            mediaProxy.prefetch(source, onProgress)
        }
    }

    override suspend fun remove(mediaId: String) {
        require(mediaId.isNotBlank()) { "mediaId must not be blank" }
        withContext(Dispatchers.IO) {
            mediaProxy.remove(mediaId)
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            mediaProxy.clear()
        }
    }

    /** Stops the process-local proxy. Persisted media segments remain. */
    override fun close() {
        mediaProxy.close()
    }

    companion object {
        const val DEFAULT_MAX_MEDIA_CACHE_BYTES: Long = 512L * 1024L * 1024L
        private const val MEDIA_DIRECTORY = "video-mobile-player-cache"
    }
}
