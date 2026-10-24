@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
    "ReturnCount",
    "TooManyFunctions",
)

package com.glancemap.glancemapwearos.presentation.features.routetools

import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.routing.RouteGeometryPoint
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxTrackDetails
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPosition
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackProfile
import com.glancemap.glancemapwearos.presentation.features.gpx.findClosestTrackPosition
import com.glancemap.glancemapwearos.presentation.features.gpx.totalAscent
import com.glancemap.glancemapwearos.presentation.features.gpx.totalDistance
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.util.MercatorProjection

private data class RouteRejoinCandidate(
    val position: TrackPosition,
    val distanceMeters: Double,
)

private data class RouteReshapeWindow(
    val startPosition: TrackPosition,
    val defaultEndPosition: TrackPosition,
    val anchorDistance: Double,
    val halfWindow: Double,
)

private const val ROUTE_RESHAPE_SNAP_THRESHOLD_METERS = 250.0
private const val ROUTE_RESHAPE_MAX_HANDLES = 9
private const val ROUTE_RESHAPE_MIN_HALF_WINDOW_METERS = 120.0
private const val ROUTE_RESHAPE_MAX_HALF_WINDOW_METERS = 400.0
private const val ROUTE_RESHAPE_MIN_FORWARD_REJOIN_METERS = 60.0
private const val ROUTE_RESHAPE_MAX_FORWARD_REJOIN_METERS = 1_200.0
private const val ROUTE_RESHAPE_REJOIN_DISTANCE_MARGIN_METERS = 20.0
private const val ROUTE_RESHAPE_REJOIN_CANDIDATE_STEP_METERS = 120.0
private const val ROUTE_RESHAPE_MAX_REJOIN_CANDIDATES = 6
private const val ROUTE_RESHAPE_REJOIN_OVERLAP_THRESHOLD_METERS = 18.0
private const val ROUTE_RESHAPE_REJOIN_ORIGIN_IGNORE_METERS = 30.0
private const val ROUTE_RESHAPE_REJOIN_BACKWARD_TOLERANCE_METERS = 20.0
private const val ROUTE_RESHAPE_MIN_BACKWARD_OVERLAP_METERS = 30.0
private const val ROUTE_RESHAPE_MIN_REVERSE_REJOIN_TRAVEL_METERS = 24.0
private const val ROUTE_RESHAPE_MIN_MEANINGFUL_FORWARD_GAIN_METERS = 120.0
private const val ROUTE_RESHAPE_FORWARD_GAIN_OFFSET_FACTOR = 1.6
private const val ROUTE_RESHAPE_MAX_REJOIN_OVERHEAD_METERS = 70.0
private const val ROUTE_RESHAPE_MIN_REJOIN_PROGRESS_RATIO = 0.5
private const val ROUTE_RESHAPE_MAX_REJOIN_DIRECTNESS_OVERHEAD_METERS = 120.0
private const val ROUTE_RESHAPE_MAX_REJOIN_DIRECTNESS_RATIO = 2.0
private const val ROUTE_RESHAPE_MAX_TOTAL_OVERHEAD_METERS = 120.0
private const val ROUTE_RESHAPE_MIN_TOTAL_REPLACEMENT_RATIO = 0.55

internal fun buildRouteReshapeHandles(points: List<LatLong>): List<RouteReshapeHandle> {
    if (points.isEmpty()) return emptyList()
    val lastIndex = points.lastIndex
    return buildRouteReshapeHandleIndices(points.size).map { pointIndex ->
        RouteReshapeHandle(
            pointIndex = pointIndex,
            latLong = points[pointIndex],
            isEndpoint = pointIndex == 0 || pointIndex == lastIndex,
        )
    }
}

