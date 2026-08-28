@file:Suppress("ComplexMethod", "LargeClass", "LongMethod", "MagicNumber", "TooManyFunctions")

package com.glancemap.glancemapcompanionapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.glancemap.glancemapcompanionapp.map.PhoneMapRendererCatalog
import com.glancemap.glancemapcompanionapp.map.maplibre.mapLibreRasterStyleJson
import com.google.android.gms.location.LocationServices
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val MAP_LATITUDE_LIMIT = 85.0
private const val MIN_PICKER_SPAN = 0.02
private const val MAP_PICKER_MIN_ZOOM = 2.0
private const val MAP_PICKER_MAX_ZOOM = 18.0
private const val MAP_PICKER_ZOOM_STEP = 1.0
private const val ROUTING_TILE_DEGREES = 5
private const val ROUTING_TILE_EPSILON = 1e-9
private val MAP_PICKER_FALLBACK_COLOR = 0xffeef1f5.toInt()

internal data class MapPickerBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    val lonSpan: Double = abs(east - west)
    val latSpan: Double = abs(north - south)
    val areaDegrees: Double = lonSpan * latSpan

    fun toBboxString(): String =
        listOf(west, south, east, north)
            .joinToString(",") { coordinate ->
                String
                    .format(Locale.US, "%.5f", coordinate)
                    .trimEnd('0')
                    .trimEnd('.')
            }

    fun center(): LatLng = LatLng((south + north) / 2.0, (west + east) / 2.0)

    fun movedTo(
        latitude: Double,
        longitude: Double,
    ): MapPickerBounds =
        safe(
            west = longitude - lonSpan / 2.0,
            south = latitude - latSpan / 2.0,
            east = longitude + lonSpan / 2.0,
            north = latitude + latSpan / 2.0,
        )

    fun toMapLibreBounds(): LatLngBounds {
        val renderSouth = south.coerceIn(-MAP_LATITUDE_LIMIT, MAP_LATITUDE_LIMIT)
        val renderNorth = north.coerceIn(-MAP_LATITUDE_LIMIT, MAP_LATITUDE_LIMIT)
        return LatLngBounds
            .Builder()
            .include(LatLng(renderSouth, west))
            .include(LatLng(renderNorth, east))
            .build()
    }

    companion object {
        fun parseOrNull(input: String): MapPickerBounds? {
            val values =
                input
                    .split(',')
                    .map { it.trim().toDoubleOrNull() }
            val bounds =
                if (values.size == 4 && values.none { it == null }) {
                    MapPickerBounds(
                        west = values[0] ?: 0.0,
                        south = values[1] ?: 0.0,
                        east = values[2] ?: 0.0,
                        north = values[3] ?: 0.0,
                    )
                } else {
                    null
                }

            return bounds?.takeIf { candidate ->
                val longitudeValid = candidate.west in -180.0..180.0 && candidate.east in -180.0..180.0
                val latitudeValid = candidate.south in -90.0..90.0 && candidate.north in -90.0..90.0
                val ordered = candidate.west < candidate.east && candidate.south < candidate.north
                longitudeValid && latitudeValid && ordered
            }
        }

        fun safe(
            west: Double,
            south: Double,
            east: Double,
            north: Double,
            minSpan: Double = MIN_PICKER_SPAN,
        ): MapPickerBounds {
            var safeWest = west.coerceIn(-180.0, 180.0)
            var safeEast = east.coerceIn(-180.0, 180.0)
            var safeSouth = south.coerceIn(-MAP_LATITUDE_LIMIT, MAP_LATITUDE_LIMIT)
            var safeNorth = north.coerceIn(-MAP_LATITUDE_LIMIT, MAP_LATITUDE_LIMIT)
            if (safeWest > safeEast) {
                val oldWest = safeWest
                safeWest = safeEast
                safeEast = oldWest
            }
            if (safeSouth > safeNorth) {
                val oldSouth = safeSouth
                safeSouth = safeNorth
                safeNorth = oldSouth
            }
            if (safeEast - safeWest < minSpan) {
                val center = (safeWest + safeEast) / 2.0
                safeWest = (center - minSpan / 2.0).coerceIn(-180.0, 180.0 - minSpan)
                safeEast = safeWest + minSpan
            }
            if (safeNorth - safeSouth < minSpan) {
                val center = (safeSouth + safeNorth) / 2.0
                safeSouth = (center - minSpan / 2.0).coerceIn(-MAP_LATITUDE_LIMIT, MAP_LATITUDE_LIMIT - minSpan)
                safeNorth = safeSouth + minSpan
            }
            return MapPickerBounds(safeWest, safeSouth, safeEast, safeNorth)
        }

        fun defaultAround(
            latitude: Double,
            longitude: Double,
        ): MapPickerBounds =
            safe(
                west = longitude - 0.35,
                south = latitude - 0.25,
                east = longitude + 0.35,
                north = latitude + 0.25,
            )
    }
}

