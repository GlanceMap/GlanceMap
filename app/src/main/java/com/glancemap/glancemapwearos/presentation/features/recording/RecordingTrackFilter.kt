@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.core.service.location.config.WATCH_GPS_EFFECTIVE_ACCURACY_M
import com.glancemap.glancemapwearos.core.service.location.config.resolveEffectiveWatchGpsAccuracyMeters
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.mapsforge.core.model.LatLong
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import com.glancemap.glancemapwearos.core.service.location.config.isKnownWatchGpsAccuracyFloor as platformWatchGpsAccuracyFloor

internal const val RECORDING_TRACK_FILTER_VERSION = 9
internal const val EARTH_RADIUS_METERS = 6_371_000.0
internal const val RECORDING_WATCH_GPS_FLOOR_FILTER_ACCURACY_M = WATCH_GPS_EFFECTIVE_ACCURACY_M

/**
 * Some watches expose a fixed 125 m accuracy value for otherwise usable direct-GNSS fixes.
 * Keep that raw value in GPX, but do not let it make the live recording filter reject every
 * direct watch-GPS point.
 */
internal fun resolveRecordingFilterAccuracyMeters(
    rawAccuracyMeters: Float?,
    knownWatchGpsAccuracyFloorActive: Boolean,
): Float? {
    val rawAccuracy = rawAccuracyMeters?.takeIf { it.isFinite() && it >= 0f } ?: return rawAccuracyMeters
    return resolveEffectiveWatchGpsAccuracyMeters(
        rawAccuracyMeters = rawAccuracy,
        watchGpsActive = knownWatchGpsAccuracyFloorActive,
    )
}

internal fun isKnownWatchGpsAccuracyFloor(accuracyMeters: Float?) = platformWatchGpsAccuracyFloor(accuracyMeters)

internal fun resolveRecordingContinuityRecoveryGapMillis(
    deliveryGapMillis: Long,
    committedPointGapMillis: Long,
    thresholdMillis: Long,
): Long? =
    maxOf(deliveryGapMillis, committedPointGapMillis)
        .takeIf { it >= thresholdMillis }

internal data class RecordingFixSample(
    val latLong: LatLong,
    val timeMillis: Long,
    val elapsedRealtimeMillis: Long,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
)

internal enum class RecordingFixQualityStatus {
    ACCEPTED,
    HELD,
    REJECTED,
}

internal enum class RecordingFixQualityReason {
    FIRST_FIX,
    GOOD_FIX,
    CONFIRMED_SUSTAINED_MOVEMENT,
    NON_MONOTONIC,
    POOR_ACCURACY,
    IMPLAUSIBLE_JUMP,
    CONFIRMED_RELOCATION,
}

internal data class RecordingFixQualityResult(
    val status: RecordingFixQualityStatus,
    val reason: RecordingFixQualityReason,
) {
    val accepted: Boolean get() = status == RecordingFixQualityStatus.ACCEPTED
}

internal data class RecordingAccuracyPolicySnapshot(
    val sampleCount: Int,
    val baselineMedianMeters: Float?,
    val profileLimitMeters: Float,
    val resolvedLimitMeters: Float,
) {
    val adaptiveLimitActive: Boolean
        get() = resolvedLimitMeters > profileLimitMeters
}

/**
 * Rejects fixes that cannot safely become part of the canonical recording. A single
 * implausible jump is held until the next sampled fix either disproves it or confirms that
 * GPS has genuinely reacquired in a different location.
 */
internal class RecordingFixQualityGate {
    private var lastSeenElapsedRealtimeMillis = Long.MIN_VALUE
    private var lastAccepted: RecordingFixSample? = null
    private var pendingImplausible: RecordingFixSample? = null
    private var sustainedMovementValidUntilElapsedRealtimeMillis = Long.MIN_VALUE
    private val recentAccuracyMeters = mutableListOf<Float>()
    var latestAccuracyPolicySnapshot: RecordingAccuracyPolicySnapshot? = null
        private set

    fun reset() {
        lastSeenElapsedRealtimeMillis = Long.MIN_VALUE
        lastAccepted = null
        pendingImplausible = null
        sustainedMovementValidUntilElapsedRealtimeMillis = Long.MIN_VALUE
        recentAccuracyMeters.clear()
        latestAccuracyPolicySnapshot = null
    }

    @Suppress("ReturnCount")
    fun evaluate(
        candidate: RecordingFixSample,
        activityProfile: String,
    ): RecordingFixQualityResult {
        if (
            lastSeenElapsedRealtimeMillis != Long.MIN_VALUE &&
            candidate.elapsedRealtimeMillis <= lastSeenElapsedRealtimeMillis
        ) {
            return RecordingFixQualityResult(
                status = RecordingFixQualityStatus.REJECTED,
                reason = RecordingFixQualityReason.NON_MONOTONIC,
            )
        }
        lastSeenElapsedRealtimeMillis = candidate.elapsedRealtimeMillis

        val accuracyPolicy = observeAndResolveAccuracyPolicy(candidate, activityProfile)
        latestAccuracyPolicySnapshot = accuracyPolicy
        if (candidate.accuracyMeters.isUnacceptablyPoor(accuracyPolicy.resolvedLimitMeters)) {
            return RecordingFixQualityResult(
                status = RecordingFixQualityStatus.REJECTED,
                reason = RecordingFixQualityReason.POOR_ACCURACY,
            )
        }

        val previous = lastAccepted
        if (previous == null) {
            return accept(candidate, RecordingFixQualityReason.FIRST_FIX)
        }

        if (
            candidate.elapsedRealtimeMillis <= sustainedMovementValidUntilElapsedRealtimeMillis &&
            isPlausibleConfirmedSustainedTransition(previous, candidate, activityProfile)
        ) {
            extendSustainedMovementWindow(candidate)
            return accept(candidate, RecordingFixQualityReason.GOOD_FIX)
        }
        sustainedMovementValidUntilElapsedRealtimeMillis = Long.MIN_VALUE

        val pending = pendingImplausible
        if (pending != null) {
            return evaluateCandidateAfterHeldFix(
                previous = previous,
                pending = pending,
                candidate = candidate,
                activityProfile = activityProfile,
            )
        }

        if (!isPlausibleTransition(previous, candidate, activityProfile)) {
            pendingImplausible = candidate
            return RecordingFixQualityResult(
                status = RecordingFixQualityStatus.HELD,
                reason = RecordingFixQualityReason.IMPLAUSIBLE_JUMP,
            )
        }
        return accept(candidate, RecordingFixQualityReason.GOOD_FIX)
    }

