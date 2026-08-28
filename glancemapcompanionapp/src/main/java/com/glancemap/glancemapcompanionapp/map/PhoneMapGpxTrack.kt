package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint

/** Semantic GPX geometry supplied to the phone map after companion parsing has completed. */
internal data class PhoneMapGpxTrack(
    val id: String,
    val points: List<TrailPoint>,
) {
    init {
        require(id.isNotBlank())
    }
}

/** A renderable contiguous GPX segment, deliberately independent from a map SDK. */
internal data class PhoneMapRouteSegment(
    val points: List<GeoPoint>,
)

internal fun PhoneMapGpxTrack.toRouteSegments(): List<PhoneMapRouteSegment> {
    val segments = mutableListOf<List<GeoPoint>>()
    var currentSegment = mutableListOf<GeoPoint>()
    points.forEach { point ->
        if (point.startsNewSegment && currentSegment.isNotEmpty()) {
            segments += currentSegment
            currentSegment = mutableListOf()
        }
        currentSegment += point.location
    }
    if (currentSegment.isNotEmpty()) {
        segments += currentSegment
    }
    return segments
        .filter { segment -> segment.size >= MINIMUM_RENDERABLE_SEGMENT_POINTS }
        .map(::PhoneMapRouteSegment)
}

internal data class PhoneMapRouteBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

internal fun List<PhoneMapRouteSegment>.boundsOrNull(): PhoneMapRouteBounds? {
    val points = flatMap(PhoneMapRouteSegment::points)
    if (points.size < MINIMUM_RENDERABLE_SEGMENT_POINTS) return null
    return PhoneMapRouteBounds(
        west = points.minOf(GeoPoint::longitude),
        south = points.minOf(GeoPoint::latitude),
        east = points.maxOf(GeoPoint::longitude),
        north = points.maxOf(GeoPoint::latitude),
    )
}

private const val MINIMUM_RENDERABLE_SEGMENT_POINTS = 2