internal fun resolveRouteReshapeWaypoint(
    sourcePath: String,
    sourceTitle: String?,
    profile: TrackProfile,
    anchor: LatLong,
    rejoinHint: LatLong? = null,
    direction: RouteReshapeDirection,
): LatLong {
    val bounds =
        resolveRouteReshapeBounds(
            sourcePath = sourcePath,
            sourceTitle = sourceTitle,
            profile = profile,
            anchor = anchor,
            rejoinHint = rejoinHint,
        )
    return when (direction) {
        RouteReshapeDirection.START -> bounds.startPoint.latLong
        RouteReshapeDirection.END -> bounds.endPoint.latLong
    }
}

internal fun resolveRouteReshapeCandidateBounds(
    sourcePath: String,
    sourceTitle: String?,
    profile: TrackProfile,
    anchor: LatLong,
    rejoinHint: LatLong? = null,
): List<RouteReshapeBounds> {
    val window =
        resolveRouteReshapeWindow(
            sourcePath = sourcePath,
            sourceTitle = sourceTitle,
            profile = profile,
            anchor = anchor,
        )
    val preferredEndPosition =
        resolvePreferredRejoinPosition(
            profile = profile,
            anchorDistance = window.anchorDistance,
            halfWindow = window.halfWindow,
            defaultEndPosition = window.defaultEndPosition,
            rejoinHint = rejoinHint,
        )
    val maxForwardDistance =
        (window.anchorDistance + reshapeMaxForwardRejoinMeters(window.halfWindow))
            .coerceAtMost(profile.totalDistance)
    val preferredEndDistance = trackDistanceAt(profile, preferredEndPosition)
    val defaultEndDistance = trackDistanceAt(profile, window.defaultEndPosition)
    val candidateStep = ROUTE_RESHAPE_REJOIN_CANDIDATE_STEP_METERS

    val endPositions = ArrayList<TrackPosition>(ROUTE_RESHAPE_MAX_REJOIN_CANDIDATES)

    fun addEndPosition(position: TrackPosition) {
        if (endPositions.any { comparePositions(it, position) == 0 }) return
        require(comparePositions(window.startPosition, position) < 0) {
            throw RouteToolArgumentException(
                R.string.gpx_pick_a_point_farther_from_the_route_end_to_reshape_it,
                "Pick a point farther from the route end to reshape it.",
            )
        }
        endPositions += position
    }

    addEndPosition(preferredEndPosition)
    if (
        endPositions.size < ROUTE_RESHAPE_MAX_REJOIN_CANDIDATES &&
        comparePositions(window.defaultEndPosition, preferredEndPosition) > 0
    ) {
        addEndPosition(window.defaultEndPosition)
    }

    var nextDistance =
        maxOf(
            preferredEndDistance + candidateStep,
            defaultEndDistance + candidateStep * 0.5,
        )
    while (
        endPositions.size < ROUTE_RESHAPE_MAX_REJOIN_CANDIDATES - 1 &&
        nextDistance < maxForwardDistance - POSITION_EPSILON
    ) {
        addEndPosition(positionAtDistance(profile, nextDistance))
        nextDistance += candidateStep
    }
    if (endPositions.size < ROUTE_RESHAPE_MAX_REJOIN_CANDIDATES) {
        addEndPosition(positionAtDistance(profile, maxForwardDistance))
    }

    return endPositions.map { endPosition ->
        RouteReshapeBounds(
            startPosition = window.startPosition,
            endPosition = endPosition,
            startPoint = pointAt(profile.points, window.startPosition),
            endPoint = pointAt(profile.points, endPosition),
        )
    }
}

