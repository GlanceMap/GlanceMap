package com.glancemap.glancemapwearos.presentation.features.gpx

import android.content.res.Resources
import com.glancemap.glancemapwearos.core.gpx.GpxElevationFilterConfig
import com.glancemap.glancemapwearos.core.routing.RoutePlanner
import com.glancemap.glancemapwearos.core.routing.RoutePlannerOutput
import com.glancemap.glancemapwearos.presentation.features.routetools.LoopTargetMode
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolSession
import com.glancemap.glancemapwearos.presentation.features.routetools.toRoundTripPlannerRequest
import org.mapsforge.core.model.LatLong
import kotlin.math.abs
import kotlin.math.roundToInt

private const val LOOP_TIME_TARGET_MIN_CORRECTION_RATIO = 0.10
private const val LOOP_TIME_TARGET_MIN_CORRECTION_SECONDS = 180.0
private const val LOOP_TIME_TARGET_MIN_CORRECTION_METERS = 500
private const val LOOP_TIME_TARGET_MIN_DISTANCE_METERS = 2_000
private const val LOOP_TIME_TARGET_MAX_DISTANCE_METERS = 60_000
private const val LOOP_TIME_TARGET_MAX_CORRECTION_FACTOR = 1.35

// Keep the existing UI API; resource-backed defaults and labels do not change its inputs.
@Suppress("LongParameterList")
internal suspend fun createLoopRoute(
    routePlanner: RoutePlanner,
    elevationFilterConfig: () -> GpxElevationFilterConfig,
    etaModelConfig: () -> GpxEtaModelConfig,
    session: RouteToolSession,
    currentLocation: LatLong?,
    resources: Resources,
): RoutePlannerOutput {
    val etaConfig = etaModelConfig()
    val initialRequest =
        session.toRoundTripPlannerRequest(
            currentLocation = currentLocation,
            etaModelConfig = etaConfig,
            resources = resources,
        )
    val initialRoute = routePlanner.createLoop(initialRequest)
    val adjustedDistanceMeters =
        if (session.options.loopTargetMode == LoopTargetMode.TIME) {
            adjustedLoopTimeTargetDistanceMeters(
                currentDistanceMeters = initialRequest.targetDistanceMeters,
                actualSeconds =
                    buildEtaProjection(
                        profile =
                            buildRouteOutputProfile(
                                route = initialRoute,
                                elevationFilterConfig = elevationFilterConfig,
                            ),
                        config = etaConfig,
                    )?.totalSeconds,
                targetSeconds = session.options.loopDurationMinutes.coerceAtLeast(1) * 60.0,
            )
        } else {
            null
        }

    return adjustedDistanceMeters
        ?.let { targetDistanceMeters ->
            val adjustedRequest =
                session.toRoundTripPlannerRequest(
                    currentLocation = currentLocation,
                    etaModelConfig = etaConfig,
                    targetDistanceMetersOverride = targetDistanceMeters,
                    resources = resources,
                )
            runCatching { routePlanner.createLoop(adjustedRequest) }
                .getOrElse { initialRoute }
        } ?: initialRoute
}

internal fun buildRouteOutputProfile(
    route: RoutePlannerOutput,
    elevationFilterConfig: () -> GpxElevationFilterConfig,
): TrackProfile =
    buildProfile(
        sig = FileSig(0L, route.points.size.toLong()),
        pts =
            route.points.map { routePoint ->
                TrackPoint(
                    latLong = routePoint.latLong,
                    elevation = routePoint.elevation,
                )
            },
        elevationFilterConfig = elevationFilterConfig(),
    )

private fun adjustedLoopTimeTargetDistanceMeters(
    currentDistanceMeters: Int,
    actualSeconds: Double?,
    targetSeconds: Double,
): Int? {
    val canCorrect =
        actualSeconds != null &&
            actualSeconds.isFinite() &&
            actualSeconds > 0.0 &&
            targetSeconds.isFinite() &&
            targetSeconds > 0.0
    return if (canCorrect) {
        resolveLoopTimeTargetDistanceCorrection(
            currentDistanceMeters = currentDistanceMeters,
            actualSeconds = actualSeconds,
            targetSeconds = targetSeconds,
        )
    } else {
        null
    }
}

private fun resolveLoopTimeTargetDistanceCorrection(
    currentDistanceMeters: Int,
    actualSeconds: Double,
    targetSeconds: Double,
): Int? {
    val missSeconds = abs(actualSeconds - targetSeconds)
    val missRatio = missSeconds / targetSeconds
    val shouldCorrect =
        missSeconds >= LOOP_TIME_TARGET_MIN_CORRECTION_SECONDS ||
            missRatio >= LOOP_TIME_TARGET_MIN_CORRECTION_RATIO
    val adjustedDistanceMeters =
        if (shouldCorrect) {
            val correctionFactor =
                (targetSeconds / actualSeconds)
                    .coerceIn(
                        1.0 / LOOP_TIME_TARGET_MAX_CORRECTION_FACTOR,
                        LOOP_TIME_TARGET_MAX_CORRECTION_FACTOR,
                    )
            (currentDistanceMeters * correctionFactor)
                .roundToInt()
                .coerceIn(
                    LOOP_TIME_TARGET_MIN_DISTANCE_METERS,
                    LOOP_TIME_TARGET_MAX_DISTANCE_METERS,
                )
        } else {
            null
        }
    val adjustmentMeters = adjustedDistanceMeters?.let { abs(it - currentDistanceMeters) }
    return adjustedDistanceMeters
        ?.takeIf { adjustmentMeters != null && adjustmentMeters >= LOOP_TIME_TARGET_MIN_CORRECTION_METERS }
}
