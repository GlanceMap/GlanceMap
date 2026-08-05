package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.trailcore.geo.haversineDistanceMeters
import com.glancemap.trailcore.profile.TrailRouteProfile
import java.lang.StringBuilder

/** Creates a transferable GPX for a mission-day segment without saving another library route. */
internal object MissionPlanGpxExporter {
    fun export(
        day: MissionPlanDay,
        routeTitle: String,
        parsedRoute: ParsedCompanionRoute,
        profile: TrailRouteProfile,
    ): String {
        val startIndex = profile.closestPointIndex(day.startDistanceMeters)
        val endIndex = profile.closestPointIndex(day.endDistanceFor(profile.totalDistanceMeters))
        val (firstPointIndex, lastPointIndex) =
            if (startIndex == endIndex && startIndex == profile.points.lastIndex) {
                (startIndex - 1).coerceAtLeast(0) to startIndex
            } else {
                minOf(startIndex, endIndex) to maxOf(startIndex, endIndex).coerceAtLeast(startIndex + 1)
            }
        val startDistance = profile.cumulativeDistanceMeters[firstPointIndex]
        val endDistance = profile.cumulativeDistanceMeters[lastPointIndex]
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"GlanceMap\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            parsedRoute.waypoints
                .filter { waypoint ->
                    val waypointDistance = profile.nearestDistanceFor(waypoint)
                    waypointDistance in startDistance..endDistance
                }.forEach { waypoint ->
                    append("  <wpt lat=\"")
                    append(waypoint.location.latitude)
                    append("\" lon=\"")
                    append(waypoint.location.longitude)
                    append("\">")
                    waypoint.title?.let { title -> appendTag("name", title) }
                    waypoint.description?.let { description -> appendTag("desc", description) }
                    append("</wpt>\n")
                }
            append("  <trk><name>")
            append(escape("$routeTitle — Day ${day.dayNumber}"))
            append("</name><trkseg>\n")
            profile.points.subList(firstPointIndex, lastPointIndex + 1).forEach { point ->
                append("    <trkpt lat=\"")
                append(point.location.latitude)
                append("\" lon=\"")
                append(point.location.longitude)
                append("\">")
                point.elevationMeters?.let { elevation -> appendTag("ele", elevation.toString()) }
                append("</trkpt>\n")
            }
            append("  </trkseg></trk>\n</gpx>\n")
        }
    }

    private fun TrailRouteProfile.closestPointIndex(distanceMeters: Double): Int =
        cumulativeDistanceMeters.indices.minByOrNull { index ->
            kotlin.math.abs(cumulativeDistanceMeters[index] - distanceMeters)
        } ?: 0

    private fun TrailRouteProfile.nearestDistanceFor(waypoint: RouteWaypoint): Double {
        val pointIndex =
            points.indices.minByOrNull { index ->
                haversineDistanceMeters(points[index].location, waypoint.location)
            } ?: 0
        return cumulativeDistanceMeters[pointIndex]
    }

    private fun StringBuilder.appendTag(
        name: String,
        value: String,
    ) {
        append('<')
        append(name)
        append('>')
        append(escape(value))
        append("</")
        append(name)
        append('>')
    }

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
