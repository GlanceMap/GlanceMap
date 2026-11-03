package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

private const val PHONE_WORLDWIND_GROUND_ANCHOR_TOLERANCE_METERS = 5.0

private const val PHONE_WORLDWIND_FULL_TILT_RANGE_METERS = 50_000.0
private const val PHONE_WORLDWIND_FLAT_RANGE_METERS = 2_000_000.0
private const val PHONE_WORLDWIND_GLOBE_FIT_MARGIN = 1.08

/**
 * The chosen tilt applies up to 50 km away, then its limit shrinks with the logarithm of the distance to
 * straight down at 2,000 km. Tilted from that far, the globe sat at the bottom of the screen.
 */
internal fun phoneWorldWindTiltForRange(
    preferredTilt: Double,
    range: Double,
): Double {
    val distance = ln(range / PHONE_WORLDWIND_FULL_TILT_RANGE_METERS)
    val span = ln(PHONE_WORLDWIND_FLAT_RANGE_METERS / PHONE_WORLDWIND_FULL_TILT_RANGE_METERS)
    val progress = (distance / span).coerceIn(0.0, 1.0)
    return min(phoneWorldWindClampedTilt(preferredTilt), PHONE_WORLDWIND_MAX_TILT_DEGREES * (1.0 - progress))
}

/**
 * Looking straight down, the farthest the view can be while the whole globe still fits the narrower
 * screen side, with a small margin. [verticalFieldOfViewDegrees] is WorldWind's camera field of view.
 */
internal fun phoneWorldWindGlobeFitRange(
    verticalFieldOfViewDegrees: Double,
    width: Int,
    height: Int,
    globeRadius: Double,
): Double {
    if (width <= 0 || height <= 0) return Double.MAX_VALUE
    val halfVertical = Math.toRadians(verticalFieldOfViewDegrees / 2.0)
    val halfHorizontal = atan(tan(halfVertical) * width / height)
    val eyeDistance = globeRadius / sin(min(halfVertical, halfHorizontal)) * PHONE_WORLDWIND_GLOBE_FIT_MARGIN
    return eyeDistance - globeRadius
}

private const val PHONE_WORLDWIND_GROUND_EASE_FRACTION = 0.25
private const val PHONE_WORLDWIND_GROUND_SNAP_METERS = 10.0

/**
 * Moves the camera's ground a quarter of the way to newly loaded terrain each frame, about a quarter of a
 * second for a 1,000 m change at 60 fps, so the view glides instead of jumping. Snaps once close enough
 * that the last step lands exactly on the terrain.
 */
internal fun phoneWorldWindEasedGround(
    current: Double,
    target: Double,
): Double =
    if (abs(target - current) <= PHONE_WORLDWIND_GROUND_SNAP_METERS) {
        target
    } else {
        current + (target - current) * PHONE_WORLDWIND_GROUND_EASE_FRACTION
    }

/** Ground-clamped cameras resolve elevation once, so they are repositioned when terrain arrives or refines. */
internal fun phoneWorldWindGroundMoved(
    anchoredElevation: Double,
    currentElevation: Double,
): Boolean = abs(currentElevation - anchoredElevation) >= PHONE_WORLDWIND_GROUND_ANCHOR_TOLERANCE_METERS

/** Applies the existing map command without changing the chosen 3D tilt or bearing. */
internal fun phoneWorldWindCommandCamera(
    current: LookAt,
    command: PhoneMapCameraCommand,
    settings: PhoneMapSettings,
    viewportWidth: Double,
): LookAt {
    val result = LookAt(current)
    val zoom = phoneWorldWindZoomForRange(result.position.latitude.inDegrees, result.range)
    val newZoom = command.requestedZoom(zoom, settings, viewportWidth, 256.0).coerceIn(0.0, 22.0)
    command.target?.let { target ->
        result.position.latitude = target.latitude.degrees
        result.position.longitude = target.longitude.degrees
        result.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
    }
    result.range = phoneWorldWindRangeMeters(result.position.latitude.inDegrees, newZoom)
    return result
}

/** Updates a following camera without overriding a free camera or its tilt. */
internal fun phoneWorldWindFollowCamera(
    current: LookAt,
    mode: PhoneMapMode,
    location: GeoPoint?,
    compassBearingDegrees: Float,
): LookAt? {
    val result = LookAt(current)
    if (mode.follow == PhoneMapFollowMode.FOLLOW_LOCATION && !mode.isOffline) {
        location?.let { point ->
            result.position.latitude = point.latitude.degrees
            result.position.longitude = point.longitude.degrees
            result.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        }
        result.heading = compassBearingDegrees.toDouble().degrees
    } else if (mode.isOffline && mode.manualBearingDegrees == null) {
        result.heading = 0.0.degrees
    } else {
        return null
    }
    return result
}
