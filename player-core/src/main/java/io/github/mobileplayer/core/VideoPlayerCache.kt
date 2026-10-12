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

/** Download progress reported while a network resource is copied into the cache. */
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

/**
 * Engine-neutral cache seam used by [VideoPlayerController].
 *
 * Implementations store small progress records synchronously and must keep
 * those operations fast. Media downloads are suspend functions. Local sources
 * are returned unchanged and never copied into the media cache.
 */
interface VideoPlayerCache {
    fun getProgress(mediaId: String): PlaybackProgress?

    fun saveProgress(progress: PlaybackProgress)

    /** Returns a complete local cached source, or null when it is not cached. */
    fun getCachedSource(source: VideoSource): VideoSource?

    /** Caches a network source completely and returns its local source. */
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