internal class MapLibreBboxPickerView(
    context: Context,
    initialBounds: MapPickerBounds,
    private val useLocationDefault: Boolean,
    watchInstalledCoverageAreas: List<WatchInstalledCoverageArea>,
    private val onBoundsChanged: (String) -> Unit,
    onReady: () -> Unit,
) : BaseMapLibrePickerView(context, onReady) {
    private var selectedBounds = initialBounds
    private var watchInstalledCoverageAreas = watchInstalledCoverageAreas
    private val watchCoverageOverlay =
        WatchInstalledCoverageOverlay(
            context = context,
            areasProvider = { this.watchInstalledCoverageAreas },
            mapProvider = { map },
        )
    private val overlay =
        BboxSelectionOverlay(
            context = context,
            boundsProvider = { selectedBounds },
            mapProvider = { map },
            onBoundsChanged = { bounds ->
                setSelectedBounds(bounds)
            },
        )

    init {
        addView(
            watchCoverageOverlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            overlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun setWatchInstalledCoverageAreas(areas: List<WatchInstalledCoverageArea>) {
        watchInstalledCoverageAreas = areas
        watchCoverageOverlay.invalidate()
    }

    fun setFineControlEnabled(enabled: Boolean) {
        overlay.fineControlEnabled = enabled
        overlay.invalidate()
    }

    override fun onStyleReady(map: MapLibreMap) {
        map.addOnMapClickListener(
            MapLibreMap.OnMapClickListener { point ->
                setSelectedBounds(selectedBounds.movedTo(point.latitude, point.longitude))
                true
            },
        )
        setSelectedBounds(selectedBounds)
        moveCameraToBounds(selectedBounds, paddingDp = 44)
        if (useLocationDefault) {
            centerOnLastKnownLocation()
        }
    }

    private fun centerOnLastKnownLocation() {
        requestLastKnownLocation(context) { location ->
            val bounds = MapPickerBounds.defaultAround(location.latitude, location.longitude)
            setSelectedBounds(bounds)
            moveCameraToBounds(bounds, paddingDp = 44)
        }
    }

    private fun setSelectedBounds(bounds: MapPickerBounds) {
        selectedBounds = bounds
        overlay.invalidate()
        onBoundsChanged(bounds.toBboxString())
    }
}

internal class MapLibreRoutingTilePickerView(
    context: Context,
    private val initialBounds: MapPickerBounds,
    private val useLocationDefault: Boolean,
    watchInstalledCoverageAreas: List<WatchInstalledCoverageArea>,
    private val onSelectionChanged: (bbox: String, tiles: List<String>) -> Unit,
    onReady: () -> Unit,
) : BaseMapLibrePickerView(context, onReady) {
    private val selectedTiles = linkedSetOf<RoutingTile>()
    private var watchInstalledCoverageAreas = watchInstalledCoverageAreas
    private val watchCoverageOverlay =
        WatchInstalledCoverageOverlay(
            context = context,
            areasProvider = { this.watchInstalledCoverageAreas },
            mapProvider = { map },
        )
    private val overlay =
        RoutingTileOverlay(
            context = context,
            selectedTilesProvider = { selectedTiles.toSet() },
            mapProvider = { map },
        )

    init {
        selectedTiles += routingTilesForBounds(initialBounds)
        addView(
            watchCoverageOverlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            overlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun setWatchInstalledCoverageAreas(areas: List<WatchInstalledCoverageArea>) {
        watchInstalledCoverageAreas = areas
        watchCoverageOverlay.invalidate()
    }

    override fun onStyleReady(map: MapLibreMap) {
        map.addOnMapClickListener(
            MapLibreMap.OnMapClickListener { point ->
                toggleTileAt(point)
                true
            },
        )
        notifySelection()
        moveCameraToBounds(selectedBounds() ?: initialBounds, paddingDp = 36)
        if (useLocationDefault) {
            centerOnLastKnownLocation()
        }
    }

    private fun centerOnLastKnownLocation() {
        requestLastKnownLocation(context) { location ->
            val tile = RoutingTile.containing(location.latitude, location.longitude)
            selectedTiles.clear()
            selectedTiles += tile
            overlay.invalidate()
            notifySelection()
            moveCameraToBounds(tile.bounds, paddingDp = 36)
        }
    }

    private fun toggleTileAt(point: LatLng) {
        val tile = RoutingTile.containing(point.latitude, point.longitude)
        if (selectedTiles.contains(tile)) {
            selectedTiles -= tile
        } else {
            selectedTiles += tile
        }
        overlay.invalidate()
        notifySelection()
    }

    private fun selectedBounds(): MapPickerBounds? {
        if (selectedTiles.isEmpty()) return null
        val west = selectedTiles.minOf { it.west }.toDouble()
        val south = selectedTiles.minOf { it.south }.toDouble()
        val east = selectedTiles.maxOf { it.east }.toDouble()
        val north = selectedTiles.maxOf { it.north }.toDouble()
        return MapPickerBounds(west, south, east, north)
    }

    private fun notifySelection() {
        val bounds = selectedBounds()
        if (bounds == null) {
            onSelectionChanged("", emptyList())
            return
        }
        onSelectionChanged(
            bounds.toBboxString(),
            selectedTiles.map { it.fileName }.sorted(),
        )
    }
}

internal abstract class BaseMapLibrePickerView(
    context: Context,
    private val onReady: () -> Unit,
) : FrameLayout(context) {
    protected val mapView: MapView
    protected var map: MapLibreMap? = null
    private var destroyed = false
    private var started = false
    private var readyNotified = false

    init {
        ensureMapLibreConfigured(context)
        setBackgroundColor(MAP_PICKER_FALLBACK_COLOR)
        mapView =
            MapView(
                context,
                MapLibreMapOptions
                    .createFromAttributes(context)
                    .textureMode(true)
                    .translucentTextureSurface(false)
                    .setPrefetchZoomDelta(0)
                    .foregroundLoadColor(MAP_PICKER_FALLBACK_COLOR),
            )
        mapView.setBackgroundColor(MAP_PICKER_FALLBACK_COLOR)
        mapView.onCreate(null)
        addView(
            mapView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        mapView.getMapAsync { loadedMap ->
            if (destroyed) return@getMapAsync
            map = loadedMap
            configureMapCallbacks(loadedMap)
            loadedMap.setStyle(
                Style.Builder().fromJson(
                    PhoneMapRendererCatalog.utilityPickerRasterProvider.mapLibreRasterStyleJson(),
                ),
            ) {
                onStyleReady(loadedMap)
                notifyReady()
            }
        }
    }

    abstract fun onStyleReady(map: MapLibreMap)

    fun zoomIn() {
        zoomBy(MAP_PICKER_ZOOM_STEP)
    }

    fun zoomOut() {
        zoomBy(-MAP_PICKER_ZOOM_STEP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!destroyed && !started) {
            mapView.onStart()
            mapView.onResume()
            started = true
        }
    }

    override fun onDetachedFromWindow() {
        if (!destroyed) {
            if (started) {
                mapView.onPause()
                mapView.onStop()
                started = false
            }
            mapView.onDestroy()
            destroyed = true
        }
        super.onDetachedFromWindow()
    }

    protected fun moveCameraToBounds(
        bounds: MapPickerBounds,
        paddingDp: Int,
    ) {
        val activeMap = map ?: return
        mapView.post {
            if (mapView.width == 0 || mapView.height == 0) {
                moveCameraToBounds(bounds, paddingDp)
                return@post
            }
            activeMap.moveCamera(
                CameraUpdateFactory.newLatLngBounds(
                    bounds.toMapLibreBounds(),
                    dp(paddingDp),
                ),
            )
        }
    }

    private fun configureMapCallbacks(activeMap: MapLibreMap) {
        activeMap.setMinZoomPreference(MAP_PICKER_MIN_ZOOM)
        activeMap.setMaxZoomPreference(MAP_PICKER_MAX_ZOOM)
        activeMap.addOnCameraMoveListener(
            MapLibreMap.OnCameraMoveListener {
                invalidateOverlays()
            },
        )
        activeMap.addOnCameraIdleListener(
            MapLibreMap.OnCameraIdleListener {
                invalidateOverlays()
            },
        )
    }

    private fun zoomBy(delta: Double) {
        val activeMap = map ?: return
        val nextZoom =
            (activeMap.cameraPosition.zoom + delta)
                .coerceIn(MAP_PICKER_MIN_ZOOM, MAP_PICKER_MAX_ZOOM)
        activeMap.animateCamera(CameraUpdateFactory.zoomTo(nextZoom))
    }

    private fun invalidateOverlays() {
        for (index in 0 until childCount) {
            getChildAt(index).invalidate()
        }
    }

    private fun notifyReady() {
        if (!readyNotified) {
            readyNotified = true
            onReady()
        }
    }

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class BboxSelectionOverlay(
    context: Context,
    private val boundsProvider: () -> MapPickerBounds,
    private val mapProvider: () -> MapLibreMap?,
    private val onBoundsChanged: (MapPickerBounds) -> Unit,
) : View(context) {
    var fineControlEnabled: Boolean = false
    private var activeHandle: BboxHandle? = null
    private var moveDragPending = false
    private var moveDragActive = false
    private var downX = 0f
    private var downY = 0f
    private var moveStartPoint: LatLng? = null
    private var moveStartBounds: MapPickerBounds? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val moveDragRunnable =
        Runnable {
            if (moveDragPending) {
                moveDragActive = true
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xff0b7285.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dpFloat(3)
        }
    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x330b7285
            style = Paint.Style.FILL
        }
    private val shadePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x1f0a0f14
            style = Paint.Style.FILL
        }
    private val handleFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xff0b7285.toInt()
            style = Paint.Style.FILL
        }
    private val handleStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xffffffff.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dpFloat(3)
        }
    private val moveHandleFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xe6ffffff.toInt()
            style = Paint.Style.FILL
        }
    private val moveHandleIcon = ContextCompat.getDrawable(context, R.drawable.ic_drag_pan)?.mutate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = selectionRect()
        drawOutsideShade(canvas, rect)
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, strokePaint)
        handlesForRect(rect).forEach { point ->
            val radius = if (fineControlEnabled) dpFloat(9) else dpFloat(12)
            drawHandle(canvas, point, radius)
        }
        drawMoveHandle(canvas, PointF(rect.centerX(), rect.centerY()))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val currentHandle = activeHandle
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = handleAt(event.x, event.y)
                if (activeHandle != null) {
                    true
                } else {
                    prepareMoveDrag(event.x, event.y)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when {
                    currentHandle != null -> {
                        updateBoundsFromHandle(currentHandle, event.x, event.y)
                        true
                    }

                    moveDragPending -> {
                        if (moveDragActive) {
                            updateBoundsFromMoveDrag(event.x, event.y)
                        } else if (movedBeyondSlop(event.x, event.y)) {
                            cancelMoveDrag()
                        }
                        true
                    }

                    else -> false
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                val consumed = activeHandle != null || moveDragPending
                activeHandle = null
                cancelMoveDrag()
                consumed
            }

            else -> false
        }
    }

    private fun prepareMoveDrag(
        x: Float,
        y: Float,
    ): Boolean {
        val point =
            mapProvider()
                ?.projection
                ?.fromScreenLocation(PointF(x, y))
        val canMove = moveHandleHitAt(x, y) && point != null
        if (canMove) {
            downX = x
            downY = y
            moveStartPoint = point
            moveStartBounds = boundsProvider()
            moveDragPending = true
            moveDragActive = false
            postDelayed(moveDragRunnable, ViewConfiguration.getLongPressTimeout().toLong())
        }
        return canMove
    }

    private fun updateBoundsFromHandle(
        handle: BboxHandle,
        x: Float,
        y: Float,
    ) {
        val latLng =
            mapProvider()
                ?.projection
                ?.fromScreenLocation(PointF(x, y))
                ?: return
        val current = boundsProvider()
        val nextBounds =
            if (handle == BboxHandle.Scale) {
                current.scaledTo(latLng)
            } else {
                current.resizedTo(handle, latLng)
            }
        onBoundsChanged(nextBounds)
    }

    private fun updateBoundsFromMoveDrag(
        x: Float,
        y: Float,
    ) {
        val startPoint = moveStartPoint
        val startBounds = moveStartBounds
        val point =
            mapProvider()
                ?.projection
                ?.fromScreenLocation(PointF(x, y))
        if (startPoint != null && startBounds != null && point != null) {
            onBoundsChanged(
                startBounds.translatedBy(
                    latitudeDelta = point.latitude - startPoint.latitude,
                    longitudeDelta = point.longitude - startPoint.longitude,
                ),
            )
        }
    }

    private fun MapPickerBounds.translatedBy(
        latitudeDelta: Double,
        longitudeDelta: Double,
    ): MapPickerBounds =
        MapPickerBounds.safe(
            west = west + longitudeDelta,
            south = south + latitudeDelta,
            east = east + longitudeDelta,
            north = north + latitudeDelta,
        )

    private fun MapPickerBounds.scaledTo(point: LatLng): MapPickerBounds {
        val center = center()
        val lonScale = abs(point.longitude - center.longitude) / max(lonSpan / 2.0, MIN_PICKER_SPAN / 2.0)
        val latScale = abs(point.latitude - center.latitude) / max(latSpan / 2.0, MIN_PICKER_SPAN / 2.0)
        val scale = max(max(lonScale, latScale), 0.1)
        return MapPickerBounds.safe(
            west = center.longitude - lonSpan * scale / 2.0,
            south = center.latitude - latSpan * scale / 2.0,
            east = center.longitude + lonSpan * scale / 2.0,
            north = center.latitude + latSpan * scale / 2.0,
        )
    }

    private fun MapPickerBounds.resizedTo(
        handle: BboxHandle,
        point: LatLng,
    ): MapPickerBounds =
        MapPickerBounds.safe(
            west = if (handle.movesWest) point.longitude else west,
            south = if (handle.movesSouth) point.latitude else south,
            east = if (handle.movesEast) point.longitude else east,
            north = if (handle.movesNorth) point.latitude else north,
        )

    private fun handleAt(
        x: Float,
        y: Float,
    ): BboxHandle? {
        val rect = selectionRect()
        val points = handlesForRect(rect)
        val handles = visibleHandles()
        val threshold = dpFloat(34)
        return handles
            .zip(points)
            .minByOrNull { (_, point) -> hypot((x - point.x).toDouble(), (y - point.y).toDouble()) }
            ?.takeIf { (_, point) -> hypot((x - point.x).toDouble(), (y - point.y).toDouble()) <= threshold }
            ?.first
    }

    private fun moveHandleHitAt(
        x: Float,
        y: Float,
    ): Boolean {
        val rect = selectionRect()
        val center = PointF(rect.centerX(), rect.centerY())
        return hypot((x - center.x).toDouble(), (y - center.y).toDouble()) <= dpFloat(34)
    }

    private fun movedBeyondSlop(
        x: Float,
        y: Float,
    ): Boolean = hypot((x - downX).toDouble(), (y - downY).toDouble()) > touchSlop

    private fun cancelMoveDrag() {
        removeCallbacks(moveDragRunnable)
        moveDragPending = false
        moveDragActive = false
        moveStartPoint = null
        moveStartBounds = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun handlesForRect(rect: RectF): List<PointF> =
        visibleHandles().map { handle ->
            PointF(
                when (handle) {
                    BboxHandle.NorthWest,
                    BboxHandle.SouthWest,
                    -> rect.left

                    BboxHandle.NorthEast,
                    BboxHandle.SouthEast,
                    BboxHandle.Scale,
                    -> rect.right
                },
                when (handle) {
                    BboxHandle.NorthWest,
                    BboxHandle.NorthEast,
                    -> rect.top

                    BboxHandle.SouthEast,
                    BboxHandle.SouthWest,
                    BboxHandle.Scale,
                    -> rect.bottom
                },
            )
        }

    private fun visibleHandles(): List<BboxHandle> =
        if (fineControlEnabled) {
            BBOX_VERTEX_HANDLES
        } else {
            listOf(BboxHandle.Scale)
        }

    private fun selectionRect(): RectF {
        val bounds = boundsProvider()
        val activeMap = mapProvider()
        if (activeMap != null) {
            val northWest = activeMap.projection.toScreenLocation(LatLng(bounds.north, bounds.west))
            val southEast = activeMap.projection.toScreenLocation(LatLng(bounds.south, bounds.east))
            return RectF(
                min(northWest.x, southEast.x),
                min(northWest.y, southEast.y),
                max(northWest.x, southEast.x),
                max(northWest.y, southEast.y),
            )
        }
        return RectF(width * 0.22f, height * 0.26f, width * 0.78f, height * 0.74f)
    }

    private fun drawOutsideShade(
        canvas: Canvas,
        rect: RectF,
    ) {
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, shadePaint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), shadePaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, shadePaint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, shadePaint)
    }

    private fun drawHandle(
        canvas: Canvas,
        point: PointF,
        radius: Float,
    ) {
        canvas.drawCircle(point.x, point.y, radius, handleFillPaint)
        canvas.drawCircle(point.x, point.y, radius, handleStrokePaint)
    }

    private fun drawMoveHandle(
        canvas: Canvas,
        point: PointF,
    ) {
        val radius = dpFloat(13)
        canvas.drawCircle(point.x, point.y, radius, moveHandleFillPaint)
        canvas.drawCircle(point.x, point.y, radius, strokePaint)
        moveHandleIcon?.let { icon ->
            val halfSize = dpFloat(9).toInt()
            icon.setBounds(
                (point.x - halfSize).toInt(),
                (point.y - halfSize).toInt(),
                (point.x + halfSize).toInt(),
                (point.y + halfSize).toInt(),
            )
            icon.draw(canvas)
        }
    }

    private fun dpFloat(value: Int): Float = value * resources.displayMetrics.density
}

