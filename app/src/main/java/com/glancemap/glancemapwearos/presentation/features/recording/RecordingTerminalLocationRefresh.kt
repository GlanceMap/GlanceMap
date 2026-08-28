package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import com.glancemap.glancemapwearos.data.repository.SettingsRepository

data class RecordingRuntimeDependencies(
    val applicationContext: Context? = null,
    val requestImmediateLocation: (String) -> Unit = {},
)

internal enum class RecordingTerminalAction {
    PAUSE,
    SAVE,
}

internal data class RecordingTerminalActionRequest(
    val action: RecordingTerminalAction,
    val saveTitleOverride: String? = null,
)

internal data class RecordingTerminalLocationRefreshInput(
    val active: Boolean,
    val paused: Boolean,
    val saving: Boolean,
    val activityProfile: String,
    val hasRecordedPoint: Boolean,
    val previousSpeedMps: Float?,
    val acceptedPointAgeMillis: Long,
    val latestLocationAgeMillis: Long,
    val freshLocationMaxAgeMillis: Long,
)

/** Holds one manual pause/save while a stale Bike terminal endpoint is reacquired. */
internal class RecordingTerminalLocationRefresh {
    private var pendingRequest: RecordingTerminalActionRequest? = null
    private var requestedAtElapsedMillis = Long.MIN_VALUE

    val isPending: Boolean get() = pendingRequest != null

    fun begin(
        request: RecordingTerminalActionRequest,
        input: RecordingTerminalLocationRefreshInput,
        nowElapsedMillis: Long,
    ): Boolean {
        if (pendingRequest != null) {
            if (request.action == RecordingTerminalAction.SAVE) {
                pendingRequest = request
            }
        } else if (shouldRequestTerminalRecordingLocationRefresh(input)) {
            pendingRequest = request
            requestedAtElapsedMillis = nowElapsedMillis
        }
        return pendingRequest != null
    }

    fun takeActionAfterResolvedCandidate(candidateElapsedMillis: Long): RecordingTerminalActionRequest? =
        pendingRequest
            ?.takeIf { candidateElapsedMillis >= requestedAtElapsedMillis }
            ?.also { clear() }

    fun takeActionOnTimeout(): RecordingTerminalActionRequest? = pendingRequest?.also { clear() }

    fun cancelPause(): Boolean {
        if (pendingRequest?.action != RecordingTerminalAction.PAUSE) return false
        clear()
        return true
    }

    fun clear() {
        pendingRequest = null
        requestedAtElapsedMillis = Long.MIN_VALUE
    }
}

internal fun shouldRequestTerminalRecordingLocationRefresh(
    input: RecordingTerminalLocationRefreshInput,
): Boolean {
    val previousSpeed = input.previousSpeedMps?.takeIf { it.isFinite() && it >= 0f } ?: return false
    return input.active &&
        !input.paused &&
        !input.saving &&
        input.activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE &&
        input.hasRecordedPoint &&
        previousSpeed > RECORDING_MOTION_BIKE_SPEED_THRESHOLD_MPS &&
        input.acceptedPointAgeMillis in 1..RECORDING_MOTION_CONFIRMATION_MAX_INTERVAL_MS &&
        input.latestLocationAgeMillis > input.freshLocationMaxAgeMillis
}
