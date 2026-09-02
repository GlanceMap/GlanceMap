package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.profile.TrailRouteProfile
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import org.mapsforge.core.util.MercatorProjection

internal data class PhoneMapRouteAnalysisPosition(
    val segmentIndex: Int,
    val fraction: Double,
)

internal data class PhoneMapRouteAnalysisHit(
    val item: PhoneMapGpxItem,
    val position: PhoneMapRouteAnalysisPosition,
    val coordinate: PhoneMapCoordinate,
    val distanceToTrackMeters: Double,
)

internal data class PhoneMapRouteAnalysisLeg(
    val distanceMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val durationSeconds: Double,
)

internal data class PhoneMapRouteAnalysis(
    val trackId: String,
    val displayName: String,
    val pointA: PhoneMapCoordinate,
    val pointAPosition: PhoneMapRouteAnalysisPosition,
    val pointB: PhoneMapCoordinate? = null,
    val pointBPosition: PhoneMapRouteAnalysisPosition? = null,
    val startToA: PhoneMapRouteAnalysisLeg,
    val aToEnd: PhoneMapRouteAnalysisLeg,
    val aToB: PhoneMapRouteAnalysisLeg? = null,
    val bToEnd: PhoneMapRouteAnalysisLeg? = null,
)

internal fun findClosestPhoneMapRoutePosition(
    press: PhoneMapCoordinate,
    items: List<PhoneMapGpxItem>,
    allowedTrackId: String? = null,
): PhoneMapRouteAnalysisHit? {
    var best: PhoneMapRouteAnalysisHit? = null
    var bestDistance = Double.MAX_VALUE
    items.forEach { item ->
        if (allowedTrackId != null && item.id != allowedTrackId) return@forEach
        val points = item.track.points
        if (points.size < 2) return@forEach
        if (points.zipWithNext().none { (_, to) -> !to.startsNewSegment }) return@forEach
        val hit = nearestSegmentPick(press, item, points)
        if (hit.distanceToTrackMeters < bestDistance) {
            best = hit
            bestDistance = hit.distanceToTrackMeters
        }
    }
    return best
}

internal fun buildPhoneMapRouteAnalysis(
    pointA: PhoneMapRouteAnalysisHit,
    pointB: PhoneMapRouteAnalysisHit? = null,
    settings: PhoneMapGpxSettings,
    generalSettings: PhoneGeneralSettings,
): PhoneMapRouteAnalysis {
    require(pointB == null || pointA.item.id == pointB.item.id) {
        "Route analysis points must belong to the same GPX track."
    }
    val points = pointA.item.track.points
    val profile = buildTrailRouteProfile(points, settings.toTrailPacingConfig(points, generalSettings))
    val scalarA = profile.scalarAt(pointA.position)
    val scalarB = pointB?.let { profile.scalarAt(it.position) }
    return PhoneMapRouteAnalysis(
        trackId = pointA.item.id,
        displayName = pointA.item.displayName,
        pointA = pointA.coordinate,
        pointAPosition = pointA.position,
        pointB = pointB?.coordinate,
        pointBPosition = pointB?.position,
        startToA = profile.legFromStart(scalarA),
        aToEnd = profile.legToEnd(scalarA),
        aToB = scalarB?.let { scalarA.legBetween(it) },
        bToEnd = scalarB?.let(profile::legToEnd),
    )
}

private data class RouteAnalysisScalar(
    val distanceMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val durationSeconds: Double,
)

private fun TrailRouteProfile.scalarAt(position: PhoneMapRouteAnalysisPosition): RouteAnalysisScalar {
    if (points.size < 2) return RouteAnalysisScalar(0.0, 0.0, 0.0, 0.0)
    val index = position.segmentIndex.coerceIn(0, points.lastIndex - 1)
    val fraction = position.fraction.coerceIn(0.0, 1.0)
    return RouteAnalysisScalar(
        distanceMeters = interpolate(cumulativeDistanceMeters, index, fraction),
        ascentMeters = interpolate(cumulativeAscentMeters, index, fraction),
        descentMeters = interpolate(cumulativeDescentMeters, index, fraction),
        durationSeconds = interpolate(cumulativeEstimatedDurationSeconds, index, fraction),
    )
}

private fun TrailRouteProfile.legFromStart(scalar: RouteAnalysisScalar): PhoneMapRouteAnalysisLeg =
    PhoneMapRouteAnalysisLeg(
        distanceMeters = scalar.distanceMeters,
        ascentMeters = scalar.ascentMeters,
        descentMeters = scalar.descentMeters,
        durationSeconds = scalar.durationSeconds,
    )

