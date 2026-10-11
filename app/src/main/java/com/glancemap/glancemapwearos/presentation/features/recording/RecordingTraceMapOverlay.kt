package com.glancemap.glancemapwearos.presentation.features.recording

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.glancemap.glancemapwearos.presentation.features.maps.RotatableMarker
import com.glancemap.glancemapwearos.presentation.features.maps.mutateLayers
import com.glancemap.glancemapwearos.presentation.features.navigate.MapTopOverlayCoordinator
import com.glancemap.glancemapwearos.presentation.features.navigate.requestLayerRedrawSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.GraphicFactory
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
import kotlin.math.roundToInt

@Composable
@Suppress("FunctionNaming")
internal fun RecordingTraceOverlayEffect(
    mapView: MapView,
    recordingState: TraceRecordingUiState,
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
    val cachedGeometry = remember(mapView) { mutableStateOf<RecordingTraceGeometry?>(null) }

    LaunchedEffect(
        mapView,
        recordingState.startedAtMillis,
        recordingState.pointsRevision,
        followLocationMarker,
        locationMarker,
        topOverlayCoordinator,
    ) {
        val previous = cachedGeometry.value
        val geometry =
            if (recordingState.points.isEmpty() || previous?.points === recordingState.points) {
                prepareRecordingTraceGeometry(recordingState, previous)
            } else {
                withContext(Dispatchers.Default) { prepareRecordingTraceGeometry(recordingState, previous) }
            }
        cachedGeometry.value = geometry
        mapView.mutateLayers { layers ->
            val renderState =
                recordingTraceRenderState(
                    segments = geometry.segments,
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
                    add(lastSegment.subList(0, stablePointCount))
                },
            liveTailStart =
                lastSegment.getOrNull(stablePointCount - 1)
                    ?: lastSegment.first(),
        )
    }

private class RecordingTraceLayers(
    paint: Paint,
) {
    private val historyLayer = RecordingTraceHistoryLayer(paint)
    private val liveTailLayer = RecordingTraceLiveTailLayer(paint)

    fun sync(
        layers: Layers,
        renderState: RecordingTraceRenderState,
        locationMarker: RotatableMarker?,
    ): Boolean {
        historyLayer.segments = renderState.segments
        var changed = syncHistory(layers, renderState.segments.any { it.size >= MIN_RECORDING_TRACE_POINTS })
        changed = syncLiveTail(layers, renderState.liveTailStart, locationMarker) || changed
        return changed
    }

    fun clear(layers: Layers): Boolean {
        var changed = layers.remove(historyLayer)
        historyLayer.segments = emptyList()
        liveTailLayer.anchorMarker = null
        liveTailLayer.startLatLong = null
        changed = layers.remove(liveTailLayer) || changed
        return changed
    }

    private fun syncHistory(
        layers: Layers,
        visible: Boolean,
    ): Boolean =
        when {
            visible && !layers.contains(historyLayer) -> {
                layers.add(historyLayer)
                true
            }
            visible -> true // A new immutable geometry snapshot still needs drawing.
            else -> layers.remove(historyLayer)
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

internal class RecordingTraceHistoryLayer(
    private val paint: Paint,
    private val graphicFactory: GraphicFactory = AndroidGraphicFactory.INSTANCE,
) : Layer() {
    @Volatile var segments: List<List<LatLong>> = emptyList()

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeft: Point,
        mapViewRotation: Rotation,
    ) {
        val snapshot = segments
        if (!isVisible) return
        val mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel.tileSize)
        snapshot.forEach { points ->
            if (points.size >= MIN_RECORDING_TRACE_POINTS) {
                val path = graphicFactory.createPath()
                points.forEachIndexed { index, point ->
                    val x = (MercatorProjection.longitudeToPixelX(point.longitude, mapSize) - topLeft.x).toFloat()
                    val y = (MercatorProjection.latitudeToPixelY(point.latitude, mapSize) - topLeft.y).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, paint)
            }
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

private const val MIN_RECORDING_TRACE_POINTS = 2
private const val RECORDING_TRACE_REVISION_TAIL_POINT_COUNT = 2
