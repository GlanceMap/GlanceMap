package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.maps.mapZoomScaleStepsMeters
import com.glancemap.glancemapwearos.core.maps.scaleMetersForZoomLevel
import org.mapsforge.map.android.view.MapView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ScaleIndicatorUi(
    val label: String,
    val widthRatio: Float,
)

private val metricScaleStepsMeters = mapZoomScaleStepsMeters.map { it.toDouble() }.toDoubleArray()

private val imperialScaleStepsFeet =
    doubleArrayOf(
        20.0,
        50.0,
        100.0,
        150.0,
        200.0,
        250.0,
        300.0,
        500.0,
        800.0,
        1000.0,
        2000.0,
        3000.0,
        5000.0,
        8000.0,
    )

private val imperialScaleStepsMiles =
    doubleArrayOf(
        0.1,
        0.2,
        0.5,
        1.0,
        2.0,
        5.0,
        10.0,
        20.0,
        25.0,
        30.0,
        40.0,
        50.0,
        80.0,
        100.0,
        200.0,
        250.0,
        500.0,
        1000.0,
        2000.0,
        2500.0,
        5000.0,
    )

internal fun calculateScaleIndicator(
    mapView: MapView,
    isMetric: Boolean,
    preferredScaleMeters: Int? = null,
    resources: Resources,
): ScaleIndicatorUi? {
    val widthPx = mapView.width
    val zoomLevel =
        mapView.model.mapViewPosition.zoomLevel
            .toInt()
    if (widthPx <= 0 || zoomLevel < 0) return null
    val centerLat =
        mapView.model.mapViewPosition.center.latitude
            .coerceIn(-85.0, 85.0)
    return calculateScaleIndicatorForZoom(
        zoomLevel = zoomLevel,
        viewportWidthPx = widthPx.toDouble(),
        latitudeDegrees = centerLat,
        isMetric = isMetric,
        preferredScaleMeters = preferredScaleMeters,
        resources = resources,
    )
}

// Keep the existing UI API; resource-backed defaults and labels do not change its inputs.
@Suppress("LongParameterList")
internal fun calculateScaleIndicatorForZoom(
    zoomLevel: Int,
    viewportWidthPx: Double,
    latitudeDegrees: Double,
    isMetric: Boolean,
    preferredScaleMeters: Int? = null,
    resources: Resources,
): ScaleIndicatorUi? {
    val targetMeters =
        scaleMetersForZoomLevel(
            zoom = zoomLevel,
            viewportWidthPx = viewportWidthPx,
            latitudeDegrees = latitudeDegrees,
        )
    val naturalScaleMeters =
        chooseScaleDistanceMeters(
            targetMeters = targetMeters,
            isMetric = isMetric,
        )
    val scaleMeters =
        preferredScaleMeters
            ?.let { preferredMeters ->
                chooseScaleDistanceMeters(
                    targetMeters = preferredMeters.toDouble(),
                    isMetric = isMetric,
                )
            }
            ?: naturalScaleMeters
    val widthRatio = (scaleMeters / targetMeters).toFloat()
    val hasValidTarget = targetMeters.isFinite() && targetMeters > 0.0
    val hasValidScale = scaleMeters.isFinite() && scaleMeters > 0.0
    val hasValidWidth = widthRatio.isFinite() && widthRatio > 0f
    return if (hasValidTarget && hasValidScale && hasValidWidth) {
        ScaleIndicatorUi(
            label = formatScaleDistance(meters = scaleMeters, isMetric = isMetric, resources = resources),
            widthRatio = widthRatio,
        )
    } else {
        null
    }
}

private fun chooseScaleDistanceMeters(
    targetMeters: Double,
    isMetric: Boolean,
): Double {
    if (!targetMeters.isFinite() || targetMeters <= 0.0) return 0.0

    if (isMetric) {
        return pickLargestNotExceeding(metricScaleStepsMeters, targetMeters)
    }

    val targetFeet = targetMeters * 3.28084
    return if (targetFeet < 2640.0) {
        val feet = pickLargestNotExceeding(imperialScaleStepsFeet, targetFeet)
        feet / 3.28084
    } else {
        val targetMiles = targetMeters * 0.000621371
        val miles = pickLargestNotExceeding(imperialScaleStepsMiles, targetMiles)
        miles / 0.000621371
    }
}

private fun pickLargestNotExceeding(
    steps: DoubleArray,
    target: Double,
): Double {
    var candidate = steps.firstOrNull() ?: target
    for (step in steps) {
        if (step <= target) candidate = step else break
    }
    return candidate
}

private fun formatScaleDistance(
    meters: Double,
    isMetric: Boolean,
    resources: Resources,
): String {
    if (isMetric) {
        return if (meters >= 1000.0) {
            val km = meters / 1000.0
            if (km >= 10.0) {
                resources.getString(R.string.gpx_km, km.roundToInt())
            } else {
                String.format(Locale.getDefault(), resources.getString(R.string.navigation_1f_km), km)
            }
        } else {
            val roundedMeters = meters.roundToInt()
            if (meters < 10.0 && abs(meters - roundedMeters) >= SCALE_DISTANCE_INTEGER_EPSILON) {
                String.format(Locale.getDefault(), resources.getString(R.string.settings_1f_m), meters)
            } else {
                resources.getString(R.string.settings_m, roundedMeters)
            }
        }
    }

    val feet = meters * 3.28084
    return if (feet < 2640.0) {
        resources.getString(R.string.settings_ft, feet.roundToInt())
    } else {
        val miles = meters * 0.000621371
        if (miles >= 10.0) {
            resources.getString(R.string.settings_mi, miles.roundToInt())
        } else {
            String.format(Locale.getDefault(), resources.getString(R.string.navigation_1f_mi), miles)
        }
    }
}

private const val SCALE_DISTANCE_INTEGER_EPSILON = 0.01
