package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.geo.haversineDistanceMeters
import com.glancemap.trailcore.profile.TrailPoint
import java.util.Locale

internal fun replacePhoneRouteSection(
    original: List<TrailPoint>,
    pointA: GeoPoint,
    pointB: GeoPoint,
    replacement: List<TrailPoint>,
): List<TrailPoint> {
    require(original.size >= 2) { "The selected GPX has no editable route." }
    require(replacement.size >= 2) { "The replacement route is empty." }
    val firstIndex = original.nearestPointIndex(pointA)
    val secondIndex = original.nearestPointIndex(pointB)
    require(firstIndex != secondIndex) { "Select two different points on the route." }
    val startIndex = minOf(firstIndex, secondIndex)
    val endIndex = maxOf(firstIndex, secondIndex)
    val orderedReplacement =
        if (firstIndex <= secondIndex) {
            replacement
        } else {
            replacement.reversed()
        }
    return buildList {
        original.take(startIndex).forEach(::appendDistinct)
        orderedReplacement.forEach(::appendDistinct)
        original.drop(endIndex + 1).forEach(::appendDistinct)
    }
}

internal fun encodePhoneRouteGpx(
    title: String,
    points: List<TrailPoint>,
): ByteArray {
    require(points.size >= 2) { "At least two route points are required." }
    val escapedTitle = title.escapeXml()
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<gpx version=\"1.1\" creator=\"GlanceMap\" ")
        append("xmlns=\"http://www.topografix.com/GPX/1/1\">")
        append("<trk><name>").append(escapedTitle).append("</name><trkseg>")
        points.forEach { point ->
            append("<trkpt lat=\"")
                .append(point.location.latitude.toGpxCoordinate())
                .append("\" lon=\"")
                .append(point.location.longitude.toGpxCoordinate())
                .append("\">")
            point.elevationMeters?.let { elevation ->
                append("<ele>").append(elevation.toGpxCoordinate()).append("</ele>")
            }
            append("</trkpt>")
        }
        append("</trkseg></trk></gpx>")
    }.toByteArray(Charsets.UTF_8)
}

private fun List<TrailPoint>.nearestPointIndex(target: GeoPoint): Int =
    indices.minByOrNull { index -> haversineDistanceMeters(this[index].location, target) }
        ?: error("The selected GPX has no route points.")

private fun MutableList<TrailPoint>.appendDistinct(point: TrailPoint) {
    if (lastOrNull()?.location == point.location) return
    add(point.copy(startsNewSegment = false))
}

private fun Double.toGpxCoordinate(): String = String.format(Locale.US, "%.7f", this)

private fun String.escapeXml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
