package com.glancemap.glancemapcompanionapp.map

import org.mapsforge.core.model.BoundingBox
import kotlin.math.roundToInt

/** Runtime-only facts for explicit debug capture; this deliberately contains no coordinates or paths. */
internal data class PhoneOfflineMapRuntimeDiagnostics(
    val displayName: String,
    val rendererId: Int,
    val mapViewId: Int,
    val layerId: Int?,
    val cacheId: String?,
    val mapViewAttached: Boolean,
    val mapViewHasWindowFocus: Boolean,
    val mapViewWidth: Int,
    val mapViewHeight: Int,
    val mapViewRenderReady: Boolean,
    val androidMapViewDrawObserved: Boolean,
    val tileLayerDrawObserved: Boolean,
    val firstVisibleBaseTileObserved: Boolean,
    val layerCount: Int,
    val tileLayerPresent: Boolean,
    val tileLayerVisible: Boolean?,
    val frameBufferDimensionAvailable: Boolean,
    val frameBufferWidth: Int?,
    val frameBufferHeight: Int?,
    val frameBufferDrawingBitmapReady: Boolean?,
    val zoom: Int?,
    val cameraInsideMapBounds: Boolean?,
    val visibleTileCount: Int,
    val drawableVisibleTileCount: Int,
    val parentFallbackTileCount: Int,
    val pendingTileJobCount: Int,
    val locationPermissionGranted: Boolean,
    val locationAvailable: Boolean,
    val locationAgeMillis: Long?,
    val locationAccuracyMeters: Float?,
    val locationInsideMapBounds: Boolean?,
    val followMode: PhoneMapFollowMode,
    val orientation: PhoneMapOrientation,
    val locationMarkerAttached: Boolean,
    val locationMarkerVisible: Boolean?,
    val locationMarkerDrawCalls: Int,
    val locationMarkerBitmapDrawObserved: Boolean,
    val locationMarkerLastDrawResult: String?,
) {
    fun toReportSection(): String =
        buildString {
            appendLine("Offline map runtime")
            appendLine("Renderer id: $rendererId")
            appendLine("MapView id: $mapViewId")
            appendLine("Base layer id: ${layerId ?: "none"}")
            appendLine("Tile cache id: ${cacheId ?: "none"}")
            appendLine("MapView attached: $mapViewAttached")
            appendLine("MapView window focus: $mapViewHasWindowFocus")
            appendLine("MapView size: ${mapViewWidth}x$mapViewHeight")
            appendLine("MapView render ready: $mapViewRenderReady")
            appendLine("Android MapView draw observed: $androidMapViewDrawObserved")
            appendLine("Tile layer draw observed: $tileLayerDrawObserved")
            appendLine("First visible base tile: $firstVisibleBaseTileObserved")
            appendLine("Layer count: $layerCount")
            appendLine("Tile layer present: $tileLayerPresent")
            appendLine("Tile layer visible: ${tileLayerVisible ?: "unknown"}")
            appendLine("Framebuffer dimension available: $frameBufferDimensionAvailable")
            appendLine("Framebuffer size: ${frameBufferWidth ?: "unknown"}x${frameBufferHeight ?: "unknown"}")
            appendLine("Framebuffer drawing bitmap ready: ${frameBufferDrawingBitmapReady ?: "unknown"}")
            appendLine("Zoom: ${zoom ?: "unknown"}")
            appendLine("Camera inside map bounds: ${cameraInsideMapBounds ?: "unknown"}")
            appendLine("Visible tiles: $visibleTileCount")
            appendLine("Drawable visible tiles: $drawableVisibleTileCount")
            appendLine("Parent fallback tiles: $parentFallbackTileCount")
            appendLine("Pending tile jobs: $pendingTileJobCount")
            appendLine("Location permission: $locationPermissionGranted")
            appendLine("Location available: $locationAvailable")
            appendLine("Location age: ${locationAgeMillis.toPhoneMapLocationAgeLabel()}")
            appendLine("Location accuracy: ${locationAccuracyMeters.toPhoneMapLocationAccuracyLabel()}")
            appendLine("Location inside map bounds: ${locationInsideMapBounds ?: "unknown"}")
            appendLine("Follow mode: $followMode")
            appendLine("Orientation: $orientation")
            append("Location marker attached: $locationMarkerAttached")
            appendLine()
            appendLine("Location marker visible: ${locationMarkerVisible ?: "unknown"}")
            appendLine("Location marker draw calls: $locationMarkerDrawCalls")
            appendLine("Location marker bitmap draw observed: $locationMarkerBitmapDrawObserved")
            append("Location marker last draw result: ${locationMarkerLastDrawResult ?: "unknown"}")
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
    val insideBounds = location?.let { fix -> mapBounds?.contains(fix.latitude, fix.longitude) }
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
