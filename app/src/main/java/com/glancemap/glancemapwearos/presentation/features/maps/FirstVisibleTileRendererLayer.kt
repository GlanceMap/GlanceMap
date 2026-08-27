package com.glancemap.glancemapwearos.presentation.features.maps

import android.os.SystemClock
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.diagnostics.MapHotPathDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.GraphicFactory
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.model.MapViewPosition
import org.mapsforge.map.util.LayerUtil
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class FirstVisibleBaseTileSource(
    val telemetryToken: String,
) {
    WARM_CACHE("warm"),
    COLD_RENDER("cold"),
}

internal class VisibleTileDiagnosticsContext(
    val mapView: MapView,
    val rendererId: Int,
    val cacheId: String,
)

internal data class VisibleTileViewportKey(
    val zoomLevel: Byte,
    val tiles: Set<Tile>,
)

internal data class VisibleTileViewportReadinessRequest(
    val layerId: Int,
    val requestId: Long,
    val viewportKey: VisibleTileViewportKey,
)

internal data class VisibleTileViewportReadinessEvent(
    val layerId: Int,
    val requestId: Long,
    val viewportKey: VisibleTileViewportKey,
)

internal fun visibleTileViewportReadinessMatches(
    request: VisibleTileViewportReadinessRequest,
    event: VisibleTileViewportReadinessEvent,
): Boolean =
    request.layerId == event.layerId &&
        request.requestId == event.requestId &&
        request.viewportKey == event.viewportKey