private fun TrailRouteProfile.legToEnd(scalar: RouteAnalysisScalar): PhoneMapRouteAnalysisLeg =
    PhoneMapRouteAnalysisLeg(
        distanceMeters = (totalDistanceMeters - scalar.distanceMeters).coerceAtLeast(0.0),
        ascentMeters = (totalAscentMeters - scalar.ascentMeters).coerceAtLeast(0.0),
        descentMeters = (totalDescentMeters - scalar.descentMeters).coerceAtLeast(0.0),
        durationSeconds = (estimatedDurationSeconds - scalar.durationSeconds).coerceAtLeast(0.0),
    )

private fun RouteAnalysisScalar.legBetween(other: RouteAnalysisScalar): PhoneMapRouteAnalysisLeg {
    val isForward = distanceMeters <= other.distanceMeters
    val start = if (isForward) this else other
    val end = if (isForward) other else this
    return PhoneMapRouteAnalysisLeg(
        distanceMeters = (end.distanceMeters - start.distanceMeters).coerceAtLeast(0.0),
        ascentMeters =
            if (isForward) {
                (end.ascentMeters - start.ascentMeters).coerceAtLeast(0.0)
            } else {
                (end.descentMeters - start.descentMeters).coerceAtLeast(0.0)
            },
        descentMeters =
            if (isForward) {
                (end.descentMeters - start.descentMeters).coerceAtLeast(0.0)
            } else {
                (end.ascentMeters - start.ascentMeters).coerceAtLeast(0.0)
            },
        durationSeconds = (end.durationSeconds - start.durationSeconds).coerceAtLeast(0.0),
    )
}

private fun interpolate(
    values: List<Double>,
    index: Int,
    fraction: Double,
): Double = values[index] + fraction * (values[index + 1] - values[index])

private fun nearestSegmentPick(
    press: PhoneMapCoordinate,
    item: PhoneMapGpxItem,
    points: List<TrailPoint>,
): PhoneMapRouteAnalysisHit {
    val zoom: Byte = 20
    val mapSize = MercatorProjection.getMapSize(zoom, 256)

    fun toXY(point: PhoneMapCoordinate): Pair<Double, Double> =
        MercatorProjection.longitudeToPixelX(point.longitude, mapSize) to
            MercatorProjection.latitudeToPixelY(point.latitude, mapSize)

    fun toCoordinate(
        x: Double,
        y: Double,
    ): PhoneMapCoordinate =
        PhoneMapCoordinate(
            latitude = MercatorProjection.pixelYToLatitude(y, mapSize),
            longitude = MercatorProjection.pixelXToLongitude(x, mapSize),
        )

    val (pressX, pressY) = toXY(press)
    var bestIndex = 0
    var bestFraction = 0.0
    var bestDistanceSquared = Double.MAX_VALUE
    var bestX = pressX
    var bestY = pressY

    for (index in 0 until points.lastIndex) {
        if (points[index + 1].startsNewSegment) continue
        val (startX, startY) = toXY(points[index].location.toPhoneMapCoordinate())
        val (endX, endY) = toXY(points[index + 1].location.toPhoneMapCoordinate())
        val directionX = endX - startX
        val directionY = endY - startY
        val lengthSquared = directionX * directionX + directionY * directionY
        val fraction =
            if (lengthSquared > 0.0) {
                ((pressX - startX) * directionX + (pressY - startY) * directionY) / lengthSquared
            } else {
                0.0
            }
        val clampedFraction = fraction.coerceIn(0.0, 1.0)
        val projectedX = startX + clampedFraction * directionX
        val projectedY = startY + clampedFraction * directionY
        val deltaX = pressX - projectedX
        val deltaY = pressY - projectedY
        val distanceSquared = deltaX * deltaX + deltaY * deltaY
        if (distanceSquared < bestDistanceSquared) {
            bestIndex = index
            bestFraction = clampedFraction
            bestDistanceSquared = distanceSquared
            bestX = projectedX
            bestY = projectedY
        }
    }

    val snapped = toCoordinate(bestX, bestY)
    return PhoneMapRouteAnalysisHit(
        item = item,
        position = PhoneMapRouteAnalysisPosition(bestIndex, bestFraction),
        coordinate = snapped,
        distanceToTrackMeters = phoneMapDistanceMeters(press, snapped),
    )
}

@Suppress("MaxLineLength") // This private coordinate adapter is deliberately a one-line value conversion.
private fun com.glancemap.trailcore.geo.GeoPoint.toPhoneMapCoordinate(): PhoneMapCoordinate = PhoneMapCoordinate(latitude = latitude, longitude = longitude)

internal const val PHONE_ROUTE_ANALYSIS_PRESS_THRESHOLD_METERS = 30.0
