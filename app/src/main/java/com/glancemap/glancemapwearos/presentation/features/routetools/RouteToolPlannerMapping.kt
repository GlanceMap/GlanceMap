package com.glancemap.glancemapwearos.presentation.features.routetools

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.routing.RoundTripPlannerRequest
import com.glancemap.glancemapwearos.core.routing.RoutePlannerPreset
import com.glancemap.glancemapwearos.core.routing.RoutePlannerRequest
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxEtaModelConfig
import org.mapsforge.core.model.LatLong
import kotlin.math.roundToInt

// Resource-call wrapping increases line count; keep the existing flow together.
@Suppress("LongMethod")
internal fun RouteToolSession.toRoutePlannerRequest(
    currentLocation: LatLong?,
    activeGpxEnd: LatLong? = null,
    resources: Resources,
): RoutePlannerRequest {
    require(options.toolKind == RouteToolKind.CREATE) {
        resources.getString(R.string.gpx_only_create_actions_can_be_mapped_to_the_route_planner)
    }

    return when (options.createMode) {
        RouteCreateMode.CURRENT_TO_HERE ->
            RoutePlannerRequest(
                origin =
                    requireNotNull(currentLocation) {
                        resources.getString(
                            R.string.gpx_wait_for_a_fresh_gps_fix_before_creating_a_route_from_current_location,
                        )
                    },
                destination =
                    requireNotNull(destination) {
                        resources.getString(R.string.gpx_pick_a_destination_first)
                    },
                preset = options.routeStyle.toPlannerPreset(),
                useElevation = options.useElevation,
                allowFerries = options.allowFerries,
                customHikeParams = options.customHikeParams,
            )

        RouteCreateMode.MULTI_POINT_CHAIN -> {
            require(chainPoints.size >= 2) {
                resources.getString(R.string.gpx_pick_at_least_two_points_first)
            }
            RoutePlannerRequest(
                origin = chainPoints.first(),
                destination = chainPoints.last(),
                viaPoints = chainPoints.drop(1).dropLast(1),
                preset = options.routeStyle.toPlannerPreset(),
                useElevation = options.useElevation,
                allowFerries = options.allowFerries,
                customHikeParams = options.customHikeParams,
            )
        }

        RouteCreateMode.ACTIVE_GPX_END_TO_HERE ->
            RoutePlannerRequest(
                origin =
                    requireNotNull(activeGpxEnd) {
                        resources.getString(R.string.gpx_activate_exactly_one_gpx_before_extending_it)
                    },
                destination =
                    requireNotNull(destination) {
                        resources.getString(R.string.gpx_pick_a_destination_first)
                    },
                preset = options.routeStyle.toPlannerPreset(),
                useElevation = options.useElevation,
                allowFerries = options.allowFerries,
                customHikeParams = options.customHikeParams,
            )

        RouteCreateMode.POINT_A_TO_B ->
            RoutePlannerRequest(
                origin =
                    requireNotNull(pointA) {
                        resources.getString(R.string.gpx_pick_point_a_first)
                    },
                destination =
                    requireNotNull(pointB) {
                        resources.getString(R.string.gpx_pick_point_b_first)
                    },
                preset = options.routeStyle.toPlannerPreset(),
                useElevation = options.useElevation,
                allowFerries = options.allowFerries,
                customHikeParams = options.customHikeParams,
            )

        RouteCreateMode.SEARCH ->
            RoutePlannerRequest(
                origin =
                    requireNotNull(currentLocation) {
                        resources.getString(
                            R.string.gpx_wait_for_a_fresh_gps_fix_before_creating_a_route_from_current_location,
                        )
                    },
                destination =
                    requireNotNull(destination) {
                        resources.getString(R.string.gpx_pick_a_poi_first)
                    },
                preset = options.routeStyle.toPlannerPreset(),
                useElevation = options.useElevation,
                allowFerries = options.allowFerries,
                customHikeParams = options.customHikeParams,
            )

        RouteCreateMode.COORDINATES -> {
            val endpoints = options.resolveCoordinateEndpoints(currentLocation, resources = resources)
            RoutePlannerRequest(
                origin = endpoints.origin,
                destination = endpoints.destination,
                preset = options.routeStyle.toPlannerPreset(),
                useElevation = options.useElevation,
                allowFerries = options.allowFerries,
                customHikeParams = options.customHikeParams,
            )
        }

        RouteCreateMode.LOOP_AROUND_HERE -> {
            throw IllegalArgumentException(resources.getString(R.string.gpx_loop_creation_uses_the_round_trip_planner))
        }
    }
}

