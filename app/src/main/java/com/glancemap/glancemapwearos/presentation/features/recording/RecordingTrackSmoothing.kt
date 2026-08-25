@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.mapsforge.core.model.LatLong
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

internal const val EARTH_RADIUS_METERS = 6_371_000.0

internal data class RecordingPointSmoothingResult(
    val point: RecordedTracePoint,
    val adjustmentMeters: Double,
)

internal data class RecordingPointSmoothingOptions(
    val mode: String,
    val activityProfile: String,
    val sampleIntervalSeconds: Int = SettingsRepository.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS,
)

/**
 * Per-append accounting for the fixed-lag saved-track estimator. It is deliberately carried
 * with the pure append result so replay tests can accumulate it without a second state holder.
 */
internal data class RecordingTrajectorySmoothingDiagnostics(
    val evaluatedPointCount: Int = 0,
    val adjustedPointCount: Int = 0,
    val totalAdjustmentMeters: Double = 0.0,
    val maximumAdjustmentMeters: Double = 0.0,
    val turnProtectedPointCount: Int = 0,
    val barrierCount: Int = 0,
    val gapResetCount: Int = 0,
) {
    val averageAdjustmentMeters: Double
        get() = if (adjustedPointCount > 0) totalAdjustmentMeters / adjustedPointCount else 0.0

    fun plus(other: RecordingTrajectorySmoothingDiagnostics): RecordingTrajectorySmoothingDiagnostics =
        RecordingTrajectorySmoothingDiagnostics(
            evaluatedPointCount = evaluatedPointCount + other.evaluatedPointCount,
            adjustedPointCount = adjustedPointCount + other.adjustedPointCount,
            totalAdjustmentMeters = totalAdjustmentMeters + other.totalAdjustmentMeters,
            maximumAdjustmentMeters = maxOf(maximumAdjustmentMeters, other.maximumAdjustmentMeters),
            turnProtectedPointCount = turnProtectedPointCount + other.turnProtectedPointCount,
            barrierCount = barrierCount + other.barrierCount,
            gapResetCount = gapResetCount + other.gapResetCount,
        )
}

internal data class RecordingCanonicalAppendResult(
    val points: List<RecordedTracePoint>,
    val distanceDeltaMeters: Double,
    val adjustedPointCount: Int,
    val adjustmentMeters: Double,
    val maximumAdjustmentMeters: Double,
    val confirmedReversalCorrected: Boolean,
    val straightDriftCorrectedPointCount: Int,
    val trajectoryDiagnostics: RecordingTrajectorySmoothingDiagnostics = RecordingTrajectorySmoothingDiagnostics(),
)

/**
 * Appends one real accepted fix to the canonical track. The recent raw tail is bounded by
 * elapsed time, travelled distance and a point cap. Points are changed only when they leave
 * that tail, so saved geometry gets look-ahead without recursively smoothing prior output.
 */
internal fun appendCanonicalRecordingPoint(
    existingPoints: List<RecordedTracePoint>,
    point: RecordedTracePoint,
    options: RecordingPointSmoothingOptions,
): RecordingCanonicalAppendResult {
    if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF) {
        return RecordingCanonicalAppendResult(
            points = existingPoints + point,
            distanceDeltaMeters = recordingLastLegDistance(existingPoints.lastOrNull(), point),
            adjustedPointCount = 0,
            adjustmentMeters = 0.0,
            maximumAdjustmentMeters = 0.0,
            confirmedReversalCorrected = false,
            straightDriftCorrectedPointCount = 0,
        )
    }

    val gapReset =
        existingPoints.lastOrNull()?.let { previous ->
            recordingTrajectoryGap(previous = previous, current = point, options = options)
        } ?: false
    if (point.startsNewSegment || gapReset) {
        val flushed = flushCanonicalRecordingTail(existingPoints = existingPoints, options = options)
        val appendedPoints = flushed.points + point
        return flushed.copy(
            points = appendedPoints,
            distanceDeltaMeters = recordingLastLegDistance(flushed.points.lastOrNull(), point),
            trajectoryDiagnostics =
                flushed.trajectoryDiagnostics.plus(
                    RecordingTrajectorySmoothingDiagnostics(gapResetCount = if (gapReset) 1 else 0),
                ),
        )
    }

    val oldTailStartIndex = recordingTrajectoryTailStartIndex(existingPoints, options)
    val combinedPoints = existingPoints + point
    val newTailStartIndex = recordingTrajectoryTailStartIndex(combinedPoints, options)
    val revision =
        finalizeRecordingTrajectoryPoints(
            sourcePoints = combinedPoints,
            firstFinalizeIndex = oldTailStartIndex,
            endFinalizeExclusive = newTailStartIndex,
            options = options,
        )
    val distanceStartIndex = (oldTailStartIndex - 1).coerceAtLeast(0)
    val oldTailDistance = recordingCanonicalPathDistance(existingPoints.subList(distanceStartIndex, existingPoints.size))
    val newTailDistance = recordingCanonicalPathDistance(revision.points.subList(distanceStartIndex, revision.points.size))
    return RecordingCanonicalAppendResult(
        points = revision.points,
        distanceDeltaMeters = newTailDistance - oldTailDistance,
        adjustedPointCount = revision.adjustedPointCount,
        adjustmentMeters = revision.totalAdjustmentMeters,
        maximumAdjustmentMeters = revision.maximumAdjustmentMeters,
        confirmedReversalCorrected = revision.confirmedReversalCorrected,
        straightDriftCorrectedPointCount = revision.straightDriftCorrectedPointCount,
        trajectoryDiagnostics = revision.trajectoryDiagnostics,
    )
}

