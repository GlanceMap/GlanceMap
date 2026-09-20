package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GpxGuidanceSession
import kotlin.math.abs

internal data class RouteProgressRingSegment(
    val startFraction: Float,
    val endFraction: Float,
    val color: Int,
)

internal val ROUTE_PROGRESS_RING_FALLBACK_GREEN = 0xFF34D399.toInt()

private const val ROUTE_PROGRESS_RING_MIN_SECTION_METERS = 100.0
private const val ROUTE_PROGRESS_RING_NOISY_BLIP_METERS = 60.0
private const val ROUTE_PROGRESS_RING_STRONG_SHORT_SECTION_METERS = 45.0
private const val ROUTE_PROGRESS_RING_STRONG_SHORT_CHANGE_METERS = 3.0
private const val ROUTE_PROGRESS_RING_DISTANCE_EPSILON_METERS = 1e-6

private data class CanonicalRouteProgressRingSection(
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
    val color: Int,
    val barrierBefore: Boolean = false,
    val meaningfulShortTerrain: Boolean = false,
) {
    val distanceMeters: Double
        get() = endDistanceMeters - startDistanceMeters
}

private data class CanonicalRouteProgressRingInput(
    val points: List<TrackPoint>,
    val cumulativeDistancesMeters: List<Double>,
    val cumulativeAscentMeters: List<Double>,
    val cumulativeDescentMeters: List<Double>,
)

internal fun buildRouteProgressRingSegments(
    session: GpxGuidanceSession,
): List<RouteProgressRingSegment> =
    buildRouteProgressRingSegments(
        points = session.trackPoints,
        cumulativeDistancesMeters = session.cumulativeDistancesMeters,
        cumulativeAscentMeters = session.cumulativeAscentMeters,
        cumulativeDescentMeters = session.cumulativeDescentMeters,
        totalDistanceMeters = session.totalDistanceMeters,
    )

internal fun buildRouteProgressRingSegments(
    points: List<TrackPoint>,
    cumulativeDistancesMeters: List<Double>,
    totalDistanceMeters: Double,
    cumulativeAscentMeters: List<Double> = emptyList(),
    cumulativeDescentMeters: List<Double> = emptyList(),
): List<RouteProgressRingSegment> {
    if (
        !hasValidRouteProgressRingInput(
            points = points,
            cumulativeDistancesMeters = cumulativeDistancesMeters,
            cumulativeAscentMeters = cumulativeAscentMeters,
            cumulativeDescentMeters = cumulativeDescentMeters,
            totalDistanceMeters = totalDistanceMeters,
        )
    ) {
        return emptyList()
    }

    val canonicalSections =
        buildCanonicalRouteProgressRingSections(
            CanonicalRouteProgressRingInput(
                points = points,
                cumulativeDistancesMeters = cumulativeDistancesMeters,
                cumulativeAscentMeters = cumulativeAscentMeters,
                cumulativeDescentMeters = cumulativeDescentMeters,
            ),
        )

    return coalesceCanonicalRouteProgressRingSections(canonicalSections)
        .map { section ->
            RouteProgressRingSegment(
                startFraction = (section.startDistanceMeters / totalDistanceMeters).coerceIn(0.0, 1.0).toFloat(),
                endFraction = (section.endDistanceMeters / totalDistanceMeters).coerceIn(0.0, 1.0).toFloat(),
                color = section.color,
            )
        }.filter { it.endFraction > it.startFraction }
}

private fun buildCanonicalRouteProgressRingSections(
    input: CanonicalRouteProgressRingInput,
): List<CanonicalRouteProgressRingSection> {
    val sections = mutableListOf<CanonicalRouteProgressRingSection>()
    var barrierBeforeNextSection = false
    repeat(input.points.lastIndex) { index ->
        val startDistance = input.cumulativeDistancesMeters[index]
        val endDistance = input.cumulativeDistancesMeters[index + 1]
        if (startDistance.isFinite() && endDistance.isFinite()) {
            if (input.points[index + 1].startsNewSegment) {
                if (endDistance - startDistance > ROUTE_PROGRESS_RING_DISTANCE_EPSILON_METERS) {
                    sections +=
                        CanonicalRouteProgressRingSection(
                            startDistanceMeters = startDistance,
                            endDistanceMeters = endDistance,
                            color = ROUTE_PROGRESS_RING_FALLBACK_GREEN,
                            barrierBefore = true,
                        )
                }
                barrierBeforeNextSection = true
            } else if (endDistance - startDistance > ROUTE_PROGRESS_RING_DISTANCE_EPSILON_METERS) {
                sections +=
                    canonicalRouteProgressRingSection(
                        input = input,
                        index = index,
                        barrierBefore = barrierBeforeNextSection,
                    )
                barrierBeforeNextSection = false
            }
        }
    }
    return sections
}

private fun canonicalRouteProgressRingSection(
    input: CanonicalRouteProgressRingInput,
    index: Int,
    barrierBefore: Boolean,
): CanonicalRouteProgressRingSection {
    val startDistance = input.cumulativeDistancesMeters[index]
    val endDistance = input.cumulativeDistancesMeters[index + 1]
    val ascentChange =
        (input.cumulativeAscentMeters[index + 1] - input.cumulativeAscentMeters[index]).coerceAtLeast(0.0)
    val descentChange =
        (input.cumulativeDescentMeters[index + 1] - input.cumulativeDescentMeters[index]).coerceAtLeast(0.0)
    val intervalDistance = endDistance - startDistance
    val gradePercent = ((ascentChange - descentChange) / intervalDistance) * 100.0
    val type = classifyElevationGradePercent(gradePercent)
    return CanonicalRouteProgressRingSection(
        startDistanceMeters = startDistance,
        endDistanceMeters = endDistance,
        color = elevationSegmentColor(type),
        barrierBefore = barrierBefore,
        meaningfulShortTerrain =
            (type == GpxElevationSegmentType.CLIMB || type == GpxElevationSegmentType.DESCENT) &&
                intervalDistance <= ROUTE_PROGRESS_RING_STRONG_SHORT_SECTION_METERS &&
                abs(ascentChange - descentChange) >= ROUTE_PROGRESS_RING_STRONG_SHORT_CHANGE_METERS,
    )
}

