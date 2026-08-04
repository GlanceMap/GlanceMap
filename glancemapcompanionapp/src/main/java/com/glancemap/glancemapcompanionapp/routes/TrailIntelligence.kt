package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.shared.transfer.ActiveHikePhase
import com.glancemap.shared.transfer.ActiveHikeSnapshot
import com.glancemap.trailcore.profile.TrailWindow
import com.glancemap.trailcore.profile.windowFromDistance

data class TrailIntelligence(
    val window: TrailWindow,
    val upcomingWaypoints: List<TrailIntelligenceWaypoint>,
)

data class TrailIntelligenceWaypoint(
    val title: String,
    val description: String?,
    val distanceAheadMeters: Double,
)

/**
 * Builds a route forecast from the watch's matched distance, rather than from the GPX start.
 * This is planning information only; the watch remains responsible for navigation decisions.
 */
fun RouteLibraryRouteDetails.trailIntelligenceFor(
    snapshot: ActiveHikeSnapshot,
): TrailIntelligence? =
    snapshot.distanceFromStartMeters
        ?.takeIf { snapshot.isForecastableFor(this) }
        ?.let { distanceFromStartMeters ->
            profile.windowFromDistance(
                startDistanceMeters = distanceFromStartMeters,
                maximumDurationSeconds = NEXT_WINDOW_SECONDS,
            )
        }?.takeIf { window -> window.distanceMeters > 0.0 }
        ?.let(::trailIntelligenceForWindow)

private fun RouteLibraryRouteDetails.trailIntelligenceForWindow(window: TrailWindow): TrailIntelligence =
    TrailIntelligence(
        window = window,
        upcomingWaypoints =
            waypoints
                .asSequence()
                .filter { waypoint -> waypoint.distanceFromStartMeters > window.startDistanceMeters }
                .filter { waypoint -> waypoint.distanceFromStartMeters <= window.endDistanceMeters }
                .map { waypoint ->
                    TrailIntelligenceWaypoint(
                        title = waypoint.title,
                        description = waypoint.description,
                        distanceAheadMeters = waypoint.distanceFromStartMeters - window.startDistanceMeters,
                    )
                }.take(MAX_UPCOMING_WAYPOINTS)
                .toList(),
    )

private fun ActiveHikeSnapshot.isForecastableFor(routeDetails: RouteLibraryRouteDetails): Boolean =
    phase != ActiveHikePhase.IDLE &&
        phase != ActiveHikePhase.FINISHED &&
        routeDetails.matchesActiveHike(this)

fun RouteLibraryRouteDetails.matchesActiveHike(snapshot: ActiveHikeSnapshot): Boolean {
    val activeFileName = snapshot.routeId?.fileNameOrNull()
    val sameTransferredFile = activeFileName == route.storedFileName
    val sameTitle = snapshot.routeTitle?.trim()?.equals(route.title.trim(), ignoreCase = true) == true
    return sameTransferredFile || sameTitle
}

@Suppress("ktlint:standard:function-expression-body")
private fun String.fileNameOrNull(): String? {
    return substringAfterLast('/').substringAfterLast('\\').takeIf(String::isNotBlank)
}

private const val MAX_UPCOMING_WAYPOINTS = 3
private const val NEXT_WINDOW_SECONDS = 30.0 * 60.0
