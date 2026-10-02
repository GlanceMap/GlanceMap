package com.glancemap.glancemapwearos.presentation.features.gpx

import org.mapsforge.core.model.LatLong

private const val PROFILE_LOCATION_MIN_ROUTE_MATCH_METERS = 30.0
private const val PROFILE_LOCATION_MAX_ROUTE_MATCH_METERS = 100.0
private const val PROFILE_LOCATION_MAX_FIX_ACCURACY_METERS = 50f

internal fun elevationProfileRouteMatchRadiusMeters(accuracyMeters: Float): Double? {
    if (!accuracyMeters.isFinite() || accuracyMeters > PROFILE_LOCATION_MAX_FIX_ACCURACY_METERS) {
        return null
    }
    return (accuracyMeters.toDouble() * 2.0).coerceIn(
        minimumValue = PROFILE_LOCATION_MIN_ROUTE_MATCH_METERS,
        maximumValue = PROFILE_LOCATION_MAX_ROUTE_MATCH_METERS,
    )
}

internal fun elevationProfileLocationMarker(
    profile: TrackProfile,
    currentLocation: LatLong,
    accuracyMeters: Float,
): ElevationProfileLocationMarker? =
    elevationProfileRouteMatchRadiusMeters(accuracyMeters)?.let { matchRadiusMeters ->
        findClosestTrackPosition(
            press = currentLocation,
            trackId = "",
            profile = profile,
        )?.takeIf { closest -> closest.distanceToLineMeters <= matchRadiusMeters }
            ?.let { closest ->
                val segmentIndex = closest.pos.segmentIndex.coerceIn(0, profile.segLen.lastIndex)
                ElevationProfileLocationMarker(
                    distance =
                        (
                            profile.cumDist[segmentIndex] +
                                closest.pos.t.coerceIn(0.0, 1.0) * profile.segLen[segmentIndex]
                        ).coerceIn(0.0, profile.totalDistance),
                )
            }
    }
