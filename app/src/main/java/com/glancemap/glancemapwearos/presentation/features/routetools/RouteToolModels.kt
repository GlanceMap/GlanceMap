package com.glancemap.glancemapwearos.presentation.features.routetools

import com.glancemap.glancemapwearos.core.routing.HikeRouteProfileParams
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPosition
import org.mapsforge.core.model.LatLong

internal const val ROUTE_TOOL_TRACK_END_SENTINEL_SEGMENT_INDEX = -1

internal enum class RouteToolKind(
    val title: String,
) {
    CREATE(title = "Create"),
    MODIFY(title = "Modify"),
}

internal enum class RouteCreateMode(
    val title: String,
    val summary: String,
) {
    CURRENT_TO_HERE(
        title = "Select a point",
        summary = "Create a new GPX from current location to the selected destination.",
    ),
    MULTI_POINT_CHAIN(
        title = "Multi-point",
        summary = "Pick one point, then another, to build a route section by section.",
    ),
    ACTIVE_GPX_END_TO_HERE(
        title = "Extend to here",
        summary = "Extend the active GPX from its end to the crosshair.",
    ),
    POINT_A_TO_B(
        title = "Two points",
        summary = "Pick a start and end point on the map.",
    ),
    SEARCH(
        title = "Search POI",
        summary = "Search enabled offline POIs and create a route to a result.",
    ),
    COORDINATES(
        title = "Coordinates",
        summary = "Set precise start and destination coordinates.",
    ),
    LOOP_AROUND_HERE(
        title = "Loop route",
        summary = "Create a round trip with a target distance or time.",
    ),
}

internal enum class RouteModifyMode(
    val title: String,
    val summary: String,
) {
    RESHAPE_ROUTE(
        title = "Reshape route",
        summary = "Select a route point, then select the replacement point to recalculate the route.",
    ),
    REPLACE_SECTION_A_TO_B(
        title = "Replace A-B",
        summary = "Replace the selected section of the active GPX with a newly routed segment.",
    ),
    KEEP_ONLY_A_TO_B(
        title = "Keep A-B",
        summary = "Keep only the selected section of the active GPX.",
    ),
    TRIM_START_TO_HERE(
        title = "Change start",
        summary = "Move the active GPX start forward to the picked point.",
    ),
    TRIM_END_FROM_HERE(
        title = "Change end",
        summary = "Move the active GPX end back to the picked point.",
    ),
    REVERSE_GPX(
        title = "Reverse GPX",
        summary = "Reverse the active GPX so the end becomes the start.",
    ),
}

internal val RouteModifyMode.previewBeforeSaving: Boolean
    get() =
        when (this) {
            RouteModifyMode.RESHAPE_ROUTE,
            RouteModifyMode.TRIM_START_TO_HERE,
            RouteModifyMode.TRIM_END_FROM_HERE,
            -> true

            RouteModifyMode.REPLACE_SECTION_A_TO_B,
            RouteModifyMode.KEEP_ONLY_A_TO_B,
            RouteModifyMode.REVERSE_GPX,
            -> false
        }

internal enum class RouteStylePreset(
    val title: String,
    val summary: String,
) {
    BALANCED_HIKE(
        title = "Balanced hike",
        summary = "Good default for most hikes, with balanced path and difficulty choices.",
    ),
    PREFER_TRAILS(
        title = "Prefer trails",
        summary = "Favors paths, marked hiking routes, and more natural-feeling terrain.",
    ),
    PREFER_EASIEST(
        title = "Prefer easiest",
        summary = "Avoids harder terrain when possible and prefers simpler route choices.",
    ),
    CUSTOM_HIKE(
        title = "Custom hike",
        summary = "Uses your advanced hike routing choices.",
    ),
    BIKE_TOURING(
        title = "Touring bike",
        summary = "Uses BRouter trekking routing for efficient, calmer bike touring.",
    ),
    BIKE_ROAD(
        title = "Roadbike",
        summary = "Uses BRouter fastbike routing for faster road cycling.",
    ),
    BIKE_QUIET_ROAD(
        title = "Roadbike Low-traffic",
        summary = "Uses BRouter fastbike low-traffic routing for quieter roads.",
    ),
    BIKE_GRAVEL(
        title = "Gravel",
        summary = "Uses BRouter gravel routing for mixed paved and unpaved surfaces.",
    ),
    BIKE_MTB(
        title = "MTB",
        summary = "Uses BRouter MTB routing for mountain-bike oriented routes.",
    ),
}