private enum class BboxHandle(
    val movesWest: Boolean = false,
    val movesSouth: Boolean = false,
    val movesEast: Boolean = false,
    val movesNorth: Boolean = false,
) {
    Scale,
    NorthWest(movesWest = true, movesNorth = true),
    NorthEast(movesEast = true, movesNorth = true),
    SouthEast(movesEast = true, movesSouth = true),
    SouthWest(movesWest = true, movesSouth = true),
}

private val BBOX_VERTEX_HANDLES =
    listOf(
        BboxHandle.NorthWest,
        BboxHandle.NorthEast,
        BboxHandle.SouthEast,
        BboxHandle.SouthWest,
    )

private class WatchInstalledCoverageOverlay(
    context: Context,
    private val areasProvider: () -> List<WatchInstalledCoverageArea>,
    private val mapProvider: () -> MapLibreMap?,
) : View(context) {
    private val poiFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x262ab16d
            style = Paint.Style.FILL
        }
    private val poiStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xd92ab16d.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dpFloat(2)
        }
    private val routingFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x245a80ff
            style = Paint.Style.FILL
        }
    private val routingStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xd95a80ff.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dpFloat(2)
        }
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xff10212b.toInt()
            textSize = 11 * resources.displayMetrics.density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val activeMap = mapProvider()
        areasProvider().forEach { area ->
            val bounds = MapPickerBounds.parseOrNull(area.bbox) ?: return@forEach
            val rect = bounds.screenRect(activeMap) ?: return@forEach
            val (fill, stroke) =
                when (area.kind) {
                    WatchInstalledCoverageKind.POI -> poiFillPaint to poiStrokePaint
                    WatchInstalledCoverageKind.ROUTING -> routingFillPaint to routingStrokePaint
                }
            canvas.drawRect(rect, fill)
            canvas.drawRect(rect, stroke)
            drawCoverageLabel(canvas, rect, area)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private fun MapPickerBounds.screenRect(activeMap: MapLibreMap?): RectF? {
        if (activeMap == null) return null
        val northWest = activeMap.projection.toScreenLocation(LatLng(north, west))
        val southEast = activeMap.projection.toScreenLocation(LatLng(south, east))
        return RectF(
            min(northWest.x, southEast.x),
            min(northWest.y, southEast.y),
            max(northWest.x, southEast.x),
            max(northWest.y, southEast.y),
        )
    }

    private fun drawCoverageLabel(
        canvas: Canvas,
        rect: RectF,
        area: WatchInstalledCoverageArea,
    ) {
        if (rect.width() < dpFloat(74) || rect.height() < dpFloat(26)) return
        val label =
            when (area.kind) {
                WatchInstalledCoverageKind.POI -> "POI"
                WatchInstalledCoverageKind.ROUTING -> area.fileName.substringBeforeLast('.')
            }
        val baseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(label, rect.centerX(), baseline, labelPaint)
    }

    private fun dpFloat(value: Int): Float = value * resources.displayMetrics.density
}

