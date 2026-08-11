package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.mapsforge.core.model.LatLong
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot

internal const val RECORDING_FIX_SUSTAINED_CONFIRMATION_WINDOW_MS = 20_000L

internal fun recordingFixProfileSpeedLimit(activityProfile: String): Double =
    if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
        RECORDING_FIX_MAX_BIKE_SPEED_MPS
    } else {
        RECORDING_FIX_MAX_HIKE_SPEED_MPS
    }

/**
 * A selected activity profile describes the recording, but it must not turn a coherent sequence
 * of accurate fixes into repeated gaps. Two short transitions that agree with the provider's
 * reported speed and direction confirm sustained movement without relaxing isolated-jump checks.
 */
@Suppress("ReturnCount")
internal fun isConfirmedSustainedMovement(
    previous: RecordingFixSample,
    pending: RecordingFixSample,
    candidate: RecordingFixSample,
    activityProfile: String,
): Boolean {
    val profileReportedSpeedLimit =
        recordingFixProfileSpeedLimit(activityProfile) * RECORDING_FIX_MAX_REPORTED_SPEED_FACTOR
    val pendingSpeed = pending.reliableReportedSpeedMps() ?: return false
    val candidateSpeed = candidate.reliableReportedSpeedMps() ?: return false
    if (minOf(pendingSpeed, candidateSpeed) <= profileReportedSpeedLimit) return false
    if (maxOf(pendingSpeed, candidateSpeed) > RECORDING_FIX_MAX_CONFIRMED_SPEED_MPS) return false

    val firstElapsedSeconds = elapsedSeconds(previous, pending) ?: return false
    val secondElapsedSeconds = elapsedSeconds(pending, candidate) ?: return false
    if (
        firstElapsedSeconds > RECORDING_FIX_SUSTAINED_MAX_INTERVAL_SECONDS ||
        secondElapsedSeconds > RECORDING_FIX_SUSTAINED_MAX_INTERVAL_SECONDS
    ) {
        return false
    }
    if (
        !isPlausibleAtReportedSpeed(previous, pending, pendingSpeed, firstElapsedSeconds) ||
        !isPlausibleAtReportedSpeed(pending, candidate, candidateSpeed, secondElapsedSeconds)
    ) {
        return false
    }

    val maximumSpeedDifference =
        maxOf(
            RECORDING_FIX_SUSTAINED_SPEED_DIFFERENCE_FLOOR_MPS,
            maxOf(pendingSpeed, candidateSpeed) * RECORDING_FIX_SUSTAINED_SPEED_DIFFERENCE_FACTOR,
        )
    if (abs(pendingSpeed - candidateSpeed) > maximumSpeedDifference) return false
    return movementDirectionChangeDegrees(previous.latLong, pending.latLong, candidate.latLong) <=
        RECORDING_FIX_SUSTAINED_MAX_DIRECTION_CHANGE_DEGREES
}

internal fun isPlausibleConfirmedSustainedTransition(
    previous: RecordingFixSample,
    candidate: RecordingFixSample,
    activityProfile: String,
): Boolean {
    val speed = candidate.reliableReportedSpeedMps()
    val elapsedSeconds = elapsedSeconds(previous, candidate)
    return speed != null &&
        elapsedSeconds != null &&
        speed > recordingFixProfileSpeedLimit(activityProfile) * RECORDING_FIX_MAX_REPORTED_SPEED_FACTOR &&
        elapsedSeconds <= RECORDING_FIX_SUSTAINED_MAX_INTERVAL_SECONDS &&
        isPlausibleAtReportedSpeed(previous, candidate, speed, elapsedSeconds)
}

private fun RecordingFixSample.reliableReportedSpeedMps(): Double? {
    val speed = speedMps?.takeIf { it.isFinite() && it >= 0f }?.toDouble() ?: return null
    val speedAccuracy = speedAccuracyMps?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
    val reliable =
        speedAccuracy == null ||
            speedAccuracy <=
            maxOf(
                RECORDING_FIX_MAX_SPEED_ACCURACY_MPS,
                speed * RECORDING_FIX_MAX_RELATIVE_SPEED_ACCURACY,
            )
    return speed.takeIf { reliable }
}

private fun elapsedSeconds(
    from: RecordingFixSample,
    to: RecordingFixSample,
): Double? =
    ((to.elapsedRealtimeMillis - from.elapsedRealtimeMillis) / 1_000.0)
        .takeIf { it.isFinite() && it > 0.0 }

private fun isPlausibleAtReportedSpeed(
    previous: RecordingFixSample,
    candidate: RecordingFixSample,
    reportedSpeedMps: Double,
    elapsedSeconds: Double,
): Boolean {
    val speedAccuracy =
        candidate.speedAccuracyMps
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.toDouble()
            ?.coerceAtMost(RECORDING_FIX_MAX_SPEED_UNCERTAINTY_ALLOWANCE_MPS)
            ?: 0.0
    val modeledSpeed =
        (reportedSpeedMps + speedAccuracy)
            .coerceAtMost(RECORDING_FIX_MAX_CONFIRMED_SPEED_MPS)
    return haversineMeters(previous.latLong, candidate.latLong) <=
        modeledSpeed * elapsedSeconds + recordingTransitionUncertaintyAllowance(previous, candidate)
}

private fun movementDirectionChangeDegrees(
    start: LatLong,
    middle: LatLong,
    end: LatLong,
): Double {
    val longitudeScale = cos(Math.toRadians(middle.latitude)).coerceAtLeast(0.01)
    val firstX = (middle.longitude - start.longitude) * longitudeScale
    val firstY = middle.latitude - start.latitude
    val secondX = (end.longitude - middle.longitude) * longitudeScale
    val secondY = end.latitude - middle.latitude
    val denominator = hypot(firstX, firstY) * hypot(secondX, secondY)
    if (denominator <= 0.0) return 180.0
    val cosine = ((firstX * secondX + firstY * secondY) / denominator).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(cosine))
}

private const val RECORDING_FIX_MAX_HIKE_SPEED_MPS = 8.0
private const val RECORDING_FIX_MAX_BIKE_SPEED_MPS = 45.0
private const val RECORDING_FIX_MAX_CONFIRMED_SPEED_MPS = 55.0
private const val RECORDING_FIX_SUSTAINED_MAX_INTERVAL_SECONDS = 12.0
private const val RECORDING_FIX_SUSTAINED_MAX_DIRECTION_CHANGE_DEGREES = 100.0
private const val RECORDING_FIX_SUSTAINED_SPEED_DIFFERENCE_FLOOR_MPS = 5.0
private const val RECORDING_FIX_SUSTAINED_SPEED_DIFFERENCE_FACTOR = 0.5
private const val RECORDING_FIX_MAX_SPEED_ACCURACY_MPS = 5.0
