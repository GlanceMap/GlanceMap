package com.glancemap.trailcore.recording

import kotlin.math.min

fun resolveRecordingContinuityRecoveryGapMillis(
    deliveryGapMillis: Long,
    committedPointGapMillis: Long,
    thresholdMillis: Long,
): Long? =
    maxOf(deliveryGapMillis, committedPointGapMillis)
        .takeIf { it >= thresholdMillis }

@Suppress("ReturnCount")
fun shouldStartRecordingGpsGapSegment(
    previous: RecordingPoint?,
    current: RecordingPoint,
    continuityGapMillis: Long?,
    activityProfile: String,
): Boolean {
    val gapMillis = continuityGapMillis ?: return false
    val profile =
        if (activityProfile == RecordingSettings.ACTIVITY_PROFILE_BIKE) {
            RecordingGpsGapSegmentPolicy(
                minimumGapMillis = 30_000L,
                baseAllowanceMeters = 80.0,
                maximumAllowanceMeters = 150.0,
            )
        } else {
            RecordingGpsGapSegmentPolicy(
                minimumGapMillis = 60_000L,
                baseAllowanceMeters = 40.0,
                maximumAllowanceMeters = 80.0,
            )
        }
    if (gapMillis < profile.minimumGapMillis || previous == null) return false
    val beforeAccuracy = previous.accuracyMeters?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
    val afterAccuracy = current.accuracyMeters?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
    val allowanceMeters =
        if (beforeAccuracy != null && afterAccuracy != null) {
            maxOf(profile.baseAllowanceMeters, beforeAccuracy + afterAccuracy)
                .coerceAtMost(profile.maximumAllowanceMeters)
        } else {
            profile.baseAllowanceMeters
        }
    return recordingHaversineMeters(previous.location, current.location) > allowanceMeters
}

private data class RecordingGpsGapSegmentPolicy(
    val minimumGapMillis: Long,
    val baseAllowanceMeters: Double,
    val maximumAllowanceMeters: Double,
)

/**
 * Keeps a one-point provisional tail for Watch-GPS distance only. It always uses the fixed
 * local Adaptive policy, regardless of the user's saved-track smoothing choice.
 */
class RecordingWatchGpsDistanceGeometry {
    private var finalizedPoint: RecordingPoint? = null
    private var provisionalPoint: PendingWatchGpsDistancePoint? = null

    @Suppress("ReturnCount")
    fun append(
        current: RecordingPoint,
        isContinuityRecovery: Boolean,
        activityProfile: String,
        sampleIntervalSeconds: Int,
    ): RecordingWatchGpsDistanceSegment? {
        val previous = finalizedPoint
        if (previous == null) {
            finalizedPoint = current
            return null
        }
        val provisional = provisionalPoint
        if (provisional == null) {
            provisionalPoint = PendingWatchGpsDistancePoint(current, isContinuityRecovery)
            return null
        }
        val finalizedCurrent =
            smoothRecordingMiddlePoint(
                before = previous,
                middle = provisional.point,
                after = current,
                options =
                    RecordingPointSmoothingOptions(
                        mode = RecordingSettings.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                        activityProfile = activityProfile,
                        sampleIntervalSeconds = sampleIntervalSeconds,
                    ),
            )?.point ?: provisional.point
        finalizedPoint = finalizedCurrent
        provisionalPoint = PendingWatchGpsDistancePoint(current, isContinuityRecovery)
        return RecordingWatchGpsDistanceSegment(
            previous = previous,
            current = finalizedCurrent,
            isContinuityRecovery = provisional.isContinuityRecovery,
        )
    }

    @Suppress("ReturnCount")
    fun flush(): RecordingWatchGpsDistanceSegment? {
        val previous = finalizedPoint ?: return null
        val provisional = provisionalPoint ?: return null
        finalizedPoint = provisional.point
        provisionalPoint = null
        return RecordingWatchGpsDistanceSegment(
            previous = previous,
            current = provisional.point,
            isContinuityRecovery = provisional.isContinuityRecovery,
        )
    }

    fun reset() {
        finalizedPoint = null
        provisionalPoint = null
    }
}

data class RecordingWatchGpsDistanceSegment(
    val previous: RecordingPoint,
    val current: RecordingPoint,
    val isContinuityRecovery: Boolean,
) {
    val geometricDeltaMeters: Double
        get() = recordingHaversineMeters(previous.location, current.location)
    val elapsedSincePreviousMs: Long
        get() = (current.timeMillis - previous.timeMillis).coerceAtLeast(0L)
}

private data class PendingWatchGpsDistancePoint(
    val point: RecordingPoint,
    val isContinuityRecovery: Boolean,
)

/**
 * Keeps Watch-GPS activity distance independent from saved-track smoothing and visual
 * recovery connectors. A real delivery outage or confirmed relocation is still bounded by
 * reported pace and accuracy so a bad reacquisition cannot add a large diagonal.
 */
data class RecordingDistanceEstimate(
    val distanceMeters: Double,
    val capped: Boolean,
    val maximumTrustedMeters: Double? = null,
)

