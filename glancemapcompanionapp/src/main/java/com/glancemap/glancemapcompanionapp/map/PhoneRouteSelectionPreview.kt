package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.profile.buildTrailRouteProfile

/** Distance, elevation, and time summary shared by direct estimates and routed previews. */
internal data class PhoneRouteSelectionPreview(
    val distanceMeters: Double,
    val elevationGainMeters: Double?,
    val elevationLossMeters: Double?,
    val estimatedDurationSeconds: Double,
)

internal data class PhoneMultiPointRoutePreview(
    val chainPointCount: Int,
    val points: List<TrailPoint>,
    val summary: PhoneRouteSelectionPreview,
)

internal fun PhoneRouteToolsUiState.visibleMultiPointRoutePreview(): PhoneMultiPointRoutePreview? {
    if (mode != PhoneRouteCreationMode.MULTI_POINT_CHAIN || isRouting) return null
    val preview = multiPointRoutePreview ?: return null
    return when {
        preview.chainPointCount == chainPoints.size -> preview
        isMultiPointPreviewLoading && preview.chainPointCount == chainPoints.size - 1 -> preview
        else -> null
    }
}

internal fun PhoneRouteToolsUiState.multiPointDraftConnectorPoints(): List<GeoPoint> {
    if (mode != PhoneRouteCreationMode.MULTI_POINT_CHAIN || isRouting || chainPoints.size < 2) return emptyList()
    val preview = visibleMultiPointRoutePreview()
    if (preview?.chainPointCount == chainPoints.size) return emptyList()
    val previewPointCount = preview?.chainPointCount
    return if (isMultiPointPreviewLoading || previewPointCount == null || previewPointCount < chainPoints.size) {
        chainPoints.takeLast(2)
    } else {
        emptyList()
    }
}

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
            PhoneRouteCreationMode.MULTI_POINT_CHAIN -> chainPoints.size >= 2 && !isMultiPointPreviewLoading
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
    return buildPhoneTrackSelectionPreview(trailPoints, settings, generalSettings)
}

internal fun buildPhoneRoutedRouteSelectionPreview(
    points: List<TrailPoint>,
    settings: PhoneMapGpxSettings,
    generalSettings: PhoneGeneralSettings,
): PhoneRouteSelectionPreview? =
    buildPhoneTrackSelectionPreview(
        trailPoints =
            if (points.all { it.elevationMeters != null }) {
                points
            } else {
                points.map { it.copy(elevationMeters = null) }
            },
        settings = settings,
        generalSettings = generalSettings,
    )

private fun buildPhoneTrackSelectionPreview(
    trailPoints: List<TrailPoint>,
    settings: PhoneMapGpxSettings,
    generalSettings: PhoneGeneralSettings,
): PhoneRouteSelectionPreview? {
    if (trailPoints.size < 2) return null
    val elevationAvailableForEveryPoint = trailPoints.all { it.elevationMeters != null }
    val profile =
        buildTrailRouteProfile(
            trailPoints,
            settings.toTrailPacingConfig(trailPoints, generalSettings),
        )
    return PhoneRouteSelectionPreview(
        distanceMeters = profile.totalDistanceMeters,
        elevationGainMeters = profile.totalAscentMeters.takeIf { elevationAvailableForEveryPoint },
        elevationLossMeters = profile.totalDescentMeters.takeIf { elevationAvailableForEveryPoint },
        estimatedDurationSeconds = profile.estimatedDurationSeconds,
    )
}
