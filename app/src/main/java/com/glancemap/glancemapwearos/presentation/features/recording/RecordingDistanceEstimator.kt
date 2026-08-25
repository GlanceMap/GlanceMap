package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import kotlin.math.min

internal fun resolveRecordingContinuityRecoveryGapMillis(
    deliveryGapMillis: Long,
    committedPointGapMillis: Long,
    thresholdMillis: Long,
): Long? =
    maxOf(deliveryGapMillis, committedPointGapMillis)
        .takeIf { it >= thresholdMillis }

/**
 * Keeps the distance total independent from a visual recovery connector. Normal canonical
 * points contribute their smoothed geometry. When a real delivery outage or confirmed
 * relocation is bridged for a continuous GPX line, reported pace and accuracy bound the
 * contribution so a bad reacquisition cannot add a large diagonal to the activity total.
 */
internal data class RecordingDistanceEstimate(
    val distanceMeters: Double,
    val capped: Boolean,
    val maximumTrustedMeters: Double? = null,
)

internal fun estimateRecordingDistanceDelta(
    geometricDeltaMeters: Double,
    previous: RecordedTracePoint?,
    current: RecordedTracePoint,
    elapsedSincePreviousMs: Long,
    activityProfile: String,
    isContinuityRecovery: Boolean,
): RecordingDistanceEstimate {
    if (!isContinuityRecovery || previous == null || elapsedSincePreviousMs <= 0L) {
        return RecordingDistanceEstimate(distanceMeters = geometricDeltaMeters, capped = false)
    }
    val geometricDistance = geometricDeltaMeters.coerceAtLeast(0.0)
    val elapsedSeconds = elapsedSincePreviousMs / 1_000.0
    val reportedSpeeds =
        listOfNotNull(previous.speedMps, current.speedMps)
            .filter { it.isFinite() && it >= 0f }
    val fallbackSpeedMps =
        if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            RECORDING_DISTANCE_BIKE_FALLBACK_SPEED_MPS
        } else {
            RECORDING_DISTANCE_HIKE_FALLBACK_SPEED_MPS
        }
    val trustedSpeedMps =
        reportedSpeeds
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.coerceAtMost(
                if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                    RECORDING_DISTANCE_BIKE_MAX_RECOVERY_SPEED_MPS
                } else {
                    RECORDING_DISTANCE_HIKE_MAX_RECOVERY_SPEED_MPS
                },
            )
            ?: fallbackSpeedMps
    val accuracyAllowanceMeters =
        listOfNotNull(previous.accuracyMeters, current.accuracyMeters)
            .filter { it.isFinite() && it >= 0f }
            .sumOf { it.toDouble() }
            .coerceAtLeast(RECORDING_DISTANCE_MIN_RECOVERY_ALLOWANCE_M)
    val maximumTrustedMeters =
        trustedSpeedMps * elapsedSeconds * RECORDING_DISTANCE_RECOVERY_SPEED_ALLOWANCE +
            accuracyAllowanceMeters
    val estimatedDistance = min(geometricDistance, maximumTrustedMeters)
    return RecordingDistanceEstimate(
        distanceMeters = estimatedDistance,
        capped = estimatedDistance + RECORDING_DISTANCE_CAP_EPSILON_M < geometricDistance,
        maximumTrustedMeters = maximumTrustedMeters,
    )
}

private const val RECORDING_DISTANCE_HIKE_FALLBACK_SPEED_MPS = 1.4
private const val RECORDING_DISTANCE_BIKE_FALLBACK_SPEED_MPS = 5.5
private const val RECORDING_DISTANCE_HIKE_MAX_RECOVERY_SPEED_MPS = 3.0
private const val RECORDING_DISTANCE_BIKE_MAX_RECOVERY_SPEED_MPS = 12.0
private const val RECORDING_DISTANCE_RECOVERY_SPEED_ALLOWANCE = 1.35
private const val RECORDING_DISTANCE_MIN_RECOVERY_ALLOWANCE_M = 8.0
private const val RECORDING_DISTANCE_CAP_EPSILON_M = 0.01