    private fun accept(
        candidate: RecordingFixSample,
        reason: RecordingFixQualityReason,
    ): RecordingFixQualityResult {
        lastAccepted = candidate
        pendingImplausible = null
        return RecordingFixQualityResult(
            status = RecordingFixQualityStatus.ACCEPTED,
            reason = reason,
        )
    }

    private fun extendSustainedMovementWindow(candidate: RecordingFixSample) {
        sustainedMovementValidUntilElapsedRealtimeMillis =
            candidate.elapsedRealtimeMillis + RECORDING_FIX_SUSTAINED_CONFIRMATION_WINDOW_MS
    }

    /**
     * Watch vendors do not report horizontal accuracy in exactly the same way. Keep the
     * conservative profile limit for a normal session, but learn a higher ceiling when
     * several consecutive fixes show that the device consistently reports a wider radius.
     * A hard ceiling still prevents a no-fix/coarse location from entering the recording.
     */
    private fun observeAndResolveAccuracyPolicy(
        candidate: RecordingFixSample,
        activityProfile: String,
    ): RecordingAccuracyPolicySnapshot {
        val profileLimit = recordingFixProfileAccuracyLimitMeters(activityProfile)
        val hardLimit = recordingFixHardAccuracyLimitMeters(activityProfile)
        candidate.accuracyMeters
            ?.takeIf { it.isFinite() && it >= 0f && it <= hardLimit }
            ?.let { accuracy ->
                recentAccuracyMeters += accuracy
                while (recentAccuracyMeters.size > RECORDING_FIX_ACCURACY_BASELINE_WINDOW) {
                    recentAccuracyMeters.removeAt(0)
                }
            }
        val median =
            recentAccuracyMeters
                .takeIf { it.size >= RECORDING_FIX_ACCURACY_BASELINE_MIN_SAMPLES }
                ?.sorted()
                ?.let { it[it.size / 2] }
        val resolvedLimit =
            if (recentAccuracyMeters.size < RECORDING_FIX_ACCURACY_BASELINE_MIN_SAMPLES || median == null) {
                profileLimit
            } else {
                maxOf(
                    profileLimit,
                    median * RECORDING_FIX_ACCURACY_BASELINE_FACTOR + RECORDING_FIX_ACCURACY_BASELINE_MARGIN_M,
                ).coerceAtMost(hardLimit)
            }
        return RecordingAccuracyPolicySnapshot(
            sampleCount = recentAccuracyMeters.size,
            baselineMedianMeters = median,
            profileLimitMeters = profileLimit,
            resolvedLimitMeters = resolvedLimit,
        )
    }

