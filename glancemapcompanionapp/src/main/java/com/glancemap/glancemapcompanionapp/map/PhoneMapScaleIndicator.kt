package com.glancemap.glancemapcompanionapp.map

import java.util.Locale
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class PhoneMapScaleIndicator(
    val label: String,
    val widthRatio: Float,
)

internal fun calculatePhoneMapScaleIndicator(
    latitudeDegrees: Double,
    zoom: Double,
    viewportWidthPx: Double,
    isMetric: Boolean = true,
): PhoneMapScaleIndicator? {
    val hasFiniteCoordinates = latitudeDegrees.isFinite() && zoom.isFinite()
    val hasValidViewport = viewportWidthPx.isFinite() && viewportWidthPx > 0.0
    return if (!hasFiniteCoordinates || !hasValidViewport) {
        null
    } else {
        val safeLatitude = latitudeDegrees.coerceIn(-85.0, 85.0)
        val safeZoom = zoom.coerceIn(0.0, 22.0)
        val metersPerPixel =
            PHONE_MAP_METERS_PER_PIXEL_AT_ZOOM_ZERO *
                cos(Math.toRadians(safeLatitude)) /
                2.0.pow(safeZoom)
        val targetMeters = metersPerPixel * viewportWidthPx * PHONE_MAP_SCALE_TARGET_RATIO
        val scaleMeters = phoneMapScaleStepAtMost(targetMeters)
        val widthRatio = (scaleMeters / (metersPerPixel * viewportWidthPx)).toFloat()

        if (!metersPerPixel.isFinite() || metersPerPixel <= 0.0) {
            null
        } else {
            if (!widthRatio.isFinite() || widthRatio <= 0f) {
                null
            } else {
                PhoneMapScaleIndicator(
                    label = formatPhoneMapScaleDistance(scaleMeters, isMetric),
                    widthRatio = widthRatio,
                )
            }
        }
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
): Double? {
    val hasValidInput = latitudeDegrees.isFinite() && viewportWidthPx.isFinite()
    if (!hasValidInput || viewportWidthPx <= 0.0 || scaleMeters <= 0) {
        return null
    }
    val metersPerPixelAtZoomZero =
        PHONE_MAP_METERS_PER_PIXEL_AT_ZOOM_ZERO *
            cos(Math.toRadians(latitudeDegrees.coerceIn(-85.0, 85.0)))
    val targetMetersAtZoomZero = metersPerPixelAtZoomZero * viewportWidthPx * PHONE_MAP_SCALE_TARGET_RATIO
    return (log2(targetMetersAtZoomZero / scaleMeters.toDouble())).coerceIn(0.0, 22.0)
}

internal fun phoneMapScaleStepAtMost(targetMeters: Double): Int =
    PHONE_MAP_SCALE_STEPS_METERS
        .lastOrNull { it <= targetMeters }
        ?: PHONE_MAP_SCALE_STEPS_METERS.first()

internal const val PHONE_MAP_SCALE_TARGET_RATIO = 0.28
internal const val PHONE_MAP_METERS_PER_PIXEL_AT_ZOOM_ZERO = 156543.03392804097

private const val METERS_TO_FEET = 3.28084
private const val FEET_PER_MILE = 5_280.0