/** Finalizes the current raw tail before a pause, segment boundary or GPX save. */
internal fun flushCanonicalRecordingTail(
    existingPoints: List<RecordedTracePoint>,
    options: RecordingPointSmoothingOptions,
): RecordingCanonicalAppendResult {
    if (existingPoints.isEmpty() || options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF) {
        return RecordingCanonicalAppendResult(
            points = existingPoints,
            distanceDeltaMeters = 0.0,
            adjustedPointCount = 0,
            adjustmentMeters = 0.0,
            maximumAdjustmentMeters = 0.0,
            confirmedReversalCorrected = false,
            straightDriftCorrectedPointCount = 0,
        )
    }
    val tailStartIndex =
        existingPoints.indexOfFirst { point -> !point.trajectoryFinalized }
            .takeIf { index -> index >= 0 }
            ?: existingPoints.size
    val revision =
        finalizeRecordingTrajectoryPoints(
            sourcePoints = existingPoints,
            firstFinalizeIndex = tailStartIndex,
            endFinalizeExclusive = existingPoints.size,
            options = options,
        )
    return RecordingCanonicalAppendResult(
        points = revision.points,
        distanceDeltaMeters = 0.0,
        adjustedPointCount = revision.adjustedPointCount,
        adjustmentMeters = revision.totalAdjustmentMeters,
        maximumAdjustmentMeters = revision.maximumAdjustmentMeters,
        confirmedReversalCorrected = revision.confirmedReversalCorrected,
        straightDriftCorrectedPointCount = revision.straightDriftCorrectedPointCount,
        trajectoryDiagnostics = revision.trajectoryDiagnostics,
    )
}

private fun recordingLastLegDistance(
    previous: RecordedTracePoint?,
    current: RecordedTracePoint,
): Double =
    if (previous == null || current.startsNewSegment) 0.0 else haversineMeters(previous.latLong, current.latLong)

internal fun recordingCanonicalPathDistance(points: List<RecordedTracePoint>): Double =
    points.zipWithNext().sumOf { (before, after) ->
        if (after.startsNewSegment) {
            0.0
        } else {
            haversineMeters(before.latLong, after.latLong)
        }
    }

private data class RecordingTrajectoryPolicy(
    val adaptiveTailLagMillis: Long,
    val strongTailLagMillis: Long,
    val adaptiveTailDistanceMeters: Double,
    val strongTailDistanceMeters: Double,
    val maximumTailPointCount: Int,
    val minimumGapResetMillis: Long,
    val maximumGapResetMillis: Long,
    val directionContextMillis: Long,
    val directionContextDistanceMeters: Double,
    val directionMaximumPointCount: Int,
    val minimumDirectionLegCount: Int,
    val minimumTurnEvidenceMeters: Double,
    val minimumDirectionCoherence: Double,
    val barrierTurnDegrees: Double,
    val adaptiveFitLagMillis: Long,
    val strongFitLagMillis: Long,
    val adaptiveFitDistanceMeters: Double,
    val strongFitDistanceMeters: Double,
    val adaptiveFitMaximumPointCount: Int,
    val strongFitMaximumPointCount: Int,
    val adaptiveStrength: Double,
    val strongStrength: Double,
    val adjustmentAccuracyCapFactor: Double,
    val adaptiveMaximumAdjustmentMeters: Double,
    val strongMaximumAdjustmentMeters: Double,
    val minimumAdjustmentMeters: Double,
    val barrierGuardPoints: Int,
    val detourContextMillis: Long,
    val detourContextDistanceMeters: Double,
    val detourMaximumPointCount: Int,
    val detourMinimumRatio: Double,
    val detourMinimumLateralMeters: Double,
    val detourMaximumRejoinHeadingDegrees: Double,
    val adaptiveDetourFraction: Double,
    val strongDetourFraction: Double,
)

private data class RecordingTrajectoryContext(
    val maximumElapsedMillis: Long,
    val maximumTravelledMeters: Double,
    val maximumPointCount: Int,
)

private data class RecordingTrajectoryDirectionEvidence(
    val direction: LocalMeters,
    val coherence: Double,
    val legCount: Int,
    val travelledMeters: Double,
)

private data class RecordingTrajectoryTurnEvidence(
    val incoming: RecordingTrajectoryDirectionEvidence,
    val outgoing: RecordingTrajectoryDirectionEvidence,
    val turnDegrees: Double,
)

private data class RecordingTrajectoryRevision(
    val points: List<RecordedTracePoint>,
    val adjustedPointCount: Int,
    val totalAdjustmentMeters: Double,
    val maximumAdjustmentMeters: Double,
    val confirmedReversalCorrected: Boolean,
    val straightDriftCorrectedPointCount: Int,
    val trajectoryDiagnostics: RecordingTrajectorySmoothingDiagnostics,
)

