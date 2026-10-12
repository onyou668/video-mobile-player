package io.github.mobileplayer.core

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** Process-local HTTP range proxy shared by every playback engine. */
internal class ProgressiveMediaProxy(
    private val baseDirectory: File,
    private val maxCacheBytes: Long,
    private val config: VideoCacheConfig = VideoCacheConfig(),
    private val segmentSizeBytes: Long = DEFAULT_SEGMENT_SIZE_BYTES,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val entries = ConcurrentHashMap<String, CacheEntry>()
    private val segmentLocks = ConcurrentHashMap<String, Any>()
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "video-cache-proxy").apply { isDaemon = true }
    }
    private val sessionToken = UUID.randomUUID().toString().replace("-", "")
    private val serverSocketHolder = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        check(!closed.get()) { "Media cache proxy is closed" }
        ServerSocket().apply {
            reuseAddress = true
            bind(
                InetSocketAddress(
                    InetAddress.getByName(LOOPBACK_ADDRESS),
                    0,
                ),
            )
        }.also { socket -> executor.execute { acceptConnections(socket) } }
    }
    private val serverSocket: ServerSocket by serverSocketHolder

    init {
        require(maxCacheBytes > 0L) { "Media cache size must be positive" }
        require(segmentSizeBytes > 0L) { "Segment size must be positive" }
    }

    fun supportsContentCaching(source: VideoSource): Boolean {
        if (!config.enabled) return false
        if (source.type != VideoSource.Type.NETWORK) return false
        if (source.contentCachePolicy == VideoSource.ContentCachePolicy.DISABLED) return false
        val uri = runCatching { URI(source.uri) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase(Locale.US) !in setOf("http", "https")) return false
        val path = uri.path.orEmpty()
        val extension = path.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension == "m3u8" || extension == "mpd") return false
        return extension.isNotBlank() && extension in config.normalizedExtensions ||
            extension.isBlank() && config.allowUnknownExtension
    }

    fun playbackSource(source: VideoSource): VideoSource? {
        check(!closed.get()) { "Media cache proxy is closed" }
        if (!supportsContentCaching(source)) return null
        val key = cacheKeyFor(source.mediaId)
        val entry = entries[key] ?: synchronized(entries) {
            entries[key] ?: createEntry(key, source).also { entries[key] = it }
        }
        entry.source = source
        if (entry.contentCachingDisabled) return null
        val port = serverSocket.localPort
        return source.copy(
            uri = "http://$LOOPBACK_ADDRESS:$port/media/$sessionToken/$key",
            type = VideoSource.Type.NETWORK,
            headers = source.headers,
            contentCachePolicy = VideoSource.ContentCachePolicy.DISABLED,
        ).also {
            entry.directory.setLastModified(System.currentTimeMillis())
        }
    }

    fun prefetch(
        source: VideoSource,
        onProgress: (MediaCacheProgress) -> Unit,
    ): VideoSource {
        val playbackSource = playbackSource(source) ?: return source
        val entry = requireNotNull(entries[cacheKeyFor(source.mediaId)])
        val metadata = try {
            ensureMetadata(entry)
        } catch (error: CacheNotSupportedException) {
            entry.contentCachingDisabled = true
            return source
        }
        val totalBytes = requireNotNull(metadata.contentLength) {
            "Upstream content length is required for prefetch"
        }
        require(totalBytes <= maxCacheBytes) {
            "Media ${source.mediaId} is larger than the configured cache limit"
        }

        var cachedBytes = validCachedBytes(entry, totalBytes)
        onProgress(MediaCacheProgress(source.mediaId, cachedBytes, totalBytes))
        val lastSegment = (totalBytes - 1L) / segmentSizeBytes
        for (segmentIndex in 0L..lastSegment) {
            ensureSegment(
                entry = entry,
                metadata = metadata,
                segmentIndex = segmentIndex,
                sendStart = 1L,
                sendEnd = 0L,
                output = null,
            )
            cachedBytes = validCachedBytes(entry, totalBytes)
            onProgress(MediaCacheProgress(source.mediaId, cachedBytes, totalBytes))
        }
        return playbackSource
    }

    fun remove(mediaId: String) {
        val key = cacheKeyFor(mediaId)
        entries.remove(key)
        File(baseDirectory, key).deleteRecursively()
    }

    fun clear() {
        entries.clear()
        baseDirectory.deleteRecursively()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (serverSocketHolder.isInitialized()) serverSocket.close()
        executor.shutdownNow()
        entries.clear()
    }

    private fun createEntry(key: String, source: VideoSource): CacheEntry {
        val directory = File(baseDirectory, key)
        directory.mkdirs()
        val entry = CacheEntry(key, source, directory)
        synchronized(entry) {
            val metadata = readMetadata(entry) ?: CacheMetadata(segmentSizeBytes = segmentSizeBytes)
            if (metadata.segmentSizeBytes != segmentSizeBytes) {
                directory.listFiles()
                    ?.filter { it.name.endsWith(SEGMENT_EXTENSION) || it.name.endsWith(PART_EXTENSION) }
                    ?.forEach(File::delete)
                metadata.segmentSizeBytes = segmentSizeBytes
                metadata.contentLength = null
                metadata.contentType = null
                metadata.etag = null
                metadata.lastModified = null
            }
            entry.metadata = metadata
            writeMetadata(entry, metadata)
        }
        return entry
    }

    private fun acceptConnections(server: ServerSocket) {
        while (!closed.get()) {
            try {
                val socket = server.accept()
                executor.execute { handleConnection(socket) }
            } catch (_: SocketException) {
                if (!closed.get()) continue
            } catch (_: Throwable) {
                if (closed.get()) return
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { client ->
            client.soTimeout = CLIENT_TIMEOUT_MS
            var fallbackEntry: CacheEntry? = null
            runCatching {
                val request = readRequest(client) ?: return
                val key = routeKey(request.path)
                val entry = key?.let(entries::get)
                if (entry == null) {
                    sendError(client, 404, "Not Found")
                    return
                }
                if (request.method != "GET" && request.method != "HEAD") {
                    sendError(client, 405, "Method Not Allowed")
                    return
                }
                fallbackEntry = entry
                serve(entry, request, client)
            }.onFailure { error ->
                val entry = fallbackEntry
                if (error is CacheNotSupportedException && entry != null) {
                    entry.contentCachingDisabled = true
                    runCatching { sendRedirect(client, entry.source.uri) }
                } else {
                    runCatching { sendError(client, 502, error.message ?: "Upstream Error") }
                }
            }
        }
    }

    private fun serve(entry: CacheEntry, request: ProxyRequest, socket: Socket) {
        val metadata = ensureMetadata(entry)
        val totalBytes = requireNotNull(metadata.contentLength) {
            "Upstream server did not report a content length"
        }
        val requestedRange = request.headers["range"]
        val range = if (requestedRange == null) {
            ByteRange(0L, totalBytes - 1L)
        } else {
            parseRange(requestedRange, totalBytes) ?: run {
                sendRangeNotSatisfiable(socket, totalBytes)
                return
            }
        }

        val status = if (requestedRange == null) 200 else 206
        val contentLength = range.endInclusive - range.start + 1L
        val responseHeaders = linkedMapOf(
            "Accept-Ranges" to "bytes",
            "Content-Length" to contentLength.toString(),
            "Content-Type" to (metadata.contentType ?: DEFAULT_CONTENT_TYPE),
            "Connection" to "close",
        )
        if (status == 206) {
            responseHeaders["Content-Range"] =
                "bytes ${range.start}-${range.endInclusive}/$totalBytes"
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        writeResponseHead(output, status, if (status == 206) "Partial Content" else "OK", responseHeaders)
        if (request.method == "HEAD") {
            output.flush()
            return
        }

        streamRange(entry, metadata, range, output)
        output.flush()
        entry.directory.setLastModified(System.currentTimeMillis())
    }

    private fun streamRange(
        entry: CacheEntry,
        metadata: CacheMetadata,
        range: ByteRange,
        output: OutputStream,
    ) {
        val firstSegment = range.start / segmentSizeBytes
        val lastSegment = range.endInclusive / segmentSizeBytes
        for (segmentIndex in firstSegment..lastSegment) {
            ensureSegment(
                entry = entry,
                metadata = metadata,
                segmentIndex = segmentIndex,
                sendStart = range.start,
                sendEnd = range.endInclusive,
                output = output,
            )
        }
    }

    private fun ensureSegment(
        entry: CacheEntry,
        metadata: CacheMetadata,
        segmentIndex: Long,
        sendStart: Long,
        sendEnd: Long,
        output: OutputStream?,
    ) {
        val totalBytes = requireNotNull(metadata.contentLength)
        val segmentStart = segmentIndex * segmentSizeBytes
        val segmentEnd = min(totalBytes - 1L, segmentStart + segmentSizeBytes - 1L)
        val expectedLength = segmentEnd - segmentStart + 1L
        val file = segmentFile(entry, segmentIndex)
        val lockKey = "${entry.key}:$segmentIndex"
        val lock = segmentLocks.getOrPut(lockKey) { Any() }

        try {
            synchronized(lock) {
                if (file.isFile && file.length() == expectedLength) {
                    if (output != null && sendStart <= sendEnd) {
                        copyCachedRange(file, segmentStart, sendStart, sendEnd, output)
                    }
                    file.setLastModified(System.currentTimeMillis())
                    return
                }
                file.delete()
                downloadSegment(
                    entry = entry,
                    metadata = metadata,
                    segmentIndex = segmentIndex,
                    segmentStart = segmentStart,
                    segmentEnd = segmentEnd,
                    sendStart = sendStart,
                    sendEnd = sendEnd,
                    output = output,
                    destination = file,
                )
            }
        } finally {
            segmentLocks.remove(lockKey, lock)
        }
    }

    private fun downloadSegment(
        entry: CacheEntry,
        metadata: CacheMetadata,
        segmentIndex: Long,
        segmentStart: Long,
        segmentEnd: Long,
        sendStart: Long,
        sendEnd: Long,
        output: OutputStream?,
        destination: File,
    ) {
        val partial = partFile(entry, segmentIndex)
        partial.delete()
        val connection = openConnection(entry.source, segmentStart, segmentEnd, metadata.validator())
        try {
            val responseCode = connection.responseCode
            requireCacheSupport(responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "Upstream server must support HTTP byte ranges (received $responseCode)"
            }
            validateContentRange(connection.getHeaderField("Content-Range"), segmentStart, segmentEnd)
            validateIdentity(entry, metadata, connection)

            var absoluteOffset = segmentStart
            var bytesWritten = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partial).buffered().use { cacheOutput ->
                    val buffer = ByteArray(BUFFER_SIZE_BYTES)
                    while (absoluteOffset <= segmentEnd) {
                        val remaining = segmentEnd - absoluteOffset + 1L
                        val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                        check(read >= 0) {
                            "Incomplete upstream range for ${entry.source.mediaId}"
                        }
                        cacheOutput.write(buffer, 0, read)
                        if (output != null && sendStart <= sendEnd) {
                            val chunkStart = absoluteOffset
                            val chunkEnd = absoluteOffset + read - 1L
                            val overlapStart = max(chunkStart, sendStart)
                            val overlapEnd = min(chunkEnd, sendEnd)
                            if (overlapStart <= overlapEnd) {
                                val offset = (overlapStart - chunkStart).toInt()
                                val length = (overlapEnd - overlapStart + 1L).toInt()
                                output.write(buffer, offset, length)
                                output.flush()
                            }
                        }
                        absoluteOffset += read
                        bytesWritten += read
                    }
                }
            }
            check(bytesWritten == segmentEnd - segmentStart + 1L) {
                "Incomplete cached segment for ${entry.source.mediaId}"
            }
            moveCompleted(partial, destination)
            destination.setLastModified(System.currentTimeMillis())
            trimToSize(destination)
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun copyCachedRange(
        file: File,
        segmentStart: Long,
        requestedStart: Long,
        requestedEnd: Long,
        output: OutputStream,
    ) {
        val start = max(segmentStart, requestedStart)
        val end = min(segmentStart + file.length() - 1L, requestedEnd)
        if (start > end) return
        RandomAccessFile(file, "r").use { input ->
            input.seek(start - segmentStart)
            var remaining = end - start + 1L
            val buffer = ByteArray(BUFFER_SIZE_BYTES)
            while (remaining > 0L) {
                val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                check(read >= 0) { "Cached segment ended unexpectedly" }
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private fun ensureMetadata(entry: CacheEntry): CacheMetadata = synchronized(entry) {
        val existing = entry.metadata ?: CacheMetadata(segmentSizeBytes = segmentSizeBytes)
        if (existing.contentLength != null) return@synchronized existing

        val connection = openConnection(entry.source, 0L, 0L, null)
        try {
            val responseCode = connection.responseCode
            requireCacheSupport(responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "Upstream server must support HTTP byte ranges (received $responseCode)"
            }
            val contentRange = parseContentRange(connection.getHeaderField("Content-Range"))
            val totalBytes = contentRange?.totalBytes ?: throw CacheNotSupportedException(
                "Upstream server did not report a complete Content-Range",
            )
            requireCacheSupport(totalBytes > 0L) { "Upstream media is empty" }
            existing.contentLength = totalBytes
            existing.contentType = connection.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf(String::isNotBlank)
            val normalizedContentType = existing.contentType.orEmpty().lowercase(Locale.US)
            requireCacheSupport("mpegurl" !in normalizedContentType && "dash+xml" !in normalizedContentType) {
                "HLS/DASH content must disable progressive content caching"
            }
            existing.etag = connection.getHeaderField("ETag")
            existing.lastModified = connection.getHeaderField("Last-Modified")
            existing.segmentSizeBytes = segmentSizeBytes
            entry.metadata = existing
            writeMetadata(entry, existing)
            existing
        } finally {
            connection.disconnect()
        }
    }

    private fun validateIdentity(
        entry: CacheEntry,
        metadata: CacheMetadata,
        connection: HttpURLConnection,
    ) {
        val responseRange = parseContentRange(connection.getHeaderField("Content-Range"))
        val responseTotal = responseRange?.totalBytes
        val responseEtag = connection.getHeaderField("ETag")
        val responseLastModified = connection.getHeaderField("Last-Modified")
        val changed = responseTotal != null && responseTotal != metadata.contentLength ||
            metadata.etag != null && responseEtag != null && metadata.etag != responseEtag ||
            metadata.lastModified != null && responseLastModified != null &&
            metadata.lastModified != responseLastModified
        if (changed) {
            invalidateContent(entry)
            error("Upstream media changed; retry playback with the same mediaId")
        }
        synchronized(entry) {
            if (metadata.etag == null && responseEtag != null) metadata.etag = responseEtag
            if (metadata.lastModified == null && responseLastModified != null) {
                metadata.lastModified = responseLastModified
            }
            writeMetadata(entry, metadata)
        }
    }

    private fun invalidateContent(entry: CacheEntry) = synchronized(entry) {
        entry.directory.listFiles()
            ?.filter { it.name.endsWith(SEGMENT_EXTENSION) || it.name.endsWith(PART_EXTENSION) }
            ?.forEach(File::delete)
        entry.metadata = CacheMetadata(segmentSizeBytes = segmentSizeBytes)
        writeMetadata(entry, requireNotNull(entry.metadata))
    }

    private fun openConnection(
        source: VideoSource,
        start: Long,
        end: Long,
        validator: String?,
    ): HttpURLConnection = (URL(source.uri).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        instanceFollowRedirects = true
        source.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("Range", "bytes=$start-$end")
        if (validator != null) setRequestProperty("If-Range", validator)
    }

    private fun validateContentRange(value: String?, expectedStart: Long, expectedEnd: Long) {
        val parsed = parseContentRange(value)
        requireCacheSupport(parsed != null && parsed.start == expectedStart && parsed.endInclusive == expectedEnd) {
            "Unexpected upstream Content-Range: $value"
        }
    }

    private inline fun requireCacheSupport(condition: Boolean, message: () -> String) {
        if (!condition) throw CacheNotSupportedException(message())
    }

    private fun validCachedBytes(entry: CacheEntry, totalBytes: Long): Long {
        val lastSegment = (totalBytes - 1L) / segmentSizeBytes
        var total = 0L
        for (index in 0L..lastSegment) {
            val expected = min(segmentSizeBytes, totalBytes - index * segmentSizeBytes)
            val file = segmentFile(entry, index)
            if (file.isFile && file.length() == expected) total += expected
        }
        return total
    }

    private fun trimToSize(protectedFile: File) {
        val files = baseDirectory.walkTopDown()
            .filter { it.isFile && it.name.endsWith(SEGMENT_EXTENSION) }
            .sortedBy(File::lastModified)
            .toList()
        var totalBytes = files.sumOf(File::length)
        for (file in files) {
            if (totalBytes <= maxCacheBytes) break
            if (file == protectedFile) continue
            val length = file.length()
            if (file.delete()) totalBytes -= length
        }
    }

    private fun readRequest(socket: Socket): ProxyRequest? {
        val input = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
        val requestLine = input.readLine()?.takeIf { it.isNotBlank() } ?: return null
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) return null
        val headers = linkedMapOf<String, String>()
        repeat(MAX_HEADER_LINES) {
            val line = input.readLine() ?: return@repeat
            if (line.isEmpty()) return ProxyRequest(parts[0].uppercase(Locale.US), parts[1], headers)
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                    line.substring(separator + 1).trim()
            }
        }
        error("HTTP request headers are too large")
    }

    private fun routeKey(path: String): String? {
        val prefix = "/media/$sessionToken/"
        return path.substringBefore('?').takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf { it.matches(CACHE_KEY_REGEX) }
    }

    private fun sendError(socket: Socket, status: Int, message: String) {
        if (socket.isClosed || socket.isOutputShutdown) return
        val body = message.toByteArray(StandardCharsets.UTF_8)
        val output = BufferedOutputStream(socket.getOutputStream())
        writeResponseHead(
            output,
            status,
            message,
            mapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Content-Length" to body.size.toString(),
                "Connection" to "close",
            ),
        )
        output.write(body)
        output.flush()
    }

    private fun sendRedirect(socket: Socket, location: String) {
        if (socket.isClosed || socket.isOutputShutdown) return
        val output = BufferedOutputStream(socket.getOutputStream())
        writeResponseHead(
            output,
            307,
            "Temporary Redirect",
            mapOf(
                "Location" to location,
                "Content-Length" to "0",
                "Connection" to "close",
            ),
        )
        output.flush()
    }

    private fun sendRangeNotSatisfiable(socket: Socket, totalBytes: Long) {
        val output = BufferedOutputStream(socket.getOutputStream())
        writeResponseHead(
            output,
            416,
            "Range Not Satisfiable",
            mapOf(
                "Content-Range" to "bytes */$totalBytes",
                "Content-Length" to "0",
                "Connection" to "close",
            ),
        )
        output.flush()
    }

    private fun writeResponseHead(
        output: OutputStream,
        status: Int,
        reason: String,
        headers: Map<String, String>,
    ) {
        val text = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(text.toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun readMetadata(entry: CacheEntry): CacheMetadata? {
        val file = metadataFile(entry)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            CacheMetadata(
                segmentSizeBytes = json.optLong("segmentSizeBytes", segmentSizeBytes),
                contentLength = json.optionalLong("contentLength"),
                contentType = json.optionalString("contentType"),
                etag = json.optionalString("etag"),
                lastModified = json.optionalString("lastModified"),
            )
        }.getOrNull()
    }

    private fun writeMetadata(entry: CacheEntry, metadata: CacheMetadata) {
        entry.directory.mkdirs()
        val json = JSONObject()
            .put("version", METADATA_VERSION)
            .put("segmentSizeBytes", metadata.segmentSizeBytes)
            .put("contentLength", metadata.contentLength ?: JSONObject.NULL)
            .put("contentType", metadata.contentType ?: JSONObject.NULL)
            .put("etag", metadata.etag ?: JSONObject.NULL)
            .put("lastModified", metadata.lastModified ?: JSONObject.NULL)
        val destination = metadataFile(entry)
        val partial = File(entry.directory, "$METADATA_FILE_NAME$PART_EXTENSION")
        partial.writeText(json.toString())
        moveCompleted(partial, destination)
    }

    private fun moveCompleted(partial: File, destination: File) {
        if (partial.renameTo(destination)) return
        partial.copyTo(destination, overwrite = true)
        partial.delete()
    }

    private fun metadataFile(entry: CacheEntry): File = File(entry.directory, METADATA_FILE_NAME)

    private fun segmentFile(entry: CacheEntry, index: Long): File =
        File(entry.directory, segmentFileName(index))

    private fun partFile(entry: CacheEntry, index: Long): File =
        File(entry.directory, "${segmentFileName(index)}$PART_EXTENSION")

    private fun JSONObject.optionalLong(name: String): Long? =
        if (has(name) && !isNull(name)) getLong(name) else null

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private data class ProxyRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

    private data class CacheEntry(
        val key: String,
        @Volatile var source: VideoSource,
        val directory: File,
        var metadata: CacheMetadata? = null,
        @Volatile var contentCachingDisabled: Boolean = false,
    )

    private class CacheNotSupportedException(message: String) : IllegalStateException(message)

    private data class CacheMetadata(
        var segmentSizeBytes: Long,
        var contentLength: Long? = null,
        var contentType: String? = null,
        var etag: String? = null,
        var lastModified: String? = null,
    ) {
        fun validator(): String? = etag ?: lastModified
    }

    internal data class ByteRange(val start: Long, val endInclusive: Long)

    internal data class ContentRange(
        val start: Long,
        val endInclusive: Long,
        val totalBytes: Long?,
    )

    internal companion object {
        const val DEFAULT_SEGMENT_SIZE_BYTES = 512L * 1024L
        private const val LOOPBACK_ADDRESS = "127.0.0.1"
        private const val METADATA_VERSION = 1
        private const val METADATA_FILE_NAME = "metadata.json"
        private const val SEGMENT_EXTENSION = ".segment"
        private const val PART_EXTENSION = ".part"
        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val CLIENT_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE_BYTES = 64 * 1024
        private const val MAX_HEADER_LINES = 100
        private val CACHE_KEY_REGEX = Regex("[0-9a-f]{64}")
        private val CONTENT_RANGE_REGEX = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)

        fun cacheKeyFor(mediaId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(mediaId.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        fun segmentFileName(index: Long): String =
            String.format(Locale.US, "%012d%s", index, SEGMENT_EXTENSION)

        fun parseRange(value: String, totalBytes: Long): ByteRange? {
            if (totalBytes <= 0L) return null
            val normalized = value.trim()
            if (!normalized.startsWith("bytes=", ignoreCase = true)) return null
            val specification = normalized.substringAfter('=').trim()
            if (',' in specification) return null
            val separator = specification.indexOf('-')
            if (separator < 0) return null
            val startText = specification.substring(0, separator).trim()
            val endText = specification.substring(separator + 1).trim()
            if (startText.isEmpty()) {
                val suffixLength = endText.toLongOrNull()?.takeIf { it > 0L } ?: return null
                val start = (totalBytes - suffixLength).coerceAtLeast(0L)
                return ByteRange(start, totalBytes - 1L)
            }
            val start = startText.toLongOrNull()?.takeIf { it >= 0L } ?: return null
            if (start >= totalBytes) return null
            val requestedEnd = if (endText.isEmpty()) {
                totalBytes - 1L
            } else {
                endText.toLongOrNull()?.takeIf { it >= start } ?: return null
            }
            return ByteRange(start, min(requestedEnd, totalBytes - 1L))
        }

        fun parseContentRange(value: String?): ContentRange? {
            val match = value?.let(CONTENT_RANGE_REGEX::matchEntire) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
            if (start < 0L || end < start) return null
            return ContentRange(start, end, total)
        }
    }
}
