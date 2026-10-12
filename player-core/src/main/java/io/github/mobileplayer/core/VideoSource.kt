package io.github.mobileplayer.core

/**
 * A media location understood by every engine.
 *
 * Local sources should normally be a `content://` URI obtained from Android's
 * photo picker or storage access framework. Network sources may include HTTP
 * headers such as authorization or referer values.
 */
@ConsistentCopyVisibility
data class VideoSource internal constructor(
    val uri: String,
    val type: Type,
    val headers: Map<String, String> = emptyMap(),
    val title: String? = null,
    /** Stable opaque identifier generated from the source type and URI. */
    val mediaId: String = when (type) {
        Type.LOCAL -> MediaIdGenerator.forLocalUri(uri)
        Type.NETWORK -> MediaIdGenerator.forNetworkUrl(uri)
    },
    /** Controls whether the configured cache may persist progressive network bytes. */
    val contentCachePolicy: ContentCachePolicy = ContentCachePolicy.AUTO,
) {
    init {
        require(uri.isNotBlank()) { "Video source URI must not be blank" }
        require(mediaId.isNotBlank()) { "Video source mediaId must not be blank" }
    }

    enum class Type {
        LOCAL,
        NETWORK,
    }

    enum class ContentCachePolicy {
        /** Try content caching when the global cache policy and source allow it. */
        AUTO,

        /** Bypass content caching while still persisting playback progress. */
        DISABLED,
    }

    companion object {
        fun local(
            uri: String,
            title: String? = null,
        ): VideoSource = VideoSource(
            uri = uri,
            type = Type.LOCAL,
            title = title,
        )

        fun network(
            url: String,
            headers: Map<String, String> = emptyMap(),
            title: String? = null,
            contentCachePolicy: ContentCachePolicy = ContentCachePolicy.AUTO,
        ): VideoSource = VideoSource(
            uri = url,
            type = Type.NETWORK,
            headers = headers,
            title = title,
            contentCachePolicy = contentCachePolicy,
        )

    }
}