    @Suppress("ReturnCount")
    private fun evaluateCandidateAfterHeldFix(
        previous: RecordingFixSample,
        pending: RecordingFixSample,
        candidate: RecordingFixSample,
        activityProfile: String,
    ): RecordingFixQualityResult {
        if (isPlausibleTransition(previous, candidate, activityProfile)) {
            pendingImplausible = null
            return accept(candidate, RecordingFixQualityReason.GOOD_FIX)
        }
        if (isConfirmedSustainedMovement(previous, pending, candidate, activityProfile)) {
            extendSustainedMovementWindow(candidate)
            return accept(candidate, RecordingFixQualityReason.CONFIRMED_SUSTAINED_MOVEMENT)
        }
        if (isPlausibleTransition(pending, candidate, activityProfile)) {
            pendingImplausible = null
            return accept(
                candidate = candidate,
                reason = RecordingFixQualityReason.CONFIRMED_RELOCATION,
            )
        }
        pendingImplausible = candidate
        return RecordingFixQualityResult(
            status = RecordingFixQualityStatus.HELD,
            reason = RecordingFixQualityReason.IMPLAUSIBLE_JUMP,
        )
    }
}

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

    @Suppress("LongMethod", "ReturnCount")
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
        if (candidate.isWeakHikingFixWithUnchangedSteps(activityProfile, stepsUnchanged)) {
            pendingSlowProgress = null
            return candidate.result(
                status = RecordingMotionStatus.SUPPRESSED,
                reason = RecordingMotionReason.STEP_STILLNESS,
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

internal data class RecordingPointSmoothingResult(
    val point: RecordedTracePoint,
    val adjustmentMeters: Double,
)

internal data class RecordingPointSmoothingOptions(
    val mode: String,
    val activityProfile: String,
    val sampleIntervalSeconds: Int = SettingsRepository.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS,
)

internal data class RecordingCanonicalAppendResult(
    val points: List<RecordedTracePoint>,
    val distanceDeltaMeters: Double,
    val adjustedPointCount: Int,
    val adjustmentMeters: Double,
    val maximumAdjustmentMeters: Double,
    val confirmedReversalCorrected: Boolean,
    val straightDriftCorrectedPointCount: Int,
)

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

/**
 * Appends one real accepted fix to the canonical track. Only the small tail that can still
 * be changed by delayed confirmation is revisited, so distance, map geometry and GPX always
 * use the same coordinates without making the work grow with the recording length.
 */
internal fun appendCanonicalRecordingPoint(
    existingPoints: List<RecordedTracePoint>,
    point: RecordedTracePoint,
    options: RecordingPointSmoothingOptions,
): RecordingCanonicalAppendResult {
    val tailStartIndex = (existingPoints.size - RECORDING_CANONICAL_MUTABLE_TAIL_POINTS).coerceAtLeast(0)
    val originalTail = existingPoints.subList(tailStartIndex, existingPoints.size)
    val revisedTail = originalTail.toMutableList()
    var adjustedPointCount = 0
    var totalAdjustmentMeters = 0.0
    var maximumAdjustmentMeters = 0.0
    var confirmedReversalCorrected = false
    var straightDriftCorrectedPointCount = 0

    // A single GPS error can look like a sharp reversal and is handled below. A poorer fix
    // can also drift for two consecutive samples on the same side of a straight path. Keep
    // three recorded points mutable so the fourth fix can confirm that shape before moving
    // the older interior point toward the outer chord. Correcting only that point means it
    // becomes immutable on the next append, so no point can receive stacked side-arc pulls.
    if (revisedTail.size >= 3) {
        val straightDriftResult =
            smoothRecordingStraightDrift(
                before = revisedTail[revisedTail.lastIndex - 2],
                firstInterior = revisedTail[revisedTail.lastIndex - 1],
                secondInterior = revisedTail[revisedTail.lastIndex],
                after = point,
                options = options,
            )
        if (straightDriftResult != null) {
            revisedTail[revisedTail.lastIndex - 1] = straightDriftResult.point
            straightDriftCorrectedPointCount = 1
            adjustedPointCount += straightDriftCorrectedPointCount
            totalAdjustmentMeters += straightDriftResult.adjustmentMeters
            maximumAdjustmentMeters = maxOf(maximumAdjustmentMeters, straightDriftResult.adjustmentMeters)
        }
    }

    if (revisedTail.size >= 3) {
        val reversalResult =
            smoothConfirmedRecordingReversal(
                before = revisedTail[revisedTail.lastIndex - 2],
                candidate = revisedTail[revisedTail.lastIndex - 1],
                recovered = revisedTail.last(),
                following = point,
                options = options,
            )
        if (reversalResult != null) {
            revisedTail[revisedTail.lastIndex - 1] = reversalResult.point
            adjustedPointCount += 1
            totalAdjustmentMeters += reversalResult.adjustmentMeters
            maximumAdjustmentMeters = maxOf(maximumAdjustmentMeters, reversalResult.adjustmentMeters)
            confirmedReversalCorrected = true
        }
    }

    if (revisedTail.size >= 2) {
        val middleIndex = revisedTail.lastIndex
        val smoothingResult =
            smoothRecordingMiddlePoint(
                before = revisedTail[middleIndex - 1],
                middle = revisedTail[middleIndex],
                after = point,
                options = options,
            )
        if (smoothingResult != null) {
            revisedTail[middleIndex] = smoothingResult.point
            adjustedPointCount += 1
            totalAdjustmentMeters += smoothingResult.adjustmentMeters
            maximumAdjustmentMeters = maxOf(maximumAdjustmentMeters, smoothingResult.adjustmentMeters)
        }
    }

    val oldTailDistance = recordingCanonicalPathDistance(originalTail)
    val revisedTailWithPoint = revisedTail + point
    val newTailDistance = recordingCanonicalPathDistance(revisedTailWithPoint)
    return RecordingCanonicalAppendResult(
        points = existingPoints.take(tailStartIndex) + revisedTailWithPoint,
        distanceDeltaMeters = newTailDistance - oldTailDistance,
        adjustedPointCount = adjustedPointCount,
        adjustmentMeters = totalAdjustmentMeters,
        maximumAdjustmentMeters = maximumAdjustmentMeters,
        confirmedReversalCorrected = confirmedReversalCorrected,
        straightDriftCorrectedPointCount = straightDriftCorrectedPointCount,
    )
}

internal fun recordingCanonicalPathDistance(points: List<RecordedTracePoint>): Double =
    points.zipWithNext().sumOf { (before, after) ->
        if (after.startsNewSegment) {
            0.0
        } else {
            haversineMeters(before.latLong, after.latLong)
        }
    }

/**
 * Corrects a sustained side-arc only after its fourth point arrives. This is deliberately
 * stricter than normal three-point smoothing: both interior points must progress along the
 * same side of a long outer chord, reported uncertainty must be meaningful, and the complete
 * polyline must remain close to a straight route. A real corner crosses sides of the chord,
 * while a gentle low-accuracy arc receives only an accuracy-capped lateral correction.
 */
@Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
internal fun smoothRecordingStraightDrift(
    before: RecordedTracePoint,
    firstInterior: RecordedTracePoint,
    secondInterior: RecordedTracePoint,
    after: RecordedTracePoint,
    options: RecordingPointSmoothingOptions,
): RecordingPointSmoothingResult? {
    if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF) return null
    if (
        firstInterior.startsNewSegment ||
        secondInterior.startsNewSegment ||
        after.startsNewSegment
    ) {
        return null
    }
    val maximumIntervalMillis = recordingStraightDriftMaximumIntervalMillis(options.sampleIntervalSeconds)
    val intervals =
        listOf(
            firstInterior.timeMillis - before.timeMillis,
            secondInterior.timeMillis - firstInterior.timeMillis,
            after.timeMillis - secondInterior.timeMillis,
        )
    if (intervals.any { it !in 1..maximumIntervalMillis }) return null

    val firstLocal = firstInterior.latLong.toLocalMeters(before.latLong)
    val secondLocal = secondInterior.latLong.toLocalMeters(before.latLong)
    val afterLocal = after.latLong.toLocalMeters(before.latLong)
    val chordLength = afterLocal.length()
    val minimumChordMeters =
        if (options.activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            RECORDING_STRAIGHT_DRIFT_BIKE_MIN_CHORD_M
        } else {
            RECORDING_STRAIGHT_DRIFT_HIKE_MIN_CHORD_M
        }
    if (chordLength < minimumChordMeters) return null

    val chordSquared = afterLocal.x * afterLocal.x + afterLocal.y * afterLocal.y
    val firstProjection = (firstLocal.x * afterLocal.x + firstLocal.y * afterLocal.y) / chordSquared
    val secondProjection = (secondLocal.x * afterLocal.x + secondLocal.y * afterLocal.y) / chordSquared
    if (
        firstProjection !in RECORDING_STRAIGHT_DRIFT_MIN_PROJECTION..RECORDING_STRAIGHT_DRIFT_MAX_PROJECTION ||
        secondProjection !in RECORDING_STRAIGHT_DRIFT_MIN_PROJECTION..RECORDING_STRAIGHT_DRIFT_MAX_PROJECTION ||
        secondProjection - firstProjection < RECORDING_STRAIGHT_DRIFT_MIN_PROGRESS_FRACTION
    ) {
        return null
    }

    val firstProjected =
        LocalMeters(
            x = afterLocal.x * firstProjection,
            y = afterLocal.y * firstProjection,
        )
    val secondProjected =
        LocalMeters(
            x = afterLocal.x * secondProjection,
            y = afterLocal.y * secondProjection,
        )
    val firstCorrection = LocalMeters(firstProjected.x - firstLocal.x, firstProjected.y - firstLocal.y)
    val secondCorrection = LocalMeters(secondProjected.x - secondLocal.x, secondProjected.y - secondLocal.y)
    val firstLateralError = firstCorrection.length()
    val secondLateralError = secondCorrection.length()
    val minimumLateralError =
        if (options.activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            RECORDING_STRAIGHT_DRIFT_BIKE_MIN_LATERAL_ERROR_M
        } else {
            RECORDING_STRAIGHT_DRIFT_HIKE_MIN_LATERAL_ERROR_M
        }
    if (firstLateralError < minimumLateralError || secondLateralError < minimumLateralError) return null

    val firstSignedLateral = (afterLocal.x * firstLocal.y - afterLocal.y * firstLocal.x) / chordLength
    val secondSignedLateral = (afterLocal.x * secondLocal.y - afterLocal.y * secondLocal.x) / chordLength
    if (firstSignedLateral * secondSignedLateral <= 0.0) return null

    val detourRatio =
        (
            firstLocal.length() +
                LocalMeters(secondLocal.x - firstLocal.x, secondLocal.y - firstLocal.y).length() +
                LocalMeters(afterLocal.x - secondLocal.x, afterLocal.y - secondLocal.y).length()
        ) / chordLength
    if (detourRatio > RECORDING_STRAIGHT_DRIFT_MAX_DETOUR_RATIO) return null

    val firstAccuracy = firstInterior.accuracyMeters.validAccuracyOr(RECORDING_FIX_FALLBACK_ACCURACY_M)
    val secondAccuracy = secondInterior.accuracyMeters.validAccuracyOr(RECORDING_FIX_FALLBACK_ACCURACY_M)
    val minimumAccuracyForCorrection =
        if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG) {
            RECORDING_STRAIGHT_DRIFT_STRONG_MIN_ACCURACY_M
        } else {
            RECORDING_STRAIGHT_DRIFT_ADAPTIVE_MIN_ACCURACY_M
        }
    if ((firstAccuracy + secondAccuracy) / 2.0 < minimumAccuracyForCorrection) return null

    val correctionStrength =
        if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG) {
            RECORDING_STRAIGHT_DRIFT_STRONG_STRENGTH
        } else {
            RECORDING_STRAIGHT_DRIFT_ADAPTIVE_STRENGTH
        }
    val maximumAdjustment =
        if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG) {
            RECORDING_STRAIGHT_DRIFT_STRONG_MAX_ADJUSTMENT_M
        } else {
            RECORDING_STRAIGHT_DRIFT_ADAPTIVE_MAX_ADJUSTMENT_M
        }
    val firstAdjustment =
        min(
            firstLateralError * correctionStrength,
            min(firstAccuracy * RECORDING_STRAIGHT_DRIFT_ACCURACY_CAP_FACTOR, maximumAdjustment),
        )
    if (
        firstAdjustment < RECORDING_STRAIGHT_DRIFT_MIN_APPLIED_ADJUSTMENT_M
    ) {
        return null
    }
    val fraction = (firstAdjustment / firstLateralError).coerceIn(0.0, 1.0)
    return RecordingPointSmoothingResult(
        point =
            firstInterior.copy(
                latLong =
                    LocalMeters(
                        x = firstLocal.x + firstCorrection.x * fraction,
                        y = firstLocal.y + firstCorrection.y * fraction,
                    ).toLatLong(before.latLong),
            ),
        adjustmentMeters = firstAdjustment,
    )
}