internal val RouteStylePreset.settingsValue: String
    get() =
        when (this) {
            RouteStylePreset.BALANCED_HIKE -> SettingsRepository.GPX_TOOL_ROUTE_STYLE_BALANCED_HIKE
            RouteStylePreset.PREFER_TRAILS -> SettingsRepository.GPX_TOOL_ROUTE_STYLE_PREFER_TRAILS
            RouteStylePreset.PREFER_EASIEST -> SettingsRepository.GPX_TOOL_ROUTE_STYLE_PREFER_EASIEST
            RouteStylePreset.CUSTOM_HIKE -> SettingsRepository.GPX_TOOL_ROUTE_STYLE_CUSTOM_HIKE
            RouteStylePreset.BIKE_TOURING -> SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_TOURING
            RouteStylePreset.BIKE_ROAD -> SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_ROAD
            RouteStylePreset.BIKE_QUIET_ROAD -> SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_QUIET_ROAD
            RouteStylePreset.BIKE_GRAVEL -> SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_GRAVEL
            RouteStylePreset.BIKE_MTB -> SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_MTB
        }

internal val hikeRouteStylePresets: List<RouteStylePreset> =
    listOf(
        RouteStylePreset.BALANCED_HIKE,
        RouteStylePreset.PREFER_TRAILS,
        RouteStylePreset.PREFER_EASIEST,
        RouteStylePreset.CUSTOM_HIKE,
    )

internal val bikeRouteStylePresets: List<RouteStylePreset> =
    listOf(
        RouteStylePreset.BIKE_TOURING,
        RouteStylePreset.BIKE_ROAD,
        RouteStylePreset.BIKE_QUIET_ROAD,
        RouteStylePreset.BIKE_GRAVEL,
        RouteStylePreset.BIKE_MTB,
    )

internal fun routeStylePickerOptionsFor(selected: RouteStylePreset): List<Pair<RouteStylePreset, String>> =
    if (selected in bikeRouteStylePresets) {
        bikeRouteStylePresets
    } else {
        hikeRouteStylePresets
    }.map { preset -> preset to preset.title }

internal val routeStyleSettingsOptions: List<Pair<String, String>> =
    RouteStylePreset.entries.map { preset -> preset.settingsValue to preset.title }

internal fun routeStyleSettingsOptionsForActivityProfile(activityProfile: String): List<Pair<String, String>> =
    if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
        bikeRouteStylePresets
    } else {
        hikeRouteStylePresets
    }.map { preset -> preset.settingsValue to preset.title }

internal fun routeStylePresetFromSettingsValue(value: String): RouteStylePreset = RouteStylePreset.entries.firstOrNull { it.settingsValue == value } ?: RouteStylePreset.BALANCED_HIKE

internal fun routeStylePresetFromSavedName(name: String): RouteStylePreset =
    when (name) {
        "BIKE" -> RouteStylePreset.BIKE_TOURING
        else -> RouteStylePreset.entries.firstOrNull { it.name == name } ?: RouteStylePreset.BALANCED_HIKE
    }

internal fun routeStyleTitleForSettingsValue(value: String): String = routeStyleSettingsOptions.firstOrNull { it.first == value }?.second ?: RouteStylePreset.BALANCED_HIKE.title

