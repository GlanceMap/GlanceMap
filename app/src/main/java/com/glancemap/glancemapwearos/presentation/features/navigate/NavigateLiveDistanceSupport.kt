package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView
import java.util.Locale
import kotlin.math.roundToInt

internal fun resolveLiveDistanceOrigin(
    currentMarkerLatLong: LatLong?,
    fallbackLatLong: LatLong?,
): LatLong? = currentMarkerLatLong ?: fallbackLatLong

internal fun resolveLiveDistanceMeters(
    origin: LatLong,
    target: LatLong,
): Double =
    navigateHaversineMeters(
        lat1 = origin.latitude,
        lon1 = origin.longitude,
        lat2 = target.latitude,
        lon2 = target.longitude,
    )

internal fun resolveVisibleScreenCenterLatLong(
    mapView: MapView,
    visibleHeightPx: Int = mapView.height,
    mapRotationDeg: Double = mapView.mapRotation.degrees.toDouble(),
    rotationPivot: ScreenAnchor = ScreenAnchor(mapView.width / 2.0, mapView.height / 2.0),
): LatLong? {
    if (mapView.width <= 0 || mapView.height <= 0) return null
    val resolvedVisibleHeightPx =
        if (visibleHeightPx > 0) {
            visibleHeightPx
        } else {
            mapView.height
        }
    val mapSpacePoint =
        visibleScreenCenterToMapSpace(
            mapWidthPx = mapView.width.toDouble(),
            mapHeightPx = mapView.height.toDouble(),
            visibleHeightPx = resolvedVisibleHeightPx,
            mapRotationDeg = mapRotationDeg,
            rotationPivot = rotationPivot,
        )

    return runCatching {
        mapView.mapViewProjection.fromPixels(
            mapSpacePoint.x,
            mapSpacePoint.y,
        )
    }.getOrNull()
}

internal fun visibleScreenCenterToMapSpace(
    mapWidthPx: Double,
    mapHeightPx: Double,
    visibleHeightPx: Int,
    mapRotationDeg: Double,
    rotationPivot: ScreenAnchor = ScreenAnchor(mapWidthPx / 2.0, mapHeightPx / 2.0),
): ScreenAnchor {
    val screenPoint =
        ScreenAnchor(
            x = mapWidthPx / 2.0,
            y = visibleHeightPx.coerceIn(1, maxOf(1, mapHeightPx.toInt())) / 2.0,
        )
    return unrotateTouchToMapSpace(
        point = screenPoint,
        mapWidth = mapWidthPx,
        mapHeight = mapHeightPx,
        mapRotationDeg = mapRotationDeg,
        pivot = rotationPivot,
    )
}

internal fun formatLiveDistance(
    meters: Double,
    isMetric: Boolean,
): Pair<String, String> =
    when {
        !meters.isFinite() || meters < 0.0 -> "--" to ""
        isMetric -> formatMetricLiveDistance(meters)
        else -> formatImperialLiveDistance(meters)
    }

internal fun formatLiveDistanceLabel(
    meters: Double,
    isMetric: Boolean,
    resources: Resources,
): String {
    val (value, unit) = formatLiveDistance(meters = meters, isMetric = isMetric)
    return if (unit.isBlank()) value else resources.getString(R.string.common_value_unit, value, unit)
}

private fun formatMetricLiveDistance(
    meters: Double,
): Pair<String, String> =
    if (meters < 1000.0) {
        meters.roundToInt().toString() to "m"
    } else {
        formatLargeDistanceValue(value = meters / 1000.0, unit = "km")
    }

private fun formatImperialLiveDistance(
    meters: Double,
): Pair<String, String> {
    val feet = meters * 3.28084
    return if (feet < 2640.0) {
        feet.roundToInt().toString() to "ft"
    } else {
        formatLargeDistanceValue(value = meters * 0.000621371, unit = "mi")
    }
}

private fun formatLargeDistanceValue(
    value: Double,
    unit: String,
): Pair<String, String> {
    val displayValue =
        if (value >= 10.0) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }
    return displayValue to unit
}