/**
 * Corrects a short out-and-back GPS excursion only after a fourth point confirms that travel
 * continued along the recovered line. This extra confirmation avoids flattening real corners
 * and switchbacks while allowing optimistic watch accuracy values to be handled safely.
 */
@Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
private fun smoothConfirmedRecordingReversal(
    before: RecordedTracePoint,
    candidate: RecordedTracePoint,
    recovered: RecordedTracePoint,
    following: RecordedTracePoint,
    options: RecordingPointSmoothingOptions,
): RecordingPointSmoothingResult? {
    if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF) return null
    if (candidate.startsNewSegment || recovered.startsNewSegment || following.startsNewSegment) return null
    val maximumIntervalMillis = recordingSmoothingMaximumIntervalMillis(options.sampleIntervalSeconds)
    val intervals =
        listOf(
            candidate.timeMillis - before.timeMillis,
            recovered.timeMillis - candidate.timeMillis,
            following.timeMillis - recovered.timeMillis,
        )
    if (intervals.any { it !in 1..maximumIntervalMillis }) return null

    val candidateLocal = candidate.latLong.toLocalMeters(before.latLong)
    val recoveredLocal = recovered.latLong.toLocalMeters(before.latLong)
    val followingLocal = following.latLong.toLocalMeters(before.latLong)
    val firstVector = candidateLocal
    val returnVector =
        LocalMeters(
            x = recoveredLocal.x - candidateLocal.x,
            y = recoveredLocal.y - candidateLocal.y,
        )
    val recoveredDirection = recoveredLocal
    val followingDirection =
        LocalMeters(
            x = followingLocal.x - recoveredLocal.x,
            y = followingLocal.y - recoveredLocal.y,
        )
    val minimumLegMeters =
        if (options.activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            RECORDING_SMOOTHING_MIN_BIKE_LEG_M
        } else {
            RECORDING_SMOOTHING_MIN_HIKE_LEG_M
        }
    val firstLength = firstVector.length()
    val returnLength = returnVector.length()
    val baselineLength = recoveredDirection.length()
    if (
        firstLength < minimumLegMeters ||
        returnLength < minimumLegMeters ||
        baselineLength < minimumLegMeters ||
        followingDirection.length() < minimumLegMeters
    ) {
        return null
    }
    val baselineSquared =
        recoveredDirection.x * recoveredDirection.x + recoveredDirection.y * recoveredDirection.y
    val projectionFraction =
        (candidateLocal.x * recoveredDirection.x + candidateLocal.y * recoveredDirection.y) / baselineSquared
    val candidateAccuracy = candidate.accuracyMeters.validAccuracyOr(RECORDING_FIX_FALLBACK_ACCURACY_M)
    val beforeAccuracy = before.accuracyMeters.validAccuracyOr(RECORDING_FIX_FALLBACK_ACCURACY_M)
    val sensorBackedNearOriginExcursion =
        projectionFraction in
            RECORDING_REVERSAL_SENSOR_BACKED_MIN_PROJECTION..<RECORDING_REVERSAL_MIN_PROJECTION &&
            before.stepCount != null &&
            candidate.stepCount != null &&
            candidate.stepCount <= before.stepCount &&
            candidateAccuracy - beforeAccuracy >= RECORDING_REVERSAL_MIN_ACCURACY_DEGRADATION_M
    if (
        projectionFraction !in RECORDING_REVERSAL_MIN_PROJECTION..RECORDING_REVERSAL_MAX_PROJECTION &&
        !sensorBackedNearOriginExcursion
    ) {
        return null
    }
    val maximumRecoveryHeadingDegrees =
        if (sensorBackedNearOriginExcursion) {
            RECORDING_REVERSAL_SENSOR_BACKED_MAX_RECOVERY_HEADING_DEGREES
        } else {
            RECORDING_REVERSAL_MAX_RECOVERY_HEADING_DEGREES
        }
    if (angleDegrees(recoveredDirection, followingDirection) > maximumRecoveryHeadingDegrees) return null
    val projected =
        LocalMeters(
            x = recoveredDirection.x * projectionFraction,
            y = recoveredDirection.y * projectionFraction,
        )
    val correction =
        LocalMeters(
            x = projected.x - candidateLocal.x,
            y = projected.y - candidateLocal.y,
        )
    val lateralErrorMeters = correction.length()
    val minimumLateralError =
        if (options.activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            RECORDING_REVERSAL_MIN_BIKE_LATERAL_ERROR_M
        } else {
            RECORDING_REVERSAL_MIN_HIKE_LATERAL_ERROR_M
        }
    if (lateralErrorMeters < minimumLateralError) return null
    val turnDegrees = angleDegrees(firstVector, returnVector)
    val detourRatio = (firstLength + returnLength) / baselineLength
    if (
        turnDegrees < RECORDING_REVERSAL_MIN_TURN_DEGREES ||
        detourRatio < RECORDING_REVERSAL_MIN_DETOUR_RATIO
    ) {
        return null
    }

    val strong = options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG
    val bike = options.activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE
    val requestedAdjustment = lateralErrorMeters * if (strong) 0.94 else 0.84
    val maximumAdjustment =
        when {
            bike && strong -> 24.0
            bike -> 18.0
            strong -> 18.0
            else -> 16.0
        }
    val geometryCap = min(firstLength, returnLength) * if (strong) 0.95 else 0.90
    val adjustmentMeters = min(requestedAdjustment, min(maximumAdjustment, geometryCap))
    if (adjustmentMeters < minimumLateralError) return null
    val fraction = (adjustmentMeters / lateralErrorMeters).coerceIn(0.0, 1.0)
    val smoothedLocal =
        LocalMeters(
            x = candidateLocal.x + correction.x * fraction,
            y = candidateLocal.y + correction.y * fraction,
        )
    return RecordingPointSmoothingResult(
        point = candidate.copy(latLong = smoothedLocal.toLatLong(before.latLong)),
        adjustmentMeters = adjustmentMeters,
    )
}