internal fun secondLegRejoinsOriginalPathForward(
    sourcePath: String,
    sourceTitle: String?,
    profile: TrackProfile,
    anchor: LatLong,
    routedPoints: List<RouteGeometryPoint>,
): Boolean {
    if (profile.points.size <= 1 || routedPoints.size <= 2) return true

    val sourceTrack =
        GpxTrackDetails(
            id = sourcePath,
            points = profile.points.map { it.latLong },
            title = sourceTitle,
            distance = profile.totalDistance,
            elevationGain = profile.totalAscent,
            startPoint = profile.points.firstOrNull()?.latLong,
            endPoint = profile.points.lastOrNull()?.latLong,
        )
    val anchorPosition =
        snapToTrackPosition(
            target = anchor,
            sourceTrack = sourceTrack,
            profile = profile,
            thresholdMeters = ROUTE_RESHAPE_SNAP_THRESHOLD_METERS,
            failureMessage = "Move closer to the active GPX to choose where it should bend.",
            failureMessageRes = R.string.gpx_move_closer_to_the_active_gpx_to_choose_where_it_should_bend,
        )
    val minimumAllowedTrackDistance =
        trackDistanceAt(profile, anchorPosition) -
            ROUTE_RESHAPE_REJOIN_BACKWARD_TOLERANCE_METERS

    var routedDistance = 0.0
    var backwardOverlapDistance = 0.0
    var previousBackwardOverlap = false
    var reverseRejoinDistance = 0.0
    var previousNearTrack = false
    var previousTrackDistance: Double? = null
    var previousPoint = routedPoints.first().latLong

    for (index in 1 until routedPoints.size) {
        val currentPoint = routedPoints[index].latLong
        val segmentDistance = haversineMeters(previousPoint, currentPoint)
        routedDistance += segmentDistance

        val matchedTrackDistance =
            if (routedDistance < ROUTE_RESHAPE_REJOIN_ORIGIN_IGNORE_METERS) {
                null
            } else {
                val pick =
                    findClosestTrackPosition(
                        press = currentPoint,
                        tracks = listOf(sourceTrack),
                        profileProvider = { profile },
                        allowedTrackId = sourceTrack.id,
                    )
                pick
                    ?.takeIf { it.distanceToLineMeters <= ROUTE_RESHAPE_REJOIN_OVERLAP_THRESHOLD_METERS }
                    ?.let { trackDistanceAt(profile, it.pos) }
            }
        val backwardOverlap =
            matchedTrackDistance != null &&
                matchedTrackDistance < minimumAllowedTrackDistance

        backwardOverlapDistance =
            when {
                backwardOverlap && previousBackwardOverlap -> backwardOverlapDistance + segmentDistance
                backwardOverlap -> segmentDistance
                else -> 0.0
            }
        if (backwardOverlapDistance >= ROUTE_RESHAPE_MIN_BACKWARD_OVERLAP_METERS) {
            return false
        }

        val reverseRejoin =
            previousNearTrack &&
                matchedTrackDistance != null &&
                previousTrackDistance != null &&
                matchedTrackDistance + ROUTE_RESHAPE_REJOIN_BACKWARD_TOLERANCE_METERS <
                previousTrackDistance

        reverseRejoinDistance =
            when {
                reverseRejoin -> reverseRejoinDistance + segmentDistance
                else -> 0.0
            }
        if (reverseRejoinDistance >= ROUTE_RESHAPE_MIN_REVERSE_REJOIN_TRAVEL_METERS) {
            return false
        }

        previousBackwardOverlap = backwardOverlap
        previousNearTrack = matchedTrackDistance != null
        previousTrackDistance = matchedTrackDistance
        previousPoint = currentPoint
    }

    return true
}

