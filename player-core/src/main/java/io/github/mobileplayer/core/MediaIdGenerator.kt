package io.github.mobileplayer.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Single source of truth for stable media identities used by playback and history. */
object MediaIdGenerator {
    fun forNetworkUrl(url: String): String = generate("NETWORK:$url")

    fun forLocalUri(uri: String): String = generate("LOCAL:$uri")

    private fun generate(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