/**
 * Smooths only the middle point of a three-point sequence. The latest endpoint is never
 * delayed or pulled backwards. Corrections are lateral, accuracy-weighted and disabled at
 * segment boundaries, long gaps and confirmed turns.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
internal fun smoothRecordingMiddlePoint(
    before: RecordedTracePoint,
    middle: RecordedTracePoint,
    after: RecordedTracePoint,
    options: RecordingPointSmoothingOptions,
): RecordingPointSmoothingResult? {
    val mode = options.mode
    val activityProfile = options.activityProfile
    if (mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF) return null
    if (middle.startsNewSegment || after.startsNewSegment) return null
    val firstIntervalMillis = middle.timeMillis - before.timeMillis
    val secondIntervalMillis = after.timeMillis - middle.timeMillis
    val maximumIntervalMillis =
        recordingSmoothingMaximumIntervalMillis(options.sampleIntervalSeconds)
    if (
        firstIntervalMillis !in 1..maximumIntervalMillis ||
        secondIntervalMillis !in 1..maximumIntervalMillis
    ) {
        return null
    }

    val middleLocal = middle.latLong.toLocalMeters(before.latLong)
    val afterLocal = after.latLong.toLocalMeters(before.latLong)
    val firstLength = middleLocal.length()
    val secondVector = LocalMeters(afterLocal.x - middleLocal.x, afterLocal.y - middleLocal.y)
    val secondLength = secondVector.length()
    val minimumLegMeters =
        if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            RECORDING_SMOOTHING_MIN_BIKE_LEG_M
        } else {
            RECORDING_SMOOTHING_MIN_HIKE_LEG_M
        }
    if (firstLength < minimumLegMeters || secondLength < minimumLegMeters) return null

    val config = smoothingConfig(mode = mode, activityProfile = activityProfile)
    val baselineSquared = afterLocal.x * afterLocal.x + afterLocal.y * afterLocal.y
    if (baselineSquared < minimumLegMeters * minimumLegMeters) return null
    val baselineLength = afterLocal.length()
    val projectionFraction =
        ((middleLocal.x * afterLocal.x + middleLocal.y * afterLocal.y) / baselineSquared)
    if (projectionFraction !in RECORDING_SMOOTHING_MIN_PROJECTION..RECORDING_SMOOTHING_MAX_PROJECTION) return null

    val projected =
        LocalMeters(
            x = afterLocal.x * projectionFraction,
            y = afterLocal.y * projectionFraction,
        )
    val correction = LocalMeters(projected.x - middleLocal.x, projected.y - middleLocal.y)
    val lateralErrorMeters = correction.length()
    if (lateralErrorMeters < config.minimumAdjustmentMeters) return null

    val middleAccuracy = middle.accuracyMeters.validAccuracyOr(config.fallbackAccuracyMeters)
    val neighbourAccuracy =
        listOf(before.accuracyMeters, after.accuracyMeters)
            .mapNotNull { it?.takeIf(Float::isFinite)?.takeIf { value -> value >= 0f } }
            .average()
            .takeIf(Double::isFinite)
            ?: middleAccuracy
    val turnDegrees = angleDegrees(middleLocal, secondVector)
    val likelyIsolatedSpike =
        RecordingSpikeCandidate(
            turnDegrees = turnDegrees,
            detourRatio = (firstLength + secondLength) / baselineLength,
            lateralErrorMeters = lateralErrorMeters,
            middleAccuracyMeters = middleAccuracy,
            neighbourAccuracyMeters = neighbourAccuracy,
        ).isLikely()
    if (!likelyIsolatedSpike && turnDegrees > config.maximumTurnDegrees) {
        return null
    }
    val relativeUncertainty = (middleAccuracy / neighbourAccuracy.coerceAtLeast(1.0)).coerceIn(0.65, 1.6)
    val accuracyNeed = (middleAccuracy / (middleAccuracy + RECORDING_SMOOTHING_ACCURACY_PIVOT_M)).coerceIn(0.2, 0.9)
    val adjustmentProfile =
        config.adjustmentProfile(
            mode = mode,
            activityProfile = activityProfile,
            likelyIsolatedSpike = likelyIsolatedSpike,
        )
    val requestedAdjustment =
        lateralErrorMeters * adjustmentProfile.strength * relativeUncertainty * accuracyNeed
    val accuracyCap =
        (middleAccuracy * adjustmentProfile.accuracyAdjustmentFactor)
            .coerceIn(config.minimumCapMeters, adjustmentProfile.maximumCapMeters)
    val geometryCap = min(firstLength, secondLength) * adjustmentProfile.maximumLegFraction
    val adjustmentMeters = min(requestedAdjustment, min(accuracyCap, geometryCap))
    if (adjustmentMeters < config.minimumAdjustmentMeters) return null

    val fraction = (adjustmentMeters / lateralErrorMeters).coerceIn(0.0, 1.0)
    val smoothedLocal =
        LocalMeters(
            x = middleLocal.x + correction.x * fraction,
            y = middleLocal.y + correction.y * fraction,
        )
    return RecordingPointSmoothingResult(
        point = middle.copy(latLong = smoothedLocal.toLatLong(before.latLong)),
        adjustmentMeters = adjustmentMeters,
    )
}

internal fun smoothRecordingMiddlePoint(
    before: RecordedTracePoint,
    middle: RecordedTracePoint,
    after: RecordedTracePoint,
    mode: String,
    activityProfile: String,
): RecordingPointSmoothingResult? =
    smoothRecordingMiddlePoint(
        before = before,
        middle = middle,
        after = after,
        options =
            RecordingPointSmoothingOptions(
                mode = mode,
                activityProfile = activityProfile,
            ),
    )

private fun isPlausibleTransition(
    previous: RecordingFixSample,
    candidate: RecordingFixSample,
    activityProfile: String,
): Boolean {
    val elapsedSeconds =
        (candidate.elapsedRealtimeMillis - previous.elapsedRealtimeMillis) / 1_000.0
    if (!elapsedSeconds.isFinite() || elapsedSeconds <= 0.0) return false
    val maximumSpeed = recordingTransitionMaximumSpeedMps(candidate, activityProfile)
    val uncertaintyAllowance = recordingTransitionUncertaintyAllowance(previous, candidate)
    val maximumDistance = maximumSpeed * elapsedSeconds + uncertaintyAllowance
    return haversineMeters(previous.latLong, candidate.latLong) <= maximumDistance
}

/**
 * Keep a modest activity-safe motion floor for sparse GPS delivery, but never turn the speed
 * limit into the profile maximum. Combined with the bounded accuracy allowance below, this
 * rejects short poor-accuracy jumps without splitting normal 8–10 second hiking fixes.
 */
