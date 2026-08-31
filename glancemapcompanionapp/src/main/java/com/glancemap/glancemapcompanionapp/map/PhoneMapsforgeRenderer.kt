package com.glancemap.glancemapcompanionapp.map

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.GraphicFactory
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layers
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.hills.AClasyHillShading
import org.mapsforge.map.layer.hills.AdaptiveClasyHillShading
import org.mapsforge.map.layer.hills.HillsRenderConfig
import org.mapsforge.map.layer.hills.MemoryCachingHgtReaderTileSource
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.model.MapViewPosition
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import org.mapsforge.map.util.LayerUtil
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

private const val PHONE_MAP_CACHE_PREFIX = "phone-offline"
private const val PHONE_MAP_CACHE_MIN_TILES = 64
private const val PHONE_MAP_CACHE_MAX_TILES = 256
private const val PHONE_MAP_CONSTRAINED_CACHE_MIN_TILES = 24
private const val PHONE_MAP_CONSTRAINED_CACHE_MAX_TILES = 80
private const val PHONE_MAP_MEMORY_BUDGET_FRACTION = 1.0 / 16.0
private const val PHONE_MAP_MEMORY_BUDGET_CAP_BYTES = 32L * 1024L * 1024L
private const val PHONE_MAP_CONSTRAINED_MEMORY_BUDGET_FRACTION = 1.0 / 20.0
private const val PHONE_MAP_CONSTRAINED_MEMORY_BUDGET_CAP_BYTES = 8L * 1024L * 1024L
private const val PHONE_MAP_CONSTRAINED_MEMORY_CLASS_MB = 128
private const val PHONE_MAP_CONSTRAINED_MAX_HEAP_BYTES = 160L * 1024L * 1024L
private const val PHONE_MAP_MAX_PARENT_TILE_DEPTH = 4
private const val PHONE_FIRST_VISIBLE_TILE_COVERAGE_SAMPLE_INTERVAL_MS = 250L

internal data class PhoneMapsforgeTileCacheConfig(
    val firstLevelTiles: Int,
    val memoryBudgetBytes: Long,
    val constrainedMemory: Boolean,
)

internal fun phoneMapsforgeTileCacheConfig(
    tileSize: Int,
    bytesPerPixel: Int,
    memoryClassMb: Int,
    maxHeapBytes: Long,
): PhoneMapsforgeTileCacheConfig {
    val constrained =
        (memoryClassMb in 1..PHONE_MAP_CONSTRAINED_MEMORY_CLASS_MB) ||
            maxHeapBytes <= PHONE_MAP_CONSTRAINED_MAX_HEAP_BYTES
    val budget =
        min(
            (
                maxHeapBytes *
                    if (constrained) {
                        PHONE_MAP_CONSTRAINED_MEMORY_BUDGET_FRACTION
                    } else {
                        PHONE_MAP_MEMORY_BUDGET_FRACTION
                    }
            ).toLong(),
            if (constrained) {
                PHONE_MAP_CONSTRAINED_MEMORY_BUDGET_CAP_BYTES
            } else {
                PHONE_MAP_MEMORY_BUDGET_CAP_BYTES
            },
        )
    val minimum =
        if (constrained) PHONE_MAP_CONSTRAINED_CACHE_MIN_TILES else PHONE_MAP_CACHE_MIN_TILES
    val maximum =
        if (constrained) PHONE_MAP_CONSTRAINED_CACHE_MAX_TILES else PHONE_MAP_CACHE_MAX_TILES
    val tileBytes = tileSize.toLong() * tileSize * bytesPerPixel
    val calculated = if (tileBytes > 0L) (budget / tileBytes).toInt() else minimum
    return PhoneMapsforgeTileCacheConfig(
        firstLevelTiles = calculated.coerceIn(minimum, maximum),
        memoryBudgetBytes = budget,
        constrainedMemory = constrained,
    )
}

internal fun phoneMapsforgeTileCacheId(identity: PhoneMapsforgeBaseLayerIdentity): String =
    "$PHONE_MAP_CACHE_PREFIX-${phoneMapsforgeIdentityHash(identity.mapIdentity)}-" +
        phoneMapsforgeIdentityHash("${identity.themeConfig.themeId}:${identity.themeConfig.styleId}")

private fun phoneMapsforgeIdentityHash(value: String): String = value.hashCode().toUInt().toString(16)

internal enum class PhoneOfflineInitialCameraReason {
    CURRENT_VIEWPORT,
    MAP_METADATA,
    DEFAULT,
}