private class RoutingTileOverlay(
    context: Context,
    private val selectedTilesProvider: () -> Set<RoutingTile>,
    private val mapProvider: () -> MapLibreMap?,
) : View(context) {
    private val gridPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xb8495057.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dpFloat(1)
        }
    private val selectedFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x400b7285
            style = Paint.Style.FILL
        }
    private val selectedStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xff0b7285.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dpFloat(3)
        }
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xff073b4c.toInt()
            textSize = 11 * resources.displayMetrics.density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val activeMap = mapProvider()
        val selectedTiles = selectedTilesProvider()
        val visibleTiles =
            if (activeMap == null) {
                selectedTiles
            } else {
                routingTilesForVisibleMap(activeMap)
            }
        (visibleTiles + selectedTiles).forEach { tile ->
            val rect = tile.screenRect(activeMap) ?: fallbackTileRect(tile, selectedTiles)
            val selected = selectedTiles.contains(tile)
            if (selected) {
                canvas.drawRect(rect, selectedFillPaint)
                canvas.drawRect(rect, selectedStrokePaint)
            } else {
                canvas.drawRect(rect, gridPaint)
            }
            drawTileLabel(canvas, rect, tile)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private fun drawTileLabel(
        canvas: Canvas,
        rect: RectF,
        tile: RoutingTile,
    ) {
        if (rect.width() < dpFloat(56) || rect.height() < dpFloat(28)) return
        val baseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(tile.fileName, rect.centerX(), baseline, labelPaint)
    }

    private fun routingTilesForVisibleMap(activeMap: MapLibreMap): Set<RoutingTile> {
        if (width == 0 || height == 0) return emptySet()
        val points =
            listOf(
                activeMap.projection.fromScreenLocation(PointF(0f, 0f)),
                activeMap.projection.fromScreenLocation(PointF(width.toFloat(), 0f)),
                activeMap.projection.fromScreenLocation(PointF(0f, height.toFloat())),
                activeMap.projection.fromScreenLocation(PointF(width.toFloat(), height.toFloat())),
            )
        val west = points.minOf { it.longitude }
        val east = points.maxOf { it.longitude }
        val south = points.minOf { it.latitude }
        val north = points.maxOf { it.latitude }
        return routingTilesForBounds(
            MapPickerBounds.safe(
                west = west,
                south = south,
                east = east,
                north = north,
                minSpan = ROUTING_TILE_DEGREES.toDouble(),
            ),
            bufferTiles = 1,
        )
    }

    private fun RoutingTile.screenRect(activeMap: MapLibreMap?): RectF? {
        if (activeMap == null) return null
        val northWest = activeMap.projection.toScreenLocation(LatLng(north.toDouble(), west.toDouble()))
        val southEast = activeMap.projection.toScreenLocation(LatLng(south.toDouble(), east.toDouble()))
        return RectF(
            min(northWest.x, southEast.x),
            min(northWest.y, southEast.y),
            max(northWest.x, southEast.x),
            max(northWest.y, southEast.y),
        )
    }

    private fun fallbackTileRect(
        tile: RoutingTile,
        selectedTiles: Set<RoutingTile>,
    ): RectF {
        val orderedTiles = selectedTiles.sortedWith(compareBy<RoutingTile> { it.south }.thenBy { it.west })
        val index = orderedTiles.indexOf(tile).takeIf { it >= 0 } ?: 0
        val columns = max(1, min(2, orderedTiles.size))
        val cellWidth = width / columns.toFloat()
        val cellHeight = height / max(1, (orderedTiles.size + columns - 1) / columns).toFloat()
        val column = index % columns
        val row = index / columns
        return RectF(
            column * cellWidth + dpFloat(16),
            row * cellHeight + dpFloat(16),
            (column + 1) * cellWidth - dpFloat(16),
            (row + 1) * cellHeight - dpFloat(16),
        )
    }

    private fun dpFloat(value: Int): Float = value * resources.displayMetrics.density
}