internal fun recordingTransitionMaximumSpeedMps(
    candidate: RecordingFixSample,
    activityProfile: String,
): Double {
    val fallbackSpeed =
        if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            RECORDING_FIX_FALLBACK_BIKE_SPEED_MPS
        } else {
            RECORDING_FIX_FALLBACK_HIKE_SPEED_MPS
        }
    val reportedSpeed =
        candidate.speedMps?.takeIf { it.isFinite() && it >= 0f }?.toDouble() ?: return fallbackSpeed
    val speedAccuracy =
        candidate.speedAccuracyMps?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
    val speedAccuracyIsReliable =
        speedAccuracy == null ||
            speedAccuracy <=
            maxOf(
                RECORDING_FIX_MAX_TRUSTED_SPEED_ACCURACY_MPS,
                reportedSpeed * RECORDING_FIX_MAX_RELATIVE_SPEED_ACCURACY,
            )
    if (!speedAccuracyIsReliable) return fallbackSpeed
    val speedUncertainty =
        (speedAccuracy ?: 0.0).coerceAtMost(RECORDING_FIX_MAX_SPEED_UNCERTAINTY_ALLOWANCE_MPS)
    return (reportedSpeed + speedUncertainty)
        .coerceAtLeast(fallbackSpeed)
        .coerceAtMost(recordingFixProfileSpeedLimit(activityProfile) * RECORDING_FIX_MAX_REPORTED_SPEED_FACTOR)
}

/**
 * Two independent GPS accuracy circles must not be added in full. That makes a 20–30 m fix
 * able to create a 25 m detour even when the watch reports normal walking speed. A bounded
 * fraction leaves room for normal GPS noise while requiring unusually large moves to be
 * confirmed by a following fix.
 */
internal fun recordingTransitionUncertaintyAllowance(
    previous: RecordingFixSample,
    candidate: RecordingFixSample,
): Double =
    (
        maxOf(
            previous.accuracyMeters.validAccuracyOr(RECORDING_FIX_FALLBACK_ACCURACY_M),
            candidate.accuracyMeters.validAccuracyOr(RECORDING_FIX_FALLBACK_ACCURACY_M),
        ) * RECORDING_FIX_ACCURACY_ALLOWANCE_FACTOR
    ) + RECORDING_FIX_BASE_ALLOWANCE_M

private fun Float?.isUnacceptablyPoor(maximumAccuracyMeters: Float): Boolean {
    val accuracy = this?.takeIf { it.isFinite() && it >= 0f } ?: return false
    return accuracy > maximumAccuracyMeters
}

private fun recordingFixProfileAccuracyLimitMeters(activityProfile: String): Float =
    if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
        RECORDING_FIX_MAX_BIKE_ACCURACY_M
    } else {
        RECORDING_FIX_MAX_HIKE_ACCURACY_M
    }

private fun recordingFixHardAccuracyLimitMeters(activityProfile: String): Float =
    if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
        RECORDING_FIX_HARD_MAX_BIKE_ACCURACY_M
    } else {
        RECORDING_FIX_HARD_MAX_HIKE_ACCURACY_M
    }

