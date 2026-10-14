package com.glancemap.trailcore.recording

import kotlin.math.abs
import com.glancemap.trailcore.recording.RecordingElevationProfileDefaults as Defaults

data class RecordingElevationProfilePoint(
    val elevationMeters: Double?,
    val startsNewSegment: Boolean = false,
    val hasTimestamp: Boolean = true,
)

data class RecordingElevationProfileConfig(
    val smoothingDistanceMeters: Float = Defaults.DEFAULT_SMOOTHING_DISTANCE_METERS,
    val neutralDiffThresholdMeters: Float = Defaults.DEFAULT_NEUTRAL_DIFF_THRESHOLD_METERS,
    val trendActivationThresholdMeters: Float = Defaults.DEFAULT_TREND_ACTIVATION_THRESHOLD_METERS,
    val autoAdjustPerGpx: Boolean = true,
)

object RecordingElevationProfileDefaults {
    const val DEFAULT_SMOOTHING_DISTANCE_METERS = 20f
    const val DEFAULT_NEUTRAL_DIFF_THRESHOLD_METERS = 0.3f
    const val DEFAULT_TREND_ACTIVATION_THRESHOLD_METERS = 3f
    const val MIN_SMOOTHING_DISTANCE_METERS = 5f
    const val MIN_TREND_ACTIVATION_THRESHOLD_METERS = 0.5f
}

private data class EffectiveElevationFilter(
    val smoothingDistanceMeters: Double,
    val neutralDiffThresholdMeters: Double,
    val trendActivationThresholdMeters: Double,
    val minimumGradePercent: Double,
)

// Preserve the validated watch accumulation order and direction gates.
@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "NestedBlockDepth")
fun buildRecordingElevationProfileCumulative(
    points: List<RecordingElevationProfilePoint>,
    segmentLengths: DoubleArray,
    cumulativeDistances: DoubleArray,
    elevationFilterConfig: RecordingElevationProfileConfig = RecordingElevationProfileConfig(),
): Pair<DoubleArray, DoubleArray> {
    val count = points.size
    val cumAsc = DoubleArray(count)
    val cumDesc = DoubleArray(count)
    if (count <= 1) return cumAsc to cumDesc

    val normalizedElevations =
        normalizeElevations(
            points = points,
            cumulativeDistances = cumulativeDistances,
        ) ?: return cumAsc to cumDesc

    val effectiveFilter =
        resolveEffectiveElevationFilter(
            points = points,
            normalizedElevations = normalizedElevations,
            segmentLengths = segmentLengths,
            cumulativeDistances = cumulativeDistances,
            baseConfig = elevationFilterConfig,
        )

    val smoothedElevations =
        smoothElevationsByDistance(
            points = points,
            elevations = normalizedElevations,
            segmentLengths = segmentLengths,
            smoothingDistanceMeters = effectiveFilter.smoothingDistanceMeters,
        )

    val neutralThresholdMeters = effectiveFilter.neutralDiffThresholdMeters
    val trendActivationThresholdMeters = effectiveFilter.trendActivationThresholdMeters
    val directionActivationThresholdMeters =
        maxOf(neutralThresholdMeters, trendActivationThresholdMeters)

    var ascent = 0.0
    var descent = 0.0
    var pendingAscent = 0.0
    var pendingDescent = 0.0
    var ascentActive = false
    var descentActive = false

    for (index in 1 until count) {
        if (points[index].startsNewSegment) {
            pendingAscent = 0.0
            pendingDescent = 0.0
            ascentActive = false
            descentActive = false
            cumAsc[index] = ascent
            cumDesc[index] = descent
            continue
        }
        val stepMeters = segmentLengths.getOrElse(index - 1) { 0.0 }.coerceAtLeast(0.0)
        val diff =
            applyMinimumGradeGate(
                diffMeters = smoothedElevations[index] - smoothedElevations[index - 1],
                stepMeters = stepMeters,
                minimumGradePercent = effectiveFilter.minimumGradePercent,
            )
        when {
            diff > 0.0 -> {
                if (descentActive) {
                    pendingAscent += diff
                    if (pendingAscent >= directionActivationThresholdMeters) {
                        ascent += pendingAscent
                        pendingAscent = 0.0
                        pendingDescent = 0.0
                        descentActive = false
                        ascentActive = true
                    }
                } else {
                    val recoveredDescent = minOf(diff, pendingDescent)
                    pendingDescent -= recoveredDescent
                    val netRise = diff - recoveredDescent
                    if (ascentActive) {
                        ascent += netRise
                    } else {
                        pendingAscent += netRise
                        if (pendingAscent >= directionActivationThresholdMeters) {
                            ascent += pendingAscent
                            pendingAscent = 0.0
                            ascentActive = true
                        }
                    }
                }
            }

            diff < 0.0 -> {
                val loss = -diff
                if (ascentActive) {
                    pendingDescent += loss
                    if (pendingDescent >= directionActivationThresholdMeters) {
                        descent += pendingDescent
                        pendingAscent = 0.0
                        pendingDescent = 0.0
                        ascentActive = false
                        descentActive = true
                    }
                } else {
                    val recoveredAscent = minOf(loss, pendingAscent)
                    pendingAscent -= recoveredAscent
                    val netLoss = loss - recoveredAscent
                    if (descentActive) {
                        descent += netLoss
                    } else {
                        pendingDescent += netLoss
                        if (pendingDescent >= directionActivationThresholdMeters) {
                            descent += pendingDescent
                            pendingDescent = 0.0
                            descentActive = true
                        }
                    }
                }
            }
        }

        cumAsc[index] = ascent
        cumDesc[index] = descent
    }

    return cumAsc to cumDesc
}

