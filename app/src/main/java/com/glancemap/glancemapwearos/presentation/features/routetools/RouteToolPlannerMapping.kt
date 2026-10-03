package com.glancemap.glancemapwearos.presentation.features.routetools

import com.glancemap.glancemapwearos.core.routing.RoundTripPlannerRequest
import com.glancemap.glancemapwearos.core.routing.RoutePlannerPreset
import com.glancemap.glancemapwearos.core.routing.RoutePlannerRequest
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxEtaModelConfig
import org.mapsforge.core.model.LatLong
import kotlin.math.roundToInt

internal fun RouteToolSession.toRoutePlannerRequest(
    currentLocation: LatLong?,
    activeGpxEnd: LatLong? = null,
): RoutePlannerRequest {
    require(options.toolKind == RouteToolKind.CREATE) {
        "Only create actions can be mapped to the route planner."
    }

    return when (options.createMode) {
        RouteCreateMode.CURRENT_TO_HERE ->
            RoutePlannerRequest(
                origin =
                    requireNotNull(currentLocation) {
                        "Wait for a fresh GPS fix before creating a route from current location."
                    },
                destination =
                    requireNotNull(destination) {
                        "Pick a destination first."
                    },
                preset = options.routeStyle.toPlannerPreset(),
                useElevation = options.useElevation,
                allowFerries = options.allowFerries,
                customHikeParams = options.customHikeParams,
            )

        RouteCreateMode.MULTI_POINT_CHAIN -> {
            require(chainPoints.size >= 2) {
                "Pick at least two points first."
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
                        "Activate exactly one GPX before extending it."
                    },
                destination =
                    requireNotNull(destination) {
                        "Pick a destination first."
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
                        "Pick point A first."
                    },
                destination =
                    requireNotNull(pointB) {
                        "Pick point B first."
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
                        "Wait for a fresh GPS fix before creating a route from current location."
                    },
                destination =
                    requireNotNull(destination) {
                        "Pick a POI first."
                    },
                preset = options.routeStyle.toPlannerPreset(),
                useElevation = options.useElevation,
                allowFerries = options.allowFerries,
                customHikeParams = options.customHikeParams,
            )

        RouteCreateMode.COORDINATES -> {
            val endpoints = options.resolveCoordinateEndpoints(currentLocation)
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
            throw IllegalArgumentException("Loop creation uses the round-trip planner.")
        }
    }
}

internal fun RouteToolSession.toRoundTripPlannerRequest(
    currentLocation: LatLong?,
    etaModelConfig: GpxEtaModelConfig? = null,
    targetDistanceMetersOverride: Int? = null,
): RoundTripPlannerRequest {
    require(options.toolKind == RouteToolKind.CREATE) {
        "Only create actions can be mapped to the route planner."
    }
    require(options.createMode == RouteCreateMode.LOOP_AROUND_HERE) {
        "Only loop creation uses the round-trip planner."
    }

    val start =
        when (options.loopStartMode) {
            LoopStartMode.CURRENT_LOCATION ->
                requireNotNull(currentLocation) {
                    "Wait for a fresh GPS fix before creating a loop from current location."
                }

            LoopStartMode.PICK_ON_MAP ->
                requireNotNull(pointA) {
                    "Pick a start point first."
                }
        }

    return RoundTripPlannerRequest(
        start = start,
        targetDistanceMeters =
            targetDistanceMetersOverride
                ?: options.resolvedLoopDistanceMeters(etaModelConfig),
        targetLabel = options.loopTargetLabel(),
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

private fun RouteToolOptions.loopTargetLabel(): String =
    when (loopTargetMode) {
        LoopTargetMode.DISTANCE -> "$loopDistanceKm km"
        LoopTargetMode.TIME -> formatLoopDuration(loopDurationMinutes)
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