internal enum class RouteSaveBehavior(
    val title: String,
) {
    SAVE_AS_NEW(title = "Save as new"),
    REPLACE_CURRENT(title = "Replace current"),
}

internal enum class LoopStartMode(
    val title: String,
) {
    CURRENT_LOCATION(title = "Current"),
    PICK_ON_MAP(title = "Pick on map"),
}

internal enum class RouteEndpointSource(
    val title: String,
) {
    CURRENT_LOCATION(title = "Current"),
    COORDINATES(title = "Coordinates"),
}

internal enum class LoopTargetMode(
    val title: String,
) {
    DISTANCE(title = "Distance"),
    TIME(title = "Time"),
}

internal enum class LoopShapeMode(
    val title: String,
    val summary: String,
) {
    PREFER_CIRCUIT(
        title = "Prefer circuit",
        summary = "Try to avoid using the same path twice.",
    ),
    ALLOW_OUT_AND_BACK(
        title = "Allow out-and-back",
        summary = "Allow same-path fallback if no better loop exists.",
    ),
}

internal data class RouteToolOptions(
    val toolKind: RouteToolKind = RouteToolKind.CREATE,
    val createMode: RouteCreateMode = RouteCreateMode.CURRENT_TO_HERE,
    val modifyMode: RouteModifyMode = RouteModifyMode.RESHAPE_ROUTE,
    val routeStyle: RouteStylePreset = RouteStylePreset.BALANCED_HIKE,
    val loopTargetMode: LoopTargetMode = LoopTargetMode.DISTANCE,
    val loopDistanceKm: Int = 10,
    val loopDurationMinutes: Int = 120,
    val loopShapeMode: LoopShapeMode = LoopShapeMode.PREFER_CIRCUIT,
    val loopStartMode: LoopStartMode = LoopStartMode.CURRENT_LOCATION,
    val startEndpointSource: RouteEndpointSource = RouteEndpointSource.CURRENT_LOCATION,
    val startCoordinateLatitude: Double? = null,
    val startCoordinateLongitude: Double? = null,
    val destinationEndpointSource: RouteEndpointSource = RouteEndpointSource.COORDINATES,
    val destinationCoordinateLatitude: Double? = null,
    val destinationCoordinateLongitude: Double? = null,
    val useElevation: Boolean = true,
    val allowFerries: Boolean = false,
    val customHikeParams: HikeRouteProfileParams? = null,
    val showAdvancedOptions: Boolean = false,
    val saveBehavior: RouteSaveBehavior = RouteSaveBehavior.SAVE_AS_NEW,
) {
    val activeSummary: String
        get() =
            when (toolKind) {
                RouteToolKind.CREATE -> createMode.summary
                RouteToolKind.MODIFY -> modifyMode.summary
            }

    val requiresSingleActiveGpx: Boolean
        get() =
            when (toolKind) {
                RouteToolKind.CREATE -> createMode == RouteCreateMode.ACTIVE_GPX_END_TO_HERE
                RouteToolKind.MODIFY -> true
            }

    val requiresRoutingData: Boolean
        get() =
            when (toolKind) {
                RouteToolKind.CREATE -> true
                RouteToolKind.MODIFY ->
                    when (modifyMode) {
                        RouteModifyMode.RESHAPE_ROUTE,
                        RouteModifyMode.REPLACE_SECTION_A_TO_B,
                        -> true
                        RouteModifyMode.KEEP_ONLY_A_TO_B,
                        RouteModifyMode.TRIM_START_TO_HERE,
                        RouteModifyMode.TRIM_END_FROM_HERE,
                        RouteModifyMode.REVERSE_GPX,
                        -> false
                    }
            }
}

internal fun RouteToolOptions.withVisibleLoopDefaults(): RouteToolOptions = copy(loopShapeMode = LoopShapeMode.PREFER_CIRCUIT)

internal data class RouteToolResolvedEndpoints(
    val origin: LatLong,
    val destination: LatLong,
)