private fun recordingTrajectoryPolicy(activityProfile: String): RecordingTrajectoryPolicy =
    if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
        RecordingTrajectoryPolicy(
            adaptiveTailLagMillis = 18_000L,
            strongTailLagMillis = 26_000L,
            adaptiveTailDistanceMeters = 120.0,
            strongTailDistanceMeters = 160.0,
            maximumTailPointCount = 16,
            minimumGapResetMillis = 12_000L,
            maximumGapResetMillis = 60_000L,
            directionContextMillis = 15_000L,
            directionContextDistanceMeters = 70.0,
            directionMaximumPointCount = 3,
            minimumDirectionLegCount = 2,
            minimumTurnEvidenceMeters = 18.0,
            minimumDirectionCoherence = 0.72,
            barrierTurnDegrees = 65.0,
            adaptiveFitLagMillis = 18_000L,
            strongFitLagMillis = 26_000L,
            adaptiveFitDistanceMeters = 120.0,
            strongFitDistanceMeters = 160.0,
            adaptiveFitMaximumPointCount = 8,
            strongFitMaximumPointCount = 12,
            adaptiveStrength = 0.60,
            strongStrength = 0.78,
            adjustmentAccuracyCapFactor = 0.60,
            adaptiveMaximumAdjustmentMeters = 12.0,
            strongMaximumAdjustmentMeters = 16.0,
            minimumAdjustmentMeters = 0.45,
            barrierGuardPoints = 2,
            detourContextMillis = 18_000L,
            detourContextDistanceMeters = 75.0,
            detourMaximumPointCount = 6,
            detourMinimumRatio = 1.35,
            detourMinimumLateralMeters = 5.0,
            detourMaximumRejoinHeadingDegrees = 35.0,
            adaptiveDetourFraction = 0.50,
            strongDetourFraction = 0.75,
        )
    } else {
        RecordingTrajectoryPolicy(
            adaptiveTailLagMillis = 30_000L,
            strongTailLagMillis = 42_000L,
            adaptiveTailDistanceMeters = 65.0,
            strongTailDistanceMeters = 90.0,
            maximumTailPointCount = 14,
            minimumGapResetMillis = 18_000L,
            maximumGapResetMillis = 90_000L,
            directionContextMillis = 21_000L,
            directionContextDistanceMeters = 42.0,
            directionMaximumPointCount = 3,
            minimumDirectionLegCount = 2,
            minimumTurnEvidenceMeters = 12.0,
            minimumDirectionCoherence = 0.72,
            barrierTurnDegrees = 55.0,
            adaptiveFitLagMillis = 30_000L,
            strongFitLagMillis = 42_000L,
            adaptiveFitDistanceMeters = 65.0,
            strongFitDistanceMeters = 90.0,
            adaptiveFitMaximumPointCount = 8,
            strongFitMaximumPointCount = 12,
            adaptiveStrength = 0.72,
            strongStrength = 0.88,
            adjustmentAccuracyCapFactor = 0.65,
            adaptiveMaximumAdjustmentMeters = 9.0,
            strongMaximumAdjustmentMeters = 12.0,
            minimumAdjustmentMeters = 0.35,
            barrierGuardPoints = 2,
            detourContextMillis = 25_000L,
            detourContextDistanceMeters = 45.0,
            detourMaximumPointCount = 6,
            detourMinimumRatio = 1.40,
            detourMinimumLateralMeters = 4.0,
            detourMaximumRejoinHeadingDegrees = 35.0,
            adaptiveDetourFraction = 0.55,
            strongDetourFraction = 0.78,
        )
    }

private fun recordingTrajectoryTailContext(
    policy: RecordingTrajectoryPolicy,
    options: RecordingPointSmoothingOptions,
): RecordingTrajectoryContext =
    RecordingTrajectoryContext(
        maximumElapsedMillis =
            recordingTrajectoryContextMillis(
                baseMillis =
                    if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG) {
                        policy.strongTailLagMillis
                    } else {
                        policy.adaptiveTailLagMillis
                    },
                maximumMillis = policy.maximumGapResetMillis,
                options = options,
            ),
        maximumTravelledMeters =
            if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG) {
                policy.strongTailDistanceMeters
            } else {
                policy.adaptiveTailDistanceMeters
            },
        maximumPointCount = policy.maximumTailPointCount,
    )

private fun recordingTrajectoryFitContext(
    policy: RecordingTrajectoryPolicy,
    options: RecordingPointSmoothingOptions,
): RecordingTrajectoryContext =
    RecordingTrajectoryContext(
        maximumElapsedMillis =
            recordingTrajectoryContextMillis(
                baseMillis =
                    if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG) {
                        policy.strongFitLagMillis
                    } else {
                        policy.adaptiveFitLagMillis
                    },
                maximumMillis = policy.maximumGapResetMillis,
                options = options,
            ),
        maximumTravelledMeters =
            if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG) {
                policy.strongFitDistanceMeters
            } else {
                policy.adaptiveFitDistanceMeters
            },
        maximumPointCount =
            if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG) {
                policy.strongFitMaximumPointCount
            } else {
                policy.adaptiveFitMaximumPointCount
            },
    )

private fun recordingTrajectoryDirectionContext(
    policy: RecordingTrajectoryPolicy,
    options: RecordingPointSmoothingOptions,
): RecordingTrajectoryContext =
    RecordingTrajectoryContext(
        maximumElapsedMillis =
            recordingTrajectoryContextMillis(
                baseMillis = policy.directionContextMillis,
                maximumMillis = policy.maximumGapResetMillis,
                options = options,
            ),
        maximumTravelledMeters = policy.directionContextDistanceMeters,
        maximumPointCount = policy.directionMaximumPointCount,
    )

