package io.github.mobileplayer.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores progress and complete network media files in app-private storage.
 * Android removes both when the app is uninstalled. The media directory uses
 * cache storage, so the OS may reclaim downloaded files under storage pressure.
 */
class AppPrivateVideoPlayerCache(
    context: Context,
    private val maxMediaCacheBytes: Long = DEFAULT_MAX_MEDIA_CACHE_BYTES,
) : VideoPlayerCache {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mediaDirectory = File(context.applicationContext.cacheDir, MEDIA_DIRECTORY)
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()

    init {
        require(maxMediaCacheBytes > 0L) { "Media cache size must be positive" }
    }

    override fun getProgress(mediaId: String): PlaybackProgress? {
        if (mediaId.isBlank()) return null
        val encoded = preferences.getString(progressKey(mediaId), null) ?: return null
        return runCatching {
            val json = JSONObject(encoded)
            PlaybackProgress(
                mediaId = json.getString("mediaId"),
                positionMs = json.getLong("positionMs"),
                durationMs = json.optLong("durationMs", 0L),
                updatedAtEpochMs = json.optLong("updatedAtEpochMs", 0L),
            )
        }.getOrNull()
    }

    override fun saveProgress(progress: PlaybackProgress) {
        val json = JSONObject()
            .put("mediaId", progress.mediaId)
            .put("positionMs", progress.positionMs)
            .put("durationMs", progress.durationMs)
            .put("updatedAtEpochMs", progress.updatedAtEpochMs)
        preferences.edit().putString(progressKey(progress.mediaId), json.toString()).apply()
    }

    override fun getCachedSource(source: VideoSource): VideoSource? {
        if (source.type != VideoSource.Type.NETWORK) return null
        val file = completedFile(source.mediaId)
        if (!file.isFile || file.length() <= 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return source.asLocalCacheSource(file)
    }

    override suspend fun cache(
        source: VideoSource,
        onProgress: (MediaCacheProgress) -> Unit,
    ): VideoSource {
        if (source.type == VideoSource.Type.LOCAL) return source
        require(source.supportsCompleteFileCaching()) {
            "The default cache supports progressive network files, not HLS/DASH manifests"
        }
        getCachedSource(source)?.let { return it }

        val lock = downloadLocks.getOrPut(source.mediaId) { Mutex() }
        return lock.withLock {
            getCachedSource(source)?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                download(source, onProgress)
                trimToSize()
                requireNotNull(getCachedSource(source)) {
                    "Cached media file was not created for ${source.mediaId}"
                }
            }
        }
    }

    override suspend fun remove(mediaId: String) {
        require(mediaId.isNotBlank()) { "mediaId must not be blank" }
        val lock = downloadLocks.getOrPut(mediaId) { Mutex() }
        lock.withLock {
            withContext(Dispatchers.IO) {
                preferences.edit().remove(progressKey(mediaId)).apply()
                completedFile(mediaId).delete()
                partialFile(mediaId).delete()
            }
        }
        downloadLocks.remove(mediaId, lock)
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            preferences.edit().clear().apply()
            mediaDirectory.listFiles()?.forEach(File::delete)
        }
    }

    private suspend fun download(
        source: VideoSource,
        onProgress: (MediaCacheProgress) -> Unit,
    ) {
        mediaDirectory.mkdirs()
        val partial = partialFile(source.mediaId)
        val completed = completedFile(source.mediaId)
        partial.delete()

        val connection = (URL(source.uri).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            source.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

        try {
            val responseCode = connection.responseCode
            check(responseCode in 200..299) {
                "Unable to cache ${source.mediaId}: HTTP $responseCode"
            }
            val contentType = connection.contentType.orEmpty().lowercase()
            check("mpegurl" !in contentType && "dash+xml" !in contentType) {
                "The default cache cannot store segmented streaming manifests"
            }
            val totalBytes = connection.getHeaderField("Content-Length")
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            check(totalBytes == null || totalBytes <= maxMediaCacheBytes) {
                "Media ${source.mediaId} is larger than the configured cache limit"
            }
            var bytesCached = 0L
            connection.inputStream.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesCached += read
                        onProgress(MediaCacheProgress(source.mediaId, bytesCached, totalBytes))
                    }
                }
            }
            check(totalBytes == null || bytesCached == totalBytes) {
                "Incomplete cache for ${source.mediaId}: $bytesCached of $totalBytes bytes"
            }
            if (!partial.renameTo(completed)) {
                partial.copyTo(completed, overwrite = true)
                partial.delete()
            }
            completed.setLastModified(System.currentTimeMillis())
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun trimToSize() {
        val files = mediaDirectory.listFiles()
            ?.filter { it.isFile && it.extension == COMPLETED_EXTENSION }
            ?.sortedBy(File::lastModified)
            .orEmpty()
        var totalBytes = files.sumOf(File::length)
        for (file in files) {
            if (totalBytes <= maxMediaCacheBytes) break
            val length = file.length()
            if (file.delete()) totalBytes -= length
        }
    }

    private fun completedFile(mediaId: String): File =
        File(mediaDirectory, "${mediaId.cacheKey()}.$COMPLETED_EXTENSION")

    private fun partialFile(mediaId: String): File =
        File(mediaDirectory, "${mediaId.cacheKey()}.$PARTIAL_EXTENSION")

    private fun progressKey(mediaId: String): String = "$PROGRESS_PREFIX${mediaId.cacheKey()}"

    private fun VideoSource.asLocalCacheSource(file: File): VideoSource = copy(
        uri = Uri.fromFile(file).toString(),
        type = VideoSource.Type.LOCAL,
        headers = emptyMap(),
    )

    private fun String.cacheKey(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun VideoSource.supportsCompleteFileCaching(): Boolean {
        val path = runCatching { Uri.parse(uri).path.orEmpty().lowercase() }.getOrDefault("")
        return !path.endsWith(".m3u8") && !path.endsWith(".mpd")
    }

    companion object {
        const val DEFAULT_MAX_MEDIA_CACHE_BYTES: Long = 512L * 1024L * 1024L
        private const val PREFERENCES_NAME = "video_mobile_player_progress"
        private const val MEDIA_DIRECTORY = "video-mobile-player"
        private const val PROGRESS_PREFIX = "progress_"
        private const val COMPLETED_EXTENSION = "media"
        private const val PARTIAL_EXTENSION = "part"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE_BYTES = 64 * 1024
    }
}