internal fun RouteToolOptions.seedCoordinateEndpoints(seed: LatLong?): RouteToolOptions {
    if (seed == null) return this
    var seeded = this
    if (
        startEndpointSource == RouteEndpointSource.COORDINATES &&
        startCoordinateLatitude == null &&
        startCoordinateLongitude == null
    ) {
        seeded =
            seeded.copy(
                startCoordinateLatitude = seed.latitude,
                startCoordinateLongitude = seed.longitude,
            )
    }
    if (
        destinationEndpointSource == RouteEndpointSource.COORDINATES &&
        destinationCoordinateLatitude == null &&
        destinationCoordinateLongitude == null
    ) {
        seeded =
            seeded.copy(
                destinationCoordinateLatitude = seed.latitude,
                destinationCoordinateLongitude = seed.longitude,
            )
    }
    return seeded
}

internal fun RouteToolOptions.coordinateEndpointValidationMessage(currentLocation: LatLong?): String? {
    val endpointMessage =
        when {
            startEndpointSource == RouteEndpointSource.CURRENT_LOCATION &&
                destinationEndpointSource == RouteEndpointSource.CURRENT_LOCATION ->
                "Choose coordinates for start or destination."
            startEndpointSource == RouteEndpointSource.COORDINATES &&
                !isValidRouteToolCoordinate(startCoordinateLatitude, startCoordinateLongitude) ->
                "Enter valid start coordinates."
            destinationEndpointSource == RouteEndpointSource.COORDINATES &&
                !isValidRouteToolCoordinate(destinationCoordinateLatitude, destinationCoordinateLongitude) ->
                "Enter valid destination coordinates."
            else -> null
        }
    if (endpointMessage != null) return endpointMessage

    val endpoints =
        listOfNotNull(
            if (startEndpointSource == RouteEndpointSource.CURRENT_LOCATION) {
                currentLocation
            } else {
                routeToolLatLong(startCoordinateLatitude, startCoordinateLongitude)
            },
            if (destinationEndpointSource == RouteEndpointSource.CURRENT_LOCATION) {
                currentLocation
            } else {
                routeToolLatLong(destinationCoordinateLatitude, destinationCoordinateLongitude)
            },
        )
    return if (endpoints.size == 2 && sameRouteToolEndpointLocation(endpoints[0], endpoints[1])) {
        "Start and destination must be different."
    } else {
        null
    }
}

internal fun RouteToolOptions.resolveCoordinateEndpoints(currentLocation: LatLong?): RouteToolResolvedEndpoints {
    require(coordinateEndpointValidationMessage(currentLocation) == null) {
        coordinateEndpointValidationMessage(currentLocation) ?: "Enter valid route endpoints."
    }
    return RouteToolResolvedEndpoints(
        origin =
            when (startEndpointSource) {
                RouteEndpointSource.CURRENT_LOCATION ->
                    requireNotNull(currentLocation) {
                        "Wait for a fresh GPS fix before using current location."
                    }
                RouteEndpointSource.COORDINATES ->
                    requireNotNull(routeToolLatLong(startCoordinateLatitude, startCoordinateLongitude)) {
                        "Enter valid start coordinates."
                    }
            },
        destination =
            when (destinationEndpointSource) {
                RouteEndpointSource.CURRENT_LOCATION ->
                    requireNotNull(currentLocation) {
                        "Wait for a fresh GPS fix before using current location."
                    }
                RouteEndpointSource.COORDINATES ->
                    requireNotNull(routeToolLatLong(destinationCoordinateLatitude, destinationCoordinateLongitude)) {
                        "Enter valid destination coordinates."
                    }
            },
    )
}

internal fun isValidRouteToolCoordinate(
    latitude: Double?,
    longitude: Double?,
): Boolean =
    latitude != null &&
        longitude != null &&
        latitude.isFinite() &&
        longitude.isFinite() &&
        latitude in -90.0..90.0 &&
        longitude in -180.0..180.0