private fun recordingTrajectoryDetourContext(
    policy: RecordingTrajectoryPolicy,
    options: RecordingPointSmoothingOptions,
): RecordingTrajectoryContext =
    RecordingTrajectoryContext(
        maximumElapsedMillis =
            recordingTrajectoryContextMillis(
                baseMillis = policy.detourContextMillis,
                maximumMillis = policy.maximumGapResetMillis,
                options = options,
            ),
        maximumTravelledMeters = policy.detourContextDistanceMeters,
        maximumPointCount = policy.detourMaximumPointCount,
    )

private fun recordingTrajectoryContextMillis(
    baseMillis: Long,
    maximumMillis: Long,
    options: RecordingPointSmoothingOptions,
): Long =
    maxOf(baseMillis, options.sampleIntervalSeconds.coerceAtLeast(1) * 3_000L)
        .coerceAtMost(maximumMillis)

private fun recordingTrajectoryGapResetMillis(
    policy: RecordingTrajectoryPolicy,
    options: RecordingPointSmoothingOptions,
): Long =
    maxOf(policy.minimumGapResetMillis, options.sampleIntervalSeconds.coerceAtLeast(1) * 3_000L)
        .coerceAtMost(policy.maximumGapResetMillis)

private fun recordingTrajectoryTailStartIndex(
    points: List<RecordedTracePoint>,
    options: RecordingPointSmoothingOptions,
): Int {
    if (points.isEmpty()) return 0
    val firstUnfinalizedIndex = points.indexOfFirst { point -> !point.trajectoryFinalized }
    if (firstUnfinalizedIndex < 0) return points.size
    val policy = recordingTrajectoryPolicy(options.activityProfile)
    val newest = points.last()
    val context = recordingTrajectoryTailContext(policy, options)
    var startIndex = points.lastIndex
    var travelledMeters = 0.0
    var pointCount = 1
    while (startIndex > firstUnfinalizedIndex && pointCount < context.maximumPointCount) {
        val current = points[startIndex]
        val previous = points[startIndex - 1]
        if (current.startsNewSegment || previous.trajectoryFinalized) break
        if (!recordingTrajectoryPointsAreContinuous(previous, current, options)) break
        if (newest.timeMillis - previous.timeMillis > context.maximumElapsedMillis) break
        val legMeters = haversineMeters(previous.latLong, current.latLong)
        if (travelledMeters + legMeters > context.maximumTravelledMeters) break
        travelledMeters += legMeters
        startIndex -= 1
        pointCount += 1
    }
    return startIndex
}

private fun recordingTrajectoryGap(
    previous: RecordedTracePoint,
    current: RecordedTracePoint,
    options: RecordingPointSmoothingOptions,
): Boolean {
    val policy = recordingTrajectoryPolicy(options.activityProfile)
    val intervalMillis = current.timeMillis - previous.timeMillis
    return intervalMillis !in 1..recordingTrajectoryGapResetMillis(policy, options)
}

private fun recordingTrajectoryPointsAreContinuous(
    previous: RecordedTracePoint,
    current: RecordedTracePoint,
    options: RecordingPointSmoothingOptions,
): Boolean {
    val policy = recordingTrajectoryPolicy(options.activityProfile)
    return !current.startsNewSegment &&
        current.timeMillis - previous.timeMillis in 1..recordingTrajectoryGapResetMillis(policy, options)
}

