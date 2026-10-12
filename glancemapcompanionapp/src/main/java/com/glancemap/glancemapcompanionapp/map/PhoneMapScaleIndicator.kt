package com.glancemap.glancemapcompanionapp.map

import java.util.Locale
import kotlin.math.log2
import kotlin.math.roundToInt

internal data class PhoneMapScaleIndicator(
    val label: String,
    val widthRatio: Float,
)

internal fun calculatePhoneMapScaleIndicator(
    groundResolutionMetersPerPixel: Double,
    viewportWidthPx: Double,
    isMetric: Boolean = true,
): PhoneMapScaleIndicator? {
    val hasValidResolution = groundResolutionMetersPerPixel.isFinite() && groundResolutionMetersPerPixel > 0.0
    val hasValidViewport = viewportWidthPx.isFinite() && viewportWidthPx > 0.0
    val viewportMeters = groundResolutionMetersPerPixel * viewportWidthPx
    val hasValidSpan = viewportMeters.isFinite() && viewportMeters > 0.0
    if (!hasValidResolution || !hasValidViewport || !hasValidSpan) return null
    val scaleMeters = phoneMapScaleStepAtMost(viewportMeters * PHONE_MAP_SCALE_TARGET_RATIO)
    val widthRatio = (scaleMeters / viewportMeters).toFloat()
    // Hide the bar when even the smallest distance step would be clipped by the viewport.
    return if (widthRatio.isFinite() && widthRatio > 0f && widthRatio <= 1f) {
        PhoneMapScaleIndicator(
            label = formatPhoneMapScaleDistance(scaleMeters, isMetric),
            widthRatio = widthRatio,
        )
    } else {
        null
    }
}

internal fun formatPhoneMapScaleDistance(
    meters: Int,
    isMetric: Boolean = true,
): String =
    if (isMetric) {
        if (meters >= 1_000) {
            val kilometers = meters / 1_000.0
            if (kilometers >= 10.0) {
                "${kilometers.roundToInt()} km"
            } else {
                String.format(Locale.getDefault(), "%.1f km", kilometers)
            }
        } else {
            "$meters m"
        }
    } else {
        val feet = meters * METERS_TO_FEET
        if (feet >= FEET_PER_MILE) {
            val miles = feet / FEET_PER_MILE
            if (miles >= 10.0) {
                "${miles.roundToInt()} mi"
            } else {
                String.format(Locale.getDefault(), "%.1f mi", miles)
            }
        } else {
            "${feet.roundToInt()} ft"
        }
    }

internal val PHONE_MAP_SCALE_STEPS_METERS =
    listOf(
        1,
        2,
        5,
        10,
        20,
        25,
        50,
        100,
        200,
        250,
        500,
        1_000,
        2_000,
        2_500,
        5_000,
        10_000,
        20_000,
        25_000,
        50_000,
        100_000,
        200_000,
        250_000,
        500_000,
        1_000_000,
        2_000_000,
        2_500_000,
        5_000_000,
    )

internal fun phoneMapZoomForScale(
    latitudeDegrees: Double,
    scaleMeters: Int,
    viewportWidthPx: Double,
    tileSizePx: Double,
): Double? {
    val hasValidViewport = viewportWidthPx.isFinite() && viewportWidthPx > 0.0
    val hasValidScale = scaleMeters > 0 && tileSizePx.isFinite() && tileSizePx > 0.0
    if (!latitudeDegrees.isFinite() || !hasValidViewport || !hasValidScale) {
        return null
    }
    val metersPerPixelAtZoomZero =
        phoneGroundResolutionMetersPerPixel(latitudeDegrees, zoom = 0.0, tileSizePx = tileSizePx)
    val targetMetersAtZoomZero = metersPerPixelAtZoomZero * viewportWidthPx * PHONE_MAP_SCALE_TARGET_RATIO
    return (log2(targetMetersAtZoomZero / scaleMeters.toDouble())).coerceIn(0.0, 22.0)
}

internal fun phoneMapScaleStepAtMost(targetMeters: Double): Int =
    PHONE_MAP_SCALE_STEPS_METERS
        .lastOrNull { it <= targetMeters }
        ?: PHONE_MAP_SCALE_STEPS_METERS.first()

internal const val PHONE_MAP_SCALE_TARGET_RATIO = 0.28

private const val METERS_TO_FEET = 3.28084
private const val FEET_PER_MILE = 5_280.0
