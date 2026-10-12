package io.github.mobileplayer.core

/**
 * A media location understood by every engine.
 *
 * Local sources should normally be a `content://` URI obtained from Android's
 * photo picker or storage access framework. Network sources may include HTTP
 * headers such as authorization or referer values.
 */
data class VideoSource(
    val uri: String,
    val type: Type,
    val headers: Map<String, String> = emptyMap(),
    val title: String? = null,
    /** Stable business identifier used for progress and media caching. */
    val mediaId: String = uri,
) {
    init {
        require(uri.isNotBlank()) { "Video source URI must not be blank" }
        require(mediaId.isNotBlank()) { "Video source mediaId must not be blank" }
    }

    enum class Type {
        LOCAL,
        NETWORK,
    }

    companion object {
        fun local(
            uri: String,
            title: String? = null,
            mediaId: String = uri,
        ): VideoSource = VideoSource(
            uri = uri,
            type = Type.LOCAL,
            mediaId = mediaId,
            title = title,
        )

        fun network(
            url: String,
            headers: Map<String, String> = emptyMap(),
            title: String? = null,
            mediaId: String = url,
        ): VideoSource = VideoSource(
            uri = url,
            type = Type.NETWORK,
            mediaId = mediaId,
            headers = headers,
            title = title,
        )
    }
}
