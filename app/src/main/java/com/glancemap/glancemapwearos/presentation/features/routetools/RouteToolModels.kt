package com.glancemap.glancemapwearos.presentation.features.routetools

import android.content.res.Resources
import androidx.annotation.StringRes
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.routing.HikeRouteProfileParams
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPosition
import org.mapsforge.core.model.LatLong

internal const val ROUTE_TOOL_TRACK_END_SENTINEL_SEGMENT_INDEX = -1

internal enum class RouteToolKind(
    @StringRes val titleRes: Int,
) {
    CREATE(titleRes = R.string.gpx_create),
    MODIFY(titleRes = R.string.gpx_modify),
}

internal enum class RouteCreateMode(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
) {
    CURRENT_TO_HERE(
        titleRes = R.string.gpx_select_a_point,
        summaryRes = R.string.gpx_create_a_new_gpx_from_current_location_to_the_selected_destination,
    ),
    MULTI_POINT_CHAIN(
        titleRes = R.string.gpx_multi_point,
        summaryRes = R.string.gpx_pick_one_point_then_another_to_build_a_route_section_by_section,
    ),
    ACTIVE_GPX_END_TO_HERE(
        titleRes = R.string.gpx_extend_to_here,
        summaryRes = R.string.gpx_extend_the_active_gpx_from_its_end_to_the_crosshair,
    ),
    POINT_A_TO_B(
        titleRes = R.string.gpx_two_points,
        summaryRes = R.string.gpx_pick_a_start_and_end_point_on_the_map,
    ),
    SEARCH(
        titleRes = R.string.gpx_search_poi,
        summaryRes = R.string.gpx_search_enabled_offline_pois_and_create_a_route_to_a_result,
    ),
    COORDINATES(
        titleRes = R.string.navigate_coordinates,
        summaryRes = R.string.gpx_set_precise_start_and_destination_coordinates,
    ),
    LOOP_AROUND_HERE(
        titleRes = R.string.gpx_loop_route,
        summaryRes = R.string.gpx_create_a_round_trip_with_a_target_distance_or_time,
    ),
}

