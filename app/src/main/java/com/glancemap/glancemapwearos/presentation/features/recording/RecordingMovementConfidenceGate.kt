package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.mapsforge.core.model.LatLong

internal data class RecordingMotionSample(
    val latLong: LatLong,
    val elapsedRealtimeMillis: Long,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
    val stepCount: Int?,
    val cadenceSpm: Int?,
    val trustReportedSpeedWithoutAccuracy: Boolean = false,
)

internal enum class RecordingMotionStatus {
    ACCEPTED,
    SUPPRESSED,
    HELD,
}

internal enum class RecordingMotionReason {
    FIRST_POINT,
    REPORTED_MOTION,
    SENSOR_MOTION,
    CONFIRMED_SLOW_PROGRESS,
    STATIONARY_JITTER,
    STEP_STILLNESS,
    UNCONFIRMED_SLOW_PROGRESS,
}

internal data class RecordingMotionResult(
    val status: RecordingMotionStatus,
    val reason: RecordingMotionReason,
    val displacementMeters: Double,
    val evidence: RecordingMotionEvidence,
) {
    val accepted: Boolean get() = status == RecordingMotionStatus.ACCEPTED
}

internal data class RecordingMotionEvidence(
    val stepDataAvailable: Boolean,
    val stepsAdvanced: Boolean,
    val stepsUnchanged: Boolean,
    val cadenceDataAvailable: Boolean,
    val cadenceShowsMotion: Boolean,
    val speedAboveThreshold: Boolean,
    val speedAccuracyAvailable: Boolean,
    val reportedSpeedCredible: Boolean,
    val stationaryRadiusMeters: Double? = null,
)

private data class RecordingReportedMotionAssessment(
    val aboveThreshold: Boolean,
    val credible: Boolean,
)

/**
 * Decides whether a plausible GPS fix represents real movement. The live map marker remains
 * driven by the latest location; this gate only protects recorded geometry and distance.
 *
 * Low-speed movement inside an accuracy-scaled radius is treated as stationary wandering.
 * Steps, cadence or credible reported speed release a point immediately. Devices without
 * usable motion sensors can still record very slow movement after two fixes confirm continued
 * progress in the same direction.
 */
internal class RecordingMovementConfidenceGate {
    private var pendingSlowProgress: RecordingMotionSample? = null
    private var lastObservedStepCount: Int? = null

