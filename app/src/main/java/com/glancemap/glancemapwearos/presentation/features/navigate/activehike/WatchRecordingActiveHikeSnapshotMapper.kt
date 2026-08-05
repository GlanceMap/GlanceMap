package com.glancemap.glancemapwearos.presentation.features.navigate.activehike

import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.shared.transfer.ActiveHikePhase
import com.glancemap.shared.transfer.ActiveHikeSnapshot

internal fun TraceRecordingUiState.toActiveHikeSnapshot(
    recordedAtEpochMillis: Long = System.currentTimeMillis(),
): ActiveHikeSnapshot =
    ActiveHikeSnapshot(
        phase = if (paused) ActiveHikePhase.RECORDING_PAUSED else ActiveHikePhase.RECORDING,
        routeId = null,
        routeTitle = "Recording",
        distanceFromStartMeters = distanceMeters.nonNegativeFiniteOrNull(),
        distanceRemainingMeters = null,
        progressFraction = null,
        estimatedRemainingSeconds = null,
        remainingAscentMeters = null,
        remainingDescentMeters = null,
        activeDurationSeconds = activeDurationSeconds(recordedAtEpochMillis),
        currentSpeedMetersPerSecond =
            externalSpeedMps
                ?.toDouble()
                .nonNegativeFiniteOrNull()
                ?: latestLivePoint?.speedMps?.toDouble().nonNegativeFiniteOrNull(),
        currentAltitudeMeters = latestLivePoint?.elevationMeters?.takeIf(Double::isFinite),
        offRoute = false,
        recordedAtEpochMillis = recordedAtEpochMillis,
    )

private fun TraceRecordingUiState.activeDurationSeconds(nowEpochMillis: Long): Long? {
    val startedAt = startedAtMillis ?: return null
    val activePauseMillis =
        if (paused) {
            pausedAtMillis?.let { pausedAt -> (nowEpochMillis - pausedAt).coerceAtLeast(0L) } ?: 0L
        } else {
            0L
        }
    return ((nowEpochMillis - startedAt - accumulatedPausedMillis - activePauseMillis) / 1_000L).coerceAtLeast(0L)
}

private fun Double?.nonNegativeFiniteOrNull(): Double? = if (this != null && isFinite() && this >= 0.0) this else null