class RecordingDistanceDiagnostics {
    var diagnosticsScope: String = RECORDING_DISTANCE_DIAGNOSTICS_SCOPE_FULL_SESSION
        private set
    var watchGpsRawGeometryMeters: Double = 0.0
        private set
    var continuityCappedMeters: Double = 0.0
        private set
    var continuityCapCount: Int = 0
        private set
    var continuityRecoverySegmentCount: Int = 0
        private set

    fun record(
        segment: RecordingWatchGpsDistanceSegment,
        estimate: RecordingDistanceEstimate,
    ) {
        watchGpsRawGeometryMeters += segment.geometricDeltaMeters.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        if (segment.isContinuityRecovery) {
            continuityRecoverySegmentCount += 1
        }
        if (estimate.capped) {
            continuityCapCount += 1
            continuityCappedMeters +=
                (segment.geometricDeltaMeters - estimate.distanceMeters)
                    .takeIf { it.isFinite() && it >= 0.0 }
                    ?: 0.0
        }
    }

    fun reset() {
        diagnosticsScope = RECORDING_DISTANCE_DIAGNOSTICS_SCOPE_FULL_SESSION
        watchGpsRawGeometryMeters = 0.0
        continuityCappedMeters = 0.0
        continuityCapCount = 0
        continuityRecoverySegmentCount = 0
    }

    fun markPostRecoveryPartial() {
        diagnosticsScope = RECORDING_DISTANCE_DIAGNOSTICS_SCOPE_POST_RECOVERY_PARTIAL
    }
}

data class RecordingDistanceComparison(
    val diagnosticsScope: String,
    val activityDistanceMeters: Double,
    val watchGpsRawGeometryMeters: Double,
    val continuityCappedMeters: Double,
    val continuityCapCount: Int,
    val continuityRecoverySegmentCount: Int,
    val canonicalGeometryMeters: Double,
    val activityMinusCanonicalMeters: Double,
    val activityVsCanonicalPercent: Double?,
)

fun buildRecordingDistanceComparison(
    activityDistanceMeters: Double,
    diagnostics: RecordingDistanceDiagnostics,
    canonicalPoints: List<RecordingPoint>,
): RecordingDistanceComparison {
    val canonicalGeometryMeters = recordingCanonicalPathDistance(canonicalPoints)
    val activityMinusCanonicalMeters = activityDistanceMeters - canonicalGeometryMeters
    return RecordingDistanceComparison(
        diagnosticsScope = diagnostics.diagnosticsScope,
        activityDistanceMeters = activityDistanceMeters,
        watchGpsRawGeometryMeters = diagnostics.watchGpsRawGeometryMeters,
        continuityCappedMeters = diagnostics.continuityCappedMeters,
        continuityCapCount = diagnostics.continuityCapCount,
        continuityRecoverySegmentCount = diagnostics.continuityRecoverySegmentCount,
        canonicalGeometryMeters = canonicalGeometryMeters,
        activityMinusCanonicalMeters = activityMinusCanonicalMeters,
        activityVsCanonicalPercent =
            canonicalGeometryMeters
                .takeIf { it.isFinite() && it > 0.0 }
                ?.let { activityMinusCanonicalMeters / it * 100.0 },
    )
}

const val RECORDING_DISTANCE_DIAGNOSTICS_SCOPE_FULL_SESSION = "full_session"
const val RECORDING_DISTANCE_DIAGNOSTICS_SCOPE_POST_RECOVERY_PARTIAL = "post_recovery_partial"

data class RecordingDistanceInput(
    val geometricDeltaMeters: Double,
    val previous: RecordingPoint?,
    val current: RecordingPoint,
    val elapsedSincePreviousMs: Long,
    val activityProfile: String,
    val isContinuityRecovery: Boolean,
)

fun estimateRecordingDistanceDelta(
    input: RecordingDistanceInput,
): RecordingDistanceEstimate {
    val geometricDeltaMeters = input.geometricDeltaMeters
    val previous = input.previous
    val current = input.current
    val elapsedSincePreviousMs = input.elapsedSincePreviousMs
    val activityProfile = input.activityProfile
    val isContinuityRecovery = input.isContinuityRecovery
    if (!isContinuityRecovery || previous == null || elapsedSincePreviousMs <= 0L) {
        return RecordingDistanceEstimate(distanceMeters = geometricDeltaMeters, capped = false)
    }
    val geometricDistance = geometricDeltaMeters.coerceAtLeast(0.0)
    val elapsedSeconds = elapsedSincePreviousMs / 1_000.0
    val reportedSpeeds =
        listOfNotNull(previous.speedMps, current.speedMps)
            .filter { it.isFinite() && it >= 0f }
    val fallbackSpeedMps =
        if (activityProfile == RecordingSettings.ACTIVITY_PROFILE_BIKE) {
            RECORDING_DISTANCE_BIKE_FALLBACK_SPEED_MPS
        } else {
            RECORDING_DISTANCE_HIKE_FALLBACK_SPEED_MPS
        }
    val trustedSpeedMps =
        reportedSpeeds
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.coerceAtMost(
                if (activityProfile == RecordingSettings.ACTIVITY_PROFILE_BIKE) {
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
