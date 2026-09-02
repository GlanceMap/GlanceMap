package com.glancemap.glancemapcompanionapp.map

import kotlin.math.abs

private const val PHONE_MAP_COMPARISON_COORDINATE_TOLERANCE = 0.000001
private const val PHONE_MAP_COMPARISON_ZOOM_TOLERANCE = 0.01

/** Avoids camera feedback loops while the interactive comparison layer drives the base renderer. */
internal fun phoneMapComparisonCameraNeedsSync(
    current: PhoneMapCameraSnapshot,
    target: PhoneMapCameraSnapshot,
): Boolean =
    abs(current.latitude - target.latitude) > PHONE_MAP_COMPARISON_COORDINATE_TOLERANCE ||
        abs(current.longitude - target.longitude) > PHONE_MAP_COMPARISON_COORDINATE_TOLERANCE ||
        abs(current.zoom - target.zoom) > PHONE_MAP_COMPARISON_ZOOM_TOLERANCE ||
        phoneMapBearingNeedsSync(current.bearingDegrees, target.bearingDegrees)

@Suppress("MaxLineLength") // The alpha conversion is intentionally a single pure expression.
internal fun PhoneMapComparisonState.overlayAlpha(): Float = (1f - transparencyPercent.coerceIn(0f, 100f) / 100f).coerceIn(0f, 1f)
