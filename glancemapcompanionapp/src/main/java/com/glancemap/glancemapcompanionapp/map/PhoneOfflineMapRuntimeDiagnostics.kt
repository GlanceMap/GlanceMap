package com.glancemap.glancemapcompanionapp.map

import org.mapsforge.core.model.BoundingBox
import kotlin.math.roundToInt

/** Runtime-only facts for explicit debug capture; this deliberately contains no coordinates or paths. */
internal data class PhoneOfflineMapRuntimeDiagnostics(
    val displayName: String,
    val mapViewAttached: Boolean,
    val mapViewWidth: Int,
    val mapViewHeight: Int,
    val drawObserved: Boolean,
    val firstVisibleBaseTileObserved: Boolean,
    val zoom: Int?,
    val cameraInsideMapBounds: Boolean?,
    val locationPermissionGranted: Boolean,
    val locationAvailable: Boolean,
    val locationAgeMillis: Long?,
    val locationAccuracyMeters: Float?,
    val locationInsideMapBounds: Boolean?,
    val followMode: PhoneMapFollowMode,
    val orientation: PhoneMapOrientation,
    val locationMarkerAttached: Boolean,
) {
    fun toReportSection(): String =
        buildString {
            appendLine("Offline map runtime")
            appendLine("MapView attached: $mapViewAttached")
            appendLine("MapView size: ${mapViewWidth}x$mapViewHeight")
            appendLine("Draw observed: $drawObserved")
            appendLine("First visible base tile: $firstVisibleBaseTileObserved")
            appendLine("Zoom: ${zoom ?: "unknown"}")
            appendLine("Camera inside map bounds: ${cameraInsideMapBounds ?: "unknown"}")
            appendLine("Location permission: $locationPermissionGranted")
            appendLine("Location available: $locationAvailable")
            appendLine("Location age: ${locationAgeMillis.toPhoneMapLocationAgeLabel()}")
            appendLine("Location accuracy: ${locationAccuracyMeters.toPhoneMapLocationAccuracyLabel()}")
            appendLine("Location inside map bounds: ${locationInsideMapBounds ?: "unknown"}")
            appendLine("Follow mode: $followMode")
            appendLine("Orientation: $orientation")
            append("Location marker attached: $locationMarkerAttached")
        }
}

/** One renderer-neutral follow decision, kept pure so outside-map fixes can never move the camera. */
internal data class PhoneOfflineLocationFollowDecision(
    val locationInsideMapBounds: Boolean?,
    val shouldCenterOnLocation: Boolean,
)

internal fun phoneOfflineLocationFollowDecision(
    location: PhoneMapLocation?,
    mapBounds: BoundingBox?,
    followMode: PhoneMapFollowMode,
): PhoneOfflineLocationFollowDecision {
    val insideBounds =
        location?.let { fix ->
            mapBounds?.contains(fix.latitude, fix.longitude)
        }
    return PhoneOfflineLocationFollowDecision(
        locationInsideMapBounds = insideBounds,
        shouldCenterOnLocation = followMode == PhoneMapFollowMode.FOLLOW_LOCATION && insideBounds == true,
    )
}

internal fun PhoneMapLocation.ageMillis(nowMs: Long): Long = (nowMs - fixElapsedRealtimeMillis).coerceAtLeast(0L)

private fun Long?.toPhoneMapLocationAgeLabel(): String =
    when {
        this == null -> "unavailable"
        this < PHONE_MAP_LOCATION_AGE_MINUTE_MS -> "${this / 1_000L}s"
        else -> "${this / PHONE_MAP_LOCATION_AGE_MINUTE_MS}m"
    }

private fun Float?.toPhoneMapLocationAccuracyLabel(): String = this?.let { "${it.roundToInt()} m" } ?: "unknown"

private const val PHONE_MAP_LOCATION_AGE_MINUTE_MS = 60_000L
