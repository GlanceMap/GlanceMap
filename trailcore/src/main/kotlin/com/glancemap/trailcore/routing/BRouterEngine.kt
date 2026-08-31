package com.glancemap.trailcore.routing

import btools.router.FormatGpx
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import java.io.File
import kotlin.math.abs
import kotlin.math.floor

private const val ROUTING_TILE_DEGREES = 5

/** The routing profiles already bundled by the watch app and reused by the phone. */
enum class BRouterRoutePreset {
    BALANCED_HIKE,
    PREFER_TRAILS,
    PREFER_EASIEST,
    CUSTOM_HIKE,
    BIKE_TOURING,
    BIKE_ROAD,
    BIKE_QUIET_ROAD,
    BIKE_GRAVEL,
    BIKE_MTB,
}

data class BRouterHikeProfileParams(
    val hikingRoutesPreference: Float,
    val pathPreference: Float,
    val sacScaleLimit: Int,
    val sacScalePreferred: Int,
    val considerForest: Boolean,
)

data class BRouterRouteRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val viaPoints: List<GeoPoint> = emptyList(),
    val preset: BRouterRoutePreset = BRouterRoutePreset.BALANCED_HIKE,
    val useElevation: Boolean = true,
    val allowFerries: Boolean = false,
    val customHikeParams: BRouterHikeProfileParams? = null,
)

data class BRouterFileLayout(
    val segmentsDirectory: File,
    val profilesDirectory: File,
)

data class BRouterRoutingAttempt(
    val routingContext: RoutingContext,
    val engine: RoutingEngine,
)

data class BRouterRouteOutput(
    val fileName: String,
    val title: String,
    val gpxBytes: ByteArray,
    val points: List<TrailPoint>,
)

/**
 * Platform-neutral wrapper around the BRouter engine.
 *
 * Android callers only provide the two on-device data directories. Asset copying, diagnostics,
 * and UI state stay in the app adapters, so both apps execute this same routing implementation.
 */