/** Reports when an exact tile from the visible viewport is first available to draw. */
internal class FirstVisibleTileRendererLayer(
    tileCache: TileCache,
    mapDataStore: MapDataStore,
    mapViewPosition: MapViewPosition,
    graphicFactory: GraphicFactory,
    private val diagnostics: VisibleTileDiagnosticsContext,
    private val onFirstVisibleBaseTile: (
        layer: FirstVisibleTileRendererLayer,
        source: FirstVisibleBaseTileSource,
    ) -> Unit,
) : TileRendererLayer(
        tileCache,
        mapDataStore,
        mapViewPosition,
        false,
        true,
        false,
        graphicFactory,
        null,
    ) {
    private val firstVisibleTileReported = AtomicBoolean(false)
    private val visibleTileDiagnosticsActivated = AtomicBoolean(false)
    private val pendingVisibleTileSnapshotReason = AtomicReference<String?>(null)
    private val viewportReadinessCounter = AtomicLong(0L)
    private val viewportReadinessEvent = MutableStateFlow<VisibleTileViewportReadinessEvent?>(null)
    private val viewportReadinessLock = Any()
    private var armedViewportReadiness: VisibleTileViewportReadinessRequest? = null
    private var hasDrawn = false
    private var lastDiagnosticState: String? = null
    private var lastCoverageSampleAtElapsedMs: Long? = null

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeftPoint: Point,
        rotation: Rotation,
    ) {
        val firstDraw = !hasDrawn
        val cachedBeforeDraw = hasCachedVisibleBaseTile(boundingBox, zoomLevel)
        hasDrawn = true

        super.draw(boundingBox, zoomLevel, canvas, topLeftPoint, rotation)

        val visibleTileReady = cachedBeforeDraw || hasCachedVisibleBaseTile(boundingBox, zoomLevel)
        reportVisibleTileAvailability(
            boundingBox = boundingBox,
            zoomLevel = zoomLevel,
            rotation = rotation,
            snapshotReason = pendingVisibleTileSnapshotReason.getAndSet(null),
        )
        if (visibleTileReady && firstVisibleTileReported.compareAndSet(false, true)) {
            onFirstVisibleBaseTile(
                this,
                if (firstDraw && cachedBeforeDraw) {
                    FirstVisibleBaseTileSource.WARM_CACHE
                } else {
                    FirstVisibleBaseTileSource.COLD_RENDER
                },
            )
        }
        completeArmedViewportReadiness(boundingBox, zoomLevel)
    }

    /** Arms one readiness signal for the map's current visible tile set. */
    fun armCurrentViewportReadiness(): VisibleTileViewportReadinessRequest {
        val mapView = diagnostics.mapView
        val viewportKey =
            visibleTileViewportKey(
                boundingBox = mapView.boundingBox,
                zoomLevel = mapView.model.mapViewPosition.zoomLevel,
                tileSize = displayModel?.tileSize ?: 0,
            )
        val request =
            VisibleTileViewportReadinessRequest(
                layerId = System.identityHashCode(this),
                requestId = viewportReadinessCounter.incrementAndGet(),
                viewportKey = viewportKey,
            )
        synchronized(viewportReadinessLock) {
            if (hasDrawableTile(request.viewportKey)) {
                viewportReadinessEvent.value = request.toReadinessEvent()
            } else {
                armedViewportReadiness = request
            }
        }
        return request
    }

    suspend fun awaitViewportReadiness(
        request: VisibleTileViewportReadinessRequest,
        timeoutMs: Long,
    ): VisibleTileViewportReadinessEvent? {
        viewportReadinessEvent.value?.let { event ->
            if (visibleTileViewportReadinessMatches(request, event)) return event
        }
        return withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            viewportReadinessEvent
                .filterNotNull()
                .first { event -> visibleTileViewportReadinessMatches(request, event) }
        }
    }

    /** Queues one FULL-diagnostics visibility snapshot for the next completed map draw. */
    fun requestVisibleTileDiagnosticSnapshot(reason: String) {
        if (DebugTelemetry.isFullDiagnosticsCaptureEnabled()) {
            pendingVisibleTileSnapshotReason.set(reason)
        }
    }

    private fun reportVisibleTileAvailability(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        rotation: Rotation,
        snapshotReason: String?,
    ) {
        if (DebugTelemetry.isFullDiagnosticsCaptureEnabled()) {
            if (visibleTileDiagnosticsActivated.compareAndSet(false, true)) {
                MapHotPathDiagnostics.recordEvent(
                    stage = "mapRenderer.visibleTilesDiagnostics",
                    status = "active",
                    detail =
                        "full=true cacheId=${diagnostics.cacheId} renderer=${diagnostics.rendererId} " +
                            "mapView=${System.identityHashCode(diagnostics.mapView)} " +
                            "layer=${System.identityHashCode(this)}",
                )
            }
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val forcedSnapshot = snapshotReason != null
            val sampleIntervalElapsed =
                isVisibleTileDiagnosticSampleDue(
                    lastSampleAtElapsedMs = lastCoverageSampleAtElapsedMs,
                    nowElapsedMs = nowElapsedMs,
                    sampleIntervalMs = COVERAGE_SAMPLE_INTERVAL_MS,
                )
            if (forcedSnapshot || sampleIntervalElapsed) {
                lastCoverageSampleAtElapsedMs = nowElapsedMs
                val coverage = visibleTileCoverage(boundingBox, zoomLevel)
                val drawable = coverage.usableTileCount > 0
                val state = visibleTileDiagnosticState(lastDiagnosticState, drawable, coverage.pendingJobCount)
                if (forcedSnapshot || lastDiagnosticState != state) {
                    lastDiagnosticState = state
                    val mapView = diagnostics.mapView
                    val center = mapView.model.mapViewPosition.center
                    MapHotPathDiagnostics.recordEvent(
                        stage = "mapRenderer.visibleTiles",
                        status = state,
                        detail =
                            "cacheId=${diagnostics.cacheId} renderer=${diagnostics.rendererId} " +
                                "layer=${System.identityHashCode(this)} " +
                                "zoom=$zoomLevel center=${center.latitude},${center.longitude} " +
                                "rotation=${rotation.degrees} attached=${mapView.isAttachedToWindow} " +
                                "size=${mapView.width}x${mapView.height} drawable=$drawable " +
                                "exactEntries=${coverage.exactCacheEntryCount}/${coverage.totalTileCount} " +
                                "exactDrawableTiles=${coverage.baseTileCount}/${coverage.totalTileCount} " +
                                "parentFallbackTiles=${coverage.parentTileCount}/${coverage.totalTileCount} " +
                                "pendingJobs=${coverage.pendingJobCount} " +
                                "semanticState=$state reason=${snapshotReason ?: "state_changed"} " +
                                "bounds=${boundingBox.minLatitude},${boundingBox.minLongitude}," +
                                "${boundingBox.maxLatitude},${boundingBox.maxLongitude}",
                    )
                }
            }
        }
    }

    private fun visibleTileCoverage(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
    ): VisibleTileCoverage =
        runCatching {
            if (renderThemeFuture == null) return@runCatching VisibleTileCoverage()
            val tileSize = displayModel?.tileSize ?: return@runCatching VisibleTileCoverage()
            var totalTileCount = 0
            var exactCacheEntryCount = 0
            var baseTileCount = 0
            var parentTileCount = 0
            LayerUtil.getTiles(boundingBox, zoomLevel, tileSize).forEach { tile ->
                totalTileCount += 1
                val job = createJob(tile)
                if (tileCache.containsKey(job)) {
                    exactCacheEntryCount += 1
                }
                if (tileCache.getImmediately(job) != null) {
                    baseTileCount += 1
                } else if (hasCachedParentTile(tile)) {
                    parentTileCount += 1
                }
            }
            VisibleTileCoverage(
                totalTileCount = totalTileCount,
                exactCacheEntryCount = exactCacheEntryCount,
                baseTileCount = baseTileCount,
                parentTileCount = parentTileCount,
                pendingJobCount = jobQueue?.size() ?: -1,
            )
        }.getOrDefault(VisibleTileCoverage())

    private fun hasCachedParentTile(tile: org.mapsforge.core.model.Tile): Boolean {
        var parent = tile.parent
        repeat(MAX_PARENT_TILE_DEPTH) {
            parent?.let { candidate ->
                if (tileCache.getImmediately(createJob(candidate)) != null) return true
                parent = candidate.parent
            }
        }
        return false
    }

    private fun hasCachedVisibleBaseTile(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
    ): Boolean =
        runCatching {
            if (renderThemeFuture == null) return@runCatching false
            val tileSize = displayModel?.tileSize ?: return@runCatching false
            LayerUtil
                .getTiles(boundingBox, zoomLevel, tileSize)
                .any { tile -> tileCache.containsKey(createJob(tile)) }
        }.getOrDefault(false)

    private fun completeArmedViewportReadiness(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
    ) {
        val request = synchronized(viewportReadinessLock) { armedViewportReadiness }
        val tileSize = displayModel?.tileSize
        if (request == null || tileSize == null) return
        val viewportKey =
            visibleTileViewportKey(
                boundingBox = boundingBox,
                zoomLevel = zoomLevel,
                tileSize = tileSize,
            )
        if (request.viewportKey == viewportKey && hasDrawableTile(viewportKey)) {
            synchronized(viewportReadinessLock) {
                if (armedViewportReadiness == request) {
                    armedViewportReadiness = null
                    viewportReadinessEvent.value = request.toReadinessEvent()
                }
            }
        }
    }

    private fun hasDrawableTile(viewportKey: VisibleTileViewportKey): Boolean =
        renderThemeFuture != null &&
            viewportKey.tiles.any { tile ->
                generateSequence(tile) { candidate -> candidate.parent }
                    .take(MAX_PARENT_TILE_DEPTH + 1)
                    .any { candidate ->
                        tileCache.getImmediately(createJob(candidate))?.let { bitmap ->
                            bitmap.decrementRefCount()
                            true
                        } ?: false
                    }
            }

    private data class VisibleTileCoverage(
        val totalTileCount: Int = 0,
        val exactCacheEntryCount: Int = 0,
        val baseTileCount: Int = 0,
        val parentTileCount: Int = 0,
        val pendingJobCount: Int = -1,
    ) {
        val usableTileCount: Int
            get() = baseTileCount + parentTileCount
    }

    private companion object {
        const val MAX_PARENT_TILE_DEPTH = 4
        const val COVERAGE_SAMPLE_INTERVAL_MS = 250L
    }
}

