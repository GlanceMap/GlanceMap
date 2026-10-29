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

internal data class StartupLocationWaitUiState(
    val visible: Boolean,
    val reason: String,
)

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
): StartupLocationWaitUiState {
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
    return StartupLocationWaitUiState(
        visible = eligible && visible,
        reason = startupLocationWaitReason(input, isScreenInteractive, hasRestoredViewport, environmentWarning),
    )
}

internal fun shouldWaitForStartupLocation(
    input: OnlineStartupMapFallbackInput,
    isScreenInteractive: Boolean,
    hasRestoredViewport: Boolean,
    environmentWarning: GpsEnvironmentWarning,
): Boolean {
    val canSearch = canSearchForStartupLocation(environmentWarning)
    return isScreenInteractive &&
        !hasRestoredViewport &&
        input.shouldFollowPosition &&
        canSearch &&
        (shouldWaitForOnlineStartupMapFallback(input) || shouldStartOnlineStartupMapCentering(input))
}

internal fun startupLocationWaitReason(
    input: OnlineStartupMapFallbackInput,
    isScreenInteractive: Boolean,
    hasRestoredViewport: Boolean,
    environmentWarning: GpsEnvironmentWarning,
): String =
    when {
        input.locationMarkerLatLong != null ||
            input.retainedLocationAnchor != null ||
            input.lastKnownLocation != null -> "location_received"
        input.offlineMode -> "gps_deactivated"
        !input.shouldTrackLocation -> "tracking_stopped"
        !isScreenInteractive -> "screen_inactive"
        hasRestoredViewport -> "viewport_restored"
        !input.shouldFollowPosition -> "panning"
        input.navigateTarget != null || input.pendingPoiFocusTarget != null -> "poi_focus"
        !canSearchForStartupLocation(environmentWarning) -> "gps_unavailable"
        else -> startupFallbackWaitReason(input.startupMapFallbackState)
    }

private fun canSearchForStartupLocation(warning: GpsEnvironmentWarning): Boolean =
    warning == GpsEnvironmentWarning.NONE ||
        warning == GpsEnvironmentWarning.AUTO_PHONE_DISCONNECTED_USING_WATCH_GPS

private fun startupFallbackWaitReason(state: StartupMapFallbackState): String =
    when (state) {
        StartupMapFallbackState.COMPLETED -> "fallback_centered"
        StartupMapFallbackState.CANCELLED -> "startup_cancelled"
        else -> "initial_location_search"
    }