internal data class PhoneOfflineInitialCameraSelection(
    val camera: PhoneMapCameraSnapshot,
    val reason: PhoneOfflineInitialCameraReason,
    val zoomClamped: Boolean,
)

internal data class PhoneOfflineMapCameraContext(
    val bounds: BoundingBox,
    val mapStart: org.mapsforge.core.model.LatLong?,
    val mapStartZoom: Byte?,
    val zoomMin: Byte,
    val zoomMax: Byte,
)

internal fun phoneOfflineInitialCameraSelection(
    requested: PhoneMapCameraSnapshot,
    context: PhoneOfflineMapCameraContext,
): PhoneOfflineInitialCameraSelection {
    val rangeMin = minOf(context.zoomMin.toInt(), context.zoomMax.toInt())
    val rangeMax = maxOf(context.zoomMin.toInt(), context.zoomMax.toInt())
    if (context.bounds.contains(requested.latitude, requested.longitude)) {
        val zoom = requested.zoom.coerceIn(rangeMin.toDouble(), rangeMax.toDouble())
        return PhoneOfflineInitialCameraSelection(
            camera = requested.copy(zoom = zoom),
            reason = PhoneOfflineInitialCameraReason.CURRENT_VIEWPORT,
            zoomClamped = zoom != requested.zoom,
        )
    }
    val hasMapStart = context.mapStart != null
    val start = context.mapStart ?: context.bounds.centerPoint
    val requestedZoom = context.mapStartZoom?.toInt() ?: PHONE_MAP_DEFAULT_ZOOM.toInt()
    val zoom = requestedZoom.coerceIn(rangeMin, rangeMax)
    return PhoneOfflineInitialCameraSelection(
        camera =
            PhoneMapCameraSnapshot(
                latitude = start.latitude,
                longitude = start.longitude,
                zoom = zoom.toDouble(),
            ),
        reason =
            if (hasMapStart) {
                PhoneOfflineInitialCameraReason.MAP_METADATA
            } else {
                PhoneOfflineInitialCameraReason.DEFAULT
            },
        zoomClamped = zoom != requestedZoom,
    )
}

internal data class PhoneFirstVisibleTileCoverage(
    val visibleTileCount: Int = 0,
    val drawableVisibleTiles: Int = 0,
    val parentFallbackTiles: Int = 0,
    val pendingJobCount: Int = -1,
)

internal fun phoneFirstVisibleTileCoverage(
    visibleTiles: Iterable<Tile>,
    hasDrawableTile: (Tile) -> Boolean,
): PhoneFirstVisibleTileCoverage {
    var visible = 0
    var drawable = 0
    var parentFallback = 0
    visibleTiles.forEach { tile ->
        visible += 1
        if (hasDrawableTile(tile)) {
            drawable += 1
        } else if (
            generateSequence(tile.parent) { candidate -> candidate.parent }
                .take(PHONE_MAP_MAX_PARENT_TILE_DEPTH)
                .any(hasDrawableTile)
        ) {
            parentFallback += 1
        }
    }
    return PhoneFirstVisibleTileCoverage(
        visibleTileCount = visible,
        drawableVisibleTiles = drawable,
        parentFallbackTiles = parentFallback,
    )
}

internal fun isPhoneFirstVisibleTileCoverageSampleDue(
    lastSampleAtElapsedMs: Long?,
    nowElapsedMs: Long,
    sampleIntervalMs: Long = PHONE_FIRST_VISIBLE_TILE_COVERAGE_SAMPLE_INTERVAL_MS,
): Boolean =
    lastSampleAtElapsedMs == null ||
        nowElapsedMs - lastSampleAtElapsedMs >= sampleIntervalMs.coerceAtLeast(1L)

internal enum class PhoneFirstVisibleTileSource {
    WARM_CACHE,
    COLD_RENDER,
}

private data class PhoneFirstVisibleTileCallbacks(
    val onFirstDraw: (PhoneFirstVisibleTileRendererLayer) -> Unit,
    val onFirstVisibleTile: (PhoneFirstVisibleTileRendererLayer, PhoneFirstVisibleTileSource) -> Unit,
    val onCoverageChanged: () -> Unit,
)