private fun finalizeRecordingTrajectoryPoints(
    sourcePoints: List<RecordedTracePoint>,
    firstFinalizeIndex: Int,
    endFinalizeExclusive: Int,
    options: RecordingPointSmoothingOptions,
): RecordingTrajectoryRevision {
    if (firstFinalizeIndex >= endFinalizeExclusive) {
        return RecordingTrajectoryRevision(
            points = sourcePoints,
            adjustedPointCount = 0,
            totalAdjustmentMeters = 0.0,
            maximumAdjustmentMeters = 0.0,
            confirmedReversalCorrected = false,
            straightDriftCorrectedPointCount = 0,
            trajectoryDiagnostics = RecordingTrajectorySmoothingDiagnostics(),
        )
    }
    val policy = recordingTrajectoryPolicy(options.activityProfile)
    val barriers = recordingTrajectoryTurnBarriers(sourcePoints, options, policy)
    val revisedPoints = sourcePoints.toMutableList()
    var adjustedPointCount = 0
    var totalAdjustmentMeters = 0.0
    var maximumAdjustmentMeters = 0.0
    var confirmedReversalCorrected = false
    var straightDriftCorrectedPointCount = 0
    var evaluatedPointCount = 0
    var trajectoryAdjustedPointCount = 0
    var trajectoryAdjustmentMeters = 0.0
    var trajectoryMaximumAdjustmentMeters = 0.0
    var turnProtectedPointCount = 0

    for (index in firstFinalizeIndex until endFinalizeExclusive) {
        evaluatedPointCount += 1
        val straightDrift =
            if (index >= 1 && index + 2 < sourcePoints.size) {
                smoothRecordingStraightDrift(
                    before = sourcePoints[index - 1],
                    firstInterior = sourcePoints[index],
                    secondInterior = sourcePoints[index + 1],
                    after = sourcePoints[index + 2],
                    options = options,
                )
            } else {
                null
            }
        val reversal =
            if (index >= 1 && index + 2 < sourcePoints.size) {
                smoothConfirmedRecordingReversal(
                    before = sourcePoints[index - 1],
                    candidate = straightDrift?.point ?: sourcePoints[index],
                    recovered = sourcePoints[index + 1],
                    following = sourcePoints[index + 2],
                    options = options,
                )
            } else {
                null
            }
        if (reversal != null) {
            revisedPoints[index] = reversal.point.copy(trajectoryFinalized = true)
            adjustedPointCount += 1
            totalAdjustmentMeters += reversal.adjustmentMeters
            maximumAdjustmentMeters = maxOf(maximumAdjustmentMeters, reversal.adjustmentMeters)
            confirmedReversalCorrected = true
            continue
        }

        val detour =
            smoothConfirmedRecordingDetour(
                sourcePoints = sourcePoints,
                index = index,
                barriers = barriers,
                options = options,
                policy = policy,
            )
        if (detour != null) {
            revisedPoints[index] = detour.point.copy(trajectoryFinalized = true)
            adjustedPointCount += 1
            totalAdjustmentMeters += detour.adjustmentMeters
            maximumAdjustmentMeters = maxOf(maximumAdjustmentMeters, detour.adjustmentMeters)
            trajectoryAdjustedPointCount += 1
            trajectoryAdjustmentMeters += detour.adjustmentMeters
            trajectoryMaximumAdjustmentMeters = maxOf(trajectoryMaximumAdjustmentMeters, detour.adjustmentMeters)
            continue
        }

        val protectedByTurn =
            barriers.any { barrier ->
                index in (barrier - policy.barrierGuardPoints)..(barrier + policy.barrierGuardPoints)
            }
        if (protectedByTurn) {
            revisedPoints[index] = sourcePoints[index].copy(trajectoryFinalized = true)
            turnProtectedPointCount += 1
            continue
        }

        if (straightDrift != null) {
            revisedPoints[index] = straightDrift.point.copy(trajectoryFinalized = true)
            adjustedPointCount += 1
            totalAdjustmentMeters += straightDrift.adjustmentMeters
            maximumAdjustmentMeters = maxOf(maximumAdjustmentMeters, straightDrift.adjustmentMeters)
            straightDriftCorrectedPointCount += 1
            continue
        }

        val trajectoryResult =
            smoothFixedLagRecordingTrajectoryPoint(
                sourcePoints = sourcePoints,
                index = index,
                barriers = barriers,
                options = options,
                policy = policy,
            )
        if (trajectoryResult != null) {
            revisedPoints[index] = trajectoryResult.point.copy(trajectoryFinalized = true)
            adjustedPointCount += 1
            totalAdjustmentMeters += trajectoryResult.adjustmentMeters
            maximumAdjustmentMeters = maxOf(maximumAdjustmentMeters, trajectoryResult.adjustmentMeters)
            trajectoryAdjustedPointCount += 1
            trajectoryAdjustmentMeters += trajectoryResult.adjustmentMeters
            trajectoryMaximumAdjustmentMeters = maxOf(trajectoryMaximumAdjustmentMeters, trajectoryResult.adjustmentMeters)
            continue
        }

        val middlePoint =
            if (index >= 1 && index + 1 < sourcePoints.size) {
                smoothRecordingMiddlePoint(
                    before = sourcePoints[index - 1],
                    middle = sourcePoints[index],
                    after = sourcePoints[index + 1],
                    options = options,
                )
            } else {
                null
            }
        if (middlePoint != null) {
            adjustedPointCount += 1
            totalAdjustmentMeters += middlePoint.adjustmentMeters
            maximumAdjustmentMeters = maxOf(maximumAdjustmentMeters, middlePoint.adjustmentMeters)
        }
        revisedPoints[index] = (middlePoint?.point ?: sourcePoints[index]).copy(trajectoryFinalized = true)
    }
    return RecordingTrajectoryRevision(
        points = revisedPoints,
        adjustedPointCount = adjustedPointCount,
        totalAdjustmentMeters = totalAdjustmentMeters,
        maximumAdjustmentMeters = maximumAdjustmentMeters,
        confirmedReversalCorrected = confirmedReversalCorrected,
        straightDriftCorrectedPointCount = straightDriftCorrectedPointCount,
        trajectoryDiagnostics =
            RecordingTrajectorySmoothingDiagnostics(
                evaluatedPointCount = evaluatedPointCount,
                adjustedPointCount = trajectoryAdjustedPointCount,
                totalAdjustmentMeters = trajectoryAdjustmentMeters,
                maximumAdjustmentMeters = trajectoryMaximumAdjustmentMeters,
                turnProtectedPointCount = turnProtectedPointCount,
                barrierCount = barriers.count { it in firstFinalizeIndex until endFinalizeExclusive },
            ),
    )
}

private fun recordingTrajectoryTurnBarriers(
    points: List<RecordedTracePoint>,
    options: RecordingPointSmoothingOptions,
    policy: RecordingTrajectoryPolicy,
): Set<Int> {
    if (points.size < 5) return emptySet()
    val barriers = mutableSetOf<Int>()
    for (index in 2..points.lastIndex - 2) {
        val turn = recordingTrajectoryTurnEvidence(points, index, options, policy) ?: continue
        if (turn.turnDegrees >= policy.barrierTurnDegrees) {
            barriers += index
        }
    }
    return barriers
}

private fun recordingTrajectoryTurnEvidence(
    points: List<RecordedTracePoint>,
    index: Int,
    options: RecordingPointSmoothingOptions,
    policy: RecordingTrajectoryPolicy,
): RecordingTrajectoryTurnEvidence? {
    val incoming =
        recordingTrajectoryDirectionEvidence(
            points = points,
            index = index,
            incoming = true,
            options = options,
            policy = policy,
        ) ?: return null
    val outgoing =
        recordingTrajectoryDirectionEvidence(
            points = points,
            index = index,
            incoming = false,
            options = options,
            policy = policy,
        ) ?: return null
    return RecordingTrajectoryTurnEvidence(
        incoming = incoming,
        outgoing = outgoing,
        turnDegrees = angleDegrees(incoming.direction, outgoing.direction),
    )
}

