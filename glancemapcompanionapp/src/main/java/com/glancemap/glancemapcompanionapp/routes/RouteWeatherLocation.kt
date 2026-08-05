package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.glancemapcompanionapp.weather.WeatherForecastLocation
import com.glancemap.shared.transfer.ActiveHikePhase
import com.glancemap.shared.transfer.ActiveHikeSnapshot
import com.glancemap.trailcore.profile.TrailRouteProfile
import kotlin.math.abs

/**
 * Resolves a weather query to the selected route, never to the phone's background location.
 * During a matched active hike it uses the latest route distance; otherwise it uses route start.
 */
fun RouteLibraryRouteDetails.weatherLocationFor(
    activeHikeSnapshot: ActiveHikeSnapshot?,
    plannedStartDistanceMeters: Double = 0.0,
): WeatherForecastLocation? {
    val activeDistance = activeHikeSnapshot?.matchedActiveDistanceFor(this)
    val targetDistanceMeters = activeDistance ?: plannedStartDistanceMeters
    val point = profile.pointNearestToDistance(targetDistanceMeters) ?: return null
    return WeatherForecastLocation(
        latitude = point.location.latitude,
        longitude = point.location.longitude,
        elevationMeters = point.elevationMeters,
        label = if (activeDistance == null) "Route start" else "Current route position",
    )
}

private fun ActiveHikeSnapshot.matchedActiveDistanceFor(
    routeDetails: RouteLibraryRouteDetails,
): Double? =
    distanceFromStartMeters?.takeIf {
        phase != ActiveHikePhase.IDLE &&
            phase != ActiveHikePhase.FINISHED &&
            routeDetails.matchesActiveHike(this)
    }

private fun TrailRouteProfile.pointNearestToDistance(targetDistanceMeters: Double) =
    points.indices
        .minByOrNull { index -> abs(cumulativeDistanceMeters[index] - targetDistanceMeters) }
        ?.let(points::get)