class BRouterEngine(
    private val files: BRouterFileLayout,
) {
    fun route(
        request: BRouterRouteRequest,
        timeoutMs: Long = DEFAULT_ROUTE_TIMEOUT_MS,
    ): BRouterRoutingAttempt {
        val points = listOf(request.origin) + request.viaPoints + request.destination
        val missingSegments =
            requiredRoutingSegmentFileNames(points)
                .filterNot { fileName -> File(files.segmentsDirectory, fileName).isFile }
        require(missingSegments.isEmpty()) {
            missingSegmentsMessage(missingSegments)
        }

        files.segmentsDirectory.mkdirs()
        val profileFile = File(files.profilesDirectory, request.preset.profileFileName())
        require(profileFile.isFile) {
            "Routing profiles missing."
        }

        val routingContext =
            RoutingContext().apply {
                localFunction = profileFile.absolutePath
                outputFormat = "gpx"
                keyValues = buildProfileParams(request)
            }
        val engine =
            RoutingEngine(
                null,
                null,
                files.segmentsDirectory,
                buildWaypoints(request),
                routingContext,
                RoutingEngine.BROUTER_ENGINEMODE_ROUTING,
            ).apply {
                quite = true
            }

        engine.doRun(timeoutMs)
        require(engine.errorMessage.isNullOrBlank()) {
            engine.errorMessage ?: "Could not create route."
        }
        return BRouterRoutingAttempt(routingContext = routingContext, engine = engine)
    }

    fun output(
        attempt: BRouterRoutingAttempt,
        title: String,
        fileName: String,
    ): BRouterRouteOutput {
        val track = requireNotNull(attempt.engine.foundTrack) { "No route found." }
        require(track.nodes.isNotEmpty()) { "No route found." }
        val messageSummary = track.message?.takeIf { it.isNotBlank() } ?: title
        when {
            track.messageList == null -> track.messageList = mutableListOf(messageSummary)
            track.messageList.isEmpty() -> track.messageList.add(messageSummary)
        }
        val gpx =
            FormatGpx(attempt.routingContext)
                .format(track)
                .rewriteTrackName(title)
                .toByteArray(Charsets.UTF_8)

        return BRouterRouteOutput(
            fileName = fileName,
            title = title,
            gpxBytes = gpx,
            points =
                track.nodes.map { node ->
                    TrailPoint(
                        location =
                            GeoPoint(
                                latitude = (node.getILat() - B_ROUTER_LATITUDE_OFFSET) / B_ROUTER_COORDINATE_SCALE,
                                longitude = (node.getILon() - B_ROUTER_LONGITUDE_OFFSET) / B_ROUTER_COORDINATE_SCALE,
                            ),
                        elevationMeters =
                            node
                                .getSElev()
                                .takeUnless { elevation -> elevation == Short.MIN_VALUE }
                                ?.let { elevation -> elevation / B_ROUTER_ELEVATION_SCALE },
                    )
                },
        )
    }

    private fun buildWaypoints(request: BRouterRouteRequest): List<OsmNodeNamed> =
        buildList {
            add(request.origin.toWaypoint("from"))
            request.viaPoints.forEachIndexed { index, point -> add(point.toWaypoint("via${index + 1}")) }
            add(request.destination.toWaypoint("to"))
        }

    private fun buildProfileParams(request: BRouterRouteRequest): HashMap<String, String> =
        hashMapOf<String, String>().apply {
            put("allow_ferries", request.allowFerries.toProfileNumber())
            put("consider_elevation", request.useElevation.toProfileNumber())
            if (!request.preset.isBikePreset()) {
                put(
                    "consider_forest",
                    (request.customHikeParams?.considerForest ?: false).toProfileNumber(),
                )
            }
            when (request.preset) {
                BRouterRoutePreset.BALANCED_HIKE -> {
                    put("hiking_routes_preference", "0.20")
                    put("path_preference", "0.0")
                    put("SAC_scale_limit", "3")
                    put("SAC_scale_preferred", "1")
                }

                BRouterRoutePreset.PREFER_TRAILS -> {
                    put("hiking_routes_preference", "0.60")
                    put("path_preference", "20.0")
                    put("SAC_scale_limit", "3")
                    put("SAC_scale_preferred", "2")
                }

                BRouterRoutePreset.PREFER_EASIEST -> {
                    put("hiking_routes_preference", "0.0")
                    put("path_preference", "0.0")
                    put("SAC_scale_limit", "1")
                    put("SAC_scale_preferred", "1")
                }

                BRouterRoutePreset.CUSTOM_HIKE -> {
                    val params = request.customHikeParams ?: DEFAULT_CUSTOM_HIKE_PARAMS
                    put("hiking_routes_preference", params.hikingRoutesPreference.toProfileNumber())
                    put("path_preference", params.pathPreference.toProfileNumber())
                    put("SAC_scale_limit", params.sacScaleLimit.toString())
                    put("SAC_scale_preferred", params.sacScalePreferred.toString())
                }

                BRouterRoutePreset.BIKE_TOURING -> {
                    put("allow_steps", "0")
                    put("consider_noise", "1")
                    put("consider_traffic", "1")
                    put("avoid_unsafe", "1")
                }

                BRouterRoutePreset.BIKE_ROAD,
                BRouterRoutePreset.BIKE_QUIET_ROAD,
                -> put("allow_steps", "0")

                BRouterRoutePreset.BIKE_GRAVEL -> put("avoid_steep_inclines", request.useElevation.toProfileNumber())
                BRouterRoutePreset.BIKE_MTB -> put("allow_steps", "0")
            }
        }

    private fun BRouterRoutePreset.profileFileName(): String =
        when (this) {
            BRouterRoutePreset.BALANCED_HIKE,
            BRouterRoutePreset.PREFER_TRAILS,
            BRouterRoutePreset.PREFER_EASIEST,
            BRouterRoutePreset.CUSTOM_HIKE,
            -> DEFAULT_PROFILE_FILE_NAME

            BRouterRoutePreset.BIKE_TOURING -> BIKE_TOURING_PROFILE_FILE_NAME
            BRouterRoutePreset.BIKE_ROAD -> BIKE_ROAD_PROFILE_FILE_NAME
            BRouterRoutePreset.BIKE_QUIET_ROAD -> BIKE_QUIET_ROAD_PROFILE_FILE_NAME
            BRouterRoutePreset.BIKE_GRAVEL -> BIKE_GRAVEL_PROFILE_FILE_NAME
            BRouterRoutePreset.BIKE_MTB -> BIKE_MTB_PROFILE_FILE_NAME
        }

    private fun GeoPoint.toWaypoint(name: String): OsmNodeNamed =
        OsmNodeNamed().apply {
            this.name = name
            ilon = ((longitude + 180.0) * B_ROUTER_COORDINATE_SCALE + 0.5).toInt()
            ilat = ((latitude + 90.0) * B_ROUTER_COORDINATE_SCALE + 0.5).toInt()
        }

    private fun Boolean.toProfileNumber(): String = if (this) "1" else "0"

    private fun Float.toProfileNumber(): String = toString()

    private companion object {
        const val B_ROUTER_COORDINATE_SCALE = 1_000_000.0
        const val B_ROUTER_LATITUDE_OFFSET = 90_000_000
        const val B_ROUTER_LONGITUDE_OFFSET = 180_000_000
        const val B_ROUTER_ELEVATION_SCALE = 4.0
        const val DEFAULT_ROUTE_TIMEOUT_MS = 60_000L
        const val DEFAULT_PROFILE_FILE_NAME = "hiking-mountain.brf"
        const val BIKE_TOURING_PROFILE_FILE_NAME = "trekking.brf"
        const val BIKE_ROAD_PROFILE_FILE_NAME = "fastbike.brf"
        const val BIKE_QUIET_ROAD_PROFILE_FILE_NAME = "fastbike-verylowtraffic.brf"
        const val BIKE_GRAVEL_PROFILE_FILE_NAME = "gravel.brf"
        const val BIKE_MTB_PROFILE_FILE_NAME = "mtb.brf"
        val DEFAULT_CUSTOM_HIKE_PARAMS =
            BRouterHikeProfileParams(
                hikingRoutesPreference = 0.20f,
                pathPreference = 0f,
                sacScaleLimit = 3,
                sacScalePreferred = 1,
                considerForest = false,
            )
    }
}

