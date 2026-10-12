package io.github.mobileplayer.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPresentationControllerTest {
    @Test
    fun fullscreenStartupIsQueuedUntilHostCollectsIt() = runTest {
        val controller = PlayerPresentationController(
            startupMode = PlayerPresentationMode.FULLSCREEN,
        )

        assertEquals(PlayerPresentationMode.INLINE, controller.state.value.currentMode)
        assertEquals(PlayerPresentationMode.FULLSCREEN, controller.state.value.displayedMode)
        assertEquals(
            PlayerPresentationRequest.ChangeMode(
                from = PlayerPresentationMode.INLINE,
                to = PlayerPresentationMode.FULLSCREEN,
            ),
            controller.requests.first(),
        )
    }

    @Test
    fun modeChangeWaitsForHostConfirmation() = runTest {
        val controller = PlayerPresentationController()

        assertTrue(controller.requestFullscreen())
        assertEquals(PlayerPresentationMode.FULLSCREEN, controller.state.value.pendingMode)
        assertEquals(
            PlayerPresentationRequest.ChangeMode(
                from = PlayerPresentationMode.INLINE,
                to = PlayerPresentationMode.FULLSCREEN,
            ),
            controller.requests.first(),
        )

        controller.confirmMode(PlayerPresentationMode.FULLSCREEN)

        assertEquals(PlayerPresentationMode.FULLSCREEN, controller.state.value.currentMode)
        assertNull(controller.state.value.pendingMode)
    }

    @Test
    fun rejectedPlatformOperationKeepsCurrentMode() = runTest {
        val controller = PlayerPresentationController()
        controller.requestFloating()
        controller.requests.first()

        controller.rejectPendingRequest("Overlay permission denied")

        assertEquals(PlayerPresentationMode.INLINE, controller.state.value.currentMode)
        assertNull(controller.state.value.pendingMode)
        assertEquals("Overlay permission denied", controller.state.value.lastFailure)
    }

    @Test
    fun unsupportedModeDoesNotCreateRequest() {
        val controller = PlayerPresentationController(
            capabilities = PlayerPresentationCapabilities(floating = false),
        )

        assertFalse(controller.requestFloating())
        assertFalse(controller.state.value.hasPendingRequest)
    }

    @Test
    fun closeRequiresHostConfirmation() = runTest {
        val controller = PlayerPresentationController()

        assertTrue(controller.requestClose())
        assertTrue(controller.state.value.closePending)
        assertEquals(PlayerPresentationRequest.Close, controller.requests.first())

        controller.confirmClosed()

        assertTrue(controller.state.value.isClosed)
        assertFalse(controller.state.value.closePending)
    }

    @Test
    fun floatingPlayerReturnsToModeFromWhichItWasMinimized() = runTest {
        val controller = PlayerPresentationController(
            initialMode = PlayerPresentationMode.FULLSCREEN,
        )

        assertTrue(controller.requestFloating())
        assertEquals(PlayerPresentationMode.FULLSCREEN, controller.state.value.floatingReturnMode)
        controller.requests.first()
        controller.confirmMode(PlayerPresentationMode.FLOATING)

        assertTrue(controller.requestReturnFromFloating())
        assertEquals(
            PlayerPresentationRequest.ChangeMode(
                from = PlayerPresentationMode.FLOATING,
                to = PlayerPresentationMode.FULLSCREEN,
            ),
            controller.requests.first(),
        )
        controller.confirmMode(PlayerPresentationMode.FULLSCREEN)

        assertEquals(PlayerPresentationMode.FULLSCREEN, controller.state.value.currentMode)
    }
}