internal enum class RouteModifyMode(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
) {
    RESHAPE_ROUTE(
        titleRes = R.string.gpx_reshape_route,
        summaryRes = R.string.gpx_select_a_route_point_then_select_the_replacement_point_to_recalculate,
    ),
    REPLACE_SECTION_A_TO_B(
        titleRes = R.string.gpx_replace_a_b,
        summaryRes = R.string.gpx_replace_the_selected_section_of_the_active_gpx_with_a_newly_routed_seg,
    ),
    KEEP_ONLY_A_TO_B(
        titleRes = R.string.gpx_keep_a_b,
        summaryRes = R.string.gpx_keep_only_the_selected_section_of_the_active_gpx,
    ),
    TRIM_START_TO_HERE(
        titleRes = R.string.gpx_change_start,
        summaryRes = R.string.gpx_move_the_active_gpx_start_forward_to_the_picked_point,
    ),
    TRIM_END_FROM_HERE(
        titleRes = R.string.gpx_change_end,
        summaryRes = R.string.gpx_move_the_active_gpx_end_back_to_the_picked_point,
    ),
    REVERSE_GPX(
        titleRes = R.string.gpx_reverse_gpx,
        summaryRes = R.string.gpx_reverse_the_active_gpx_so_the_end_becomes_the_start,
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
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
) {
    BALANCED_HIKE(
        titleRes = R.string.gpx_balanced_hike,
        summaryRes = R.string.gpx_good_default_for_most_hikes_with_balanced_path_and_difficulty_choices,
    ),
    PREFER_TRAILS(
        titleRes = R.string.gpx_prefer_trails,
        summaryRes = R.string.gpx_favors_paths_marked_hiking_routes_and_more_natural_feeling_terrain,
    ),
    PREFER_EASIEST(
        titleRes = R.string.gpx_prefer_easiest,
        summaryRes = R.string.gpx_avoids_harder_terrain_when_possible_and_prefers_simpler_route_choices,
    ),
    CUSTOM_HIKE(
        titleRes = R.string.gpx_custom_hike,
        summaryRes = R.string.gpx_uses_your_advanced_hike_routing_choices,
    ),
    BIKE_TOURING(
        titleRes = R.string.gpx_touring_bike,
        summaryRes = R.string.gpx_uses_brouter_trekking_routing_for_efficient_calmer_bike_touring,
    ),
    BIKE_ROAD(
        titleRes = R.string.gpx_roadbike,
        summaryRes = R.string.gpx_uses_brouter_fastbike_routing_for_faster_road_cycling,
    ),
    BIKE_QUIET_ROAD(
        titleRes = R.string.gpx_roadbike_low_traffic,
        summaryRes = R.string.gpx_uses_brouter_fastbike_low_traffic_routing_for_quieter_roads,
    ),
    BIKE_GRAVEL(
        titleRes = R.string.gpx_gravel,
        summaryRes = R.string.gpx_uses_brouter_gravel_routing_for_mixed_paved_and_unpaved_surfaces,
    ),
    BIKE_MTB(
        titleRes = R.string.gpx_mtb,
        summaryRes = R.string.gpx_uses_brouter_mtb_routing_for_mountain_bike_oriented_routes,
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

internal fun routeStylePickerOptionsFor(selected: RouteStylePreset): List<Pair<RouteStylePreset, Int>> =
    if (selected in bikeRouteStylePresets) {
        bikeRouteStylePresets
    } else {
        hikeRouteStylePresets
    }.map { preset -> preset to preset.titleRes }

internal val routeStyleSettingsOptions: List<Pair<String, Int>> =
    RouteStylePreset.entries.map { preset -> preset.settingsValue to preset.titleRes }

internal fun routeStyleSettingsOptionsForActivityProfile(activityProfile: String): List<Pair<String, Int>> =
    if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
        bikeRouteStylePresets
    } else {
        hikeRouteStylePresets
    }.map { preset -> preset.settingsValue to preset.titleRes }

internal fun routeStylePresetFromSettingsValue(value: String): RouteStylePreset = RouteStylePreset.entries.firstOrNull { it.settingsValue == value } ?: RouteStylePreset.BALANCED_HIKE

internal fun routeStylePresetFromSavedName(name: String): RouteStylePreset =
    when (name) {
        "BIKE" -> RouteStylePreset.BIKE_TOURING
        else -> RouteStylePreset.entries.firstOrNull { it.name == name } ?: RouteStylePreset.BALANCED_HIKE
    }

@StringRes
internal fun routeStyleTitleForSettingsValue(
    value: String,
): Int = routeStyleSettingsOptions.firstOrNull { it.first == value }?.second ?: RouteStylePreset.BALANCED_HIKE.titleRes

internal enum class RouteSaveBehavior(
    @StringRes val titleRes: Int,
) {
    SAVE_AS_NEW(titleRes = R.string.gpx_save_as_new),
    REPLACE_CURRENT(titleRes = R.string.gpx_replace_current),
}

internal enum class LoopStartMode(
    @StringRes val titleRes: Int,
) {
    CURRENT_LOCATION(titleRes = R.string.gpx_current),
    PICK_ON_MAP(titleRes = R.string.navigate_pick_on_map),
}

internal enum class RouteEndpointSource(
    @StringRes val titleRes: Int,
) {
    CURRENT_LOCATION(titleRes = R.string.gpx_current),
    COORDINATES(titleRes = R.string.navigate_coordinates),
}

internal enum class LoopTargetMode(
    @StringRes val titleRes: Int,
) {
    DISTANCE(titleRes = R.string.common_distance),
    TIME(titleRes = R.string.common_time),
}

internal enum class LoopShapeMode(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
) {
    PREFER_CIRCUIT(
        titleRes = R.string.gpx_prefer_circuit,
        summaryRes = R.string.gpx_try_to_avoid_using_the_same_path_twice,
    ),
    ALLOW_OUT_AND_BACK(
        titleRes = R.string.gpx_allow_out_and_back,
        summaryRes = R.string.gpx_allow_same_path_fallback_if_no_better_loop_exists,
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
    @get:StringRes val activeSummaryRes: Int
        get() =
            when (toolKind) {
                RouteToolKind.CREATE -> createMode.summaryRes
                RouteToolKind.MODIFY -> modifyMode.summaryRes
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

internal fun RouteToolOptions.coordinateEndpointValidationMessage(
    currentLocation: LatLong?,
    resources: Resources,
): String? {
    val endpointMessage =
        when {
            startEndpointSource == RouteEndpointSource.CURRENT_LOCATION &&
                destinationEndpointSource == RouteEndpointSource.CURRENT_LOCATION ->
                resources.getString(R.string.gpx_choose_coordinates_for_start_or_destination)
            startEndpointSource == RouteEndpointSource.COORDINATES &&
                !isValidRouteToolCoordinate(startCoordinateLatitude, startCoordinateLongitude) ->
                resources.getString(R.string.gpx_enter_valid_start_coordinates)
            destinationEndpointSource == RouteEndpointSource.COORDINATES &&
                !isValidRouteToolCoordinate(destinationCoordinateLatitude, destinationCoordinateLongitude) ->
                resources.getString(R.string.gpx_enter_valid_destination_coordinates)
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
        resources.getString(R.string.gpx_start_and_destination_must_be_different)
    } else {
        null
    }
}

internal fun RouteToolOptions.resolveCoordinateEndpoints(
    currentLocation: LatLong?,
    resources: Resources,
): RouteToolResolvedEndpoints {
    require(coordinateEndpointValidationMessage(currentLocation, resources = resources) == null) {
        coordinateEndpointValidationMessage(currentLocation, resources = resources)
            ?: resources.getString(R.string.gpx_enter_valid_route_endpoints)
    }
    return RouteToolResolvedEndpoints(
        origin =
            when (startEndpointSource) {
                RouteEndpointSource.CURRENT_LOCATION ->
                    requireNotNull(currentLocation) {
                        resources.getString(R.string.gpx_wait_for_a_fresh_gps_fix_before_using_current_location)
                    }
                RouteEndpointSource.COORDINATES ->
                    requireNotNull(routeToolLatLong(startCoordinateLatitude, startCoordinateLongitude)) {
                        resources.getString(R.string.gpx_enter_valid_start_coordinates)
                    }
            },
        destination =
            when (destinationEndpointSource) {
                RouteEndpointSource.CURRENT_LOCATION ->
                    requireNotNull(currentLocation) {
                        resources.getString(R.string.gpx_wait_for_a_fresh_gps_fix_before_using_current_location)
                    }
                RouteEndpointSource.COORDINATES ->
                    requireNotNull(routeToolLatLong(destinationCoordinateLatitude, destinationCoordinateLongitude)) {
                        resources.getString(R.string.gpx_enter_valid_destination_coordinates)
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
    @StringRes val titleRes: Int,
) {
    RESHAPE_POINT(titleRes = R.string.gpx_route_point),
    TRACK_INSERTION(titleRes = R.string.gpx_route_2),
    DESTINATION(titleRes = R.string.gpx_destination),
    POINT_A(titleRes = R.string.gpx_point_a),
    POINT_B(titleRes = R.string.gpx_point_b),
    LOOP_CENTER(titleRes = R.string.gpx_loop_center),
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

    @get:StringRes val modeTitleRes: Int
        get() =
            when (options.toolKind) {
                RouteToolKind.CREATE -> options.createMode.titleRes
                RouteToolKind.MODIFY -> options.modifyMode.titleRes
            }

    @get:StringRes val toolTitleRes: Int
        get() = options.toolKind.titleRes

    @get:StringRes val instructionTextRes: Int
        get() =
            when (currentSelectionTarget) {
                RouteSelectionTarget.RESHAPE_POINT -> R.string.gpx_pick_point_to_move
                RouteSelectionTarget.DESTINATION ->
                    when {
                        isMultiPointCreate && chainPoints.isEmpty() -> R.string.gpx_place_start_then_check
                        isMultiPointCreate && chainPoints.size == 1 -> R.string.gpx_place_next_point_then_check
                        isMultiPointCreate -> R.string.gpx_add_point_then_check
                        options.toolKind == RouteToolKind.MODIFY &&
                            options.modifyMode == RouteModifyMode.RESHAPE_ROUTE -> {
                            R.string.gpx_pick_new_point
                        }

                        else -> {
                            R.string.gpx_place_destination_then_check
                        }
                    }
                RouteSelectionTarget.TRACK_INSERTION -> R.string.gpx_tap_the_gpx
                RouteSelectionTarget.POINT_A ->
                    if (
                        options.toolKind == RouteToolKind.CREATE &&
                        options.createMode == RouteCreateMode.LOOP_AROUND_HERE
                    ) {
                        R.string.gpx_place_start_then_check
                    } else if (options.toolKind == RouteToolKind.CREATE) {
                        R.string.gpx_place_start_then_check
                    } else if (
                        options.toolKind == RouteToolKind.MODIFY &&
                        options.modifyMode == RouteModifyMode.TRIM_START_TO_HERE
                    ) {
                        R.string.gpx_pick_new_start
                    } else {
                        R.string.gpx_place_a_then_check
                    }
                RouteSelectionTarget.POINT_B ->
                    if (options.toolKind == RouteToolKind.CREATE) {
                        R.string.gpx_place_end_then_check
                    } else if (
                        options.toolKind == RouteToolKind.MODIFY &&
                        options.modifyMode == RouteModifyMode.TRIM_END_FROM_HERE
                    ) {
                        R.string.gpx_pick_new_end
                    } else {
                        R.string.gpx_place_b_then_check
                    }
                RouteSelectionTarget.LOOP_CENTER -> R.string.gpx_place_loop_center_then_check
                null -> R.string.gpx_ready
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

internal fun formatLoopDuration(
    minutes: Int,
    resources: Resources,
): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours <= 0 -> resources.getString(R.string.gpx_min, mins)
        mins == 0 -> resources.getString(R.string.gpx_h, hours)
        else -> resources.getString(R.string.gpx_h_2, hours, mins)
    }
}