internal fun RouteToolSession.toRoundTripPlannerRequest(
    currentLocation: LatLong?,
    etaModelConfig: GpxEtaModelConfig? = null,
    targetDistanceMetersOverride: Int? = null,
    resources: Resources,
): RoundTripPlannerRequest {
    require(options.toolKind == RouteToolKind.CREATE) {
        resources.getString(R.string.gpx_only_create_actions_can_be_mapped_to_the_route_planner)
    }
    require(options.createMode == RouteCreateMode.LOOP_AROUND_HERE) {
        resources.getString(R.string.gpx_only_loop_creation_uses_the_round_trip_planner)
    }

    val start =
        when (options.loopStartMode) {
            LoopStartMode.CURRENT_LOCATION ->
                requireNotNull(currentLocation) {
                    resources.getString(
                        R.string.gpx_wait_for_a_fresh_gps_fix_before_creating_a_loop_from_current_location,
                    )
                }

            LoopStartMode.PICK_ON_MAP ->
                requireNotNull(pointA) {
                    resources.getString(R.string.gpx_pick_a_start_point_first)
                }
        }

    return RoundTripPlannerRequest(
        start = start,
        targetDistanceMeters =
            targetDistanceMetersOverride
                ?: options.resolvedLoopDistanceMeters(etaModelConfig),
        targetLabel = options.loopTargetLabel(resources = resources),
        preset = options.routeStyle.toPlannerPreset(),
        useElevation = options.useElevation,
        allowFerries = options.allowFerries,
        customHikeParams = options.customHikeParams,
        allowOutAndBack = options.loopShapeMode == LoopShapeMode.ALLOW_OUT_AND_BACK,
        pointCount = options.loopPointCount(),
        variationIndex = loopVariationIndex,
    )
}

internal fun RouteStylePreset.toPlannerPreset(): RoutePlannerPreset =
    when (this) {
        RouteStylePreset.BALANCED_HIKE -> RoutePlannerPreset.BALANCED_HIKE
        RouteStylePreset.PREFER_TRAILS -> RoutePlannerPreset.PREFER_TRAILS
        RouteStylePreset.PREFER_EASIEST -> RoutePlannerPreset.PREFER_EASIEST
        RouteStylePreset.CUSTOM_HIKE -> RoutePlannerPreset.CUSTOM_HIKE
        RouteStylePreset.BIKE_TOURING -> RoutePlannerPreset.BIKE_TOURING
        RouteStylePreset.BIKE_ROAD -> RoutePlannerPreset.BIKE_ROAD
        RouteStylePreset.BIKE_QUIET_ROAD -> RoutePlannerPreset.BIKE_QUIET_ROAD
        RouteStylePreset.BIKE_GRAVEL -> RoutePlannerPreset.BIKE_GRAVEL
        RouteStylePreset.BIKE_MTB -> RoutePlannerPreset.BIKE_MTB
    }

private fun RouteToolOptions.resolvedLoopDistanceMeters(etaModelConfig: GpxEtaModelConfig?): Int {
    val distanceKm =
        when (loopTargetMode) {
            LoopTargetMode.DISTANCE -> loopDistanceKm.toDouble()
            LoopTargetMode.TIME -> {
                val hours = loopDurationMinutes / 60.0
                hours * estimatedLoopSpeedKmPerHour(etaModelConfig)
            }
        }
    return (distanceKm * 1_000.0)
        .roundToInt()
        .coerceIn(minimumValue = 2_000, maximumValue = 60_000)
}

private fun RouteToolOptions.loopTargetLabel(resources: Resources): String =
    when (loopTargetMode) {
        LoopTargetMode.DISTANCE -> resources.getString(R.string.gpx_km, loopDistanceKm)
        LoopTargetMode.TIME -> formatLoopDuration(loopDurationMinutes, resources = resources)
    }

private fun RouteToolOptions.loopPointCount(): Int =
    when (loopShapeMode) {
        LoopShapeMode.PREFER_CIRCUIT -> 5
        LoopShapeMode.ALLOW_OUT_AND_BACK -> 5
    }

private fun RouteToolOptions.estimatedLoopSpeedKmPerHour(etaModelConfig: GpxEtaModelConfig?): Double =
    etaModelConfig
        ?.flatSpeedMps
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { it * 3.6 }
        ?: when (routeStyle) {
            RouteStylePreset.BALANCED_HIKE -> if (useElevation) 4.0 else 4.5
            RouteStylePreset.PREFER_TRAILS -> if (useElevation) 3.5 else 4.0
            RouteStylePreset.PREFER_EASIEST -> if (useElevation) 4.5 else 5.0
            RouteStylePreset.CUSTOM_HIKE -> if (useElevation) 4.0 else 4.5
            RouteStylePreset.BIKE_TOURING -> 15.0
            RouteStylePreset.BIKE_ROAD -> 22.0
            RouteStylePreset.BIKE_QUIET_ROAD -> 20.0
            RouteStylePreset.BIKE_GRAVEL -> 17.0
            RouteStylePreset.BIKE_MTB -> 12.0
        }
