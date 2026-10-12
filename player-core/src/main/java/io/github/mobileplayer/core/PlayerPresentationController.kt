package io.github.mobileplayer.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Where the host currently presents the shared playback session. */
enum class PlayerPresentationMode {
    /** A bounded region in the normal application page, usually portrait. */
    INLINE,

    /** A full-window player, usually with landscape orientation and immersive UI. */
    FULLSCREEN,

    /** Android PiP or an application-owned overlay window. */
    FLOATING,
}

data class PlayerPresentationCapabilities(
    val fullscreen: Boolean = true,
    val floating: Boolean = true,
    val close: Boolean = true,
) {
    fun supports(mode: PlayerPresentationMode): Boolean = when (mode) {
        PlayerPresentationMode.INLINE -> true
        PlayerPresentationMode.FULLSCREEN -> fullscreen
        PlayerPresentationMode.FLOATING -> floating
    }
}

data class PlayerPresentationState(
    val currentMode: PlayerPresentationMode = PlayerPresentationMode.INLINE,
    val pendingMode: PlayerPresentationMode? = null,
    /** Mode restored when the user expands or taps the floating player. */
    val floatingReturnMode: PlayerPresentationMode = PlayerPresentationMode.INLINE,
    val capabilities: PlayerPresentationCapabilities = PlayerPresentationCapabilities(),
    val closePending: Boolean = false,
    val isClosed: Boolean = false,
    val lastFailure: String? = null,
) {
    val displayedMode: PlayerPresentationMode
        get() = pendingMode ?: currentMode

    val hasPendingRequest: Boolean
        get() = pendingMode != null || closePending
}

sealed interface PlayerPresentationRequest {
    data class ChangeMode(
        val from: PlayerPresentationMode,
        val to: PlayerPresentationMode,
    ) : PlayerPresentationRequest

    data object Close : PlayerPresentationRequest
}

/**
 * Coordinates presentation without depending on Activity, orientation or
 * overlay-window APIs. One host collects [requests] and confirms or rejects
 * each platform operation.
 */
class PlayerPresentationController(
    initialMode: PlayerPresentationMode = PlayerPresentationMode.INLINE,
    startupMode: PlayerPresentationMode = initialMode,
    capabilities: PlayerPresentationCapabilities = PlayerPresentationCapabilities(),
    floatingReturnMode: PlayerPresentationMode = PlayerPresentationMode.INLINE,
) {
    private val requestChannel = Channel<PlayerPresentationRequest>(Channel.BUFFERED)
    private val mutableState = MutableStateFlow(
        PlayerPresentationState(
            currentMode = initialMode,
            floatingReturnMode = if (initialMode == PlayerPresentationMode.FLOATING) {
                floatingReturnMode
            } else {
                initialMode
            },
            capabilities = capabilities,
        ),
    )

    val state: StateFlow<PlayerPresentationState> = mutableState.asStateFlow()
    val requests: Flow<PlayerPresentationRequest> = requestChannel.receiveAsFlow()

    init {
        require(capabilities.supports(initialMode)) {
            "Unsupported initial presentation mode: $initialMode"
        }
        require(capabilities.supports(startupMode)) {
            "Unsupported startup presentation mode: $startupMode"
        }
        require(floatingReturnMode != PlayerPresentationMode.FLOATING) {
            "Floating return mode must be INLINE or FULLSCREEN"
        }
        require(capabilities.supports(floatingReturnMode)) {
            "Unsupported floating return mode: $floatingReturnMode"
        }
        if (startupMode != initialMode) {
            mutableState.value = mutableState.value.copy(pendingMode = startupMode)
            check(
                requestChannel.trySend(
                    PlayerPresentationRequest.ChangeMode(initialMode, startupMode),
                ).isSuccess,
            ) { "Unable to enqueue startup presentation mode" }
        }
    }

    fun requestInline(): Boolean = requestMode(PlayerPresentationMode.INLINE)

    fun requestFullscreen(): Boolean = requestMode(PlayerPresentationMode.FULLSCREEN)

    fun requestFloating(): Boolean = requestMode(PlayerPresentationMode.FLOATING)

    /** Returns a floating player to the mode from which it was minimized. */
    fun requestReturnFromFloating(): Boolean {
        val snapshot = mutableState.value
        if (snapshot.currentMode != PlayerPresentationMode.FLOATING) return false
        return requestMode(snapshot.floatingReturnMode)
    }

    fun requestMode(mode: PlayerPresentationMode): Boolean {
        val snapshot = mutableState.value
        if (
            snapshot.isClosed ||
            snapshot.hasPendingRequest ||
            snapshot.currentMode == mode ||
            !snapshot.capabilities.supports(mode)
        ) {
            return false
        }

        mutableState.value = snapshot.copy(
            pendingMode = mode,
            floatingReturnMode = if (
                mode == PlayerPresentationMode.FLOATING &&
                snapshot.currentMode != PlayerPresentationMode.FLOATING
            ) {
                snapshot.currentMode
            } else {
                snapshot.floatingReturnMode
            },
            lastFailure = null,
        )
        val accepted = requestChannel.trySend(
            PlayerPresentationRequest.ChangeMode(snapshot.currentMode, mode),
        ).isSuccess
        if (!accepted) mutableState.value = snapshot
        return accepted
    }

    fun requestClose(): Boolean {
        val snapshot = mutableState.value
        if (
            snapshot.isClosed ||
            snapshot.hasPendingRequest ||
            !snapshot.capabilities.close
        ) {
            return false
        }

        mutableState.value = snapshot.copy(closePending = true, lastFailure = null)
        val accepted = requestChannel.trySend(PlayerPresentationRequest.Close).isSuccess
        if (!accepted) mutableState.value = snapshot
        return accepted
    }

    /** Call after the host has completed rotation, PiP, or overlay migration. */
    fun confirmMode(mode: PlayerPresentationMode) {
        val snapshot = mutableState.value
        check(snapshot.capabilities.supports(mode)) { "Unsupported presentation mode: $mode" }
        mutableState.value = snapshot.copy(
            currentMode = mode,
            pendingMode = null,
            floatingReturnMode = when {
                mode != PlayerPresentationMode.FLOATING -> mode
                snapshot.currentMode != PlayerPresentationMode.FLOATING -> snapshot.currentMode
                else -> snapshot.floatingReturnMode
            },
            closePending = false,
            lastFailure = null,
        )
    }

    /** Call when the host has actually removed the player UI/session. */
    fun confirmClosed() {
        mutableState.value = mutableState.value.copy(
            pendingMode = null,
            closePending = false,
            isClosed = true,
            lastFailure = null,
        )
    }

    /** Keeps the previous mode active when a platform operation cannot complete. */
    fun rejectPendingRequest(message: String? = null) {
        mutableState.value = mutableState.value.copy(
            pendingMode = null,
            closePending = false,
            lastFailure = message,
        )
    }
}
