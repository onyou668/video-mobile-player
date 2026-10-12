package io.github.mobileplayer.core

import android.view.Surface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerControllerTest {
    @Test
    fun localSourceStartsAtZero() = runTest {
        val media3 = FakeEngine()
        val controller = controller(media3, FakeEngine(), FakeVideoContentCache())

        controller.setMedia(
            VideoSource.local("content://media/video/42"),
        )

        assertEquals(0L, media3.preparedPositionMs)
        assertEquals(0L, controller.state.value.positionMs)
        controller.close()
    }

    @Test
    fun callerProvidedStartPositionIsUsed() = runTest {
        val source = VideoSource.network("https://example.com/episode-1.mp4")
        val media3 = FakeEngine()
        val controller = controller(media3, FakeEngine())

        controller.setMedia(source, startPositionMs = 25_000L)

        assertEquals(25_000L, media3.preparedPositionMs)
        assertEquals(25_000L, controller.state.value.positionMs)
        controller.close()
    }

    @Test
    fun callerProvidedPositionSurvivesEngineSwitches() = runTest {
        val source = VideoSource.network("https://example.com/episode-2.mp4")
        val media3 = FakeEngine()
        val ijk = FakeEngine()
        val controller = controller(media3, ijk)

        controller.setMedia(source, startPositionMs = 31_000L)
        media3.emit(
            EnginePlaybackState(
                status = PlaybackStatus.READY,
                isPlaying = true,
                positionMs = 38_000L,
                durationMs = 100_000L,
            ),
        )
        runCurrent()
        controller.switchEngine(PlayerEngineType.IJK)

        assertEquals(38_000L, ijk.preparedPositionMs)
        assertEquals(PlayerEngineType.IJK, controller.state.value.engineType)
        controller.close()
    }

    @Test
    fun resolvedCacheSourceIsSharedAcrossEngineSwitches() = runTest {
        val source = VideoSource.network("https://example.com/movie.mp4")
        val cachedSource = source.copy(
            uri = "file:///cache/cached-movie.media",
            type = VideoSource.Type.LOCAL,
        )
        val cache = FakeVideoContentCache().apply {
            cachedSources[source.mediaId] = cachedSource
        }
        val media3 = FakeEngine()
        val ijk = FakeEngine()
        val controller = controller(media3, ijk, cache)

        controller.setMedia(source)
        media3.emit(
            EnginePlaybackState(
                status = PlaybackStatus.READY,
                positionMs = 15_000L,
                durationMs = 90_000L,
            ),
        )
        runCurrent()
        controller.switchEngine(PlayerEngineType.IJK)

        assertSame(cachedSource, media3.preparedSource)
        assertSame(cachedSource, ijk.preparedSource)
        assertEquals(15_000L, ijk.preparedPositionMs)
        controller.close()
    }

    @Test
    fun callerControlsCompletedPlaybackPosition() = runTest {
        val source = VideoSource.network("https://example.com/movie.mp4")
        val media3 = FakeEngine()
        val controller = controller(media3, FakeEngine())

        controller.setMedia(source, startPositionMs = 98_000L)

        assertEquals(98_000L, media3.preparedPositionMs)
        controller.close()
    }

    @Test
    fun unchangedSourceDoesNotResetSharedPlaybackSession() = runTest {
        val media3 = FakeEngine()
        val controller = controller(media3, FakeEngine())
        val source = VideoSource.network("https://example.com/video.mp4")

        assertTrue(controller.setMediaIfChanged(source))
        media3.emit(
            EnginePlaybackState(
                status = PlaybackStatus.READY,
                isPlaying = true,
                positionMs = 35_000L,
                durationMs = 90_000L,
            ),
        )
        runCurrent()

        assertFalse(controller.setMediaIfChanged(source))

        assertEquals(1, media3.prepareCalls)
        assertEquals(35_000L, controller.state.value.positionMs)
        controller.close()
    }

    @Test
    fun hostCanReplaceUrlAndTitleThroughController() = runTest {
        val media3 = FakeEngine()
        val controller = controller(media3, FakeEngine())
        val first = VideoSource.network(
            url = "https://example.com/episode-1.mp4",
            title = "Episode 1",
        )
        val second = VideoSource.network(
            url = "https://example.com/episode-2.mp4",
            title = "Episode 2",
        )

        controller.setMedia(first)
        controller.setMedia(second)

        assertEquals(2, media3.prepareCalls)
        assertEquals(second, controller.state.value.source)
        assertEquals("Episode 2", controller.state.value.source?.title)
        assertEquals(0L, controller.state.value.positionMs)
        controller.close()
    }

    @Test
    fun switchingEngineRestoresPlaybackConfiguration() = runTest {
        val media3 = FakeEngine()
        val ijk = FakeEngine()
        val controller = controller(media3, ijk)
        val source = VideoSource.network(
            url = "https://example.com/video.mp4",
            headers = mapOf("Authorization" to "Bearer token"),
        )

        controller.setVolume(0.4f)
        controller.setPlaybackSpeed(1.5f)
        controller.setMedia(source, playWhenReady = true)
        media3.emit(
            EnginePlaybackState(
                status = PlaybackStatus.READY,
                isPlaying = true,
                positionMs = 42_000L,
                durationMs = 120_000L,
            ),
        )
        runCurrent()

        controller.switchEngine(PlayerEngineType.IJK)

        assertTrue(media3.released)
        assertSame(source, ijk.preparedSource)
        assertEquals(42_000L, ijk.preparedPositionMs)
        assertTrue(ijk.preparedPlayWhenReady)
        assertEquals(0.4f, ijk.actualVolume)
        assertEquals(1.5f, ijk.speed)
        assertEquals(PlayerEngineType.IJK, controller.state.value.engineType)
        controller.close()
    }

    @Test
    fun hostStopOnlyResumesWhenPlaybackWasActive() = runTest {
        val media3 = FakeEngine()
        val controller = controller(media3, FakeEngine())
        controller.setMedia(VideoSource.local("content://media/video/1"))
        media3.emit(EnginePlaybackState(status = PlaybackStatus.READY, isPlaying = true))
        runCurrent()

        controller.onHostStop()
        assertEquals(1, media3.pauseCalls)

        controller.onHostStart()
        assertEquals(1, media3.playCalls)

        media3.emit(EnginePlaybackState(status = PlaybackStatus.READY, isPlaying = false))
        runCurrent()
        controller.pause()
        controller.onHostStop()
        controller.onHostStart()
        assertEquals(1, media3.playCalls)
        controller.close()
    }

    @Test
    fun explicitPauseWhileStoppedCancelsAutomaticResume() = runTest {
        val media3 = FakeEngine()
        val controller = controller(media3, FakeEngine())
        controller.setMedia(VideoSource.local("content://media/video/2"), playWhenReady = true)
        media3.emit(EnginePlaybackState(status = PlaybackStatus.READY, isPlaying = true))
        runCurrent()

        controller.onHostStop()
        controller.pause()
        controller.onHostStart()
        runCurrent()

        assertEquals(0, media3.playCalls)
        assertFalse(controller.state.value.isPlaying)
        controller.close()
    }

    @Test
    fun bufferingPlaybackResumesAfterHostReturns() = runTest {
        val media3 = FakeEngine()
        val controller = controller(media3, FakeEngine())
        controller.setMedia(VideoSource.network("https://example.com/live.m3u8"), playWhenReady = true)
        media3.emit(EnginePlaybackState(status = PlaybackStatus.BUFFERING, isPlaying = false))
        runCurrent()

        controller.onHostStop()
        controller.onHostStart()

        assertEquals(1, media3.pauseCalls)
        assertEquals(1, media3.playCalls)
        controller.close()
    }

    @Test
    fun floatingPlaybackIsNotPausedWhenPageHostStops() = runTest {
        val media3 = FakeEngine()
        val controller = controller(media3, FakeEngine())
        controller.setMedia(VideoSource.network("https://example.com/video.mp4"))
        media3.emit(
            EnginePlaybackState(
                status = PlaybackStatus.READY,
                isPlaying = true,
                positionMs = 18_000L,
                networkSpeedBytesPerSecond = 2_000_000L,
            ),
        )
        runCurrent()

        controller.onHostStop(keepPlaying = true)
        controller.onHostStart()

        assertEquals(0, media3.pauseCalls)
        assertEquals(0, media3.playCalls)
        assertTrue(controller.state.value.isPlaying)
        assertEquals(18_000L, controller.state.value.positionMs)
        assertEquals(2_000_000L, controller.state.value.networkSpeedBytesPerSecond)
        controller.close()
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        media3: FakeEngine,
        ijk: FakeEngine,
        cache: VideoContentCache? = null,
    ) = VideoPlayerController(
        initialEngine = PlayerEngineType.MEDIA3,
        factories = mapOf(
            PlayerEngineType.MEDIA3 to PlaybackEngineFactory { media3 },
            PlayerEngineType.IJK to PlaybackEngineFactory { ijk },
        ),
        scope = this,
        cache = cache,
    )

    private class FakeVideoContentCache : VideoContentCache {
        val cachedSources = mutableMapOf<String, VideoSource>()

        override fun getCachedSource(source: VideoSource): VideoSource? =
            cachedSources[source.mediaId]

        override suspend fun cache(
            source: VideoSource,
            onProgress: (MediaCacheProgress) -> Unit,
        ): VideoSource = source

        override suspend fun remove(mediaId: String) {
            cachedSources.remove(mediaId)
        }

        override suspend fun clear() {
            cachedSources.clear()
        }
    }

    private class FakeEngine : PlaybackEngine {
        private val mutableState = MutableStateFlow(EnginePlaybackState())
        override val state: StateFlow<EnginePlaybackState> = mutableState

        var preparedSource: VideoSource? = null
        var prepareCalls = 0
        var preparedPlayWhenReady = false
        var preparedPositionMs = 0L
        var actualVolume = 1f
        var speed = 1f
        var playCalls = 0
        var pauseCalls = 0
        var released = false

        override fun setSurface(surface: Surface?) = Unit

        override fun prepare(source: VideoSource, playWhenReady: Boolean, startPositionMs: Long) {
            prepareCalls++
            preparedSource = source
            preparedPlayWhenReady = playWhenReady
            preparedPositionMs = startPositionMs
        }

        override fun play() {
            playCalls++
        }

        override fun pause() {
            pauseCalls++
            mutableState.value = mutableState.value.copy(isPlaying = false)
        }

        override fun seekTo(positionMs: Long) = Unit

        override fun setVolume(volume: Float) {
            actualVolume = volume
        }

        override fun setPlaybackSpeed(speed: Float) {
            this.speed = speed
        }

        override fun release() {
            released = true
        }

        fun emit(state: EnginePlaybackState) {
            mutableState.value = state
        }
    }
}