internal fun reshapeCandidateMatchesUserIntent(
    sourcePath: String,
    sourceTitle: String?,
    profile: TrackProfile,
    anchor: LatLong,
    shapingPoint: LatLong,
    bounds: RouteReshapeBounds,
    firstLegPoints: List<RouteGeometryPoint>,
    secondLegPoints: List<RouteGeometryPoint>,
): Boolean {
    if (
        !secondLegRejoinsOriginalPathForward(
            sourcePath = sourcePath,
            sourceTitle = sourceTitle,
            profile = profile,
            anchor = anchor,
            routedPoints = secondLegPoints,
        )
    ) {
        return false
    }

    val anchorDistance =
        resolveRouteReshapeAnchorDistance(
            sourcePath = sourcePath,
            sourceTitle = sourceTitle,
            profile = profile,
            anchor = anchor,
        )
    val forwardGain = trackDistanceAt(profile, bounds.endPosition) - anchorDistance
    val minimumForwardGain =
        maxOf(
            ROUTE_RESHAPE_MIN_MEANINGFUL_FORWARD_GAIN_METERS,
            haversineMeters(anchor, shapingPoint) * ROUTE_RESHAPE_FORWARD_GAIN_OFFSET_FACTOR,
        )
    if (forwardGain < minimumForwardGain) {
        return false
    }

    val replacedDistance =
        trackDistanceAt(profile, bounds.endPosition) -
            trackDistanceAt(profile, bounds.startPosition)
    val firstLegDistance = routeGeometryDistanceMeters(firstLegPoints)
    val secondLegDistance = routeGeometryDistanceMeters(secondLegPoints)
    val totalRerouteDistance = firstLegDistance + secondLegDistance
    val rejoinOverhead = secondLegDistance - forwardGain
    val rejoinProgressRatio =
        if (secondLegDistance <= POSITION_EPSILON) {
            1.0
        } else {
            forwardGain / secondLegDistance
        }
    if (
        rejoinOverhead > ROUTE_RESHAPE_MAX_REJOIN_OVERHEAD_METERS &&
        rejoinProgressRatio < ROUTE_RESHAPE_MIN_REJOIN_PROGRESS_RATIO
    ) {
        return false
    }

    val directRejoinDistance = haversineMeters(shapingPoint, bounds.endPoint.latLong)
    val rejoinDirectnessRatio =
        if (directRejoinDistance <= POSITION_EPSILON) {
            1.0
        } else {
            secondLegDistance / directRejoinDistance
        }
    val rejoinDirectnessOverhead = secondLegDistance - directRejoinDistance
    if (
        rejoinDirectnessOverhead > ROUTE_RESHAPE_MAX_REJOIN_DIRECTNESS_OVERHEAD_METERS &&
        rejoinDirectnessRatio > ROUTE_RESHAPE_MAX_REJOIN_DIRECTNESS_RATIO
    ) {
        return false
    }

    val totalOverhead = totalRerouteDistance - replacedDistance
    val replacementRatio =
        if (totalRerouteDistance <= POSITION_EPSILON) {
            1.0
        } else {
            replacedDistance / totalRerouteDistance
        }
    if (
        totalOverhead > ROUTE_RESHAPE_MAX_TOTAL_OVERHEAD_METERS &&
        replacementRatio < ROUTE_RESHAPE_MIN_TOTAL_REPLACEMENT_RATIO
    ) {
        return false
    }

    return true
}

internal fun resolveRouteReshapeBounds(
    sourcePath: String,
    sourceTitle: String?,
    profile: TrackProfile,
    anchor: LatLong,
    rejoinHint: LatLong? = null,
): RouteReshapeBounds {
    val window =
        resolveRouteReshapeWindow(
            sourcePath = sourcePath,
            sourceTitle = sourceTitle,
            profile = profile,
            anchor = anchor,
        )
    val endPosition =
        resolvePreferredRejoinPosition(
            profile = profile,
            anchorDistance = window.anchorDistance,
            halfWindow = window.halfWindow,
            defaultEndPosition = window.defaultEndPosition,
            rejoinHint = rejoinHint,
        )
    require(comparePositions(window.startPosition, endPosition) < 0) {
        throw RouteToolArgumentException(
            R.string.gpx_pick_a_point_farther_from_the_route_end_to_reshape_it,
            "Pick a point farther from the route end to reshape it.",
        )
    }

    return RouteReshapeBounds(
        startPosition = window.startPosition,
        endPosition = endPosition,
        startPoint = pointAt(profile.points, window.startPosition),
        endPoint = pointAt(profile.points, endPosition),
    )
}

