package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.glancemap.glancemapwearos.core.service.location.model.GpsEnvironmentWarning
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

internal class StartupLocationWaitMessage {
    private var consumed = false

    suspend fun showOnce(onVisibilityChanged: (Boolean) -> Unit) {
        if (consumed) return
        consumed = true
        try {
            delay(5_000L)
            onVisibilityChanged(true)
            awaitCancellation()
        } finally {
            onVisibilityChanged(false)
        }
    }

    fun dismiss() {
        consumed = true
    }
}

@Composable
internal fun rememberStartupLocationWaitingMessage(
    input: OnlineStartupMapFallbackInput,
    isScreenInteractive: Boolean,
    hasRestoredViewport: Boolean,
    environmentWarning: GpsEnvironmentWarning,
    message: StartupLocationWaitMessage,
): Boolean {
    val eligible =
        shouldWaitForStartupLocation(input, isScreenInteractive, hasRestoredViewport, environmentWarning)
    var visible by remember(message) { mutableStateOf(false) }
    DisposableEffect(message) {
        // Leaving Navigate also consumes the opportunity before the five-second delay finishes.
        onDispose { message.dismiss() }
    }
    LaunchedEffect(eligible, message) {
        if (eligible) message.showOnce { visible = it }
    }
    return eligible && visible
}

internal fun shouldWaitForStartupLocation(
    input: OnlineStartupMapFallbackInput,
    isScreenInteractive: Boolean,
    hasRestoredViewport: Boolean,
    environmentWarning: GpsEnvironmentWarning,
): Boolean {
    val canSearch =
        environmentWarning == GpsEnvironmentWarning.NONE ||
            environmentWarning == GpsEnvironmentWarning.AUTO_PHONE_DISCONNECTED_USING_WATCH_GPS
    return isScreenInteractive &&
        !hasRestoredViewport &&
        input.shouldFollowPosition &&
        canSearch &&
        (shouldWaitForOnlineStartupMapFallback(input) || shouldStartOnlineStartupMapCentering(input))
}
