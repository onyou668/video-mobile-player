package io.github.mobileplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.mobileplayer.core.PlayerPresentationCapabilities
import io.github.mobileplayer.core.PlayerPresentationController
import io.github.mobileplayer.core.PlayerPresentationMode

@Composable
fun rememberPlayerPresentationController(
    initialMode: PlayerPresentationMode = PlayerPresentationMode.INLINE,
    startupMode: PlayerPresentationMode = initialMode,
    capabilities: PlayerPresentationCapabilities = PlayerPresentationCapabilities(),
): PlayerPresentationController = remember(initialMode, startupMode, capabilities) {
    PlayerPresentationController(
        initialMode = initialMode,
        startupMode = startupMode,
        capabilities = capabilities,
    )
}
