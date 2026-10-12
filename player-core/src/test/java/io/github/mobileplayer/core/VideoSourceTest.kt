package io.github.mobileplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VideoSourceTest {
    @Test
    fun sameTypeAndUriProduceStableOpaqueIdentity() {
        val first = VideoSource.network("https://example.com/movie.mp4")
        val second = VideoSource.network("https://example.com/movie.mp4")

        assertEquals(first.mediaId, second.mediaId)
        assertEquals(64, first.mediaId.length)
        assertFalse(first.mediaId.contains(first.uri))
    }

    @Test
    fun sourceTypeParticipatesInIdentity() {
        val uri = "https://example.com/movie.mp4"

        assertNotEquals(
            VideoSource.network(uri).mediaId,
            VideoSource.local(uri).mediaId,
        )
    }

    @Test
    fun presentationMetadataDoesNotChangeIdentity() {
        val url = "https://example.com/movie.mp4"
        val first = VideoSource.network(url)
        val second = VideoSource.network(
            url = url,
            title = "Renamed movie",
            headers = mapOf("Authorization" to "Bearer refreshed-token"),
        )

        assertEquals(first.mediaId, second.mediaId)
    }

    @Test
    fun networkUrlHelpersUseTheSameMediaIdentity() {
        val url = "https://example.com/movie.mp4"

        assertEquals(
            VideoSource.network(url).mediaId,
            MediaIdGenerator.forNetworkUrl(url),
        )
    }
}
