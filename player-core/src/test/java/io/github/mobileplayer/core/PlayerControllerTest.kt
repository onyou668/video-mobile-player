package io.github.mobileplayer.core

import android.view.Surface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerControllerTest {
    @Test
    fun autoPlayUsesTheSamePlayCommand() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.setVideoUrl("https://example.com/movie.mp4", autoPlay = true)

        assertEquals(listOf("prepare:0", "play"), engine.events.takeLast(2))
        assertTrue(controller.state.value.playWhenReady)
        controller.close()
    }

    @Test
    fun sourceCanPrepareWithoutStartingUntilPlay() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.setVideoUrl("https://example.com/movie.mp4", autoPlay = false)
        assertEquals(0, engine.playCalls)

        controller.play()
        assertEquals(1, engine.playCalls)
        assertTrue(controller.state.value.playWhenReady)
        controller.close()
    }

    @Test
    fun bufferingSeparatesPlaybackIntentFromActualPlayback() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine)
        controller.setVideoUrl("https://example.com/live.m3u8")

        engine.emit(
            EnginePlaybackState(
                phase = PlaybackPhase.BUFFERING,
                isPlaying = false,
                media = MediaInfo(MediaProtocol.HLS, PlaybackType.LIVE),
                capabilities = engineCapabilities,
            ),
        )
        runCurrent()

        assertTrue(controller.state.value.playWhenReady)
        assertFalse(controller.state.value.isPlaying)
        assertEquals(PlaybackType.LIVE, controller.state.value.media.playbackType)
        controller.close()
    }

    @Test
    fun engineSwitchRestoresPositionIntentVolumeAndSpeed() = runTest {
        val first = FakeEngine()
        val second = FakeEngine()
        val controller = controller(first, second)
        controller.setVideoUrl("https://example.com/movie.mp4")
        first.emit(
            EnginePlaybackState(
                phase = PlaybackPhase.READY,
                isPlaying = true,
                timeline = PlayerTimeline(positionMs = 42_000L, durationMs = 120_000L),
                capabilities = engineCapabilities,
            ),
        )
        runCurrent()
        controller.setPlayerVolume(0.4f)
        controller.setPlaybackSpeed(1.5f)

        controller.switchEngine(PlayerEngineId.IJK)

        assertTrue(first.released)
        assertEquals(42_000L, second.preparedPositionMs)
        assertEquals(0.4f, second.volume)
        assertEquals(1.5f, second.speed)
        assertEquals(1, second.playCalls)
        assertEquals(PlayerEngineId.IJK, controller.state.value.engineId)
        controller.close()
    }

    @Test
    fun settingTheSameUrlDoesNotResetARecreatedUi() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine)
        val url = "https://example.com/movie.mp4"
        controller.setVideoUrl(url)
        engine.emit(
            EnginePlaybackState(
                phase = PlaybackPhase.READY,
                isPlaying = true,
                timeline = PlayerTimeline(positionMs = 31_000L, durationMs = 90_000L),
                capabilities = engineCapabilities,
            ),
        )
        runCurrent()

        controller.setVideoUrl(url)

        assertEquals(1, engine.prepareCalls)
        assertEquals(31_000L, controller.state.value.positionMs)
        controller.close()
    }

    @Test
    fun unsupportedFormatIsTerminalAndCannotRetry() = runTest {
        val engine = FakeEngine()
        val controller = createPlayerController(
            initialEngineId = PlayerEngineId.MEDIA3,
            engineProviders = listOf(FakeProvider(PlayerEngineId.MEDIA3, engine, supports = false)),
            scope = this,
        )

        controller.setVideoUrl("https://example.com/movie.unsupported")
        controller.retry()

        assertEquals(PlaybackPhase.ERROR, controller.state.value.phase)
        assertEquals(PlayerErrorCategory.UNSUPPORTED, controller.state.value.error?.category)
        assertFalse(controller.state.value.error?.isRetryable ?: true)
        assertEquals(0, engine.prepareCalls)
        controller.close()
    }

    @Test
    fun sourceAutomaticallySelectsACompatibleEngineAndPreparesOnce() = runTest {
        val unsupportedEngine = FakeEngine()
        val compatibleEngine = FakeEngine()
        val controller = createPlayerController(
            initialEngineId = PlayerEngineId.MEDIA3,
            engineProviders = listOf(
                FakeProvider(PlayerEngineId.MEDIA3, unsupportedEngine, supports = false),
                FakeProvider(PlayerEngineId.IJK, compatibleEngine, supports = true),
            ),
            scope = this,
        )

        controller.setVideoUrl("https://example.com/movie.custom")

        assertEquals(0, unsupportedEngine.prepareCalls)
        assertEquals(1, compatibleEngine.prepareCalls)
        assertEquals(1, compatibleEngine.playCalls)
        assertEquals(PlayerEngineId.IJK, controller.state.value.engineId)
        controller.close()
    }

    @Test
    fun retryableFailureRetainsIntentAndPreparesAgain() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine)
        controller.setVideoUrl("https://example.com/live.m3u8")
        engine.emit(
            EnginePlaybackState(
                phase = PlaybackPhase.ERROR,
                error = PlaybackError(
                    code = "network",
                    message = "Connection failed",
                    category = PlayerErrorCategory.NETWORK,
                    retryPolicy = RetryPolicy.BACKOFF,
                ),
                capabilities = engineCapabilities,
            ),
        )
        runCurrent()

        controller.retry()

        assertEquals(2, engine.prepareCalls)
        assertEquals(2, engine.playCalls)
        assertTrue(controller.state.value.playWhenReady)
        controller.close()
    }

    @Test
    fun controllerExposesEnvironmentCapabilitiesAndCommands() = runTest {
        val environment = FakeEnvironment()
        val engine = FakeEngine()
        val controller = createPlayerController(
            initialEngineId = PlayerEngineId.MEDIA3,
            engineProviders = listOf(FakeProvider(PlayerEngineId.MEDIA3, engine)),
            scope = this,
            environment = environment,
        )
        runCurrent()

        controller.setSystemVolume(0.7f)
        controller.setScreenBrightness(0.3f)

        assertTrue(controller.state.value.capabilities.canSetSystemVolume)
        assertTrue(controller.state.value.capabilities.canSetScreenBrightness)
        assertEquals(0.7f, environment.actualSystemVolume)
        assertEquals(0.3f, environment.brightness)
        controller.close()
    }

    @Test
    fun subtitleCuesAreExposedAsReadOnlyControllerState() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine)
        controller.setVideoUrl("https://example.com/subtitled.mpd")

        engine.emit(
            EnginePlaybackState(
                phase = PlaybackPhase.READY,
                tracks = PlayerTracks(
                    subtitles = listOf(
                        PlayerTrack("text:0", TrackType.SUBTITLE, language = "zh"),
                    ),
                ),
                subtitleCues = listOf(PlayerSubtitleCue("first line"), PlayerSubtitleCue("second line")),
                capabilities = engineCapabilities,
            ),
        )
        runCurrent()

        assertEquals(listOf("first line", "second line"), controller.state.value.subtitleCues.map { it.text })
        controller.close()
    }

    @Test
    fun changingSourceClearsSubtitleTracksAndCuesImmediately() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine)
        controller.setVideoUrl("https://example.com/first.mpd")
        engine.emit(
            EnginePlaybackState(
                phase = PlaybackPhase.READY,
                tracks = PlayerTracks(
                    subtitles = listOf(PlayerTrack("text:0", TrackType.SUBTITLE)),
                ),
                subtitleCues = listOf(PlayerSubtitleCue("old subtitle")),
                capabilities = engineCapabilities,
            ),
        )
        runCurrent()

        controller.setVideoUrl("https://example.com/second.mp4", autoPlay = false)

        assertTrue(controller.state.value.tracks.subtitles.isEmpty())
        assertTrue(controller.state.value.subtitleCues.isEmpty())
        controller.close()
    }

    @Test
    fun fiveTimesPlaybackSpeedIsPreserved() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.setPlaybackSpeed(5f)

        assertEquals(5f, engine.speed)
        assertEquals(5f, controller.state.value.playbackSpeed)
        controller.close()
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        media3: FakeEngine,
        ijk: FakeEngine? = null,
    ): PlayerController {
        val providers = buildList {
            add(FakeProvider(PlayerEngineId.MEDIA3, media3))
            ijk?.let { add(FakeProvider(PlayerEngineId.IJK, it)) }
        }
        return createPlayerController(PlayerEngineId.MEDIA3, providers, this)
    }

    private class FakeProvider(
        id: PlayerEngineId,
        private val engine: FakeEngine,
        private val supports: Boolean = true,
    ) : PlaybackEngineProvider {
        override val info = PlayerEngineInfo(id, id.value)
        override fun supports(source: VideoSource): Boolean = supports
        override fun create(): PlaybackEngine = engine
    }

    private class FakeEngine : PlaybackEngine {
        private val mutableState = MutableStateFlow(
            EnginePlaybackState(capabilities = engineCapabilities),
        )
        override val state: StateFlow<EnginePlaybackState> = mutableState
        val events = mutableListOf<String>()
        var prepareCalls = 0
        var preparedPositionMs = 0L
        var playCalls = 0
        var volume = 1f
        var speed = 1f
        var released = false

        override fun setSurface(surface: Surface?) = Unit
        override fun prepare(source: VideoSource, startPositionMs: Long) {
            prepareCalls++
            preparedPositionMs = startPositionMs
            events += "prepare:$startPositionMs"
        }

        override fun play() { playCalls++; events += "play" }
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setPlayerVolume(volume: Float) { this.volume = volume }
        override fun setPlaybackSpeed(speed: Float) { this.speed = speed }
        override fun release() { released = true }
        fun emit(value: EnginePlaybackState) { mutableState.value = value }
    }

    private class FakeEnvironment : PlayerEnvironmentAdapter {
        private val mutableState = MutableStateFlow(
            PlayerEnvironmentState(systemVolume = 0.5f, screenBrightness = 0.5f),
        )
        override val state: StateFlow<PlayerEnvironmentState> = mutableState
        override val canSetSystemVolume = true
        override val canSetScreenBrightness = true
        var actualSystemVolume = 0.5f
        var brightness = 0.5f

        override fun setSystemVolume(volume: Float) {
            actualSystemVolume = volume
            mutableState.value = mutableState.value.copy(systemVolume = volume)
        }

        override fun setScreenBrightness(brightness: Float) {
            this.brightness = brightness
            mutableState.value = mutableState.value.copy(screenBrightness = brightness)
        }
    }

    companion object {
        private val engineCapabilities = PlayerCapabilities(
            canPlay = true,
            canPause = true,
            canSeek = true,
            canSetPlaybackSpeed = true,
            canSetPlayerVolume = true,
        )
    }
}
