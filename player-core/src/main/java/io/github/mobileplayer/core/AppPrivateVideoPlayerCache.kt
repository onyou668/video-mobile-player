package io.github.mobileplayer.core

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Persists progress and progressive network byte ranges in app-private storage.
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
) : VideoPlayerCache, AutoCloseable {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mediaDirectory = File(appContext.filesDir, MEDIA_DIRECTORY)
    private val mediaProxy = ProgressiveMediaProxy(
        baseDirectory = mediaDirectory,
        maxCacheBytes = maxMediaCacheBytes,
        config = config,
    )

    init {
        require(maxMediaCacheBytes > 0L) { "Media cache size must be positive" }
    }

    override fun getProgress(mediaId: String): PlaybackProgress? {
        if (mediaId.isBlank()) return null
        val encoded = preferences.getString(progressKey(mediaId), null) ?: return null
        return decodeProgress(encoded)
    }

    override fun getAllProgress(): List<PlaybackProgress> = preferences.all.keys
        .asSequence()
        .filter { it.startsWith(PROGRESS_PREFIX) }
        .mapNotNull { key -> preferences.getString(key, null)?.let(::decodeProgress) }
        .sortedByDescending(PlaybackProgress::updatedAtEpochMs)
        .toList()

    private fun decodeProgress(encoded: String): PlaybackProgress? = runCatching {
        val json = JSONObject(encoded)
        PlaybackProgress(
            mediaId = json.getString("mediaId"),
            positionMs = json.getLong("positionMs"),
            durationMs = json.optLong("durationMs", 0L),
            updatedAtEpochMs = json.optLong("updatedAtEpochMs", 0L),
        )
    }.getOrNull()

    override fun saveProgress(progress: PlaybackProgress) {
        val json = JSONObject()
            .put("mediaId", progress.mediaId)
            .put("positionMs", progress.positionMs)
            .put("durationMs", progress.durationMs)
            .put("updatedAtEpochMs", progress.updatedAtEpochMs)
        preferences.edit().putString(progressKey(progress.mediaId), json.toString()).apply()
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

    @SuppressLint("ApplySharedPref")
    override suspend fun remove(mediaId: String) {
        require(mediaId.isNotBlank()) { "mediaId must not be blank" }
        withContext(Dispatchers.IO) {
            preferences.edit().remove(progressKey(mediaId)).commit()
            mediaProxy.remove(mediaId)
        }
    }

    @SuppressLint("ApplySharedPref")
    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            preferences.edit().clear().commit()
            mediaProxy.clear()
        }
    }

    /** Stops the process-local proxy. Persisted progress and media remain. */
    override fun close() {
        mediaProxy.close()
    }

    private fun progressKey(mediaId: String): String =
        "$PROGRESS_PREFIX${ProgressiveMediaProxy.cacheKeyFor(mediaId)}"

    companion object {
        const val DEFAULT_MAX_MEDIA_CACHE_BYTES: Long = 512L * 1024L * 1024L
        private const val PREFERENCES_NAME = "video_mobile_player_progress"
        private const val MEDIA_DIRECTORY = "video-mobile-player-cache"
        private const val PROGRESS_PREFIX = "progress_"
    }
}
