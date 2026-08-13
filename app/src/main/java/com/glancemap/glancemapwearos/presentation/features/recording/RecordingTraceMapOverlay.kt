package com.glancemap.glancemapwearos.presentation.features.recording

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.glancemap.glancemapwearos.presentation.features.maps.RotatableMarker
import com.glancemap.glancemapwearos.presentation.features.maps.mutateLayers
import com.glancemap.glancemapwearos.presentation.features.navigate.MapTopOverlayCoordinator
import com.glancemap.glancemapwearos.presentation.features.navigate.requestLayerRedrawSafely
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.Layers
import org.mapsforge.map.layer.overlay.Polyline
import kotlin.math.roundToInt

@Composable
@Suppress("FunctionNaming")
internal fun RecordingTraceOverlayEffect(
    mapView: MapView,
    segments: List<List<LatLong>>,
    followLocationMarker: Boolean,
    locationMarker: RotatableMarker?,
    topOverlayCoordinator: MapTopOverlayCoordinator,
) {
    val paint =
        remember {
            AndroidGraphicFactory.INSTANCE.createPaint().apply {
                setStyle(Style.STROKE)
                color = Color.argb(240, 0, 200, 83)
                strokeWidth = 5f
            }
        }
    val traceLayers = remember(mapView) { RecordingTraceLayers(paint) }

    LaunchedEffect(mapView, segments, followLocationMarker, locationMarker, topOverlayCoordinator) {
        mapView.mutateLayers { layers ->
            val renderState =
                recordingTraceRenderState(
                    segments = segments,
                    followLocationMarker = followLocationMarker && locationMarker != null,
                )
            var changed = traceLayers.sync(layers, renderState, locationMarker)
            changed = topOverlayCoordinator.sync(layers) || changed
            if (changed) {
                mapView.requestLayerRedrawSafely()
            }
        }
    }

    DisposableEffect(mapView) {
        onDispose {
            mapView.mutateLayers { layers ->
                if (traceLayers.clear(layers)) {
                    mapView.requestLayerRedrawSafely()
                }
            }
        }
    }
}

internal data class RecordingTraceRenderState(
    val segments: List<List<LatLong>>,
    val liveTailStart: LatLong?,
)

/**
 * Keeps every canonical point that can still be revised out of the visual trace. The saved GPX
 * remains untouched, while the line displayed on the map ends at the smoothly rendered marker
 * instead of shifting sideways when the recording filter corrects its tail.
 */
internal fun recordingTraceRenderState(
    segments: List<List<LatLong>>,
    followLocationMarker: Boolean,
): RecordingTraceRenderState =
    if (!followLocationMarker || segments.isEmpty() || segments.last().isEmpty()) {
        RecordingTraceRenderState(segments = segments, liveTailStart = null)
    } else {
        val lastSegment = segments.last()
        val stablePointCount =
            (lastSegment.size - RECORDING_TRACE_REVISION_TAIL_POINT_COUNT).coerceAtLeast(0)
        RecordingTraceRenderState(
            segments =
                buildList {
                    addAll(segments.dropLast(1))
                    add(lastSegment.take(stablePointCount))
                },
            liveTailStart =
                lastSegment.getOrNull(stablePointCount - 1)
                    ?: lastSegment.first(),
        )
    }

private class RecordingTraceLayers(
    paint: Paint,
) {
    private val polylines = mutableListOf<Polyline>()
    private val liveTailLayer = RecordingTraceLiveTailLayer(paint)
    private val paint = paint

    fun sync(
        layers: Layers,
        renderState: RecordingTraceRenderState,
        locationMarker: RotatableMarker?,
    ): Boolean {
        val visibleSegments = renderState.segments.filter { it.size >= MIN_RECORDING_TRACE_POINTS }
        var changed = syncPolylines(layers, visibleSegments)
        changed = syncLiveTail(layers, renderState.liveTailStart, locationMarker) || changed
        return changed
    }

    fun clear(layers: Layers): Boolean {
        var changed = false
        polylines.forEach { polyline ->
            changed = layers.remove(polyline) || changed
            polyline.latLongs.clear()
        }
        liveTailLayer.anchorMarker = null
        liveTailLayer.startLatLong = null
        changed = layers.remove(liveTailLayer) || changed
        polylines.clear()
        return changed
    }

    private fun syncPolylines(
        layers: Layers,
        visibleSegments: List<List<LatLong>>,
    ): Boolean {
        var changed = false
        while (polylines.size < visibleSegments.size) {
            polylines += Polyline(paint, AndroidGraphicFactory.INSTANCE)
        }
        visibleSegments.forEachIndexed { index, points ->
            val polyline = polylines[index]
            if (!layers.contains(polyline)) {
                layers.add(polyline)
                changed = true
            }
            if (!sameLatLongs(polyline.latLongs, points)) {
                polyline.latLongs.clear()
                polyline.latLongs.addAll(points)
                changed = true
            }
        }
        for (index in polylines.lastIndex downTo visibleSegments.size) {
            val polyline = polylines.removeAt(index)
            changed = layers.remove(polyline) || changed
            polyline.latLongs.clear()
        }
        return changed
    }

    private fun syncLiveTail(
        layers: Layers,
        startLatLong: LatLong?,
        locationMarker: RotatableMarker?,
    ): Boolean {
        liveTailLayer.anchorMarker = locationMarker
        liveTailLayer.startLatLong = startLatLong
        val shouldShow = startLatLong != null && locationMarker != null
        return when {
            shouldShow && !layers.contains(liveTailLayer) -> {
                layers.add(liveTailLayer)
                true
            }
            !shouldShow -> layers.remove(liveTailLayer)
            else -> false
        }
    }
}

private class RecordingTraceLiveTailLayer(
    private val paint: Paint,
) : Layer() {
    var startLatLong: LatLong? = null
    var anchorMarker: RotatableMarker? = null

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeft: Point,
        mapViewRotation: Rotation,
    ) {
        val endpoints =
            startLatLong
                ?.takeIf { isVisible }
                ?.let { start -> anchorMarker?.latLong?.let { end -> start to end } }
                ?: return
        val (start, end) = endpoints
        val mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel.tileSize)
        val startX =
            (MercatorProjection.longitudeToPixelX(start.longitude, mapSize) - topLeft.x)
                .roundToInt()
        val startY =
            (MercatorProjection.latitudeToPixelY(start.latitude, mapSize) - topLeft.y)
                .roundToInt()
        val endX =
            (MercatorProjection.longitudeToPixelX(end.longitude, mapSize) - topLeft.x)
                .roundToInt()
        val endY =
            (MercatorProjection.latitudeToPixelY(end.latitude, mapSize) - topLeft.y)
                .roundToInt()
        canvas.drawLine(startX, startY, endX, endY, paint)
    }
}

private fun sameLatLongs(
    current: List<LatLong>,
    next: List<LatLong>,
): Boolean {
    if (current.size != next.size) return false
    return current.indices.all { index ->
        current[index].latitude == next[index].latitude &&
            current[index].longitude == next[index].longitude
    }
}

private const val MIN_RECORDING_TRACE_POINTS = 2
private const val RECORDING_TRACE_REVISION_TAIL_POINT_COUNT = 2
