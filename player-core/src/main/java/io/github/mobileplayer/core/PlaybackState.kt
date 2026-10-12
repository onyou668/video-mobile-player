package io.github.mobileplayer.core

enum class PlaybackStatus {
    IDLE,
    PREPARING,
    READY,
    BUFFERING,
    ENDED,
    ERROR,
}

data class PlaybackError(
    val message: String,
    val cause: Throwable? = null,
)

/** Public, engine-independent snapshot consumed by Compose or application code. */
data class PlaybackState(
    val engineType: PlayerEngineType,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val source: VideoSource? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    /** Current network transfer throughput. Zero when no network data is being read. */
    val networkSpeedBytesPerSecond: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val volume: Float = 1f,
    val playbackSpeed: Float = 1f,
    val error: PlaybackError? = null,
) {
    val progress: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

    val bufferedProgress: Float
        get() = if (durationMs > 0L) {
            (bufferedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
}