    fun reset() {
        pendingSlowProgress = null
        lastObservedStepCount = null
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    fun evaluate(
        previous: RecordedTracePoint?,
        candidate: RecordingMotionSample,
        activityProfile: String,
        previousFilterAccuracyMeters: Float? = previous?.accuracyMeters,
    ): RecordingMotionResult {
        val previousObservedStepCount = lastObservedStepCount
        val stepsAdvanced = observeStepProgress(previous, candidate)
        val stepsUnchanged =
            candidate.stepCount?.let { current ->
                (previousObservedStepCount ?: previous?.stepCount)?.let { previousCount ->
                    current <= previousCount
                }
            } == true
        val cadenceShowsMotion = candidate.cadenceShowsMotion(activityProfile)
        val reportedMotion = candidate.reportedMotionAssessment(activityProfile)
        val evidence =
            RecordingMotionEvidence(
                stepDataAvailable = candidate.stepCount != null,
                stepsAdvanced = stepsAdvanced,
                stepsUnchanged = stepsUnchanged,
                cadenceDataAvailable = candidate.cadenceSpm != null,
                cadenceShowsMotion = cadenceShowsMotion,
                speedAboveThreshold = reportedMotion.aboveThreshold,
                speedAccuracyAvailable = candidate.speedAccuracyMps != null,
                reportedSpeedCredible = reportedMotion.credible,
            )
        if (previous == null) {
            pendingSlowProgress = null
            return candidate.result(
                status = RecordingMotionStatus.ACCEPTED,
                reason = RecordingMotionReason.FIRST_POINT,
                displacementMeters = 0.0,
                evidence = evidence,
            )
        }

        val displacementMeters = haversineMeters(previous.latLong, candidate.latLong)
        if (stepsAdvanced || cadenceShowsMotion) {
            pendingSlowProgress = null
            return candidate.result(
                status = RecordingMotionStatus.ACCEPTED,
                reason = RecordingMotionReason.SENSOR_MOTION,
                displacementMeters = displacementMeters,
                evidence = evidence,
            )
        }
        val stationaryRadiusMeters =
            recordingStationaryRadiusMeters(
                candidate = candidate,
                activityProfile = activityProfile,
                previousFilterAccuracyMeters = previousFilterAccuracyMeters,
            )
        val radiusEvidence = evidence.copy(stationaryRadiusMeters = stationaryRadiusMeters)
        if (displacementMeters <= stationaryRadiusMeters) {
            pendingSlowProgress = null
            return candidate.result(
                status = RecordingMotionStatus.SUPPRESSED,
                reason = RecordingMotionReason.STATIONARY_JITTER,
                displacementMeters = displacementMeters,
                evidence = radiusEvidence,
            )
        }
        val stepStillness = candidate.isWeakHikingFixWithUnchangedSteps(activityProfile, stepsUnchanged)
        val strongGpsMotion =
            reportedMotion.credible &&
                (candidate.speedMps ?: 0f) >= RECORDING_MOTION_STEP_STILLNESS_OVERRIDE_MIN_SPEED_MPS
        if (stepStillness && !strongGpsMotion) {
            pendingSlowProgress = null
            return candidate.result(
                status = RecordingMotionStatus.SUPPRESSED,
                reason = RecordingMotionReason.STEP_STILLNESS,
                displacementMeters = displacementMeters,
                evidence = radiusEvidence,
            )
        }
        if (stepStillness) {
            val pending = pendingSlowProgress
            if (
                pending != null &&
                isConfirmedSlowProgress(
                    anchor = previous.latLong,
                    pending = pending,
                    candidate = candidate,
                    activityProfile = activityProfile,
                )
            ) {
                pendingSlowProgress = null
                return candidate.result(
                    status = RecordingMotionStatus.ACCEPTED,
                    reason = RecordingMotionReason.CONFIRMED_SLOW_PROGRESS,
                    displacementMeters = displacementMeters,
                    evidence = radiusEvidence,
                )
            }
            pendingSlowProgress = candidate
            return candidate.result(
                status = RecordingMotionStatus.HELD,
                reason = RecordingMotionReason.UNCONFIRMED_SLOW_PROGRESS,
                displacementMeters = displacementMeters,
                evidence = radiusEvidence,
            )
        }
        // A watch may report a wider speed uncertainty than a phone while still reporting a
        // perfectly usable walking speed. Apply the stationary deadband first so that relaxing
        // the speed-accuracy threshold cannot turn stationary GPS wander into distance.
        if (reportedMotion.credible) {
            pendingSlowProgress = null
            return candidate.result(
                status = RecordingMotionStatus.ACCEPTED,
                reason = RecordingMotionReason.REPORTED_MOTION,
                displacementMeters = displacementMeters,
                evidence = radiusEvidence,
            )
        }

        val pending = pendingSlowProgress
        if (
            pending != null &&
            isConfirmedSlowProgress(
                anchor = previous.latLong,
                pending = pending,
                candidate = candidate,
                activityProfile = activityProfile,
            )
        ) {
            pendingSlowProgress = null
            return candidate.result(
                status = RecordingMotionStatus.ACCEPTED,
                reason = RecordingMotionReason.CONFIRMED_SLOW_PROGRESS,
                displacementMeters = displacementMeters,
                evidence = radiusEvidence,
            )
        }

        pendingSlowProgress = candidate
        return candidate.result(
            status = RecordingMotionStatus.HELD,
            reason = RecordingMotionReason.UNCONFIRMED_SLOW_PROGRESS,
            displacementMeters = displacementMeters,
            evidence = radiusEvidence,
        )
    }

    private fun observeStepProgress(
        previous: RecordedTracePoint?,
        candidate: RecordingMotionSample,
    ): Boolean {
        val current = candidate.stepCount ?: return false
        val baseline = lastObservedStepCount ?: previous?.stepCount
        lastObservedStepCount = current
        return baseline != null && current > baseline
    }
}

private fun RecordingMotionSample.isWeakHikingFixWithUnchangedSteps(
    activityProfile: String,
    stepsUnchanged: Boolean,
): Boolean =
    activityProfile == SettingsRepository.ACTIVITY_PROFILE_HIKE &&
        stepsUnchanged &&
        (accuracyMeters ?: 0f) >= RECORDING_MOTION_STEP_STILLNESS_MIN_ACCURACY_M &&
        (speedMps == null || speedMps <= RECORDING_MOTION_STEP_STILLNESS_MAX_SPEED_MPS)

private fun RecordingMotionSample.result(
    status: RecordingMotionStatus,
    reason: RecordingMotionReason,
    displacementMeters: Double,
    evidence: RecordingMotionEvidence,
): RecordingMotionResult =
    RecordingMotionResult(
        status = status,
        reason = reason,
        displacementMeters = displacementMeters,
        evidence = evidence,
    )

private fun RecordingMotionSample.cadenceShowsMotion(activityProfile: String): Boolean {
    val minimumCadence =
        if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            RECORDING_MOTION_BIKE_MIN_CADENCE
        } else {
            RECORDING_MOTION_HIKE_MIN_CADENCE
        }
    return cadenceSpm?.let { it >= minimumCadence } == true
}