/** Owns only the current Mapsforge base layer and its map-file/cache resources. */
@Suppress("TooManyFunctions") // Lifecycle ownership is intentionally explicit for Mapsforge resources.
internal class PhoneMapsforgeRenderer(
    private val context: Context,
    private val mapView: MapView,
    private val onMapBoundsChanged: (BoundingBox?) -> Unit,
    private val onRendererFailure: (PhoneOfflineMapError) -> Unit,
    private val onRuntimeChanged: () -> Unit,
) {
    private val rendererId = System.identityHashCode(this)
    private var currentIdentity: PhoneMapsforgeBaseLayerIdentity? = null
    private var requestedIdentity: PhoneMapsforgeBaseLayerIdentity? = null
    private var cacheReuseEventFor: PhoneMapsforgeBaseLayerIdentity? = null
    private var currentBase: PhoneMapsforgePreparedBaseLayer? = null
    private var currentTrace: PhoneOfflineMapRendererTrace? = null
    private var requestedTerrainSettings = PhoneMapSettings()
    private var requestedTerrainDataVersion: Long = 0L
    private var requestedTerrainDataAvailable = false
    private var activeTerrainKey: String? = null
    private var hillshadeLayer: TileRendererLayer? = null
    private var hillshadeTileCache: TileCache? = null
    private var hillshadeMapFile: MapFile? = null
    private var hillsRenderConfig: HillsRenderConfig? = null
    private var reliefOverlayLayer: PhoneReliefOverlayLayer? = null
    private val elevationRepository = PhoneElevationRepository(context)
    private var destroyed = false

    val mapBounds: BoundingBox?
        get() = currentBase?.bounds

    val currentBaseLayer: TileRendererLayer?
        get() = currentBase?.layer

    val mapZoomLevelMin: Byte?
        get() = currentBase?.mapFile?.mapFileInfo?.zoomLevelMin

    val mapZoomLevelMax: Byte?
        get() = currentBase?.mapFile?.mapFileInfo?.zoomLevelMax

    fun updateBaseLayer(
        map: PhoneOfflineMap,
        themeConfig: PhoneOfflineThemeConfig,
        initialCamera: PhoneMapCameraSnapshot,
    ) {
        if (destroyed) return
        val resolvedTheme = PhoneOfflineThemeCatalog.resolve(themeConfig.themeId, themeConfig.styleId)
        val desired = PhoneMapsforgeBaseLayerIdentity(map.rendererIdentity, resolvedTheme)
        val change = phoneMapsforgeBaseLayerChange(currentIdentity, desired)
        if (change == PhoneMapsforgeBaseLayerChange.NONE && requestedIdentity == desired) {
            if (cacheReuseEventFor != desired) {
                cacheReuseEventFor = desired
                PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                    event = "tile_cache_reused",
                    detail = "renderer=$rendererId cache=${currentBase?.cacheId ?: "none"}",
                )
            }
            return
        }
        requestedIdentity = desired
        cacheReuseEventFor = null
        PhoneMapLayerMutationCoordinator.mutateLayers(mapView, BASE_LAYER_MUTATION_KEY) { layers ->
            if (destroyed || requestedIdentity != desired) return@mutateLayers
            val appliedChange = phoneMapsforgeBaseLayerChange(currentIdentity, desired)
            if (appliedChange == PhoneMapsforgeBaseLayerChange.NONE) return@mutateLayers
            swapBaseLayer(
                layers = layers,
                map = map,
                identity = desired,
                initialCamera = initialCamera,
                change = appliedChange,
            )
        }
    }

    /** Terrain is independent from the base layer and can be toggled without reopening the map. */
    @Suppress("ReturnCount") // Renderer state updates intentionally exit when no base layer is attached.
    fun updateTerrain(
        settings: PhoneMapSettings,
        dataVersion: Long,
        dataAvailable: Boolean,
    ) {
        if (destroyed) return
        requestedTerrainSettings = settings
        requestedTerrainDataAvailable = dataAvailable
        if (requestedTerrainDataVersion != dataVersion) {
            requestedTerrainDataVersion = dataVersion
            elevationRepository.invalidate()
        }
        val base = currentBase ?: return
        if (activeTerrainKey == terrainKey(base.sourceFile, settings, dataVersion, dataAvailable)) return
        PhoneMapLayerMutationCoordinator.mutateLayers(mapView, TERRAIN_LAYER_MUTATION_KEY) { layers ->
            if (!destroyed && currentBase === base) applyTerrainLayers(layers)
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        PhoneMapLayerMutationCoordinator.mutateLayersImmediately(mapView) { layers ->
            clearCurrentBaseLayer(layers, reason = "renderer_destroyed")
        }
        PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
            event = "renderer_destroyed",
            detail = "renderer=$rendererId mapView=${System.identityHashCode(mapView)}",
        )
        onRuntimeChanged()
    }

    fun runtimeSnapshot(): PhoneMapsforgeRendererRuntimeSnapshot =
        PhoneMapsforgeRendererRuntimeSnapshot(
            rendererId = rendererId,
            layerId = currentBase?.layer?.let(System::identityHashCode),
            cacheId = currentBase?.cacheId,
            displayName = currentBase?.let { currentTrace?.mapDisplayName },
            mapBounds = currentBase?.bounds,
            coverage = currentBase?.layer?.latestCoverage ?: PhoneFirstVisibleTileCoverage(),
            tileLayerDrawObserved = currentBase?.layer?.hasDrawObserved == true,
            firstVisibleBaseTileObserved = currentBase?.layer?.hasFirstVisibleBaseTileObserved == true,
        )

    // Mapsforge constructors expose several unchecked failures; cleanup is identical.
    @Suppress("TooGenericExceptionCaught")
    private fun swapBaseLayer(
        layers: Layers,
        map: PhoneOfflineMap,
        identity: PhoneMapsforgeBaseLayerIdentity,
        initialCamera: PhoneMapCameraSnapshot,
        change: PhoneMapsforgeBaseLayerChange,
    ) {
        val trace = PhoneOfflineMapRendererTrace(map.displayName, identity.themeConfig)
        currentTrace = trace
        trace.complete(PhoneOfflineMapRendererStage.MAP_SELECTED)
        if (mapView.isAttachedToWindow) trace.viewAttached()
        try {
            clearCurrentBaseLayer(layers, reason = change.name.lowercase())
            val prepared = createBaseLayer(map, identity, initialCamera, change, trace)
            attachBaseLayer(layers, prepared, identity, trace)
            if (change == PhoneMapsforgeBaseLayerChange.MAP_SWAP) {
                PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                    event = "map_switched",
                    detail = "file=${map.displayName} renderer=$rendererId",
                )
            }
        } catch (error: Exception) {
            onBaseLayerFailure(layers, trace, error)
        }
    }

    // The partially built MapFile/cache/layer must be released for any Mapsforge failure.
    @Suppress("TooGenericExceptionCaught")
    private fun createBaseLayer(
        map: PhoneOfflineMap,
        identity: PhoneMapsforgeBaseLayerIdentity,
        initialCamera: PhoneMapCameraSnapshot,
        change: PhoneMapsforgeBaseLayerChange,
        trace: PhoneOfflineMapRendererTrace,
    ): PhoneMapsforgePreparedBaseLayer {
        check(isPhoneOfflineMapCandidate(map.file)) { "Selected map is unavailable." }
        val mapFile = MapFile(map.file)
        var cache: TileCache? = null
        var layer: PhoneFirstVisibleTileRendererLayer? = null
        try {
            val camera = configureMapFile(map, mapFile, initialCamera, change, trace)
            val cacheId = phoneMapsforgeTileCacheId(identity)
            cache = createTileCache(cacheId)
            trace.tileCacheCreated()
            trace.complete(PhoneOfflineMapRendererStage.TILE_CACHE_CREATE)
            layer = createTileLayer(cache, mapFile, identity.themeConfig, trace)
            return PhoneMapsforgePreparedBaseLayer(map.file, mapFile, cache, cacheId, layer, camera.bounds)
        } catch (error: Exception) {
            layer?.let { runCatching { it.onDestroy() } } ?: runCatching { mapFile.close() }
            runCatching { cache?.destroy() }
            throw error
        }
    }

    private fun configureMapFile(
        map: PhoneOfflineMap,
        mapFile: MapFile,
        initialCamera: PhoneMapCameraSnapshot,
        change: PhoneMapsforgeBaseLayerChange,
        trace: PhoneOfflineMapRendererTrace,
    ): PhoneOfflineMapCameraContext {
        val bounds = mapFile.boundingBox()
        val cameraContext =
            PhoneOfflineMapCameraContext(
                bounds = bounds,
                mapStart = mapFile.startPosition(),
                mapStartZoom = mapFile.startZoomLevel(),
                zoomMin = mapFile.mapFileInfo.zoomLevelMin,
                zoomMax = mapFile.mapFileInfo.zoomLevelMax,
            )
        val camera = phoneOfflineInitialCameraSelection(initialCamera, cameraContext)
        trace.begin(PhoneOfflineMapRendererStage.MAPFILE_OPEN)
        trace.mapFileOpened(
            boundsAvailable = true,
            cameraInsideBounds = camera.reason == PhoneOfflineInitialCameraReason.CURRENT_VIEWPORT,
            initialZoom = camera.camera.zoom.toInt(),
            initialZoomSource = camera.reason,
            zoomClamped = camera.zoomClamped,
        )
        trace.complete(PhoneOfflineMapRendererStage.MAPFILE_OPEN)
        PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
            event = "mapfile_opened",
            detail =
                "file=${map.displayName} initialZoom=${camera.camera.zoom.toInt()} " +
                    "reason=${camera.reason} clamped=${camera.zoomClamped}",
        )
        if (change == PhoneMapsforgeBaseLayerChange.MAP_SWAP) {
            mapView.setZoomLevelMin(mapFile.mapFileInfo.zoomLevelMin)
            mapView.setZoomLevelMax(mapFile.mapFileInfo.zoomLevelMax)
            mapView.model.mapViewPosition.setMapPosition(camera.camera.toRendererMapPosition(), false)
        }
        return cameraContext
    }

    private fun createTileLayer(
        cache: TileCache,
        mapFile: MapFile,
        themeConfig: PhoneOfflineThemeConfig,
        trace: PhoneOfflineMapRendererTrace,
    ): PhoneFirstVisibleTileRendererLayer {
        trace.begin(PhoneOfflineMapRendererStage.THEME_CREATE)
        val renderTheme =
            PhoneOfflineThemeCatalog.renderTheme(
                config = themeConfig,
                context = context,
                onResourceProviderFailure = trace::resourceProviderFailed,
            )
        trace.themeCreated(themeConfig, renderTheme.fallbackUsed)
        trace.complete(PhoneOfflineMapRendererStage.THEME_CREATE)
        trace.begin(PhoneOfflineMapRendererStage.TILE_LAYER_CREATE)
        return PhoneFirstVisibleTileRendererLayer(
            cache = cache,
            mapDataStore = mapFile,
            mapViewPosition = mapView.model.mapViewPosition,
            graphicFactory = AndroidGraphicFactory.INSTANCE,
            callbacks = firstVisibleTileCallbacks(),
        ).apply {
            setXmlRenderTheme(renderTheme.theme)
            trace.complete(PhoneOfflineMapRendererStage.TILE_LAYER_CREATE)
            trace.themeApplied(renderTheme.fallbackUsed)
            trace.complete(PhoneOfflineMapRendererStage.THEME_APPLY)
        }
    }

    private fun firstVisibleTileCallbacks(): PhoneFirstVisibleTileCallbacks =
        PhoneFirstVisibleTileCallbacks(
            onFirstDraw = { layer ->
                PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                    event = "tile_layer_first_draw",
                    detail = "renderer=$rendererId layer=${System.identityHashCode(layer)}",
                )
                onRuntimeChanged()
            },
            onFirstVisibleTile = { layer, source ->
                PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                    event = "first_visible_tile_${source.name.lowercase()}",
                    detail = "renderer=$rendererId layer=${System.identityHashCode(layer)}",
                )
                onRuntimeChanged()
            },
            onCoverageChanged = onRuntimeChanged,
        )

    // A failed attachment must release the prepared Mapsforge resources as one unit.
    @Suppress("TooGenericExceptionCaught")
    private fun attachBaseLayer(
        layers: Layers,
        prepared: PhoneMapsforgePreparedBaseLayer,
        identity: PhoneMapsforgeBaseLayerIdentity,
        trace: PhoneOfflineMapRendererTrace,
    ) {
        try {
            PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                event = "base_layer_created",
                detail =
                    "renderer=$rendererId layer=${System.identityHashCode(prepared.layer)} " +
                        "cache=${prepared.cacheId}",
            )
            layers.add(0, prepared.layer)
            currentIdentity = identity
            currentBase = prepared
            onMapBoundsChanged(prepared.bounds)
            trace.tileLayerAttached()
            trace.complete(PhoneOfflineMapRendererStage.LAYER_ATTACH)
            PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                event = "base_layer_attached",
                detail = "renderer=$rendererId layer=${System.identityHashCode(prepared.layer)}",
            )
            forceRedraw()
            trace.firstCameraPublished(true)
            trace.complete(PhoneOfflineMapRendererStage.FIRST_CAMERA)
            PhoneOfflineMapRendererDiagnostics.record(trace.ready())
            PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                event = "renderer_ready",
                detail = "renderer=$rendererId mapView=${System.identityHashCode(mapView)}",
            )
            applyTerrainLayers(layers)
            onRuntimeChanged()
        } catch (error: Exception) {
            layers.remove(prepared.layer)
            currentBase = null
            prepared.release()
            throw error
        }
    }

    private fun onBaseLayerFailure(
        layers: Layers,
        trace: PhoneOfflineMapRendererTrace,
        error: Exception,
    ) {
        clearCurrentBaseLayer(layers, reason = "load_failure")
        PhoneOfflineMapRendererDiagnostics.record(trace.failed(error))
        onRendererFailure(PhoneOfflineMapError.INVALID)
        onRuntimeChanged()
    }

    private fun createTileCache(cacheId: String): TileCache {
        val config =
            phoneMapsforgeTileCacheConfig(
                tileSize = mapView.model.displayModel.tileSize,
                bytesPerPixel =
                    if (AndroidGraphicFactory.INSTANCE.nonTransparentBitmapConfig == Bitmap.Config.RGB_565) 2 else 4,
                memoryClassMb = context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 0,
                maxHeapBytes = Runtime.getRuntime().maxMemory(),
            )
        val cache =
            AndroidUtil.createTileCache(
                context.applicationContext,
                cacheId,
                config.firstLevelTiles,
                1f,
                mapView.model.frameBufferModel.overdrawFactor,
                false,
            )
        PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
            event = "tile_cache_created",
            detail =
                "renderer=$rendererId cache=$cacheId tiles=${config.firstLevelTiles} " +
                    "constrained=${config.constrainedMemory}",
        )
        return cache
    }

    private fun clearCurrentBaseLayer(
        layers: Layers,
        reason: String,
    ) {
        clearTerrainLayers(layers, reason)
        val previousBase = currentBase
        if (previousBase != null) {
            layers.remove(previousBase.layer)
            previousBase.release()
            PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                event = "base_layer_removed",
                detail = "renderer=$rendererId reason=$reason",
            )
        }
        currentIdentity = null
        currentBase = null
        onMapBoundsChanged(null)
    }

    private fun forceRedraw() {
        PhoneMapLayerMutationCoordinator.redrawLayersSafely(mapView)
        PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
            event = "redraw_requested",
            detail = "renderer=$rendererId",
        )
    }

    private fun terrainKey(
        sourceFile: File,
        settings: PhoneMapSettings,
        dataVersion: Long,
        dataAvailable: Boolean,
    ): String =
        "${sourceFile.absolutePath}|$dataVersion|$dataAvailable|" +
            "${settings.hillShadingEnabled}|${settings.reliefOverlayEnabled}"

    @Suppress("LongMethod", "ReturnCount", "TooGenericExceptionCaught")
    private fun applyTerrainLayers(layers: Layers) {
        val base = currentBase ?: return
        val desiredKey =
            terrainKey(
                base.sourceFile,
                requestedTerrainSettings,
                requestedTerrainDataVersion,
                requestedTerrainDataAvailable,
            )
        if (activeTerrainKey == desiredKey) return
        clearTerrainLayers(layers, reason = "replace")
        activeTerrainKey = desiredKey
        if (!requestedTerrainSettings.hillShadingEnabled && !requestedTerrainSettings.reliefOverlayEnabled) return
        if (!requestedTerrainDataAvailable) {
            PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                event = "terrain_unavailable",
                detail = "renderer=$rendererId reason=missing_dem",
            )
            return
        }

        if (requestedTerrainSettings.hillShadingEnabled) {
            var terrainMapFile: MapFile? = null
            var terrainCache: TileCache? = null
            var terrainConfig: HillsRenderConfig? = null
            try {
                val demFolder = PhoneMapsforgeDemFolder(PhoneElevationStore(context).readDirectories())
                val algorithm =
                    AdaptiveClasyHillShading(
                        AClasyHillShading
                            .ClasyParams()
                            .setReadingThreadsCount(1)
                            .setComputingThreadsCount(1)
                            .setPreprocess(false),
                        false,
                    ).setAdaptiveZoomEnabled(true)
                        .setCustomQualityScale(0.5)
                        .setZoomMinOverride(10)
                terrainConfig =
                    HillsRenderConfig(
                        MemoryCachingHgtReaderTileSource(
                            demFolder,
                            algorithm,
                            AndroidGraphicFactory.INSTANCE,
                        ),
                    ).setMagnitudeScaleFactor(1f).setExternal(true).indexOnThread()
                terrainMapFile = MapFile(base.sourceFile)
                val cacheId = "phone-hillshade-${phoneMapsforgeIdentityHash(base.sourceFile.absolutePath)}"
                terrainCache =
                    AndroidUtil.createExternalStorageTileCache(
                        context,
                        cacheId,
                        16,
                        mapView.model.displayModel.tileSize,
                        true,
                    )
                val layer =
                    TileRendererLayer(
                        terrainCache,
                        terrainMapFile,
                        mapView.model.mapViewPosition,
                        true,
                        false,
                        false,
                        AndroidGraphicFactory.INSTANCE,
                        terrainConfig,
                    ).apply {
                        setXmlRenderTheme(MapsforgeThemes.HILLSHADING)
                        setCacheZoomPlus(0)
                        setCacheZoomMinus(0)
                        setCacheTileMargin(0)
                    }
                layers.add(1.coerceAtMost(layers.size()), layer)
                hillshadeLayer = layer
                hillshadeTileCache = terrainCache
                hillshadeMapFile = terrainMapFile
                hillsRenderConfig = terrainConfig
                terrainCache = null
                terrainMapFile = null
                terrainConfig = null
                PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                    event = "hillshade_layer_attached",
                    detail = "renderer=$rendererId",
                )
            } catch (error: Exception) {
                PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                    event = "hillshade_layer_failed",
                    detail = "renderer=$rendererId error=${error.javaClass.simpleName}",
                )
                runCatching { terrainMapFile?.close() }
                runCatching { terrainCache?.destroy() }
                runCatching { terrainConfig?.interruptAndDestroy() }
            }
        }
        if (requestedTerrainSettings.reliefOverlayEnabled) {
            runCatching {
                PhoneReliefOverlayLayer(elevationRepository).also { layer ->
                    val terrainIndex =
                        hillshadeLayer?.let(layers::indexOf)
                            ?: currentBase?.layer?.let(layers::indexOf)
                            ?: -1
                    val belowOverlays = terrainIndex + 1
                    layers.add(belowOverlays.coerceIn(0, layers.size()), layer)
                    reliefOverlayLayer = layer
                }
                PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                    event = "relief_overlay_attached",
                    detail = "renderer=$rendererId",
                )
            }.onFailure { error ->
                PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                    event = "relief_overlay_failed",
                    detail = "renderer=$rendererId error=${error.javaClass.simpleName}",
                )
            }
        }
        PhoneMapLayerMutationCoordinator.redrawLayersSafely(mapView)
    }

    private fun clearTerrainLayers(
        layers: Layers,
        reason: String,
    ) {
        reliefOverlayLayer?.let { layer ->
            layers.remove(layer)
            runCatching { layer.onDestroy() }
        }
        reliefOverlayLayer = null
        hillshadeLayer?.let { layer ->
            layers.remove(layer)
            runCatching { layer.onDestroy() }
        }
        hillshadeLayer = null
        hillshadeTileCache?.let { cache -> runCatching { cache.destroy() } }
        hillshadeTileCache = null
        hillshadeMapFile?.let { mapFile -> runCatching { mapFile.close() } }
        hillshadeMapFile = null
        hillsRenderConfig?.let { config -> runCatching { config.interruptAndDestroy() } }
        hillsRenderConfig = null
        if (activeTerrainKey != null) {
            PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                event = "terrain_layers_removed",
                detail = "renderer=$rendererId reason=$reason",
            )
        }
        activeTerrainKey = null
    }

    private companion object {
        const val BASE_LAYER_MUTATION_KEY = "phone_mapsforge_base_layer"
        const val TERRAIN_LAYER_MUTATION_KEY = "phone_mapsforge_terrain"
    }
}

