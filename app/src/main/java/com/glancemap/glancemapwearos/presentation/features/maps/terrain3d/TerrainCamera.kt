package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import com.glancemap.glancemapwearos.core.maps.sanitizeTerrain3dViewingAngle
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

private const val MIN_TERRAIN_CAMERA_DISTANCE = 1000f
private const val MAX_TERRAIN_CAMERA_DISTANCE = 14000f
private const val TERRAIN_ZOOM_STEP_FACTOR = 1.4f
internal const val TERRAIN_FIELD_OF_VIEW_DEGREES = 30f
internal const val INITIAL_TERRAIN_CAMERA_DISTANCE = 2000f

internal fun terrainCameraOffset(
    distance: Float,
    elevation: Float,
    bearing: Float,
): Triple<Float, Float, Float> {
    val pitchRadians = Math.toRadians(elevation.toDouble())
    val bearingRadians = Math.toRadians(bearing.toDouble())
    val horizontal = distance * cos(pitchRadians).toFloat()
    return Triple(
        -horizontal * sin(bearingRadians).toFloat(),
        distance * sin(pitchRadians).toFloat(),
        horizontal * cos(bearingRadians).toFloat(),
    )
}

internal fun terrainFootprintRadius(
    distance: Float,
    viewingAngle: Float,
): Float {
    val elevation = Math.toRadians((90f - viewingAngle.coerceIn(45f, 70f)).toDouble())
    val halfFov = Math.toRadians(TERRAIN_FIELD_OF_VIEW_DEGREES / 2.0)
    val far = distance * (sin(elevation) / tan(elevation - halfFov) - cos(elevation))
    return max(far.toFloat(), (distance * tan(halfFov)).toFloat())
}

internal fun terrainPreparedWidth(
    distance: Float,
    viewingAngle: Float,
): Float = (terrainFootprintRadius(distance, viewingAngle) * 4f).coerceIn(4000f, 100000f)

internal fun terrainSafeRadius(
    widthMeters: Float,
    distance: Float,
    viewingAngle: Float,
): Float =
    ((widthMeters / 2f - terrainFootprintRadius(distance, viewingAngle)) * 0.8f)
        .coerceIn(widthMeters * 0.05f, widthMeters * 0.25f)

internal class TerrainGesture {
    private var eligible = false
    var tilting = false
        private set
    var panning = false
        private set

    fun press() {
        eligible = true
        tilting = false
        panning = false
    }

    fun longPress(): Boolean {
        if (!eligible || panning) return false
        tilting = true
        eligible = false
        return true
    }

    fun startPan() {
        eligible = false
        panning = true
    }

    fun multiTouch() {
        eligible = false
        tilting = false
    }

    fun release() {
        eligible = false
        tilting = false
        panning = false
    }
}

@Suppress("LongParameterList")
internal fun terrainPanDelta(
    dx: Float,
    dy: Float,
    distance: Float,
    elevationDegrees: Float,
    viewportHeight: Int,
    bearingDegrees: Float = 0f,
): Pair<Float, Float> {
    val validGesture = dx.isFinite() && dy.isFinite()
    val validCamera = distance.isFinite() && elevationDegrees.isFinite() && bearingDegrees.isFinite()
    val validPitch = elevationDegrees > 0f && elevationDegrees < 90f
    val validViewport = viewportHeight > 0 && distance > 0f && validPitch
    return if (validGesture && validCamera && validViewport) {
        val halfFov = Math.toRadians(TERRAIN_FIELD_OF_VIEW_DEGREES / 2.0)
        val scale = (2.0 * distance * tan(halfFov) / viewportHeight).toFloat()
        val pitchScale = sin(Math.toRadians(elevationDegrees.toDouble())).toFloat()
        val screenEast = -dx * scale
        val screenNorth = dy * scale / pitchScale
        val bearing = Math.toRadians(bearingDegrees.toDouble())
        val east = screenEast * cos(bearing) + screenNorth * sin(bearing)
        val north = -screenEast * sin(bearing) + screenNorth * cos(bearing)
        east.toFloat() to north.toFloat()
    } else {
        0f to 0f
    }
}

internal class TerrainBearing {
    @Volatile
    var degrees: Float = 0f
        private set

    fun set(value: Float): Boolean {
        if (!value.isFinite()) return false
        val normalized = (value % 360f + 360f) % 360f
        return if (degrees == normalized) {
            false
        } else {
            degrees = normalized
            true
        }
    }

    fun rotateByDrag(deltaX: Float): Boolean = if (deltaX.isFinite()) set(degrees + deltaX * 0.5f) else false
}

internal class TerrainPitch(
    initialViewingAngle: Int,
) {
    @Volatile
    var rendererElevationDegrees: Float = 90f - sanitizeTerrain3dViewingAngle(initialViewingAngle)
        private set

    val viewingAngleDegrees: Float
        get() = 90f - rendererElevationDegrees

    @Suppress("ReturnCount")
    fun setInteractiveViewingAngle(angle: Float): Boolean {
        if (!angle.isFinite()) return false
        val elevation = 90f - angle.coerceIn(45f, 70f)
        if (rendererElevationDegrees == elevation) return false
        rendererElevationDegrees = elevation
        return true
    }

    fun tiltByDrag(deltaY: Float): Boolean =
        if (deltaY.isFinite()) {
            setInteractiveViewingAngle(viewingAngleDegrees - deltaY * 0.1f)
        } else {
            false
        }

    fun setViewingAngle(angle: Int): Boolean {
        val elevation = 90f - sanitizeTerrain3dViewingAngle(angle)
        if (rendererElevationDegrees == elevation) return false
        rendererElevationDegrees = elevation
        return true
    }
}

internal fun terrainZoomDistance(
    distance: Float,
    factor: Float,
): Float =
    if (factor.isFinite() && factor > 0f) {
        (distance / factor).coerceIn(MIN_TERRAIN_CAMERA_DISTANCE, MAX_TERRAIN_CAMERA_DISTANCE)
    } else {
        distance
    }

internal fun terrainZoomByStep(
    distance: Float,
    step: Int,
): Float =
    when {
        step > 0 -> terrainZoomDistance(distance, TERRAIN_ZOOM_STEP_FACTOR)
        step < 0 -> terrainZoomDistance(distance, 1f / TERRAIN_ZOOM_STEP_FACTOR)
        else -> distance
    }
