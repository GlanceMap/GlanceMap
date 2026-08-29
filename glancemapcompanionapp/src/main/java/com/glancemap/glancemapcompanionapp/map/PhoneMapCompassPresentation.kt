package com.glancemap.glancemapcompanionapp.map

/** Renderer-neutral compass values; renderer adapters own their SDK-specific bearing conventions. */
internal data class PhoneMapCompassPresentation(
    val mapBearingDegrees: Float,
    val markerScreenRotationDegrees: Float?,
    val northIndicatorScreenRotationDegrees: Float,
)

internal fun phoneMapCompassPresentation(
    orientation: PhoneMapOrientation,
    headingDegrees: Float?,
): PhoneMapCompassPresentation {
    val heading = headingDegrees?.takeIf(Float::isFinite)?.let(::normalizePhoneHeadingDegrees)
    return when (orientation) {
        PhoneMapOrientation.NORTH_UP ->
            PhoneMapCompassPresentation(
                mapBearingDegrees = 0f,
                markerScreenRotationDegrees = heading,
                northIndicatorScreenRotationDegrees = 0f,
            )
        PhoneMapOrientation.HEADING_UP ->
            PhoneMapCompassPresentation(
                mapBearingDegrees = heading ?: 0f,
                markerScreenRotationDegrees = heading?.let { 0f },
                northIndicatorScreenRotationDegrees = heading?.let { normalizePhoneHeadingDegrees(-it) } ?: 0f,
            )
    }
}

internal fun normalizePhoneHeadingDegrees(degrees: Float): Float = (degrees % 360f + 360f) % 360f

internal fun shortestPhoneHeadingDelta(
    targetDegrees: Float,
    currentDegrees: Float,
): Float = ((targetDegrees - currentDegrees + 540f) % 360f) - 180f

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
