package io.github.mobileplayer.core

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Platform adapter used internally by a controller for device-level controls. */
interface PlayerEnvironmentAdapter {
    val state: StateFlow<PlayerEnvironmentState>
    val canSetSystemVolume: Boolean
    val canSetScreenBrightness: Boolean

    fun setSystemVolume(volume: Float)
    fun setScreenBrightness(brightness: Float)
}

/** Android implementation. Brightness is available when constructed with an Activity context. */
class AndroidPlayerEnvironment(context: Context) : PlayerEnvironmentAdapter {
    private val activity = context.findActivity()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutableState = MutableStateFlow(readState())

    override val state: StateFlow<PlayerEnvironmentState> = mutableState.asStateFlow()
    override val canSetSystemVolume: Boolean = true
    override val canSetScreenBrightness: Boolean = activity != null

    override fun setSystemVolume(volume: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val normalized = volume.coerceIn(0f, 1f)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (normalized * max).toInt(),
            0,
        )
        mutableState.value = readState()
    }

    override fun setScreenBrightness(brightness: Float) {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply {
            screenBrightness = brightness.coerceIn(0.01f, 1f)
        }
        mutableState.value = readState()
    }

    private fun readState(): PlayerEnvironmentState {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val currentActivity = activity
        val windowBrightness = currentActivity?.window?.attributes?.screenBrightness
        val brightness = when {
            windowBrightness == null -> null
            windowBrightness >= 0f -> windowBrightness
            else -> runCatching {
                Settings.System.getInt(currentActivity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            }.getOrNull()
        }
        return PlayerEnvironmentState(
            systemVolume = current.toFloat() / max,
            screenBrightness = brightness,
        )
    }
}

internal object NoOpPlayerEnvironment : PlayerEnvironmentAdapter {
    private val emptyState = MutableStateFlow(PlayerEnvironmentState())
    override val state: StateFlow<PlayerEnvironmentState> = emptyState
    override val canSetSystemVolume = false
    override val canSetScreenBrightness = false
    override fun setSystemVolume(volume: Float) = Unit
    override fun setScreenBrightness(brightness: Float) = Unit
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