private fun recordingTrajectoryDirectionEvidence(
    points: List<RecordedTracePoint>,
    index: Int,
    incoming: Boolean,
    options: RecordingPointSmoothingOptions,
    policy: RecordingTrajectoryPolicy,
): RecordingTrajectoryDirectionEvidence? {
    val context = recordingTrajectoryDirectionContext(policy, options)
    var cursor = index
    var pointCount = 1
    var travelledMeters = 0.0
    var weightedDistance = 0.0
    var directionX = 0.0
    var directionY = 0.0
    var legCount = 0
    while (pointCount < context.maximumPointCount) {
        val next = if (incoming) cursor - 1 else cursor + 1
        if (next !in points.indices) break
        val fromIndex = if (incoming) next else cursor
        val toIndex = if (incoming) cursor else next
        val from = points[fromIndex]
        val to = points[toIndex]
        if (!recordingTrajectoryPointsAreContinuous(from, to, options)) break
        val elapsedMillis =
            if (incoming) {
                points[index].timeMillis - from.timeMillis
            } else {
                to.timeMillis - points[index].timeMillis
            }
        if (elapsedMillis > context.maximumElapsedMillis) break
        val vector = to.latLong.toLocalMeters(from.latLong)
        val length = vector.length()
        if (length <= 0.0 || travelledMeters + length > context.maximumTravelledMeters) break
        val accuracy =
            (from.accuracyMeters.validAccuracyOr(RECORDING_TRAJECTORY_FALLBACK_ACCURACY_M) +
                to.accuracyMeters.validAccuracyOr(RECORDING_TRAJECTORY_FALLBACK_ACCURACY_M)) / 2.0
        val weight = length / accuracy.coerceAtLeast(RECORDING_TRAJECTORY_MIN_WEIGHT_ACCURACY_M)
        directionX += vector.x / length * weight
        directionY += vector.y / length * weight
        weightedDistance += weight
        travelledMeters += length
        legCount += 1
        pointCount += 1
        cursor = next
    }
    val direction = LocalMeters(directionX, directionY)
    if (
        legCount < policy.minimumDirectionLegCount ||
        travelledMeters < policy.minimumTurnEvidenceMeters ||
        weightedDistance <= 0.0
    ) {
        return null
    }
    val coherence = direction.length() / weightedDistance
    if (coherence < policy.minimumDirectionCoherence) return null
    return RecordingTrajectoryDirectionEvidence(
        direction = direction,
        coherence = coherence,
        legCount = legCount,
        travelledMeters = travelledMeters,
    )
}

private fun smoothFixedLagRecordingTrajectoryPoint(
    sourcePoints: List<RecordedTracePoint>,
    index: Int,
    barriers: Set<Int>,
    options: RecordingPointSmoothingOptions,
    policy: RecordingTrajectoryPolicy,
): RecordingPointSmoothingResult? {
    val fitRange = recordingTrajectoryFitRange(sourcePoints, index, barriers, options, policy) ?: return null
    val point = sourcePoints[index]
    val origin = point.latLong
    var totalWeight = 0.0
    var totalTime = 0.0
    var totalTimeSquared = 0.0
    var totalX = 0.0
    var totalY = 0.0
    var totalTimeX = 0.0
    var totalTimeY = 0.0
    for (fitIndex in fitRange) {
        val accuracy = sourcePoints[fitIndex].accuracyMeters.validAccuracyOr(RECORDING_TRAJECTORY_FALLBACK_ACCURACY_M)
        val weight = 1.0 / (accuracy.coerceAtLeast(RECORDING_TRAJECTORY_MIN_WEIGHT_ACCURACY_M).let { it * it })
        val local = sourcePoints[fitIndex].latLong.toLocalMeters(origin)
        val timeSeconds = (sourcePoints[fitIndex].timeMillis - point.timeMillis) / 1_000.0
        totalWeight += weight
        totalTime += timeSeconds * weight
        totalTimeSquared += timeSeconds * timeSeconds * weight
        totalX += local.x * weight
        totalY += local.y * weight
        totalTimeX += timeSeconds * local.x * weight
        totalTimeY += timeSeconds * local.y * weight
    }
    if (totalWeight <= 0.0) return null
    val timeVariance = totalWeight * totalTimeSquared - totalTime * totalTime
    if (timeVariance <= RECORDING_TRAJECTORY_MIN_TIME_VARIANCE) return null
    val predicted =
        LocalMeters(
            x = (totalX - ((totalWeight * totalTimeX - totalTime * totalX) / timeVariance) * totalTime) / totalWeight,
            y = (totalY - ((totalWeight * totalTimeY - totalTime * totalY) / timeVariance) * totalTime) / totalWeight,
        )
    val correction = predicted
    val correctionMeters = correction.length()
    if (correctionMeters < policy.minimumAdjustmentMeters) return null
    val turnDegrees = recordingTrajectoryTurnEvidence(sourcePoints, index, options, policy)?.turnDegrees ?: 0.0
    val curvatureFactor = (1.0 - (turnDegrees / policy.barrierTurnDegrees) * 0.80).coerceIn(0.25, 1.0)
    val pointAccuracy = point.accuracyMeters.validAccuracyOr(RECORDING_TRAJECTORY_FALLBACK_ACCURACY_M)
    val accuracyNeed =
        (pointAccuracy / (pointAccuracy + RECORDING_TRAJECTORY_ACCURACY_PIVOT_M)).coerceIn(0.25, 0.95)
    val strength =
        (if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG) {
            policy.strongStrength
        } else {
            policy.adaptiveStrength
        }) * curvatureFactor * accuracyNeed
    val adjustmentCap =
        (pointAccuracy * policy.adjustmentAccuracyCapFactor)
            .coerceIn(
                policy.minimumAdjustmentMeters,
                if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG) {
                    policy.strongMaximumAdjustmentMeters
                } else {
                    policy.adaptiveMaximumAdjustmentMeters
                },
            )
    val adjustmentMeters = min(correctionMeters * strength, adjustmentCap)
    if (adjustmentMeters < policy.minimumAdjustmentMeters) return null
    val fraction = (adjustmentMeters / correctionMeters).coerceIn(0.0, 1.0)
    return RecordingPointSmoothingResult(
        point =
            point.copy(
                latLong =
                    LocalMeters(
                        x = correction.x * fraction,
                        y = correction.y * fraction,
                    ).toLatLong(origin),
            ),
        adjustmentMeters = adjustmentMeters,
    )
}

