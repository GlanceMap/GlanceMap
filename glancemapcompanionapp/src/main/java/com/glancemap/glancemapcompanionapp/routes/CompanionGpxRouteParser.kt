package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

data class ParsedCompanionRoute(
    val title: String?,
    val points: List<TrailPoint>,
    val waypoints: List<RouteWaypoint>,
)

data class RouteWaypoint(
    val location: GeoPoint,
    val title: String?,
    val description: String?,
)

/** Reads only the route data required for the companion's planning library. */
@Suppress("TooManyFunctions")
object CompanionGpxRouteParser {
    fun parse(input: InputStream): ParsedCompanionRoute {
        val gpxBytes = input.readBytes()
        require(!gpxBytes.toString(StandardCharsets.UTF_8).contains(DOCTYPE_MARKER, ignoreCase = true)) {
            "GPX files containing a document type declaration are not supported."
        }
        val root =
            ByteArrayInputStream(gpxBytes).use { source ->
                DocumentBuilderFactory
                    .newInstance()
                    .newDocumentBuilder()
                    .parse(source)
                    .documentElement
            }
        val trackPoints = root.trackPoints()
        val routePoints = root.routePoints()
        val points = trackPoints.takeIf { it.size >= MINIMUM_ROUTE_POINT_COUNT } ?: routePoints
        require(points.size >= MINIMUM_ROUTE_POINT_COUNT) {
            "The GPX does not contain a track or route with at least two points."
        }
        return ParsedCompanionRoute(
            title = root.routeTitle(),
            points = points,
            waypoints = root.descendantsNamed(WAYPOINT_TAG).mapNotNull { waypoint -> waypoint.toRouteWaypoint() },
        )
    }

    private fun Element.routeTitle(): String? =
        child(METADATA_TAG)?.text(NAME_TAG)
            ?: descendantsNamed(TRACK_TAG).firstOrNull()?.text(NAME_TAG)
            ?: descendantsNamed(ROUTE_TAG).firstOrNull()?.text(NAME_TAG)

    private fun Element.trackPoints(): List<TrailPoint> {
        val points = mutableListOf<TrailPoint>()
        descendantsNamed(TRACK_TAG).forEach { track ->
            track.kids(TRACK_SEGMENT_TAG).forEach { segment ->
                segment.appendPointsTo(points, TRACK_POINT_TAG)
            }
        }
        return points
    }

    private fun Element.routePoints(): List<TrailPoint> {
        val points = mutableListOf<TrailPoint>()
        descendantsNamed(ROUTE_TAG).forEach { route ->
            route.appendPointsTo(points, ROUTE_POINT_TAG)
        }
        return points
    }

    private fun Element.appendPointsTo(
        destination: MutableList<TrailPoint>,
        pointTag: String,
    ) {
        var firstValidPoint = true
        kids(pointTag).forEach { point ->
            point.toTrailPoint(startsNewSegment = destination.isNotEmpty() && firstValidPoint)?.let { trailPoint ->
                destination += trailPoint
                firstValidPoint = false
            }
        }
    }

    private fun Element.toTrailPoint(startsNewSegment: Boolean): TrailPoint? =
        locationOrNull()?.let { location ->
            TrailPoint(
                location = location,
                elevationMeters = text(ELEVATION_TAG)?.toDoubleOrNull()?.takeIf(Double::isFinite),
                startsNewSegment = startsNewSegment,
            )
        }

    private fun Element.toRouteWaypoint(): RouteWaypoint? =
        locationOrNull()?.let { location ->
            RouteWaypoint(
                location = location,
                title = text(NAME_TAG),
                description = text(DESCRIPTION_TAG),
            )
        }

    private fun Element.locationOrNull(): GeoPoint? {
        val latitude = getAttribute(LATITUDE_ATTRIBUTE).toDoubleOrNull()
        val longitude = getAttribute(LONGITUDE_ATTRIBUTE).toDoubleOrNull()
        if (latitude == null || longitude == null) return null
        return runCatching { GeoPoint(latitude = latitude, longitude = longitude) }.getOrNull()
    }

    private fun Element.text(tagName: String): String? = child(tagName)?.textContent?.trim()?.takeIf(String::isNotBlank)

    private fun Element.child(tagName: String): Element? = kids(tagName).firstOrNull()

    private fun Element.kids(tagName: String): List<Element> = childNodes.elements().filter { it.isTag(tagName) }

    private fun Element.descendantsNamed(tagName: String): List<Element> =
        childNodes.elements().flatMap { child ->
            buildList {
                if (child.isTag(tagName)) add(child)
                addAll(child.descendantsNamed(tagName))
            }
        }

    private fun NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }

    private fun Element.isTag(name: String): Boolean = localName == name || nodeName.substringAfter(':') == name

    private const val DESCRIPTION_TAG = "desc"
    private const val DOCTYPE_MARKER = "<!DOCTYPE"
    private const val ELEVATION_TAG = "ele"
    private const val LATITUDE_ATTRIBUTE = "lat"
    private const val LONGITUDE_ATTRIBUTE = "lon"
    private const val METADATA_TAG = "metadata"
    private const val MINIMUM_ROUTE_POINT_COUNT = 2
    private const val NAME_TAG = "name"
    private const val ROUTE_POINT_TAG = "rtept"
    private const val ROUTE_TAG = "rte"
    private const val TRACK_POINT_TAG = "trkpt"
    private const val TRACK_SEGMENT_TAG = "trkseg"
    private const val TRACK_TAG = "trk"
    private const val WAYPOINT_TAG = "wpt"
}