private fun routeToolLatLong(
    latitude: Double?,
    longitude: Double?,
): LatLong? =
    if (isValidRouteToolCoordinate(latitude, longitude)) {
        LatLong(latitude!!, longitude!!)
    } else {
        null
    }

internal fun visibleRouteToolCreatePreview(
    session: RouteToolSession?,
    createPreview: RouteToolCreatePreview?,
    createPreviewInProgress: Boolean,
): RouteToolCreatePreview? {
    val current = session?.takeIf { it.isMultiPointCreate }
    return when {
        createPreview == null -> null
        current == null -> createPreview
        current.chainPoints.size < 2 -> null
        else -> {
            val chainSize = current.chainPoints.size
            val previewChainSize = createPreview.multiPointChainPointCount
            when {
                previewChainSize == chainSize -> createPreview
                createPreviewInProgress && previewChainSize == chainSize - 1 -> createPreview
                else -> null
            }
        }
    }
}

internal fun routeToolMultiPointDraftConnectorPoints(
    session: RouteToolSession?,
    visibleCreatePreview: RouteToolCreatePreview?,
    createPreviewInProgress: Boolean,
): List<LatLong> {
    val current = session?.takeIf { it.isMultiPointCreate }
    return when {
        current == null -> emptyList()
        current.chainPoints.size < 2 -> emptyList()
        visibleCreatePreview?.multiPointChainPointCount == current.chainPoints.size -> emptyList()
        else -> {
            val previewChainSize = visibleCreatePreview?.multiPointChainPointCount
            if (createPreviewInProgress || previewChainSize == null || previewChainSize < current.chainPoints.size) {
                current.chainPoints.takeLast(2)
            } else {
                emptyList()
            }
        }
    }
}

internal enum class RouteSelectionTarget(
    val title: String,
) {
    RESHAPE_POINT(title = "route point"),
    TRACK_INSERTION(title = "route"),
    DESTINATION(title = "destination"),
    POINT_A(title = "point A"),
    POINT_B(title = "point B"),
    LOOP_CENTER(title = "loop center"),
}

