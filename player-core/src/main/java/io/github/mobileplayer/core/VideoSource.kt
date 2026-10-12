package io.github.mobileplayer.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
    val mediaId: String = generateMediaId(uri, type),
    /** Controls whether progressive network bytes may be persisted locally. */
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
        /** Cache HTTP range requests except known HLS/DASH manifest URLs. */
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

        private fun generateMediaId(uri: String, type: Type): String {
            val identity = "${type.name}:$uri"
            return MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
