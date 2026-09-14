package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GpxGuidanceSession

internal data class RouteProgressRingSegment(
    val startFraction: Float,
    val endFraction: Float,
    val color: Int,
)

internal val ROUTE_PROGRESS_RING_FALLBACK_GREEN = 0xFF34D399.toInt()

internal fun buildRouteProgressRingSegments(
    session: GpxGuidanceSession,
): List<RouteProgressRingSegment> =
    buildRouteProgressRingSegments(
        points = session.trackPoints,
        cumulativeDistancesMeters = session.cumulativeDistancesMeters,
        totalDistanceMeters = session.totalDistanceMeters,
        reversed = session.reversed,
    )

internal fun buildRouteProgressRingSegments(
    points: List<TrackPoint>,
    cumulativeDistancesMeters: List<Double>,
    totalDistanceMeters: Double,
    reversed: Boolean = false,
): List<RouteProgressRingSegment> {
    if (!hasValidRouteProgressRingInput(points, cumulativeDistancesMeters, totalDistanceMeters)) {
        return emptyList()
    }

    return (0 until points.lastIndex).mapNotNull { index ->
        val startDistance = cumulativeDistancesMeters[index]
        val endDistance = cumulativeDistancesMeters[index + 1]
        if (
            !startDistance.isFinite() ||
            !endDistance.isFinite() ||
            endDistance <= startDistance
        ) {
            return@mapNotNull null
        }

        val startFraction = (startDistance / totalDistanceMeters).coerceIn(0.0, 1.0).toFloat()
        val endFraction = (endDistance / totalDistanceMeters).coerceIn(0.0, 1.0).toFloat()
        if (endFraction <= startFraction) return@mapNotNull null

        val from = points[index]
        val to = points[index + 1]
        val crossesSegmentBoundary =
            if (reversed) {
                from.startsNewSegment
            } else {
                to.startsNewSegment
            }
        val color =
            if (crossesSegmentBoundary) {
                ROUTE_PROGRESS_RING_FALLBACK_GREEN
            } else {
                elevationSegmentColor(classifyElevationSegment(from, to))
            }
        RouteProgressRingSegment(
            startFraction = startFraction,
            endFraction = endFraction,
            color = color,
        )
    }
}

private fun hasValidRouteProgressRingInput(
    points: List<TrackPoint>,
    cumulativeDistancesMeters: List<Double>,
    totalDistanceMeters: Double,
): Boolean =
    when {
        points.size < 2 -> false
        cumulativeDistancesMeters.size != points.size -> false
        !totalDistanceMeters.isFinite() || totalDistanceMeters <= 0.0 -> false
        else -> points.all { it.elevation?.isFinite() == true }
    }

internal fun clipRouteProgressRingSegments(
    segments: List<RouteProgressRingSegment>,
    progress: Float,
): List<RouteProgressRingSegment> {
    val clampedProgress = progress.coerceIn(0f, 1f)
    return segments.mapNotNull { segment ->
        val startFraction = segment.startFraction.coerceIn(0f, 1f)
        val endFraction = minOf(segment.endFraction.coerceIn(0f, 1f), clampedProgress)
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