private fun coalesceCanonicalRouteProgressRingSections(
    sections: List<CanonicalRouteProgressRingSection>,
): List<CanonicalRouteProgressRingSection> {
    val merged = mergeAdjacentCanonicalRouteProgressRingSections(sections)
    if (merged.size < 3) return merged

    val smoothed = merged.toMutableList()
    var index = 1
    while (index < smoothed.lastIndex) {
        val previous = smoothed[index - 1]
        val current = smoothed[index]
        val next = smoothed[index + 1]
        val changed =
            if (canSmoothCanonicalSection(previous, current, next)) {
                when {
                    current.distanceMeters <= ROUTE_PROGRESS_RING_NOISY_BLIP_METERS &&
                        previous.color == next.color -> {
                        smoothed[index - 1] =
                            previous.copy(
                                endDistanceMeters = next.endDistanceMeters,
                                meaningfulShortTerrain =
                                    previous.meaningfulShortTerrain || next.meaningfulShortTerrain,
                            )
                        smoothed.removeAt(index + 1)
                        smoothed.removeAt(index)
                        true
                    }

                    current.distanceMeters < ROUTE_PROGRESS_RING_MIN_SECTION_METERS -> {
                        mergeShortCanonicalSection(smoothed, index)
                        true
                    }

                    else -> false
                }
            } else {
                false
            }
        if (changed) {
            index = (index - 1).coerceAtLeast(1)
        } else {
            index++
        }
    }

    return mergeAdjacentCanonicalRouteProgressRingSections(smoothed)
}

private fun canSmoothCanonicalSection(
    previous: CanonicalRouteProgressRingSection,
    current: CanonicalRouteProgressRingSection,
    next: CanonicalRouteProgressRingSection,
): Boolean =
    !previous.barrierBefore &&
        !current.barrierBefore &&
        !next.barrierBefore &&
        !current.meaningfulShortTerrain

private fun mergeShortCanonicalSection(
    sections: MutableList<CanonicalRouteProgressRingSection>,
    index: Int,
) {
    val previous = sections[index - 1]
    val current = sections[index]
    val next = sections[index + 1]
    if (previous.distanceMeters >= next.distanceMeters) {
        sections[index - 1] =
            previous.copy(
                endDistanceMeters = current.endDistanceMeters,
                meaningfulShortTerrain = previous.meaningfulShortTerrain || current.meaningfulShortTerrain,
            )
    } else {
        sections[index + 1] =
            next.copy(
                startDistanceMeters = current.startDistanceMeters,
                barrierBefore = current.barrierBefore,
                meaningfulShortTerrain = next.meaningfulShortTerrain || current.meaningfulShortTerrain,
            )
    }
    sections.removeAt(index)
}

private fun mergeAdjacentCanonicalRouteProgressRingSections(
    sections: List<CanonicalRouteProgressRingSection>,
): List<CanonicalRouteProgressRingSection> =
    sections.fold(mutableListOf()) { merged, section ->
        val previous = merged.lastOrNull()
        if (previous != null && previous.color == section.color) {
            val contiguous =
                abs(previous.endDistanceMeters - section.startDistanceMeters) <=
                    ROUTE_PROGRESS_RING_DISTANCE_EPSILON_METERS
            if (!section.barrierBefore && contiguous) {
                merged[merged.lastIndex] =
                    previous.copy(
                        endDistanceMeters = section.endDistanceMeters,
                        meaningfulShortTerrain =
                            previous.meaningfulShortTerrain || section.meaningfulShortTerrain,
                    )
            } else {
                merged += section
            }
        } else {
            merged += section
        }
        merged
    }

private fun hasValidRouteProgressRingInput(
    points: List<TrackPoint>,
    cumulativeDistancesMeters: List<Double>,
    cumulativeAscentMeters: List<Double>,
    cumulativeDescentMeters: List<Double>,
    totalDistanceMeters: Double,
): Boolean =
    points.size >= 2 &&
        cumulativeDistancesMeters.size == points.size &&
        cumulativeAscentMeters.size == points.size &&
        cumulativeDescentMeters.size == points.size &&
        totalDistanceMeters.isFinite() &&
        totalDistanceMeters > 0.0 &&
        cumulativeDistancesMeters.all(Double::isFinite) &&
        cumulativeAscentMeters.all(Double::isFinite) &&
        cumulativeDescentMeters.all(Double::isFinite) &&
        cumulativeDistancesMeters.zipWithNext().all { (start, end) -> end >= start } &&
        cumulativeAscentMeters.zipWithNext().all { (start, end) -> end >= start } &&
        cumulativeDescentMeters.zipWithNext().all { (start, end) -> end >= start }

internal fun clipUpcomingRouteProgressRingSegments(
    segments: List<RouteProgressRingSegment>,
    progress: Float,
): List<RouteProgressRingSegment> {
    val clampedProgress = progress.coerceIn(0f, 1f)
    return segments.mapNotNull { segment ->
        val startFraction = maxOf(segment.startFraction.coerceIn(0f, 1f), clampedProgress)
        val endFraction = segment.endFraction.coerceIn(0f, 1f)
        if (endFraction <= startFraction) {
            null
        } else {
            segment.copy(
                startFraction = startFraction,
                endFraction = endFraction,
            )
        }
    }
}