internal data class RouteToolSession(
    val options: RouteToolOptions,
    val pointA: LatLong? = null,
    val pointB: LatLong? = null,
    val pointATrackPosition: TrackPosition? = null,
    val pointBTrackPosition: TrackPosition? = null,
    val destination: LatLong? = null,
    val loopCenter: LatLong? = null,
    val chainPoints: List<LatLong> = emptyList(),
    val loopVariationIndex: Int = 0,
) {
    val isMultiPointCreate: Boolean
        get() =
            options.toolKind == RouteToolKind.CREATE &&
                options.createMode == RouteCreateMode.MULTI_POINT_CHAIN

    val currentSelectionTarget: RouteSelectionTarget?
        get() =
            when (options.toolKind) {
                RouteToolKind.CREATE ->
                    when (options.createMode) {
                        RouteCreateMode.CURRENT_TO_HERE -> {
                            if (destination == null) RouteSelectionTarget.DESTINATION else null
                        }

                        RouteCreateMode.MULTI_POINT_CHAIN -> RouteSelectionTarget.DESTINATION

                        RouteCreateMode.ACTIVE_GPX_END_TO_HERE -> {
                            if (destination == null) RouteSelectionTarget.DESTINATION else null
                        }

                        RouteCreateMode.POINT_A_TO_B ->
                            when {
                                pointA == null -> RouteSelectionTarget.POINT_A
                                pointB == null -> RouteSelectionTarget.POINT_B
                                else -> null
                            }

                        RouteCreateMode.SEARCH -> null

                        RouteCreateMode.COORDINATES -> null

                        RouteCreateMode.LOOP_AROUND_HERE ->
                            when (options.loopStartMode) {
                                LoopStartMode.CURRENT_LOCATION -> null
                                LoopStartMode.PICK_ON_MAP -> {
                                    if (pointA == null) RouteSelectionTarget.POINT_A else null
                                }
                            }
                    }

                RouteToolKind.MODIFY ->
                    when (options.modifyMode) {
                        RouteModifyMode.RESHAPE_ROUTE -> {
                            when {
                                pointA == null -> RouteSelectionTarget.RESHAPE_POINT
                                destination == null -> RouteSelectionTarget.DESTINATION
                                else -> null
                            }
                        }

                        RouteModifyMode.REPLACE_SECTION_A_TO_B ->
                            when {
                                pointA == null -> RouteSelectionTarget.POINT_A
                                pointB == null -> RouteSelectionTarget.POINT_B
                                else -> null
                            }

                        RouteModifyMode.KEEP_ONLY_A_TO_B ->
                            when {
                                pointA == null -> RouteSelectionTarget.POINT_A
                                pointB == null -> RouteSelectionTarget.POINT_B
                                else -> null
                            }

                        RouteModifyMode.TRIM_START_TO_HERE -> {
                            if (pointA == null) RouteSelectionTarget.POINT_A else null
                        }

                        RouteModifyMode.TRIM_END_FROM_HERE -> {
                            if (pointB == null) RouteSelectionTarget.POINT_B else null
                        }

                        RouteModifyMode.REVERSE_GPX -> null
                    }
            }

    val isComplete: Boolean
        get() = !isMultiPointCreate && currentSelectionTarget == null

    val modeTitle: String
        get() =
            when (options.toolKind) {
                RouteToolKind.CREATE -> options.createMode.title
                RouteToolKind.MODIFY -> options.modifyMode.title
            }

    val toolTitle: String
        get() = options.toolKind.title

    val instructionText: String
        get() =
            when (currentSelectionTarget) {
                RouteSelectionTarget.RESHAPE_POINT -> "Pick point to move"
                RouteSelectionTarget.DESTINATION ->
                    when {
                        isMultiPointCreate && chainPoints.isEmpty() -> "Place start, then check."
                        isMultiPointCreate && chainPoints.size == 1 -> "Place next point, then check."
                        isMultiPointCreate -> "Add point, then check."
                        options.toolKind == RouteToolKind.MODIFY &&
                            options.modifyMode == RouteModifyMode.RESHAPE_ROUTE -> {
                            "Pick new point"
                        }

                        else -> {
                            "Place destination, then check."
                        }
                    }
                RouteSelectionTarget.TRACK_INSERTION -> "Tap the GPX."
                RouteSelectionTarget.POINT_A ->
                    if (
                        options.toolKind == RouteToolKind.CREATE &&
                        options.createMode == RouteCreateMode.LOOP_AROUND_HERE
                    ) {
                        "Place start, then check."
                    } else if (options.toolKind == RouteToolKind.CREATE) {
                        "Place start, then check."
                    } else if (
                        options.toolKind == RouteToolKind.MODIFY &&
                        options.modifyMode == RouteModifyMode.TRIM_START_TO_HERE
                    ) {
                        "Pick new start"
                    } else {
                        "Place A, then check."
                    }
                RouteSelectionTarget.POINT_B ->
                    if (options.toolKind == RouteToolKind.CREATE) {
                        "Place end, then check."
                    } else if (
                        options.toolKind == RouteToolKind.MODIFY &&
                        options.modifyMode == RouteModifyMode.TRIM_END_FROM_HERE
                    ) {
                        "Pick new end"
                    } else {
                        "Place B, then check."
                    }
                RouteSelectionTarget.LOOP_CENTER -> "Place loop center, then check."
                null -> "Ready."
            }

    fun captureSelection(point: LatLong): RouteToolSession =
        when (currentSelectionTarget) {
            RouteSelectionTarget.RESHAPE_POINT -> copy(pointA = point)
            RouteSelectionTarget.TRACK_INSERTION -> this
            RouteSelectionTarget.DESTINATION -> {
                if (isMultiPointCreate) {
                    appendChainPoint(point)
                } else {
                    copy(destination = point, loopVariationIndex = 0)
                }
            }
            RouteSelectionTarget.POINT_A ->
                copy(
                    pointA = point,
                    pointATrackPosition =
                        if (
                            options.toolKind == RouteToolKind.MODIFY &&
                            options.modifyMode == RouteModifyMode.TRIM_START_TO_HERE
                        ) {
                            routeToolTrackStartPosition()
                        } else {
                            null
                        },
                    loopVariationIndex = 0,
                )

            RouteSelectionTarget.POINT_B ->
                copy(
                    pointB = point,
                    pointBTrackPosition =
                        if (
                            options.toolKind == RouteToolKind.MODIFY &&
                            options.modifyMode == RouteModifyMode.TRIM_END_FROM_HERE
                        ) {
                            routeToolTrackEndPosition()
                        } else {
                            null
                        },
                    loopVariationIndex = 0,
                )

            RouteSelectionTarget.LOOP_CENTER -> copy(loopCenter = point, loopVariationIndex = 0)
            null -> this
        }

    fun appendChainPoint(point: LatLong): RouteToolSession {
        if (!isMultiPointCreate) return this
        val lastPoint = chainPoints.lastOrNull()
        if (lastPoint != null && sameRouteToolLocation(lastPoint, point)) {
            return this
        }
        return copy(chainPoints = chainPoints + point)
    }

    fun removeLastChainPoint(): RouteToolSession {
        if (!isMultiPointCreate || chainPoints.isEmpty()) return this
        return copy(chainPoints = chainPoints.dropLast(1))
    }

    fun captureTrackInsertion(point: LatLong): RouteToolSession =
        if (currentSelectionTarget == RouteSelectionTarget.TRACK_INSERTION) {
            copy(pointA = point)
        } else {
            this
        }

    fun advanceLoopVariation(): RouteToolSession =
        if (
            options.toolKind == RouteToolKind.CREATE &&
            options.createMode == RouteCreateMode.LOOP_AROUND_HERE
        ) {
            copy(loopVariationIndex = loopVariationIndex + 1)
        } else {
            this
        }

    val usesCrosshair: Boolean
        get() = currentSelectionTarget != null
}

