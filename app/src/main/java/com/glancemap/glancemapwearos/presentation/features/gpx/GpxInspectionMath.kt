package com.glancemap.glancemapwearos.presentation.features.gpx

import org.mapsforge.core.model.LatLong
import org.mapsforge.core.util.MercatorProjection
import kotlin.math.*

internal data class ClosestTrackPick(
    val pos: TrackPosition,
    val distanceToLineMeters: Double,
    val snapped: LatLong,
)

internal fun findClosestTrackPosition(
    press: LatLong,
    tracks: List<GpxTrackDetails>,
    profileProvider: (String) -> TrackProfile?,
    allowedTrackId: String?,
): ClosestTrackPick? {
    var best: ClosestTrackPick? = null
    var bestDist = Double.MAX_VALUE

    for (track in tracks) {
        if (allowedTrackId != null && track.id != allowedTrackId) continue

        val profile = profileProvider(track.id) ?: continue
        val pick =
            findClosestTrackPosition(
                press = press,
                trackId = track.id,
                profile = profile,
            ) ?: continue
        if (pick.distanceToLineMeters < bestDist) {
            bestDist = pick.distanceToLineMeters
            best = pick
        }
    }

    return best
}

internal fun findClosestTrackPosition(
    press: LatLong,
    trackId: String,
    profile: TrackProfile,
): ClosestTrackPick? =
    profile.points
        .takeIf { it.size >= 2 }
        ?.let { points -> nearestSegmentPickMercator(press, trackId, points) }

/**
 * Nearest-point computation in Mapsforge Mercator pixel space.
 * Uses mapSize (Long) because your Mapsforge version expects it.
 */
private fun nearestSegmentPickMercator(
    press: LatLong,
    trackId: String,
    pts: List<TrackPoint>,
): ClosestTrackPick {
    val zoom: Byte = 20
    val tileSize = 256
    val mapSize: Long = MercatorProjection.getMapSize(zoom, tileSize)

    fun toXY(ll: LatLong): Pair<Double, Double> {
        val x = MercatorProjection.longitudeToPixelX(ll.longitude, mapSize)
        val y = MercatorProjection.latitudeToPixelY(ll.latitude, mapSize)
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

    val (px, py) = toXY(press)

    var bestI = 0
    var bestT = 0.0
    var bestDist2 = Double.MAX_VALUE
    var bestProjX = px
    var bestProjY = py

    for (i in 0 until pts.size - 1) {
        val (ax, ay) = toXY(pts[i].latLong)
        val (bx, by) = toXY(pts[i + 1].latLong)

        val abx = bx - ax
        val aby = by - ay
        val apx = px - ax
        val apy = py - ay

        val abLen2 = abx * abx + aby * aby
        val t = if (abLen2 > 0.0) (apx * abx + apy * aby) / abLen2 else 0.0
        val tt = t.coerceIn(0.0, 1.0)

        val projx = ax + tt * abx
        val projy = ay + tt * aby

        val dx = px - projx
        val dy = py - projy
        val d2 = dx * dx + dy * dy

        if (d2 < bestDist2) {
            bestDist2 = d2
            bestI = i
            bestT = tt
            bestProjX = projx
            bestProjY = projy
        }
    }

    val snapped = toLatLong(bestProjX, bestProjY)

    return ClosestTrackPick(
        pos = TrackPosition(trackId = trackId, segmentIndex = bestI, t = bestT),
        distanceToLineMeters = haversineMeters(press, snapped),
        snapped = snapped,
    )
}

private fun haversineMeters(
    a: LatLong,
    b: LatLong,
): Double {
    val r = 6_371_000.0
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.longitude - a.longitude)

    val s = sin(dLat / 2)
    val t = sin(dLon / 2)
    val h = s * s + cos(lat1) * cos(lat2) * t * t
    return 2 * r * asin(min(1.0, sqrt(h)))
}
