package io.github.mobileplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.mobileplayer.core.PlayerPresentationCapabilities
import io.github.mobileplayer.core.PlayerPresentationController
import io.github.mobileplayer.core.PlayerPresentationMode

@Composable
fun rememberPlayerPresentationController(
    initialMode: PlayerPresentationMode = PlayerPresentationMode.INLINE,
    startupMode: PlayerPresentationMode = initialMode,
    capabilities: PlayerPresentationCapabilities = PlayerPresentationCapabilities(),
): PlayerPresentationController {
    val saver = remember(capabilities) {
        listSaver<PlayerPresentationController, String>(
            save = { controller ->
                val state = controller.state.value
                listOf(state.currentMode.name, state.floatingReturnMode.name)
            },
            restore = { saved ->
                val restoredMode = PlayerPresentationMode.valueOf(saved[0])
                PlayerPresentationController(
                    initialMode = restoredMode,
                    startupMode = restoredMode,
                    capabilities = capabilities,
                    floatingReturnMode = PlayerPresentationMode.valueOf(saved[1]),
                )
            },
        )
    }
    return rememberSaveable(initialMode, startupMode, capabilities, saver = saver) {
        PlayerPresentationController(
            initialMode = initialMode,
            startupMode = startupMode,
            capabilities = capabilities,
        )
    }
}
