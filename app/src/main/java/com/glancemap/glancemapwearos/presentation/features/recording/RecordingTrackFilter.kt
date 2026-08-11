@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.mapsforge.core.model.LatLong
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

internal const val RECORDING_TRACK_FILTER_VERSION = 4
internal const val EARTH_RADIUS_METERS = 6_371_000.0

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

    fun reset() {
        lastSeenElapsedRealtimeMillis = Long.MIN_VALUE
        lastAccepted = null
        pendingImplausible = null
        sustainedMovementValidUntilElapsedRealtimeMillis = Long.MIN_VALUE
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

        if (candidate.accuracyMeters.isUnacceptablyPoor(activityProfile)) {
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
)

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
    if (angleDegrees(recoveredDirection, followingDirection) > RECORDING_REVERSAL_MAX_RECOVERY_HEADING_DEGREES) {
        return null
    }
    val baselineSquared =
        recoveredDirection.x * recoveredDirection.x + recoveredDirection.y * recoveredDirection.y
    val projectionFraction =
        (candidateLocal.x * recoveredDirection.x + candidateLocal.y * recoveredDirection.y) / baselineSquared
    if (projectionFraction !in RECORDING_REVERSAL_MIN_PROJECTION..RECORDING_REVERSAL_MAX_PROJECTION) return null
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
    val baseMaximumSpeed = recordingFixProfileSpeedLimit(activityProfile)
    val minimumModeledSpeed =
        if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            RECORDING_FIX_MIN_MODELED_BIKE_SPEED_MPS
        } else {
            RECORDING_FIX_MIN_MODELED_HIKE_SPEED_MPS
        }
    val reportedSpeedAllowance =
        candidate.speedMps
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.toDouble()
            ?.plus(
                RECORDING_FIX_SPEED_ACCURACY_MULTIPLIER *
                    (candidate.speedAccuracyMps?.takeIf { it.isFinite() && it >= 0f }?.toDouble() ?: 0.0),
            )
    val maximumSpeed =
        reportedSpeedAllowance
            ?.coerceAtLeast(minimumModeledSpeed)
            ?.coerceAtMost(baseMaximumSpeed * RECORDING_FIX_MAX_REPORTED_SPEED_FACTOR)
            ?: baseMaximumSpeed
    val previousAccuracy = previous.accuracyMeters.validAccuracyOr(RECORDING_FIX_FALLBACK_ACCURACY_M)
    val candidateAccuracy = candidate.accuracyMeters.validAccuracyOr(RECORDING_FIX_FALLBACK_ACCURACY_M)
    val uncertaintyAllowance =
        (previousAccuracy + candidateAccuracy) * RECORDING_FIX_ACCURACY_ALLOWANCE_FACTOR +
            RECORDING_FIX_BASE_ALLOWANCE_M
    val maximumDistance = maximumSpeed * elapsedSeconds + uncertaintyAllowance
    return haversineMeters(previous.latLong, candidate.latLong) <= maximumDistance
}

private fun Float?.isUnacceptablyPoor(activityProfile: String): Boolean {
    val accuracy = this?.takeIf { it.isFinite() && it >= 0f } ?: return false
    val limit =
        if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            RECORDING_FIX_MAX_BIKE_ACCURACY_M
        } else {
            RECORDING_FIX_MAX_HIKE_ACCURACY_M
        }
    return accuracy > limit
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
            strength = if (strong) 0.82 else 0.68,
            accuracyAdjustmentFactor = if (strong) 0.72 else 0.55,
            maximumCapMeters =
                when {
                    bike && strong -> 16.0
                    bike -> 10.0
                    strong -> 10.0
                    else -> 6.0
                },
            maximumLegFraction = if (strong) 0.78 else 0.68,
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
            strength = 0.70,
            accuracyAdjustmentFactor = if (bike) 0.42 else 0.34,
            minimumCapMeters = if (bike) 1.25 else 0.75,
            maximumCapMeters = if (bike) 6.0 else 3.5,
            minimumAdjustmentMeters = 0.25,
            fallbackAccuracyMeters = 8.0,
            maximumLegFraction = 0.45,
        )
    } else {
        RecordingSmoothingConfig(
            maximumTurnDegrees = if (bike) 55.0 else 50.0,
            strength = if (bike) 0.48 else 0.52,
            accuracyAdjustmentFactor = if (bike) 0.30 else 0.28,
            minimumCapMeters = if (bike) 0.75 else 0.4,
            maximumCapMeters = if (bike) 5.5 else 3.25,
            minimumAdjustmentMeters = 0.35,
            fallbackAccuracyMeters = 7.0,
            maximumLegFraction = if (bike) 0.42 else 0.40,
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

private const val RECORDING_FIX_MIN_MODELED_HIKE_SPEED_MPS = 3.5
private const val RECORDING_FIX_MIN_MODELED_BIKE_SPEED_MPS = 12.0
internal const val RECORDING_FIX_MAX_REPORTED_SPEED_FACTOR = 1.25
private const val RECORDING_FIX_MAX_HIKE_ACCURACY_M = 35f
private const val RECORDING_FIX_MAX_BIKE_ACCURACY_M = 50f
internal const val RECORDING_FIX_FALLBACK_ACCURACY_M = 12.0
internal const val RECORDING_FIX_ACCURACY_ALLOWANCE_FACTOR = 0.75
internal const val RECORDING_FIX_BASE_ALLOWANCE_M = 5.0
internal const val RECORDING_FIX_SPEED_ACCURACY_MULTIPLIER = 3.0
private const val RECORDING_SMOOTHING_INTERVAL_MULTIPLIER = 3L
private const val RECORDING_SMOOTHING_MIN_MAX_INTERVAL_MS = 5_000L
private const val RECORDING_SMOOTHING_ABSOLUTE_MAX_INTERVAL_MS = 30_000L
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
private const val RECORDING_REVERSAL_MIN_PROJECTION = 0.08
private const val RECORDING_REVERSAL_MAX_PROJECTION = 0.92
private const val RECORDING_REVERSAL_MIN_HIKE_LATERAL_ERROR_M = 2.5
private const val RECORDING_REVERSAL_MIN_BIKE_LATERAL_ERROR_M = 4.0
private const val RECORDING_REVERSAL_MIN_TURN_DEGREES = 100.0
private const val RECORDING_REVERSAL_MIN_DETOUR_RATIO = 1.35