@Suppress("LongMethod")
private fun resolveEffectiveElevationFilter(
    points: List<RecordingElevationProfilePoint>,
    normalizedElevations: DoubleArray,
    segmentLengths: DoubleArray,
    cumulativeDistances: DoubleArray,
    baseConfig: RecordingElevationProfileConfig,
): EffectiveElevationFilter {
    if (!baseConfig.autoAdjustPerGpx) {
        return EffectiveElevationFilter(
            smoothingDistanceMeters = baseConfig.smoothingDistanceMeters.toDouble(),
            neutralDiffThresholdMeters = baseConfig.neutralDiffThresholdMeters.toDouble(),
            trendActivationThresholdMeters = baseConfig.trendActivationThresholdMeters.toDouble(),
            minimumGradePercent = 0.0,
        )
    }
    val totalDistanceMeters = cumulativeDistances.lastOrNull() ?: 0.0
    val totalDistanceKm = totalDistanceMeters / 1000.0
    val reliefMeters =
        (
            (normalizedElevations.maxOrNull() ?: 0.0) -
                (normalizedElevations.minOrNull() ?: 0.0)
        ).coerceAtLeast(0.0)
    val reliefPerKm = if (totalDistanceKm > 0.0) reliefMeters / totalDistanceKm else 0.0
    val coarseHighReliefFactor = resolveCoarseHighReliefFactor(points, reliefPerKm)
    val lowReliefPerKmFactor =
        (
            (LOW_RELIEF_REFERENCE_METERS_PER_KM - reliefPerKm) / LOW_RELIEF_BLEND_RANGE_METERS_PER_KM
        ).coerceIn(0.0, 1.0)
    // Avoid classifying very long mountain traverses as "low relief" just because the route is long.
    val lowReliefFactor = lowReliefPerKmFactor * resolveLowReliefAbsoluteReliefFactor(reliefMeters)
    val recordedLowReliefFactor = resolveRecordedLowReliefFactor(points, lowReliefFactor)
    val editedLowReliefDenseTrackFactor = resolveEditedLowReliefDensityFactor(segmentLengths)
    val editedLowReliefDensityFactor =
        if (recordedLowReliefFactor > 0.0) {
            0.0
        } else {
            lowReliefFactor * editedLowReliefDenseTrackFactor
        }

    return EffectiveElevationFilter(
        smoothingDistanceMeters =
            (
                baseConfig.smoothingDistanceMeters.toDouble() -
                    (editedLowReliefDensityFactor * LOW_RELIEF_SMOOTHING_REDUCTION_METERS) -
                    (recordedLowReliefFactor * RECORDED_LOW_RELIEF_SMOOTHING_REDUCTION_METERS) +
                    (coarseHighReliefFactor * COARSE_HIGH_RELIEF_SMOOTHING_BOOST_METERS)
            ).coerceAtLeast(Defaults.MIN_SMOOTHING_DISTANCE_METERS.toDouble()),
        neutralDiffThresholdMeters = baseConfig.neutralDiffThresholdMeters.toDouble(),
        trendActivationThresholdMeters =
            (
                baseConfig.trendActivationThresholdMeters.toDouble() -
                    (editedLowReliefDensityFactor * LOW_RELIEF_TREND_REDUCTION_METERS) -
                    (recordedLowReliefFactor * RECORDED_LOW_RELIEF_TREND_REDUCTION_METERS) +
                    (coarseHighReliefFactor * COARSE_HIGH_RELIEF_TREND_BOOST_METERS)
            ).coerceAtLeast(
                Defaults.MIN_TREND_ACTIVATION_THRESHOLD_METERS.toDouble(),
            ),
        minimumGradePercent =
            if (recordedLowReliefFactor > 0.0) {
                0.0
            } else {
                lowReliefFactor * (
                    LOW_RELIEF_SPARSE_MIN_GRADE_PERCENT +
                        (
                            editedLowReliefDenseTrackFactor *
                                LOW_RELIEF_DENSE_MIN_GRADE_BOOST_PERCENT
                        )
                )
            },
    )
}