private fun RecordingMotionSample.reportedMotionAssessment(
    activityProfile: String,
): RecordingReportedMotionAssessment {
    val speed = speedMps?.takeIf { it.isFinite() && it >= 0f }
    val speedAccuracy = speedAccuracyMps?.takeIf { it.isFinite() && it >= 0f }
    val threshold =
        if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            RECORDING_MOTION_BIKE_SPEED_THRESHOLD_MPS
        } else {
            RECORDING_MOTION_HIKE_SPEED_THRESHOLD_MPS
        }
    val aboveThreshold = speed?.let { it > threshold } == true
    val credible =
        speed?.let { value ->
            aboveThreshold &&
                (
                    trustReportedSpeedWithoutAccuracy ||
                        speedAccuracy == null ||
                        speedAccuracy <= maxOf(RECORDING_MOTION_MAX_SPEED_ACCURACY_MPS, value * 1.25f)
                )
        } == true
    return RecordingReportedMotionAssessment(
        aboveThreshold = aboveThreshold,
        credible = credible,
    )
}

private fun recordingStationaryRadiusMeters(
    candidate: RecordingMotionSample,
    activityProfile: String,
    previousFilterAccuracyMeters: Float?,
): Double {
    val accuracyMeters =
        listOfNotNull(previousFilterAccuracyMeters, candidate.accuracyMeters)
            .filter { it.isFinite() && it >= 0f }
            .maxOrNull()
            ?: RECORDING_FIX_FALLBACK_ACCURACY_M.toFloat()
    val bike = activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE
    return (accuracyMeters * RECORDING_MOTION_STATIONARY_ACCURACY_FACTOR)
        .coerceIn(
            if (bike) RECORDING_MOTION_BIKE_MIN_RADIUS_M else RECORDING_MOTION_HIKE_MIN_RADIUS_M,
            if (bike) RECORDING_MOTION_BIKE_MAX_RADIUS_M else RECORDING_MOTION_HIKE_MAX_RADIUS_M,
        ).toDouble()
}

private fun isConfirmedSlowProgress(
    anchor: LatLong,
    pending: RecordingMotionSample,
    candidate: RecordingMotionSample,
    activityProfile: String,
): Boolean {
    val elapsedMillis = candidate.elapsedRealtimeMillis - pending.elapsedRealtimeMillis
    if (elapsedMillis !in 1..RECORDING_MOTION_CONFIRMATION_MAX_INTERVAL_MS) return false
    val pendingFromAnchor = pending.latLong.toLocalMeters(anchor)
    val candidateFromAnchor = candidate.latLong.toLocalMeters(anchor)
    val progress =
        LocalMeters(
            x = candidateFromAnchor.x - pendingFromAnchor.x,
            y = candidateFromAnchor.y - pendingFromAnchor.y,
        )
    val minimumProgress =
        if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            RECORDING_MOTION_BIKE_MIN_CONFIRMED_PROGRESS_M
        } else {
            RECORDING_MOTION_HIKE_MIN_CONFIRMED_PROGRESS_M
        }
    return progress.length() >= minimumProgress &&
        candidateFromAnchor.length() >= pendingFromAnchor.length() + minimumProgress * 0.5 &&
        angleDegrees(pendingFromAnchor, progress) <= RECORDING_MOTION_MAX_CONFIRMATION_ANGLE_DEGREES
}

private const val RECORDING_MOTION_HIKE_SPEED_THRESHOLD_MPS = 0.55f
private const val RECORDING_MOTION_BIKE_SPEED_THRESHOLD_MPS = 1.0f
private const val RECORDING_MOTION_MAX_SPEED_ACCURACY_MPS = 1.5f
private const val RECORDING_MOTION_HIKE_MIN_CADENCE = 12
private const val RECORDING_MOTION_BIKE_MIN_CADENCE = 20
private const val RECORDING_MOTION_STATIONARY_ACCURACY_FACTOR = 0.35f
private const val RECORDING_MOTION_HIKE_MIN_RADIUS_M = 2.5f
private const val RECORDING_MOTION_HIKE_MAX_RADIUS_M = 8f
private const val RECORDING_MOTION_BIKE_MIN_RADIUS_M = 4f
private const val RECORDING_MOTION_BIKE_MAX_RADIUS_M = 14f
private const val RECORDING_MOTION_HIKE_MIN_CONFIRMED_PROGRESS_M = 1.5
private const val RECORDING_MOTION_BIKE_MIN_CONFIRMED_PROGRESS_M = 3.0
private const val RECORDING_MOTION_CONFIRMATION_MAX_INTERVAL_MS = 60_000L
private const val RECORDING_MOTION_MAX_CONFIRMATION_ANGLE_DEGREES = 70.0
private const val RECORDING_MOTION_STEP_STILLNESS_MIN_ACCURACY_M = 18f
private const val RECORDING_MOTION_STEP_STILLNESS_MAX_SPEED_MPS = 1.5f
private const val RECORDING_MOTION_STEP_STILLNESS_OVERRIDE_MIN_SPEED_MPS = 1.2f
