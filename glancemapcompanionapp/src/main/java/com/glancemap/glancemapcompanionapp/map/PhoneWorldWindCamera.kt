package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt

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