private fun recordingTrajectoryFitRange(
    points: List<RecordedTracePoint>,
    index: Int,
    barriers: Set<Int>,
    options: RecordingPointSmoothingOptions,
    policy: RecordingTrajectoryPolicy,
): IntRange? {
    val context = recordingTrajectoryFitContext(policy, options)
    var startIndex = index
    var backwardTravelledMeters = 0.0
    var backwardPointCount = 0
    val backwardPointLimit = (context.maximumPointCount - 1) / 2
    while (backwardPointCount < backwardPointLimit) {
        val candidate = startIndex - 1
        if (candidate < 0 || candidate in barriers || startIndex in barriers) break
        if (!recordingTrajectoryPointsAreContinuous(points[candidate], points[startIndex], options)) break
        if (points[index].timeMillis - points[candidate].timeMillis > context.maximumElapsedMillis) break
        val legMeters = haversineMeters(points[candidate].latLong, points[startIndex].latLong)
        if (backwardTravelledMeters + legMeters > context.maximumTravelledMeters) break
        backwardTravelledMeters += legMeters
        startIndex = candidate
        backwardPointCount += 1
    }
    var endIndex = index
    var forwardTravelledMeters = 0.0
    var forwardPointCount = 0
    val forwardPointLimit = context.maximumPointCount - 1 - backwardPointLimit
    while (forwardPointCount < forwardPointLimit) {
        val candidate = endIndex + 1
        if (candidate > points.lastIndex || candidate in barriers || endIndex in barriers) break
        if (!recordingTrajectoryPointsAreContinuous(points[endIndex], points[candidate], options)) break
        if (points[candidate].timeMillis - points[index].timeMillis > context.maximumElapsedMillis) break
        val legMeters = haversineMeters(points[endIndex].latLong, points[candidate].latLong)
        if (forwardTravelledMeters + legMeters > context.maximumTravelledMeters) break
        forwardTravelledMeters += legMeters
        endIndex = candidate
        forwardPointCount += 1
    }
    return (startIndex..endIndex).takeIf { it.last - it.first + 1 >= RECORDING_TRAJECTORY_MIN_FIT_POINT_COUNT }
}

