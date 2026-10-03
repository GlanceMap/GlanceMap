package com.glancemap.glancemapwearos.presentation.features.routetools

import android.content.Context
import android.os.SystemClock
import com.glancemap.glancemapwearos.core.routing.RoutingCoverageUtils
import com.glancemap.glancemapwearos.core.routing.isRoutingSegmentFileName
import com.glancemap.glancemapwearos.core.routing.routingSegmentsDir
import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot
import org.mapsforge.core.model.LatLong
import java.io.File

internal data class RouteToolPreflightResult(
    val canStart: Boolean,
    val message: String? = null,
    val shouldRequestFreshLocation: Boolean = false,
)

internal fun RouteToolSession.preflightStart(
    context: Context,
    currentLocation: LatLong?,
    gpsSignalSnapshot: GpsSignalSnapshot,
    isOfflineMode: Boolean,
    hasSingleActiveGpx: Boolean,
    selectedMapPath: String?,
): RouteToolPreflightResult {
    if (options.requiresSingleActiveGpx && !hasSingleActiveGpx) {
        return RouteToolPreflightResult(
            canStart = false,
            message = "Activate exactly one GPX first",
        )
    }

    if (options.requiresRoutingData) {
        val routingPreflight =
            routingCoveragePreflight(
                context = context,
                selectedMapPath = selectedMapPath,
            )
        if (routingPreflight != null) {
            return routingPreflight
        }
    }

    if (options.toolKind == RouteToolKind.CREATE && options.createMode == RouteCreateMode.COORDINATES) {
        options.coordinateEndpointValidationMessage(currentLocation)?.let { message ->
            return RouteToolPreflightResult(
                canStart = false,
                message = message,
            )
        }
    }

    if (options.toolKind != RouteToolKind.CREATE ||
        !options.needsCurrentLocationForCreate()
    ) {
        return RouteToolPreflightResult(canStart = true)
    }

    if (isOfflineMode) {
        return RouteToolPreflightResult(
            canStart = false,
            message =
                when (options.createMode) {
                    RouteCreateMode.CURRENT_TO_HERE -> "To here needs GPS"
                    RouteCreateMode.MULTI_POINT_CHAIN -> "This action needs GPS"
                    RouteCreateMode.SEARCH -> "Search needs GPS"
                    RouteCreateMode.COORDINATES -> "Current endpoint needs GPS"
                    RouteCreateMode.LOOP_AROUND_HERE -> "Loop route needs GPS"
                    else -> "This action needs GPS"
                },
        )
    }

    if (
        hasUsableRouteToolCurrentLocation(
            currentLocation = currentLocation,
            gpsSignalSnapshot = gpsSignalSnapshot,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
    ) {
        return RouteToolPreflightResult(canStart = true)
    }

    return RouteToolPreflightResult(
        canStart = false,
        message = "Waiting for GPS",
        shouldRequestFreshLocation = true,
    )
}

/**
 * A route can safely start from a recently accepted position even while the fused provider reports
 * a transient availability change. This is deliberately less strict than the live-marker policy:
 * the origin is used once to plan a route, rather than to continuously render the user's position.
 */
internal fun hasUsableRouteToolCurrentLocation(
    currentLocation: LatLong?,
    gpsSignalSnapshot: GpsSignalSnapshot,
    nowElapsedMs: Long,
): Boolean {
    if (currentLocation == null || gpsSignalSnapshot.lastFixElapsedRealtimeMs <= 0L) return false
    val fixAgeMs =
        (nowElapsedMs - gpsSignalSnapshot.lastFixElapsedRealtimeMs).coerceAtLeast(0L)
    return fixAgeMs <= ROUTE_TOOL_CURRENT_LOCATION_MAX_AGE_MS
}

internal const val ROUTE_TOOL_CURRENT_LOCATION_MAX_AGE_MS = 10_000L

private fun hasInstalledRoutingData(context: Context): Boolean {
    val installedFiles = routingSegmentsDir(context).listFiles() ?: return false
    return installedFiles.any { file ->
        file.isFile && isRoutingSegmentFileName(file.name)
    }
}

private fun routingCoveragePreflight(
    context: Context,
    selectedMapPath: String?,
): RouteToolPreflightResult? {
    val selectedMapFile =
        selectedMapPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() && it.isFile }

    if (selectedMapFile != null) {
        val coverage = RoutingCoverageUtils.coverageForMap(context, selectedMapFile)
        if (coverage.isCoverageKnown) {
            return if (coverage.availableSegments == 0) {
                RouteToolPreflightResult(
                    canStart = false,
                    message = "Routing packs missing for selected map",
                )
            } else {
                null
            }
        }
    }

    return if (!hasInstalledRoutingData(context)) {
        RouteToolPreflightResult(
            canStart = false,
            message = "Routing data missing",
        )
    } else {
        null
    }
}

internal fun RouteToolOptions.needsCurrentLocationForCreate(): Boolean {
    if (toolKind != RouteToolKind.CREATE) return false
    return when (createMode) {
        RouteCreateMode.CURRENT_TO_HERE,
        RouteCreateMode.SEARCH,
        -> true

        RouteCreateMode.COORDINATES ->
            startEndpointSource == RouteEndpointSource.CURRENT_LOCATION ||
                destinationEndpointSource == RouteEndpointSource.CURRENT_LOCATION

        RouteCreateMode.LOOP_AROUND_HERE -> loopStartMode == LoopStartMode.CURRENT_LOCATION
        else -> false
    }
}