private data class RecordingSmoothingConfig(
    val maximumTurnDegrees: Double,
    val strength: Double,
    val accuracyAdjustmentFactor: Double,
    val minimumCapMeters: Double,
    val maximumCapMeters: Double,
    val minimumAdjustmentMeters: Double,
    val fallbackAccuracyMeters: Double,
    val maximumLegFraction: Double,
) {
    fun adjustmentProfile(
        mode: String,
        activityProfile: String,
        likelyIsolatedSpike: Boolean,
    ): RecordingAdjustmentProfile {
        if (!likelyIsolatedSpike) {
            return RecordingAdjustmentProfile(
                strength = strength,
                accuracyAdjustmentFactor = accuracyAdjustmentFactor,
                maximumCapMeters = maximumCapMeters,
                maximumLegFraction = maximumLegFraction,
            )
        }
        val bike = activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE
        val strong = mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG
        return RecordingAdjustmentProfile(
            strength = if (strong) 0.96 else 0.82,
            accuracyAdjustmentFactor = if (strong) 0.90 else 0.80,
            maximumCapMeters =
                when {
                    bike && strong -> 20.0
                    bike -> 14.0
                    strong -> 12.0
                    else -> 9.0
                },
            maximumLegFraction = if (strong) 0.95 else 0.85,
        )
    }
}

private data class RecordingAdjustmentProfile(
    val strength: Double,
    val accuracyAdjustmentFactor: Double,
    val maximumCapMeters: Double,
    val maximumLegFraction: Double,
)

private data class RecordingSpikeCandidate(
    private val turnDegrees: Double,
    private val detourRatio: Double,
    private val lateralErrorMeters: Double,
    private val middleAccuracyMeters: Double,
    private val neighbourAccuracyMeters: Double,
) {
    fun isLikely(): Boolean =
        turnDegrees >= RECORDING_SPIKE_MIN_TURN_DEGREES &&
            lateralErrorMeters >= RECORDING_SPIKE_MIN_LATERAL_ERROR_M &&
            detourRatio >= RECORDING_SPIKE_MIN_DETOUR_RATIO &&
            middleAccuracyMeters >= RECORDING_SPIKE_MIN_ACCURACY_M &&
            middleAccuracyMeters >= neighbourAccuracyMeters * RECORDING_SPIKE_MIN_RELATIVE_ACCURACY
}

private fun smoothingConfig(
    mode: String,
    activityProfile: String,
): RecordingSmoothingConfig {
    val bike = activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE
    return if (mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG) {
        RecordingSmoothingConfig(
            maximumTurnDegrees = if (bike) 75.0 else 65.0,
            strength = 0.82,
            accuracyAdjustmentFactor = if (bike) 0.62 else 0.60,
            minimumCapMeters = if (bike) 1.5 else 0.9,
            maximumCapMeters = if (bike) 10.0 else 8.0,
            minimumAdjustmentMeters = 0.25,
            fallbackAccuracyMeters = 8.0,
            maximumLegFraction = 0.85,
        )
    } else {
        RecordingSmoothingConfig(
            maximumTurnDegrees = 60.0,
            strength = if (bike) 0.60 else 0.65,
            accuracyAdjustmentFactor = if (bike) 0.48 else 0.45,
            minimumCapMeters = if (bike) 1.0 else 0.6,
            maximumCapMeters = if (bike) 8.0 else 7.0,
            minimumAdjustmentMeters = 0.35,
            fallbackAccuracyMeters = 7.0,
            maximumLegFraction = if (bike) 0.65 else 0.70,
        )
    }
}

private data class LocalMeters(
    val x: Double,
    val y: Double,
) {
    fun length(): Double = hypot(x, y)

    fun toLatLong(origin: LatLong): LatLong {
        val latitude = origin.latitude + Math.toDegrees(y / EARTH_RADIUS_METERS)
        val longitudeScale = cos(Math.toRadians(origin.latitude)).coerceAtLeast(0.01)
        val longitude = origin.longitude + Math.toDegrees(x / (EARTH_RADIUS_METERS * longitudeScale))
        return LatLong(latitude, longitude)
    }
}

private fun LatLong.toLocalMeters(origin: LatLong): LocalMeters {
    val longitudeScale = cos(Math.toRadians((latitude + origin.latitude) / 2.0)).coerceAtLeast(0.01)
    return LocalMeters(
        x = Math.toRadians(longitude - origin.longitude) * EARTH_RADIUS_METERS * longitudeScale,
        y = Math.toRadians(latitude - origin.latitude) * EARTH_RADIUS_METERS,
    )
}

private fun angleDegrees(
    first: LocalMeters,
    second: LocalMeters,
): Double {
    val denominator = first.length() * second.length()
    if (denominator <= 0.0) return 180.0
    val cosine = ((first.x * second.x + first.y * second.y) / denominator).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(cosine))
}

private fun Float?.validAccuracyOr(fallbackMeters: Double): Double =
    this
        ?.takeIf { it.isFinite() && it >= 0f }
        ?.toDouble()
        ?: fallbackMeters

private fun recordingSmoothingMaximumIntervalMillis(sampleIntervalSeconds: Int): Long =
    (sampleIntervalSeconds.coerceAtLeast(1) * 1_000L * RECORDING_SMOOTHING_INTERVAL_MULTIPLIER)
        .coerceIn(RECORDING_SMOOTHING_MIN_MAX_INTERVAL_MS, RECORDING_SMOOTHING_ABSOLUTE_MAX_INTERVAL_MS)

private fun recordingStraightDriftMaximumIntervalMillis(sampleIntervalSeconds: Int): Long =
    (sampleIntervalSeconds.coerceAtLeast(1) * 1_000L * RECORDING_STRAIGHT_DRIFT_INTERVAL_MULTIPLIER)
        .coerceIn(RECORDING_STRAIGHT_DRIFT_MIN_MAX_INTERVAL_MS, RECORDING_STRAIGHT_DRIFT_ABSOLUTE_MAX_INTERVAL_MS)