private fun VisibleTileViewportReadinessRequest.toReadinessEvent(): VisibleTileViewportReadinessEvent =
    VisibleTileViewportReadinessEvent(
        layerId = layerId,
        requestId = requestId,
        viewportKey = viewportKey,
    )

internal fun visibleTileViewportKey(
    boundingBox: BoundingBox,
    zoomLevel: Byte,
    tileSize: Int,
): VisibleTileViewportKey =
    VisibleTileViewportKey(
        zoomLevel = zoomLevel,
        tiles = if (tileSize > 0) LayerUtil.getTiles(boundingBox, zoomLevel, tileSize).toSet() else emptySet(),
    )

internal fun visibleTileDiagnosticState(
    previousState: String?,
    drawable: Boolean,
    pendingJobCount: Int,
): String =
    when {
        drawable && (previousState == "pending_no_tiles" || previousState == "no_tiles_no_jobs") -> "tile_arrived"
        drawable -> "drawable"
        pendingJobCount > 0 -> "pending_no_tiles"
        else -> "no_tiles_no_jobs"
    }

internal fun isVisibleTileDiagnosticSampleDue(
    lastSampleAtElapsedMs: Long?,
    nowElapsedMs: Long,
    sampleIntervalMs: Long,
): Boolean = lastSampleAtElapsedMs == null || nowElapsedMs - lastSampleAtElapsedMs >= sampleIntervalMs