private fun smoothConfirmedRecordingDetour(
    sourcePoints: List<RecordedTracePoint>,
    index: Int,
    barriers: Set<Int>,
    options: RecordingPointSmoothingOptions,
    policy: RecordingTrajectoryPolicy,
): RecordingPointSmoothingResult? {
    val context = recordingTrajectoryDetourContext(policy, options)
    for (interiorCount in 1..RECORDING_TRAJECTORY_MAX_DETOUR_INTERIOR_POINTS) {
        for (interiorPosition in 0 until interiorCount) {
            val startIndex = index - interiorPosition - 1
            val rejoinIndex = startIndex + interiorCount + 1
            if (startIndex < 0 || rejoinIndex + policy.minimumDirectionLegCount > sourcePoints.lastIndex) continue
            if (barriers.any { barrier -> barrier in startIndex..(rejoinIndex + policy.minimumDirectionLegCount) }) continue
            if (!recordingTrajectoryWindowFitsContext(sourcePoints, startIndex, rejoinIndex, context, options)) continue
            val incoming =
                recordingTrajectoryDirectionEvidence(
                    points = sourcePoints,
                    index = startIndex,
                    incoming = true,
                    options = options,
                    policy = policy,
                ) ?: continue
            val outgoing =
                recordingTrajectoryDirectionEvidence(
                    points = sourcePoints,
                    index = rejoinIndex,
                    incoming = false,
                    options = options,
                    policy = policy,
                ) ?: continue
            val start = sourcePoints[startIndex]
            val rejoin = sourcePoints[rejoinIndex]
            val rejoinLocal = rejoin.latLong.toLocalMeters(start.latLong)
            val directDistanceMeters = rejoinLocal.length()
            if (directDistanceMeters < policy.minimumTurnEvidenceMeters) continue
            if (
                angleDegrees(incoming.direction, rejoinLocal) > policy.detourMaximumRejoinHeadingDegrees ||
                angleDegrees(rejoinLocal, outgoing.direction) > policy.detourMaximumRejoinHeadingDegrees
            ) {
                continue
            }
            val polylineMeters =
                (startIndex until rejoinIndex).sumOf { pointIndex ->
                    haversineMeters(sourcePoints[pointIndex].latLong, sourcePoints[pointIndex + 1].latLong)
                }
            if (polylineMeters / directDistanceMeters < policy.detourMinimumRatio) continue
            val directSquared = rejoinLocal.x * rejoinLocal.x + rejoinLocal.y * rejoinLocal.y
            val largestInteriorLateralMeters =
                (startIndex + 1 until rejoinIndex).maxOf { interiorIndex ->
                    val interior = sourcePoints[interiorIndex].latLong.toLocalMeters(start.latLong)
                    val projection = (interior.x * rejoinLocal.x + interior.y * rejoinLocal.y) / directSquared
                    LocalMeters(
                        x = rejoinLocal.x * projection - interior.x,
                        y = rejoinLocal.y * projection - interior.y,
                    ).length()
                }
            if (largestInteriorLateralMeters < policy.detourMinimumLateralMeters) continue
            val candidate = sourcePoints[index]
            val candidateLocal = candidate.latLong.toLocalMeters(start.latLong)
            val projection = (candidateLocal.x * rejoinLocal.x + candidateLocal.y * rejoinLocal.y) / directSquared
            if (projection !in RECORDING_TRAJECTORY_DETOUR_MIN_PROJECTION..RECORDING_TRAJECTORY_DETOUR_MAX_PROJECTION) continue
            val correction =
                LocalMeters(
                    x = rejoinLocal.x * projection - candidateLocal.x,
                    y = rejoinLocal.y * projection - candidateLocal.y,
                )
            val correctionMeters = correction.length()
            if (correctionMeters < policy.detourMinimumLateralMeters) continue
            val pointAccuracy = candidate.accuracyMeters.validAccuracyOr(RECORDING_TRAJECTORY_FALLBACK_ACCURACY_M)
            val maximumAdjustment =
                (pointAccuracy * policy.adjustmentAccuracyCapFactor).coerceIn(
                    policy.minimumAdjustmentMeters,
                    if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG) {
                        policy.strongMaximumAdjustmentMeters
                    } else {
                        policy.adaptiveMaximumAdjustmentMeters
                    },
                )
            val requestedFraction =
                if (options.mode == SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG) {
                    policy.strongDetourFraction
                } else {
                    policy.adaptiveDetourFraction
                }
            val adjustmentMeters = min(correctionMeters * requestedFraction, maximumAdjustment)
            if (adjustmentMeters < policy.minimumAdjustmentMeters) continue
            val fraction = (adjustmentMeters / correctionMeters).coerceIn(0.0, 1.0)
            return RecordingPointSmoothingResult(
                point =
                    candidate.copy(
                        latLong =
                            LocalMeters(
                                x = candidateLocal.x + correction.x * fraction,
                                y = candidateLocal.y + correction.y * fraction,
                            ).toLatLong(start.latLong),
                    ),
                adjustmentMeters = adjustmentMeters,
            )
        }
    }
    return null
}

private fun recordingTrajectoryWindowFitsContext(
    points: List<RecordedTracePoint>,
    startIndex: Int,
    endIndex: Int,
    context: RecordingTrajectoryContext,
    options: RecordingPointSmoothingOptions,
): Boolean {
    if (endIndex - startIndex + 1 > context.maximumPointCount) return false
    if (points[endIndex].timeMillis - points[startIndex].timeMillis > context.maximumElapsedMillis) return false
    var travelledMeters = 0.0
    for (index in startIndex + 1..endIndex) {
        if (!recordingTrajectoryPointsAreContinuous(points[index - 1], points[index], options)) return false
        travelledMeters += haversineMeters(points[index - 1].latLong, points[index].latLong)
        if (travelledMeters > context.maximumTravelledMeters) return false
    }
    return true
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

internal data class LocalMeters(
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

internal fun LatLong.toLocalMeters(origin: LatLong): LocalMeters {
    val longitudeScale = cos(Math.toRadians((latitude + origin.latitude) / 2.0)).coerceAtLeast(0.01)
    return LocalMeters(
        x = Math.toRadians(longitude - origin.longitude) * EARTH_RADIUS_METERS * longitudeScale,
        y = Math.toRadians(latitude - origin.latitude) * EARTH_RADIUS_METERS,
    )
}

internal fun angleDegrees(
    first: LocalMeters,
    second: LocalMeters,
): Double {
    val denominator = first.length() * second.length()
    if (denominator <= 0.0) return 180.0
    val cosine = ((first.x * second.x + first.y * second.y) / denominator).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(cosine))
}

internal fun Float?.validAccuracyOr(fallbackMeters: Double): Double =
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
private const val RECORDING_TRAJECTORY_FALLBACK_ACCURACY_M = 8.0
private const val RECORDING_TRAJECTORY_MIN_WEIGHT_ACCURACY_M = 2.0
private const val RECORDING_TRAJECTORY_ACCURACY_PIVOT_M = 4.0
private const val RECORDING_TRAJECTORY_MIN_TIME_VARIANCE = 1e-6
private const val RECORDING_TRAJECTORY_MIN_FIT_POINT_COUNT = 4
private const val RECORDING_TRAJECTORY_MAX_DETOUR_INTERIOR_POINTS = 2
private const val RECORDING_TRAJECTORY_DETOUR_MIN_PROJECTION = 0.05
private const val RECORDING_TRAJECTORY_DETOUR_MAX_PROJECTION = 0.95
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