private data class RoutingTile(
    val west: Int,
    val south: Int,
) {
    val east: Int = west + ROUTING_TILE_DEGREES
    val north: Int = south + ROUTING_TILE_DEGREES
    val bounds: MapPickerBounds =
        MapPickerBounds(
            west = west.toDouble(),
            south = south.toDouble(),
            east = east.toDouble(),
            north = north.toDouble(),
        )
    val fileName: String = "${formatTileCoord(west, 'E', 'W')}_${formatTileCoord(south, 'N', 'S')}.rd5"

    companion object {
        fun containing(
            latitude: Double,
            longitude: Double,
        ): RoutingTile =
            RoutingTile(
                west = tileOrigin(longitude).coerceIn(-180, 175),
                south = tileOrigin(latitude).coerceIn(-85, 85),
            )
    }
}

private fun routingTilesForBounds(
    bounds: MapPickerBounds,
    bufferTiles: Int = 0,
): Set<RoutingTile> {
    val lonStart = (tileOrigin(bounds.west) - bufferTiles * ROUTING_TILE_DEGREES).coerceAtLeast(-180)
    val lonEnd = (tileOrigin(bounds.east - ROUTING_TILE_EPSILON) + bufferTiles * ROUTING_TILE_DEGREES).coerceAtMost(175)
    val latStart = (tileOrigin(bounds.south) - bufferTiles * ROUTING_TILE_DEGREES).coerceAtLeast(-85)
    val latEnd = (tileOrigin(bounds.north - ROUTING_TILE_EPSILON) + bufferTiles * ROUTING_TILE_DEGREES).coerceAtMost(85)
    val result = linkedSetOf<RoutingTile>()
    var lat = latStart
    while (lat <= latEnd) {
        var lon = lonStart
        while (lon <= lonEnd) {
            result += RoutingTile(lon, lat)
            lon += ROUTING_TILE_DEGREES
        }
        lat += ROUTING_TILE_DEGREES
    }
    return result
}