private fun reshapeHalfWindowMeters(profile: TrackProfile): Double =
    (profile.totalDistance * 0.03)
        .coerceIn(
            minimumValue = ROUTE_RESHAPE_MIN_HALF_WINDOW_METERS,
            maximumValue = ROUTE_RESHAPE_MAX_HALF_WINDOW_METERS,
        )

private fun resolveRouteReshapeWindow(
    sourcePath: String,
    sourceTitle: String?,
    profile: TrackProfile,
    anchor: LatLong,
): RouteReshapeWindow {
    val sourceTrack =
        GpxTrackDetails(
            id = sourcePath,
            points = profile.points.map { it.latLong },
            title = sourceTitle,
            distance = profile.totalDistance,
            elevationGain = profile.totalAscent,
            startPoint = profile.points.firstOrNull()?.latLong,
            endPoint = profile.points.lastOrNull()?.latLong,
        )
    val anchorPosition =
        snapToTrackPosition(
            target = anchor,
            sourceTrack = sourceTrack,
            profile = profile,
            thresholdMeters = ROUTE_RESHAPE_SNAP_THRESHOLD_METERS,
            failureMessage = "Move closer to the active GPX to choose where it should bend.",
            failureMessageRes = R.string.gpx_move_closer_to_the_active_gpx_to_choose_where_it_should_bend,
        )
    val anchorDistance = trackDistanceAt(profile, anchorPosition)
    val halfWindow = reshapeHalfWindowMeters(profile)
    var startDistance = anchorDistance - halfWindow
    var endDistance = anchorDistance + halfWindow
    if (startDistance < 0.0) {
        endDistance = (endDistance - startDistance).coerceAtMost(profile.totalDistance)
        startDistance = 0.0
    }
    if (endDistance > profile.totalDistance) {
        val overflow = endDistance - profile.totalDistance
        startDistance = (startDistance - overflow).coerceAtLeast(0.0)
        endDistance = profile.totalDistance
    }

    return RouteReshapeWindow(
        startPosition = positionAtDistance(profile, startDistance),
        defaultEndPosition = positionAtDistance(profile, endDistance),
        anchorDistance = anchorDistance,
        halfWindow = halfWindow,
    )
}

private fun resolveRouteReshapeAnchorDistance(
    sourcePath: String,
    sourceTitle: String?,
    profile: TrackProfile,
    anchor: LatLong,
): Double {
    val window =
        resolveRouteReshapeWindow(
            sourcePath = sourcePath,
            sourceTitle = sourceTitle,
            profile = profile,
            anchor = anchor,
        )
    return window.anchorDistance
}

private fun resolvePreferredRejoinPosition(
    profile: TrackProfile,
    anchorDistance: Double,
    halfWindow: Double,
    defaultEndPosition: TrackPosition,
    rejoinHint: LatLong?,
): TrackPosition {
    val hint = rejoinHint ?: return defaultEndPosition
    val minForwardDistance = reshapeMinForwardRejoinMeters(halfWindow)
    val maxForwardDistance = reshapeMaxForwardRejoinMeters(halfWindow)
    val searchStart =
        positionAtDistance(
            profile = profile,
            distanceMeters = (anchorDistance + minForwardDistance).coerceAtMost(profile.totalDistance),
        )
    val searchEnd =
        positionAtDistance(
            profile = profile,
            distanceMeters = (anchorDistance + maxForwardDistance).coerceAtMost(profile.totalDistance),
        )
    if (comparePositions(searchStart, searchEnd) >= 0) {
        return defaultEndPosition
    }

    return findEarliestForwardRejoinPosition(
        profile = profile,
        hint = hint,
        minPosition = searchStart,
        maxPosition = searchEnd,
    ) ?: defaultEndPosition
}

