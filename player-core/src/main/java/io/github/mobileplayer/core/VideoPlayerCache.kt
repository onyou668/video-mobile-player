package io.github.mobileplayer.core

/** Persisted, engine-independent playback position for one media resource. */
data class PlaybackProgress(
    val mediaId: String,
    val positionMs: Long,
    val durationMs: Long = 0L,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    init {
        require(mediaId.isNotBlank()) { "Playback progress mediaId must not be blank" }
        require(positionMs >= 0L) { "Playback progress position must not be negative" }
        require(durationMs >= 0L) { "Playback progress duration must not be negative" }
    }
}

/** Progress reported by an explicit full-resource prefetch. */
data class MediaCacheProgress(
    val mediaId: String,
    val bytesCached: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() = totalBytes
            ?.takeIf { it > 0L }
            ?.let { (bytesCached.toFloat() / it).coerceIn(0f, 1f) }
}

data class VideoCacheConfig(
    val enabled: Boolean = true,
    val cacheableExtensions: Set<String> = setOf("mp4", "m4v", "mov", "webm"),
    val allowUnknownExtension: Boolean = false,
) {
    init {
        require(cacheableExtensions.none { it.isBlank() || it.any { character -> character.isWhitespace() } }) {
            "Cacheable extensions must be non-blank and contain no whitespace"
        }
    }

    val normalizedExtensions: Set<String> = cacheableExtensions
        .map { it.removePrefix(".").lowercase() }
        .toSet()
}

/**
 * Engine-neutral cache seam used by [VideoPlayerController].
 *
 * Implementations store small progress records synchronously and must keep
 * those operations fast. Source resolution may return a read-through local
 * proxy that persists only the byte ranges requested by an engine. Local
 * sources and segmented streaming manifests must bypass content caching.
 */
interface VideoPlayerCache {
    fun getProgress(mediaId: String): PlaybackProgress?

    fun getProgressByUrl(url: String): PlaybackProgress? =
        getProgress(MediaIdGenerator.forNetworkUrl(url))

    fun getProgressBySource(source: VideoSource): PlaybackProgress? =
        getProgress(source.mediaId)

    fun getAllProgress(): List<PlaybackProgress> = emptyList()

    fun saveProgress(progress: PlaybackProgress)

    /** Returns an engine-neutral cached playback source, or null to use [source]. */
    fun getCachedSource(source: VideoSource): VideoSource?

    /** Explicitly prefetches a supported progressive resource into the cache. */
    suspend fun cache(
        source: VideoSource,
        onProgress: (MediaCacheProgress) -> Unit = {},
    ): VideoSource

    /** Removes both the progress record and cached media for [mediaId]. */
    suspend fun remove(mediaId: String)

    /** Removes every progress record and cached media file owned by this cache. */
    suspend fun clear()
}

/** Controls automatic progress restoration and persistence. */
data class PlaybackProgressPolicy(
    val saveIntervalMs: Long = 5_000L,
    /** A saved position this close to the end restarts from zero. */
    val restartCompletedWithinMs: Long = 5_000L,
) {
    init {
        require(saveIntervalMs > 0L) { "Progress save interval must be positive" }
        require(restartCompletedWithinMs >= 0L) {
            "Completed playback threshold must not be negative"
        }
    }
}
