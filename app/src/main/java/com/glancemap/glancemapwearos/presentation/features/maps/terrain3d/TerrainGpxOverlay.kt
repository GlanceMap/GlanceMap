package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxTrackDetails
import com.glancemap.glancemapwearos.presentation.features.navigate.applyOpacityToColor
import com.glancemap.glancemapwearos.presentation.features.navigate.buildElevationTrackSegments
import com.glancemap.glancemapwearos.presentation.features.navigate.latLongSegments
import org.mapsforge.core.model.LatLong

internal data class TerrainGpxOverlay(
    val tracks: List<GpxTrackDetails>,
    val color: Int,
    val colorMode: String,
    val width: Float,
    val opacityPercent: Int,
)

internal fun drawTerrainGpx(
    bitmap: Bitmap,
    area: TerrainArea,
    overlay: TerrainGpxOverlay,
    checkCancelled: () -> Unit,
) {
    val canvas = Canvas(bitmap)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = overlay.width.takeIf { it.isFinite() && it > 0f } ?: 3f
        }
    val path = Path()

    fun drawSegment(points: List<LatLong>) {
        path.rewind()
        var previousLongitude: Double? = null
        points.forEachIndexed { index, point ->
            if (index % 256 == 0) checkCancelled()
            val (u, v) = area.texturePosition(point.latitude, point.longitude)
            val x = (u * canvas.width).toFloat()
            val y = (v * canvas.height).toFloat()
            val previous = previousLongitude
            if (previous == null || terrainGpxCrossesDateline(previous, point.longitude)) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
            previousLongitude = point.longitude
        }
        canvas.drawPath(path, paint)
    }
    overlay.tracks.forEach { track ->
        checkCancelled()
        if (overlay.colorMode == SettingsRepository.GPX_TRACK_COLOR_MODE_ELEVATION) {
            buildElevationTrackSegments(track.trackPoints, overlay.opacityPercent).forEach { segment ->
                paint.color = segment.color
                drawSegment(segment.points)
            }
        } else {
            paint.color = applyOpacityToColor(overlay.color, overlay.opacityPercent)
            track.trackPoints.latLongSegments().forEach { segment ->
                drawSegment(segment)
            }
        }
    }
}
