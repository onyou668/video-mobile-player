package io.github.mobileplayer.core

/** Stable, open identifier used by built-in and third-party playback engines. */
@JvmInline
value class PlayerEngineId(val value: String) {
    init {
        require(value.isNotBlank()) { "Player engine id must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        val MEDIA3 = PlayerEngineId("media3")
        val IJK = PlayerEngineId("ijk")
    }
}

data class PlayerEngineInfo(
    val id: PlayerEngineId,
    val displayName: String,
    val supportedProtocols: Set<MediaProtocol> = emptySet(),
)

/** Registration seam for built-in and third-party engines. */
interface PlaybackEngineProvider {
    val info: PlayerEngineInfo

    /** Fast source-level check. Actual codec support is reported by the engine. */
    fun supports(source: VideoSource): Boolean = true

    fun create(): PlaybackEngine
}
