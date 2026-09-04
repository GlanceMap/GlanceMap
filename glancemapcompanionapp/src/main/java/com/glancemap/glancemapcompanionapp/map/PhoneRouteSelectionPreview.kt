package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.profile.buildTrailRouteProfile

/** A direct-line estimate while route points are being placed, before BRouter creates the final route. */
internal data class PhoneRouteSelectionPreview(
    val distanceMeters: Double,
    val elevationGainMeters: Double?,
    val estimatedDurationSeconds: Double,
)

/** True while the route-creation flow can stay on the map instead of opening the full dialog. */
internal fun PhoneRouteToolsUiState.usesMapRouteCreationControls(): Boolean =
    isOpen &&
        !isRouting &&
        when (mode) {
            PhoneRouteCreationMode.CURRENT_TO_DESTINATION,
            PhoneRouteCreationMode.POINT_A_TO_B,
            PhoneRouteCreationMode.MULTI_POINT_CHAIN,
            -> true

            PhoneRouteCreationMode.EXTEND_ROUTE_TO_DESTINATION -> selectedRouteId != null
            PhoneRouteCreationMode.COORDINATES,
            PhoneRouteCreationMode.MODIFY_ROUTE,
            null,
            -> false
        }

internal fun PhoneRouteToolsUiState.canCreateFromMapControls(
    currentLocationAvailable: Boolean,
): Boolean =
    !isRouting &&
        when (mode) {
            PhoneRouteCreationMode.CURRENT_TO_DESTINATION ->
                currentLocationAvailable && destination != null

            PhoneRouteCreationMode.POINT_A_TO_B -> pointA != null && pointB != null
            PhoneRouteCreationMode.MULTI_POINT_CHAIN -> chainPoints.size >= 2
            PhoneRouteCreationMode.EXTEND_ROUTE_TO_DESTINATION ->
                selectedRouteId != null && destination != null

            PhoneRouteCreationMode.COORDINATES,
            PhoneRouteCreationMode.MODIFY_ROUTE,
            null,
            -> false
        }

internal fun PhoneRouteToolsUiState.mapRouteCreationPoints(
    currentLocation: GeoPoint?,
): List<GeoPoint> =
    when (mode) {
        PhoneRouteCreationMode.CURRENT_TO_DESTINATION -> listOfNotNull(currentLocation, destination)
        PhoneRouteCreationMode.POINT_A_TO_B -> listOfNotNull(pointA, pointB)
        PhoneRouteCreationMode.MULTI_POINT_CHAIN -> chainPoints
        PhoneRouteCreationMode.EXTEND_ROUTE_TO_DESTINATION -> {
            val routeEnd =
                editableRoutes
                    .firstOrNull { it.id == selectedRouteId }
                    ?.track
                    ?.points
                    ?.lastOrNull()
                    ?.location
            listOfNotNull(routeEnd, destination)
        }

        PhoneRouteCreationMode.COORDINATES,
        PhoneRouteCreationMode.MODIFY_ROUTE,
        null,
        -> emptyList()
    }

internal fun buildPhoneRouteSelectionPreview(
    points: List<GeoPoint>,
    elevationsMeters: List<Double?>,
    settings: PhoneMapGpxSettings,
    generalSettings: PhoneGeneralSettings,
): PhoneRouteSelectionPreview? {
    if (points.size < 2 || elevationsMeters.size != points.size) return null
    val elevationAvailableForEveryPoint = elevationsMeters.all { it != null }
    val trailPoints =
        points.mapIndexed { index, point ->
            TrailPoint(
                location = point,
                elevationMeters = elevationsMeters[index].takeIf { elevationAvailableForEveryPoint },
            )
        }
    val profile =
        buildTrailRouteProfile(
            trailPoints,
            settings.toTrailPacingConfig(trailPoints, generalSettings),
        )
    return PhoneRouteSelectionPreview(
        distanceMeters = profile.totalDistanceMeters,
        elevationGainMeters = profile.totalAscentMeters.takeIf { elevationAvailableForEveryPoint },
        estimatedDurationSeconds = profile.estimatedDurationSeconds,
    )
}
