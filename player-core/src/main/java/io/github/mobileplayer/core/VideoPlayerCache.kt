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

/** Small synchronous store for engine-independent playback history. */
interface PlaybackProgressStore {
    fun getProgress(mediaId: String): PlaybackProgress?

    fun getProgressByUrl(url: String): PlaybackProgress? =
        getProgress(MediaIdGenerator.forNetworkUrl(url))

    fun getProgressBySource(source: VideoSource): PlaybackProgress? =
        getProgress(source.mediaId)

    fun getAllProgress(): List<PlaybackProgress> = emptyList()

    fun saveProgress(progress: PlaybackProgress)
}

/**
 * Shared media-byte cache boundary. It never owns playback progress; callers
 * can provide a separate [PlaybackProgressStore] when they need history.
 */
interface VideoContentCache {

    /** Returns an engine-neutral cached playback source, or null to use [source]. */
    fun getCachedSource(source: VideoSource): VideoSource?

    /** Explicitly prefetches a supported progressive resource into the cache. */
    suspend fun cache(
        source: VideoSource,
        onProgress: (MediaCacheProgress) -> Unit = {},
    ): VideoSource

    /** Removes cached media for [mediaId]. */
    suspend fun remove(mediaId: String)

    /** Removes every cached media file owned by this cache. */
    suspend fun clear()
}