private fun tileOrigin(coordinate: Double): Int {
    val tileIndex = floor(coordinate / ROUTING_TILE_DEGREES.toDouble()).toInt()
    return tileIndex * ROUTING_TILE_DEGREES
}

private fun formatTileCoord(
    value: Int,
    positivePrefix: Char,
    negativePrefix: Char,
): String {
    val prefix = if (value < 0) negativePrefix else positivePrefix
    return "$prefix${abs(value)}"
}

internal fun ensureMapLibreConfigured(context: Context) {
    if (MapLibreConfiguration.initialized) return
    synchronized(MapLibreConfiguration) {
        if (MapLibreConfiguration.initialized) return
        MapLibre.getInstance(context.applicationContext)
        val packageName = context.packageName
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain
                            .request()
                            .newBuilder()
                            .header(
                                "User-Agent",
                                "GlanceMapCompanion/1 ($packageName)",
                            ).build(),
                    )
                }.build(),
        )
        MapLibreConfiguration.initialized = true
    }
}

private object MapLibreConfiguration {
    var initialized: Boolean = false
}

@SuppressLint("MissingPermission")
private fun requestLastKnownLocation(
    context: Context,
    onLocation: (Location) -> Unit,
) {
    val hasPermission =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    if (!hasPermission) return
    LocationServices
        .getFusedLocationProviderClient(context)
        .lastLocation
        .addOnSuccessListener { location ->
            if (location != null) {
                onLocation(location)
            }
        }
}