private fun resolveRecordedLowReliefFactor(
    points: List<RecordingElevationProfilePoint>,
    lowReliefFactor: Double,
): Double {
    val timestampFraction =
        points.count(RecordingElevationProfilePoint::hasTimestamp).toDouble() /
            points.size.coerceAtLeast(1).toDouble()
    return if (timestampFraction >= RECORDED_LOW_RELIEF_MIN_TIMESTAMP_FRACTION) {
        lowReliefFactor
    } else {
        0.0
    }
}

private fun resolveLowReliefAbsoluteReliefFactor(reliefMeters: Double): Double =
    (
        (LOW_RELIEF_ZERO_ABSOLUTE_RELIEF_METERS - reliefMeters) /
            (LOW_RELIEF_ZERO_ABSOLUTE_RELIEF_METERS - LOW_RELIEF_FULL_ABSOLUTE_RELIEF_METERS)
    ).coerceIn(0.0, 1.0)

private fun resolveEditedLowReliefDensityFactor(segmentLengths: DoubleArray): Double {
    if (segmentLengths.isEmpty()) return 0.0
    val sorted = segmentLengths.copyOf().apply { sort() }
    val middleIndex = sorted.size / 2
    val medianSegmentMeters =
        if (sorted.size % 2 == 0) {
            (sorted[middleIndex - 1] + sorted[middleIndex]) / 2.0
        } else {
            sorted[middleIndex]
        }
    return (
        (LOW_RELIEF_SPARSE_TRACK_MEDIAN_SEGMENT_METERS - medianSegmentMeters) /
            (LOW_RELIEF_SPARSE_TRACK_MEDIAN_SEGMENT_METERS - LOW_RELIEF_DENSE_TRACK_MEDIAN_SEGMENT_METERS)
    ).coerceIn(0.0, 1.0)
}

@Suppress("ReturnCount") // Preserve the watch's early exits for sparse elevation data.
private fun resolveCoarseHighReliefFactor(
    points: List<RecordingElevationProfilePoint>,
    reliefPerKm: Double,
): Double {
    val knownElevations = points.mapNotNull(RecordingElevationProfilePoint::elevationMeters)
    if (knownElevations.isEmpty()) return 0.0
    val integerLikeFraction =
        knownElevations
            .count { elevation ->
                abs(elevation - kotlin.math.round(elevation)) <= COARSE_ELEVATION_INTEGER_TOLERANCE_METERS
            }.toDouble() / knownElevations.size.toDouble()
    if (integerLikeFraction < COARSE_ELEVATION_MIN_INTEGER_FRACTION) return 0.0
    return (
        (reliefPerKm - COARSE_HIGH_RELIEF_REFERENCE_METERS_PER_KM) /
            COARSE_HIGH_RELIEF_BLEND_RANGE_METERS_PER_KM
    ).coerceIn(0.0, 1.0)
}

private fun applyMinimumGradeGate(
    diffMeters: Double,
    stepMeters: Double,
    minimumGradePercent: Double,
): Double {
    if (diffMeters == 0.0 || stepMeters <= 0.0 || minimumGradePercent <= 0.0) {
        return diffMeters
    }
    val gradePercent = (abs(diffMeters) / stepMeters) * 100.0
    return if (gradePercent < minimumGradePercent) 0.0 else diffMeters
}

@Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
private fun normalizeElevations(
    points: List<RecordingElevationProfilePoint>,
    cumulativeDistances: DoubleArray,
): DoubleArray? {
    val knownIndices = points.indices.filter { points[it].elevationMeters != null }
    if (knownIndices.isEmpty()) return null

    val normalized = DoubleArray(points.size)
    val firstKnownIndex = knownIndices.first()
    val firstKnownElevation = points[firstKnownIndex].elevationMeters ?: return null
    for (index in 0..firstKnownIndex) {
        normalized[index] = firstKnownElevation
    }

    for (knownIndexPosition in 0 until knownIndices.lastIndex) {
        val startIndex = knownIndices[knownIndexPosition]
        val endIndex = knownIndices[knownIndexPosition + 1]
        val startElevation = points[startIndex].elevationMeters ?: continue
        val endElevation = points[endIndex].elevationMeters ?: continue
        val startDistance = cumulativeDistances.getOrElse(startIndex) { 0.0 }
        val endDistance = cumulativeDistances.getOrElse(endIndex) { startDistance }

        normalized[startIndex] = startElevation
        for (index in startIndex + 1 until endIndex) {
            val currentDistance = cumulativeDistances.getOrElse(index) { startDistance }
            val t =
                if (endDistance > startDistance) {
                    ((currentDistance - startDistance) / (endDistance - startDistance)).coerceIn(0.0, 1.0)
                } else {
                    (index - startIndex).toDouble() / (endIndex - startIndex).toDouble()
                }
            normalized[index] = startElevation + t * (endElevation - startElevation)
        }
        normalized[endIndex] = endElevation
    }

    val lastKnownIndex = knownIndices.last()
    val lastKnownElevation = points[lastKnownIndex].elevationMeters ?: return normalized
    for (index in lastKnownIndex until points.size) {
        normalized[index] = lastKnownElevation
    }

    return normalized
}

private fun smoothElevationsByDistance(
    points: List<RecordingElevationProfilePoint>,
    elevations: DoubleArray,
    segmentLengths: DoubleArray,
    smoothingDistanceMeters: Double,
): DoubleArray {
    if (elevations.isEmpty()) return elevations
    val smoothed = DoubleArray(elevations.size)
    smoothed[0] = elevations[0]
    for (index in 1 until elevations.size) {
        if (points.getOrNull(index)?.startsNewSegment == true) {
            smoothed[index] = elevations[index]
            continue
        }
        val stepMeters = segmentLengths.getOrElse(index - 1) { 0.0 }.coerceAtLeast(0.0)
        val alpha =
            (stepMeters / (smoothingDistanceMeters + stepMeters))
                .coerceIn(MIN_ELEVATION_SMOOTHING_ALPHA, 1.0)
        smoothed[index] = smoothed[index - 1] + alpha * (elevations[index] - smoothed[index - 1])
    }
    return smoothed
}

private const val MIN_ELEVATION_SMOOTHING_ALPHA = 0.18
private const val LOW_RELIEF_REFERENCE_METERS_PER_KM = 10.0
private const val LOW_RELIEF_BLEND_RANGE_METERS_PER_KM = 4.0
private const val LOW_RELIEF_FULL_ABSOLUTE_RELIEF_METERS = 150.0
private const val LOW_RELIEF_ZERO_ABSOLUTE_RELIEF_METERS = 400.0
private const val LOW_RELIEF_SMOOTHING_REDUCTION_METERS = 1.0
private const val LOW_RELIEF_TREND_REDUCTION_METERS = 0.5
private const val LOW_RELIEF_SPARSE_MIN_GRADE_PERCENT = 1.4
private const val LOW_RELIEF_DENSE_MIN_GRADE_BOOST_PERCENT = 0.8
private const val LOW_RELIEF_DENSE_TRACK_MEDIAN_SEGMENT_METERS = 40.0
private const val LOW_RELIEF_SPARSE_TRACK_MEDIAN_SEGMENT_METERS = 80.0
private const val RECORDED_LOW_RELIEF_MIN_TIMESTAMP_FRACTION = 0.95
private const val RECORDED_LOW_RELIEF_SMOOTHING_REDUCTION_METERS = 1.0
private const val RECORDED_LOW_RELIEF_TREND_REDUCTION_METERS = 2.5
private const val COARSE_ELEVATION_INTEGER_TOLERANCE_METERS = 0.01
private const val COARSE_ELEVATION_MIN_INTEGER_FRACTION = 0.95
private const val COARSE_HIGH_RELIEF_REFERENCE_METERS_PER_KM = 35.0
private const val COARSE_HIGH_RELIEF_BLEND_RANGE_METERS_PER_KM = 15.0
private const val COARSE_HIGH_RELIEF_SMOOTHING_BOOST_METERS = 5.0
private const val COARSE_HIGH_RELIEF_TREND_BOOST_METERS = 0.2
