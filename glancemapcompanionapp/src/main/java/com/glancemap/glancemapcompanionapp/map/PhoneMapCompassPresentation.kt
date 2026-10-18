package com.glancemap.glancemapcompanionapp.map

import kotlin.math.abs

/** Renderer-neutral compass values; renderer adapters own their SDK-specific bearing conventions. */
internal data class PhoneMapCompassPresentation(
    val mapBearingDegrees: Float,
    val markerScreenRotationDegrees: Float?,
    val northIndicatorScreenRotationDegrees: Float,
)

internal fun phoneMapCompassPresentation(
    mapMode: PhoneMapMode,
    headingDegrees: Float?,
): PhoneMapCompassPresentation {
    if (mapMode.isOffline) {
        val bearing = mapMode.manualBearingDegrees?.let(::normalizePhoneHeadingDegrees) ?: 0f
        return PhoneMapCompassPresentation(bearing, null, phoneMapNorthIndicatorRotationDegrees(bearing))
    }
    val heading = headingDegrees?.takeIf(Float::isFinite)?.let(::normalizePhoneHeadingDegrees)
    val automaticBearing =
        when (mapMode.orientation) {
            PhoneMapOrientation.NORTH_UP -> 0f
            PhoneMapOrientation.HEADING_UP -> heading ?: 0f
        }
    val bearing = mapMode.manualBearingDegrees?.let(::normalizePhoneHeadingDegrees) ?: automaticBearing
    return PhoneMapCompassPresentation(
        mapBearingDegrees = bearing,
        markerScreenRotationDegrees = heading?.let { normalizePhoneHeadingDegrees(it - bearing) },
        northIndicatorScreenRotationDegrees = phoneMapNorthIndicatorRotationDegrees(bearing),
    )
}

internal fun phoneMapCompassPresentation(
    orientation: PhoneMapOrientation,
    headingDegrees: Float?,
): PhoneMapCompassPresentation = phoneMapCompassPresentation(PhoneMapMode(orientation = orientation), headingDegrees)

/** North is opposite the bearing of the map currently displayed, including during camera movement. */
internal fun phoneMapNorthIndicatorRotationDegrees(mapBearingDegrees: Float): Float = normalizePhoneHeadingDegrees(-mapBearingDegrees)

internal fun normalizePhoneHeadingDegrees(degrees: Float): Float = (degrees % 360f + 360f) % 360f

internal fun shortestPhoneHeadingDelta(
    targetDegrees: Float,
    currentDegrees: Float,
): Float = ((targetDegrees - currentDegrees + 540f) % 360f) - 180f

internal const val PHONE_MAP_BEARING_TOLERANCE_DEGREES = 0.5f

internal fun phoneMapBearingNeedsSync(
    currentDegrees: Float,
    targetDegrees: Float,
    toleranceDegrees: Float = PHONE_MAP_BEARING_TOLERANCE_DEGREES,
): Boolean = abs(shortestPhoneHeadingDelta(targetDegrees, currentDegrees)) > toleranceDegrees

internal fun smoothPhoneHeading(
    currentDegrees: Float?,
    targetDegrees: Float,
    alpha: Float = PHONE_COMPASS_SMOOTHING_ALPHA,
): Float {
    val target = normalizePhoneHeadingDegrees(targetDegrees)
    val current = currentDegrees?.takeIf(Float::isFinite) ?: return target
    return normalizePhoneHeadingDegrees(current + shortestPhoneHeadingDelta(target, current) * alpha)
}

private const val PHONE_COMPASS_SMOOTHING_ALPHA = 0.24f
