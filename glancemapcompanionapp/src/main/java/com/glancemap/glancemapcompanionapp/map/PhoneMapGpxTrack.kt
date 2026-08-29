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

/** A Route Library GPX item whose map visibility is independent from every other item. */
internal data class PhoneMapGpxItem(
    val id: String,
    val displayName: String,
    val track: PhoneMapGpxTrack,
    val enabled: Boolean,
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(track.id == id)
    }
}

/** Small route metadata passed into the phone map without coupling its panel UI to Route Library. */
internal data class PhoneMapGpxSource(
    val id: String,
    val displayName: String,
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
    }
}

internal fun mergePhoneMapGpxItems(
    previous: List<PhoneMapGpxItem>,
    loaded: List<PhoneMapGpxItem>,
    initiallyEnabledId: String?,
): List<PhoneMapGpxItem> {
    val enabledById = previous.associate { item -> item.id to item.enabled }
    return loaded.map { item ->
        item.copy(enabled = enabledById[item.id] ?: (item.id == initiallyEnabledId))
    }
}

internal fun List<PhoneMapGpxItem>.toggleEnabled(
    id: String,
): List<PhoneMapGpxItem> =
    map { item ->
        if (item.id == id) item.copy(enabled = !item.enabled) else item
    }

internal fun List<PhoneMapGpxItem>.enabledRouteSegments(globalVisible: Boolean): List<PhoneMapRouteSegment> =
    if (globalVisible) {
        filter(PhoneMapGpxItem::enabled).flatMap { item -> item.track.toRouteSegments() }
    } else {
        emptyList()
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
