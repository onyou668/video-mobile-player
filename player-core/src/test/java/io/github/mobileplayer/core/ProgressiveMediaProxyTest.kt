package io.github.mobileplayer.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ProgressiveMediaProxyTest {
    @Test
    fun parsesBoundedOpenAndSuffixRanges() {
        assertEquals(
            ProgressiveMediaProxy.ByteRange(10L, 19L),
            ProgressiveMediaProxy.parseRange("bytes=10-19", 100L),
        )
        assertEquals(
            ProgressiveMediaProxy.ByteRange(90L, 99L),
            ProgressiveMediaProxy.parseRange("bytes=-10", 100L),
        )
        assertEquals(
            ProgressiveMediaProxy.ByteRange(95L, 99L),
            ProgressiveMediaProxy.parseRange("bytes=95-", 100L),
        )
        assertNull(ProgressiveMediaProxy.parseRange("bytes=100-", 100L))
        assertNull(ProgressiveMediaProxy.parseRange("bytes=1-2,4-5", 100L))
    }

    @Test
    fun localManifestsAndDisabledSourcesBypassContentCache() {
        withTempDirectory { directory ->
            ProgressiveMediaProxy(directory, 4L * 1024L * 1024L).use { proxy ->
                assertFalse(
                    proxy.supportsContentCaching(VideoSource.local("content://video/42")),
                )
                assertFalse(
                    proxy.supportsContentCaching(
                        VideoSource.network("https://example.com/live.m3u8"),
                    ),
                )
                assertFalse(
                    proxy.supportsContentCaching(
                        VideoSource.network("https://example.com/manifest.mpd"),
                    ),
                )
                assertFalse(
                    proxy.supportsContentCaching(
                        VideoSource.network(
                            url = "https://example.com/opaque-stream",
                            contentCachePolicy = VideoSource.ContentCachePolicy.DISABLED,
                        ),
                    ),
                )
                assertTrue(
                    proxy.supportsContentCaching(
                        VideoSource.network("https://example.com/movie.mp4"),
                    ),
                )
            }
        }
    }

    @Test
    fun cacheConfigurationLimitsProgressiveExtensions() {
        withTempDirectory { directory ->
            ProgressiveMediaProxy(
                baseDirectory = directory,
                maxCacheBytes = 4L * 1024L * 1024L,
                config = VideoCacheConfig(cacheableExtensions = setOf("mp4")),
            ).use { proxy ->
                assertTrue(proxy.supportsContentCaching(VideoSource.network("https://example.com/a.mp4")))
                assertFalse(proxy.supportsContentCaching(VideoSource.network("https://example.com/a.mov")))
                assertFalse(proxy.supportsContentCaching(VideoSource.network("https://example.com/a.m3u8")))
            }
        }
    }

    @Test
    fun requestedSegmentsPersistAndAreReusedAcrossProxyInstances() {
        val segmentSize = ProgressiveMediaProxy.DEFAULT_SEGMENT_SIZE_BYTES
        val content = ByteArray((segmentSize * 2L + 257L).toInt()) { index ->
            (index % 251).toByte()
        }
        RangeServer(content).use { upstream ->
            withTempDirectory { directory ->
                val source = VideoSource.network(url = upstream.url)
                val firstStart = 100L
                val firstEnd = 199L
                val secondStart = segmentSize + 10L
                val secondEnd = segmentSize + 109L

                ProgressiveMediaProxy(directory, 4L * 1024L * 1024L).use { proxy ->
                    val playbackSource = requireNotNull(proxy.playbackSource(source))
                    assertArrayEquals(
                        content.copyOfRange(firstStart.toInt(), firstEnd.toInt() + 1),
                        readRange(playbackSource.uri, firstStart, firstEnd),
                    )
                    assertArrayEquals(
                        content.copyOfRange(firstStart.toInt(), firstEnd.toInt() + 1),
                        readRange(playbackSource.uri, firstStart, firstEnd),
                    )

                    assertEquals(1, upstream.requestCount("bytes=0-${segmentSize - 1L}"))
                    assertEquals(0, upstream.requestCount("bytes=$segmentSize-${segmentSize * 2L - 1L}"))

                    assertArrayEquals(
                        content.copyOfRange(secondStart.toInt(), secondEnd.toInt() + 1),
                        readRange(playbackSource.uri, secondStart, secondEnd),
                    )
                    assertEquals(1, upstream.requestCount("bytes=$segmentSize-${segmentSize * 2L - 1L}"))
                }

                val requestsBeforeReopen = upstream.rangeRequests.size
                ProgressiveMediaProxy(directory, 4L * 1024L * 1024L).use { proxy ->
                    val playbackSource = requireNotNull(proxy.playbackSource(source))
                    assertArrayEquals(
                        content.copyOfRange(firstStart.toInt(), firstEnd.toInt() + 1),
                        readRange(playbackSource.uri, firstStart, firstEnd),
                    )
                }
                assertEquals(requestsBeforeReopen, upstream.rangeRequests.size)

                val cacheDirectory = File(
                    directory,
                    ProgressiveMediaProxy.cacheKeyFor(source.mediaId),
                )
                assertTrue(File(cacheDirectory, "metadata.json").isFile)
                assertTrue(
                    File(cacheDirectory, ProgressiveMediaProxy.segmentFileName(0L)).isFile,
                )
                assertTrue(
                    File(cacheDirectory, ProgressiveMediaProxy.segmentFileName(1L)).isFile,
                )
                assertFalse(
                    File(cacheDirectory, ProgressiveMediaProxy.segmentFileName(2L)).exists(),
                )
            }
        }
    }

    private fun readRange(url: String, start: Long, end: Long): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("Range", "bytes=$start-$end")
        }
        return try {
            assertEquals(HttpURLConnection.HTTP_PARTIAL, connection.responseCode)
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("progressive-media-proxy-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class RangeServer(
        private val content: ByteArray,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "range-test-server").apply { isDaemon = true }
        }
        private val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        val rangeRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val url: String = "http://127.0.0.1:${server.localPort}/movie.mp4"

        init {
            executor.execute {
                while (!closed.get()) {
                    try {
                        val socket = server.accept()
                        executor.execute { handle(socket) }
                    } catch (_: SocketException) {
                        return@execute
                    }
                }
            }
        }

        fun requestCount(range: String): Int = synchronized(rangeRequests) {
            rangeRequests.count { it == range }
        }

        private fun handle(socket: Socket) {
            socket.use { client ->
                val reader = client.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                reader.readLine() ?: return
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: return
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                            line.substring(separator + 1).trim()
                    }
                }
                val rangeValue = headers["range"] ?: return
                rangeRequests += rangeValue
                val range = requireNotNull(
                    ProgressiveMediaProxy.parseRange(rangeValue, content.size.toLong()),
                )
                val body = content.copyOfRange(
                    range.start.toInt(),
                    range.endInclusive.toInt() + 1,
                )
                val output = BufferedOutputStream(client.getOutputStream())
                val response = buildString {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Type: video/mp4\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Content-Range: bytes ${range.start}-${range.endInclusive}/${content.size}\r\n")
                    append("ETag: \"movie-v1\"\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                output.write(response.toByteArray(StandardCharsets.ISO_8859_1))
                output.write(body)
                output.flush()
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            server.close()
            executor.shutdownNow()
        }
    }
}