fun requiredRoutingSegmentFileNames(points: List<GeoPoint>): List<String> {
    require(points.isNotEmpty()) { "At least one point is required." }
    val latStart = routingTileOrigin(points.minOf(GeoPoint::latitude))
    val latEnd = routingTileOrigin(points.maxOf(GeoPoint::latitude))
    val lonStart = routingTileOrigin(points.minOf(GeoPoint::longitude))
    val lonEnd = routingTileOrigin(points.maxOf(GeoPoint::longitude))
    val tiles = linkedSetOf<String>()
    var lat = latStart
    while (lat <= latEnd) {
        var lon = lonStart
        while (lon <= lonEnd) {
            tiles += routingTileFileName(swLat = lat, swLon = lon)
            lon += ROUTING_TILE_DEGREES
        }
        lat += ROUTING_TILE_DEGREES
    }
    return tiles.toList()
}

fun normalizeBRouterErrorMessage(message: String): String =
    when {
        message.isBlank() -> "Could not create route."
        message.startsWith("Missing routing data:", ignoreCase = true) -> message
        message.contains("checksum", ignoreCase = true) ->
            "Routing data is damaged. Refresh the routing packs."
        message.contains("dummy.brf", ignoreCase = true) || message.contains("profiles", ignoreCase = true) ->
            "Routing profiles missing. Reopen route tools and try again."
        message.contains("not found", ignoreCase = true) || message.contains("no track found", ignoreCase = true) ->
            "No route found."
        else -> message
    }

private fun BRouterRoutePreset.isBikePreset(): Boolean =
    this == BRouterRoutePreset.BIKE_TOURING ||
        this == BRouterRoutePreset.BIKE_ROAD ||
        this == BRouterRoutePreset.BIKE_QUIET_ROAD ||
        this == BRouterRoutePreset.BIKE_GRAVEL ||
        this == BRouterRoutePreset.BIKE_MTB

private fun routingTileOrigin(coordinate: Double): Int = routingTileDegrees(coordinate) * ROUTING_TILE_DEGREES

private fun routingTileDegrees(coordinate: Double): Int = floor(coordinate / ROUTING_TILE_DEGREES.toDouble()).toInt()

private fun routingTileFileName(
    swLat: Int,
    swLon: Int,
): String =
    "${formatRoutingTileCoord(swLon, positivePrefix = 'E', negativePrefix = 'W')}" +
        "_${formatRoutingTileCoord(swLat, positivePrefix = 'N', negativePrefix = 'S')}.rd5"

private fun formatRoutingTileCoord(
    value: Int,
    positivePrefix: Char,
    negativePrefix: Char,
): String {
    val prefix = if (value < 0) negativePrefix else positivePrefix
    return "$prefix${abs(value)}"
}

private fun missingSegmentsMessage(missingSegments: List<String>): String =
    when (missingSegments.size) {
        0 -> "Routing data missing"
        1 -> "Missing routing data: ${missingSegments.first()}"
        else -> "Missing routing data: ${missingSegments.first()} +${missingSegments.size - 1}"
    }

private fun String.rewriteTrackName(title: String): String {
    val escapedTitle =
        title
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    val trackNameRegex =
        Regex(
            pattern = "(<trk>\\s*<name>)(.*?)(</name>)",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
    return if (trackNameRegex.containsMatchIn(this)) {
        replaceFirst(trackNameRegex, "$1$escapedTitle$3")
    } else {
        replaceFirst("<trk>", "<trk><name>$escapedTitle</name>")
    }
}
