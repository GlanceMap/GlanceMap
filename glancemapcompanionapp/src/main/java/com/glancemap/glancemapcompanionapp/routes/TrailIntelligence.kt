package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.shared.transfer.ActiveHikePhase
import com.glancemap.shared.transfer.ActiveHikeSnapshot
import com.glancemap.trailcore.profile.TrailWindow
import com.glancemap.trailcore.profile.windowBetweenDistances
import com.glancemap.trailcore.profile.windowFromDistance

data class TrailIntelligence(
    val window: TrailWindow,
    val upcomingWaypoints: List<TrailIntelligenceWaypoint>,
    val context: TrailIntelligenceContext,
)

enum class TrailIntelligenceContext {
    ACTIVE_HIKE,
    PLANNED_DAY,
}

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
        ?.let { window -> trailIntelligenceForWindow(window, TrailIntelligenceContext.ACTIVE_HIKE) }

/**
 * Builds the first upcoming portion of a selected mission day, never extending into its next day.
 * It intentionally uses the same GPX pace and waypoint data as the live route projection.
 */
fun RouteLibraryRouteDetails.trailIntelligenceFor(day: MissionPlanDay): TrailIntelligence? {
    val dayWindow = missionPlanBriefing(day)
    if (dayWindow.distanceMeters <= 0.0) return null

    val projectedWindow =
        profile.windowFromDistance(
            startDistanceMeters = dayWindow.startDistanceMeters,
            maximumDurationSeconds = NEXT_WINDOW_SECONDS,
        )
    val boundedWindow =
        profile.windowBetweenDistances(
            startDistanceMeters = dayWindow.startDistanceMeters,
            endDistanceMeters = minOf(projectedWindow.endDistanceMeters, dayWindow.endDistanceMeters),
        )
    return boundedWindow
        .takeIf { window -> window.distanceMeters > 0.0 }
        ?.let { window -> trailIntelligenceForWindow(window, TrailIntelligenceContext.PLANNED_DAY) }
}

private fun RouteLibraryRouteDetails.trailIntelligenceForWindow(
    window: TrailWindow,
    context: TrailIntelligenceContext,
): TrailIntelligence =
    TrailIntelligence(
        window = window,
        context = context,
        upcomingWaypoints =
            waypoints
                .asSequence()
                .filter { waypoint -> waypoint.distanceFromStartMeters > window.startDistanceMeters }
                .filter { waypoint -> waypoint.distanceFromStartMeters <= window.endDistanceMeters }
                .sortedBy { waypoint -> waypoint.distanceFromStartMeters }
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
    val sameTitle =
        snapshot.routeTitle?.trim()?.let { title ->
            title.equals(route.displayName.trim(), ignoreCase = true) ||
                title.equals(route.metadataTitle?.trim(), ignoreCase = true)
        } == true
    return sameTransferredFile || sameTitle
}

@Suppress("ktlint:standard:function-expression-body")
private fun String.fileNameOrNull(): String? {
    return substringAfterLast('/').substringAfterLast('\\').takeIf(String::isNotBlank)
}

private const val MAX_UPCOMING_WAYPOINTS = 3
private const val NEXT_WINDOW_SECONDS = 30.0 * 60.0