private fun reshapeMinForwardRejoinMeters(halfWindow: Double): Double =
    maxOf(
        ROUTE_RESHAPE_MIN_FORWARD_REJOIN_METERS,
        halfWindow * 0.5,
    )

private fun reshapeMaxForwardRejoinMeters(halfWindow: Double): Double =
    (halfWindow * 6.0).coerceIn(
        minimumValue = reshapeMinForwardRejoinMeters(halfWindow),
        maximumValue = ROUTE_RESHAPE_MAX_FORWARD_REJOIN_METERS,
    )

private fun findEarliestForwardRejoinPosition(
    profile: TrackProfile,
    hint: LatLong,
    minPosition: TrackPosition,
    maxPosition: TrackPosition,
): TrackPosition? {
    if (profile.points.size <= 1) return null

    val zoom: Byte = 20
    val tileSize = 256
    val mapSize: Long = MercatorProjection.getMapSize(zoom, tileSize)

    fun toXY(latLong: LatLong): Pair<Double, Double> {
        val x = MercatorProjection.longitudeToPixelX(latLong.longitude, mapSize)
        val y = MercatorProjection.latitudeToPixelY(latLong.latitude, mapSize)
        return x to y
    }

    fun toLatLong(
        x: Double,
        y: Double,
    ): LatLong {
        val lon = MercatorProjection.pixelXToLongitude(x, mapSize)
        val lat = MercatorProjection.pixelYToLatitude(y, mapSize)
        return LatLong(lat, lon)
    }

    val (hintX, hintY) = toXY(hint)
    val candidates = ArrayList<RouteRejoinCandidate>()
    val startIndex = minPosition.segmentIndex.coerceIn(0, profile.points.lastIndex - 1)
    val endIndex = maxPosition.segmentIndex.coerceIn(0, profile.points.lastIndex - 1)

    for (segmentIndex in startIndex..endIndex) {
        val minT = if (segmentIndex == startIndex) minPosition.t else 0.0
        val maxT = if (segmentIndex == endIndex) maxPosition.t else 1.0
        if (maxT <= minT + POSITION_EPSILON) continue

        val start = profile.points[segmentIndex].latLong
        val end = profile.points[segmentIndex + 1].latLong
        val (ax, ay) = toXY(start)
        val (bx, by) = toXY(end)
        val abx = bx - ax
        val aby = by - ay
        val abLenSquared = abx * abx + aby * aby
        val rawT =
            if (abLenSquared > 0.0) {
                ((hintX - ax) * abx + (hintY - ay) * aby) / abLenSquared
            } else {
                minT
            }
        val clampedT = rawT.coerceIn(minT, maxT)
        val projectedX = ax + clampedT * abx
        val projectedY = ay + clampedT * aby
        val projectedLatLong = toLatLong(projectedX, projectedY)
        candidates +=
            RouteRejoinCandidate(
                position = TrackPosition(trackId = "", segmentIndex = segmentIndex, t = clampedT),
                distanceMeters = haversineMeters(projectedLatLong, hint),
            )
    }

    if (candidates.isEmpty()) return null
    val bestDistance = candidates.minOf { it.distanceMeters }
    return candidates
        .firstOrNull {
            it.distanceMeters <= bestDistance + ROUTE_RESHAPE_REJOIN_DISTANCE_MARGIN_METERS
        }?.position
}

private fun buildRouteReshapeHandleIndices(pointCount: Int): List<Int> {
    if (pointCount <= 0) return emptyList()
    if (pointCount == 1) return listOf(0)

    val lastIndex = pointCount - 1
    val handleCount = minOf(ROUTE_RESHAPE_MAX_HANDLES, pointCount)
    val step = lastIndex.toDouble() / (handleCount - 1).toDouble()
    return buildSet {
        add(0)
        add(lastIndex)
        for (index in 1 until handleCount - 1) {
            add((index * step).toInt().coerceIn(1, lastIndex - 1))
        }
    }.toList().sorted()
}