private const val RECORDING_FIX_FALLBACK_HIKE_SPEED_MPS = 3.0
private const val RECORDING_FIX_FALLBACK_BIKE_SPEED_MPS = 10.0
internal const val RECORDING_FIX_MAX_REPORTED_SPEED_FACTOR = 1.25
private const val RECORDING_FIX_MAX_HIKE_ACCURACY_M = 35f
private const val RECORDING_FIX_MAX_BIKE_ACCURACY_M = 50f
private const val RECORDING_FIX_HARD_MAX_HIKE_ACCURACY_M = 100f
private const val RECORDING_FIX_HARD_MAX_BIKE_ACCURACY_M = 120f
private const val RECORDING_FIX_ACCURACY_BASELINE_WINDOW = 9
private const val RECORDING_FIX_ACCURACY_BASELINE_MIN_SAMPLES = 5
private const val RECORDING_FIX_ACCURACY_BASELINE_FACTOR = 1.75f
private const val RECORDING_FIX_ACCURACY_BASELINE_MARGIN_M = 3f
internal const val RECORDING_FIX_FALLBACK_ACCURACY_M = 12.0
internal const val RECORDING_FIX_ACCURACY_ALLOWANCE_FACTOR = 0.35
internal const val RECORDING_FIX_BASE_ALLOWANCE_M = 2.5
internal const val RECORDING_FIX_MAX_TRUSTED_SPEED_ACCURACY_MPS = 2.5
internal const val RECORDING_FIX_MAX_RELATIVE_SPEED_ACCURACY = 0.5
internal const val RECORDING_FIX_MAX_SPEED_UNCERTAINTY_ALLOWANCE_MPS = 2.5
private const val RECORDING_SMOOTHING_INTERVAL_MULTIPLIER = 3L
private const val RECORDING_SMOOTHING_MIN_MAX_INTERVAL_MS = 5_000L
private const val RECORDING_SMOOTHING_ABSOLUTE_MAX_INTERVAL_MS = 30_000L
private const val RECORDING_STRAIGHT_DRIFT_INTERVAL_MULTIPLIER = 5L
private const val RECORDING_STRAIGHT_DRIFT_MIN_MAX_INTERVAL_MS = 5_000L
private const val RECORDING_STRAIGHT_DRIFT_ABSOLUTE_MAX_INTERVAL_MS = 15_000L
private const val RECORDING_SMOOTHING_MIN_HIKE_LEG_M = 1.0
private const val RECORDING_SMOOTHING_MIN_BIKE_LEG_M = 3.0
private const val RECORDING_SMOOTHING_MIN_PROJECTION = 0.12
private const val RECORDING_SMOOTHING_MAX_PROJECTION = 0.88
private const val RECORDING_SMOOTHING_ACCURACY_PIVOT_M = 4.0
private const val RECORDING_SPIKE_MIN_TURN_DEGREES = 100.0
private const val RECORDING_SPIKE_MIN_LATERAL_ERROR_M = 3.5
private const val RECORDING_SPIKE_MIN_ACCURACY_M = 6.0
private const val RECORDING_SPIKE_MIN_RELATIVE_ACCURACY = 0.85
private const val RECORDING_SPIKE_MIN_DETOUR_RATIO = 1.50
private const val RECORDING_CANONICAL_MUTABLE_TAIL_POINTS = 3
private const val RECORDING_REVERSAL_MAX_RECOVERY_HEADING_DEGREES = 55.0
private const val RECORDING_REVERSAL_SENSOR_BACKED_MAX_RECOVERY_HEADING_DEGREES = 65.0
private const val RECORDING_REVERSAL_SENSOR_BACKED_MIN_PROJECTION = 0.0
private const val RECORDING_REVERSAL_MIN_PROJECTION = 0.08
private const val RECORDING_REVERSAL_MAX_PROJECTION = 0.92
private const val RECORDING_REVERSAL_MIN_ACCURACY_DEGRADATION_M = 3.0
private const val RECORDING_REVERSAL_MIN_HIKE_LATERAL_ERROR_M = 2.5
private const val RECORDING_REVERSAL_MIN_BIKE_LATERAL_ERROR_M = 4.0
private const val RECORDING_REVERSAL_MIN_TURN_DEGREES = 100.0
private const val RECORDING_REVERSAL_MIN_DETOUR_RATIO = 1.35
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
private const val RECORDING_STRAIGHT_DRIFT_HIKE_MIN_CHORD_M = 24.0
private const val RECORDING_STRAIGHT_DRIFT_BIKE_MIN_CHORD_M = 45.0
private const val RECORDING_STRAIGHT_DRIFT_HIKE_MIN_LATERAL_ERROR_M = 2.5
private const val RECORDING_STRAIGHT_DRIFT_BIKE_MIN_LATERAL_ERROR_M = 4.0
private const val RECORDING_STRAIGHT_DRIFT_MIN_PROJECTION = 0.12
private const val RECORDING_STRAIGHT_DRIFT_MAX_PROJECTION = 0.88
private const val RECORDING_STRAIGHT_DRIFT_MIN_PROGRESS_FRACTION = 0.12
private const val RECORDING_STRAIGHT_DRIFT_MAX_DETOUR_RATIO = 1.30
private const val RECORDING_STRAIGHT_DRIFT_ADAPTIVE_MIN_ACCURACY_M = 8.0
private const val RECORDING_STRAIGHT_DRIFT_STRONG_MIN_ACCURACY_M = 6.0
private const val RECORDING_STRAIGHT_DRIFT_ADAPTIVE_STRENGTH = 0.70
private const val RECORDING_STRAIGHT_DRIFT_STRONG_STRENGTH = 0.84
private const val RECORDING_STRAIGHT_DRIFT_ACCURACY_CAP_FACTOR = 0.55
private const val RECORDING_STRAIGHT_DRIFT_ADAPTIVE_MAX_ADJUSTMENT_M = 8.0
private const val RECORDING_STRAIGHT_DRIFT_STRONG_MAX_ADJUSTMENT_M = 10.0
private const val RECORDING_STRAIGHT_DRIFT_MIN_APPLIED_ADJUSTMENT_M = 1.0
private const val RECORDING_DISTANCE_HIKE_FALLBACK_SPEED_MPS = 1.4
private const val RECORDING_DISTANCE_BIKE_FALLBACK_SPEED_MPS = 5.5
private const val RECORDING_DISTANCE_HIKE_MAX_RECOVERY_SPEED_MPS = 3.0
private const val RECORDING_DISTANCE_BIKE_MAX_RECOVERY_SPEED_MPS = 12.0
private const val RECORDING_DISTANCE_RECOVERY_SPEED_ALLOWANCE = 1.35
private const val RECORDING_DISTANCE_MIN_RECOVERY_ALLOWANCE_M = 8.0
private const val RECORDING_DISTANCE_CAP_EPSILON_M = 0.01