private fun sameRouteToolLocation(
    a: LatLong,
    b: LatLong,
): Boolean =
    kotlin.math.abs(a.latitude - b.latitude) < 1e-9 &&
        kotlin.math.abs(a.longitude - b.longitude) < 1e-9

internal fun sameRouteToolEndpointLocation(
    a: LatLong,
    b: LatLong,
): Boolean =
    kotlin.math.abs(a.latitude - b.latitude) <= ROUTE_TOOL_ENDPOINT_LOCATION_EPSILON_DEGREES &&
        kotlin.math.abs(a.longitude - b.longitude) <= ROUTE_TOOL_ENDPOINT_LOCATION_EPSILON_DEGREES

internal const val ROUTE_TOOL_ENDPOINT_LOCATION_EPSILON_DEGREES = 0.00001

internal fun routeToolTrackStartPosition(): TrackPosition =
    TrackPosition(
        trackId = "",
        segmentIndex = 0,
        t = 0.0,
    )

internal fun routeToolTrackEndPosition(): TrackPosition =
    TrackPosition(
        trackId = "",
        segmentIndex = ROUTE_TOOL_TRACK_END_SENTINEL_SEGMENT_INDEX,
        t = 1.0,
    )

internal fun LatLong.toRoutePointLabel(): String = String.format("%.5f, %.5f", latitude, longitude)

internal fun formatLoopDuration(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours <= 0 -> "$mins min"
        mins == 0 -> "$hours h"
        else -> "$hours h $mins"
    }
}