internal data class PhoneMapsforgeRendererRuntimeSnapshot(
    val rendererId: Int,
    val layerId: Int?,
    val cacheId: String?,
    val displayName: String?,
    val mapBounds: BoundingBox?,
    val coverage: PhoneFirstVisibleTileCoverage,
    val tileLayerDrawObserved: Boolean,
    val firstVisibleBaseTileObserved: Boolean,
) {
    fun layerPresentIn(layers: Layers): Boolean {
        val expectedLayerId = layerId ?: return false
        return layers.any { System.identityHashCode(it) == expectedLayerId }
    }
}

private data class PhoneMapsforgePreparedBaseLayer(
    val sourceFile: File,
    val mapFile: MapFile,
    val cache: TileCache,
    val cacheId: String,
    val layer: PhoneFirstVisibleTileRendererLayer,
    val bounds: BoundingBox,
) {
    private val releaseOnce = PhoneMapsforgeReleaseOnce()

    fun release() {
        releaseOnce.release {
            val layerDestroyed = runCatching { layer.onDestroy() }.isSuccess
            if (!layerDestroyed) runCatching { mapFile.close() }
            runCatching { cache.destroy() }
        }
    }
}

/** Phone adaptation of Wear's first-visible renderer seam, without Wear telemetry dependencies. */
private class PhoneFirstVisibleTileRendererLayer(
    private val cache: TileCache,
    mapDataStore: MapFile,
    mapViewPosition: MapViewPosition,
    graphicFactory: GraphicFactory,
    private val callbacks: PhoneFirstVisibleTileCallbacks,
) : TileRendererLayer(
        cache,
        mapDataStore,
        mapViewPosition,
        false,
        true,
        false,
        graphicFactory,
        null,
    ) {
    private val drawObserved = AtomicBoolean(false)
    private val firstVisibleTileObserved = AtomicBoolean(false)
    private var lastCoverageSampleAtElapsedMs: Long? = null

    @Volatile
    var latestCoverage: PhoneFirstVisibleTileCoverage = PhoneFirstVisibleTileCoverage()
        private set

    val hasDrawObserved: Boolean
        get() = drawObserved.get()

    val hasFirstVisibleBaseTileObserved: Boolean
        get() = firstVisibleTileObserved.get()

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeftPoint: Point,
        rotation: Rotation,
    ) {
        val firstDraw = drawObserved.compareAndSet(false, true)
        val coverageBefore = if (firstDraw) visibleTileCoverage(boundingBox, zoomLevel) else latestCoverage
        if (firstDraw) callbacks.onFirstDraw(this)
        super.draw(boundingBox, zoomLevel, canvas, topLeftPoint, rotation)
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val shouldSampleCoverage =
            firstDraw ||
                isPhoneFirstVisibleTileCoverageSampleDue(
                    lastSampleAtElapsedMs = lastCoverageSampleAtElapsedMs,
                    nowElapsedMs = nowElapsedMs,
                )
        val coverageAfter =
            if (shouldSampleCoverage) {
                lastCoverageSampleAtElapsedMs = nowElapsedMs
                visibleTileCoverage(boundingBox, zoomLevel)
            } else {
                latestCoverage
            }
        latestCoverage = coverageAfter
        if (coverageAfter != coverageBefore) callbacks.onCoverageChanged()
        if (
            coverageAfter.drawableVisibleTiles + coverageAfter.parentFallbackTiles > 0 &&
            firstVisibleTileObserved.compareAndSet(false, true)
        ) {
            callbacks.onFirstVisibleTile(
                this,
                if (firstDraw && coverageBefore.drawableVisibleTiles + coverageBefore.parentFallbackTiles > 0) {
                    PhoneFirstVisibleTileSource.WARM_CACHE
                } else {
                    PhoneFirstVisibleTileSource.COLD_RENDER
                },
            )
        }
    }

    private fun visibleTileCoverage(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
    ): PhoneFirstVisibleTileCoverage =
        runCatching {
            if (renderThemeFuture == null) return@runCatching PhoneFirstVisibleTileCoverage()
            val tileSize = displayModel?.tileSize ?: return@runCatching PhoneFirstVisibleTileCoverage()
            val tiles = LayerUtil.getTiles(boundingBox, zoomLevel, tileSize)
            phoneFirstVisibleTileCoverage(tiles, ::hasDrawableTile).copy(
                pendingJobCount = jobQueue?.size() ?: -1,
            )
        }.getOrDefault(PhoneFirstVisibleTileCoverage())

    private fun hasDrawableTile(tile: Tile): Boolean =
        cache.getImmediately(createJob(tile))?.let { bitmap ->
            bitmap.decrementRefCount()
            true
        } ?: false
}

private fun PhoneMapCameraSnapshot.toRendererMapPosition(): MapPosition =
    MapPosition(
        LatLong(latitude, longitude),
        zoom.toInt().coerceIn(0, Byte.MAX_VALUE.toInt()).toByte(),
    )
