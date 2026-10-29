package com.glancemap.glancemapwearos.presentation.features.routetools

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.routing.LoopRouteSuggestionException
import kotlin.math.roundToInt

internal data class RouteToolLoopRetryOption(
    val label: String,
    val options: RouteToolOptions,
)

internal fun buildLoopRetryOptions(
    base: RouteToolOptions,
    error: LoopRouteSuggestionException,
    resources: Resources,
): List<RouteToolLoopRetryOption> =
    buildList {
        buildOutAndBackRetryOption(base, resources)?.let(::add)
        error.lowerDistanceMeters?.let { distanceMeters ->
            buildLoopRetryOption(base, distanceMeters, resources = resources)?.let(::add)
        }
        error.higherDistanceMeters?.let { distanceMeters ->
            buildLoopRetryOption(base, distanceMeters, resources = resources)?.let(::add)
        }
    }.distinctBy { option ->
        option.label
    }

private fun buildOutAndBackRetryOption(
    base: RouteToolOptions,
    resources: Resources,
): RouteToolLoopRetryOption? {
    if (base.loopShapeMode != LoopShapeMode.PREFER_CIRCUIT) return null
    return RouteToolLoopRetryOption(
        label = resources.getString(R.string.gpx_allow_out_and_back),
        options = base.copy(loopShapeMode = LoopShapeMode.ALLOW_OUT_AND_BACK),
    )
}

// Keep the existing guard order and presentation thresholds while localizing the result.
@Suppress("ReturnCount")
private fun buildLoopRetryOption(
    base: RouteToolOptions,
    distanceMeters: Int,
    resources: Resources,
): RouteToolLoopRetryOption? {
    return when (base.loopTargetMode) {
        LoopTargetMode.DISTANCE -> {
            val distanceKm =
                (distanceMeters / 1_000.0)
                    .roundToInt()
                    .coerceIn(2, 60)
            if (distanceKm == base.loopDistanceKm) return null
            RouteToolLoopRetryOption(
                label = resources.getString(R.string.gpx_km, distanceKm),
                options = base.copy(loopDistanceKm = distanceKm),
            )
        }

        LoopTargetMode.TIME -> {
            val durationMinutes = estimateLoopDurationMinutes(base, distanceMeters)
            if (durationMinutes == base.loopDurationMinutes) return null
            RouteToolLoopRetryOption(
                label = formatLoopDuration(durationMinutes, resources = resources),
                options = base.copy(loopDurationMinutes = durationMinutes),
            )
        }
    }
}

private fun estimateLoopDurationMinutes(
    base: RouteToolOptions,
    distanceMeters: Int,
): Int {
    val speedKmPerHour =
        when (base.routeStyle) {
            RouteStylePreset.BALANCED_HIKE -> if (base.useElevation) 4.0 else 4.5
            RouteStylePreset.PREFER_TRAILS -> if (base.useElevation) 3.5 else 4.0
            RouteStylePreset.PREFER_EASIEST -> if (base.useElevation) 4.5 else 5.0
            RouteStylePreset.CUSTOM_HIKE -> if (base.useElevation) 4.0 else 4.5
            RouteStylePreset.BIKE_TOURING -> 15.0
            RouteStylePreset.BIKE_ROAD -> 22.0
            RouteStylePreset.BIKE_QUIET_ROAD -> 20.0
            RouteStylePreset.BIKE_GRAVEL -> 17.0
            RouteStylePreset.BIKE_MTB -> 12.0
        }
    val rawMinutes = ((distanceMeters / 1_000.0) / speedKmPerHour * 60.0).roundToInt()
    return ((rawMinutes + 7) / 15 * 15).coerceIn(30, 480)
}
