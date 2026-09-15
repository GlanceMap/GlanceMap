package com.glancemap.glancemapwearos.presentation.features.maps

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.core.maps.Dem3CoverageUtils
import com.glancemap.glancemapwearos.core.maps.DemSignatureStore
import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.core.service.diagnostics.BenchmarkTrace
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.diagnostics.MapHotPathDiagnostics
import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.hills.HillsRenderConfig
import org.mapsforge.map.layer.hills.MemoryCachingHgtReaderTileSource
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import org.mapsforge.map.model.common.Observer as MapsforgeObserver

internal fun shouldWarmMapStartupTileCache(
    prewarmingEnabled: Boolean,
    skipNextStartupPrewarm: Boolean,
    hillshadeEnabled: Boolean,
): Boolean = prewarmingEnabled && !skipNextStartupPrewarm && !hillshadeEnabled

class MapRenderer(
    private val context: Context,
    private val mapView: MapView,
) {
    private data class TileCacheConfig(
        val firstLevelTiles: Int,
        val memoryBudgetBytes: Long,
        val constrainedMemory: Boolean,
        val startupPrewarmEnabled: Boolean,
        val startupPrewarmZoomPlus: Int,
        val startupPrewarmZoomMinus: Int,
        val startupPrewarmTileMargin: Int,
        val startupPrewarmDurationMs: Long,
        val memoryClassMb: Int,
        val maxHeapBytes: Long,
    )

    data class ThemeApplyResult(
        val requiresVisibleTileWait: Boolean = false,
        val tileUpdateBaselineVersion: Long = 0L,
    )

    internal data class FirstVisibleMapEvent(
        val version: Long,
        val source: FirstVisibleBaseTileSource,
    )

    private data class PendingFirstVisibleMapTiming(
        val requestId: Long,
        val mapName: String,
        val cacheId: String,
        val startedAtElapsedMs: Long,
        val traceMarker: BenchmarkTrace.AsyncMarker,
    )

    private data class PendingFirstVisibleHillshadeTiming(
        val requestId: Long,
        val mapName: String,
        val demSources: String,
        val zoomLevel: Int,
        val visibleTileCount: Int,
        val detailedDemTileCount: Int,
        val standardFallbackDemTileCount: Int,
        val missingDemTileCount: Int,
        val startedAtElapsedMs: Long,
        val traceMarker: BenchmarkTrace.AsyncMarker,
    )

    private data class ThemeConfigRequest(
        val themeFile: File?,
        val mapsforgeThemeName: String?,
        val bundledThemeId: String,
        val hillShadingEnabled: Boolean,
        val reliefOverlayEnabled: Boolean,
        val demSource: DemSource,
    )

    data class ReliefOverlayState(
        val enabled: Boolean,
        val processing: Boolean,
        val progressPercent: Int?,
    )

    data class HillshadeTerrainUnavailableEvent(
        val mapName: String,
        val zoomLevel: Int,
        val missingTileCount: Int,
        val areaKey: String,
    )

    data class CacheDiagnosticsSnapshot(
        val activeTileCacheId: String?,
        val activeTileCacheLastUsedMs: Long?,
        val tileCacheBucketCount: Int,
        val tileCacheTotalSizeBytes: Long,
        val activeTileCacheSizeBytes: Long?,
        val tileCacheSoftLimitBytes: Long,
        val tileCacheTargetLimitBytes: Long,
        val tileCacheMaxAgeMs: Long,
        val tileCacheCleanupIntervalMs: Long,
        val lastCleanupMs: Long?,
        val tileCacheBuckets: List<CacheBucketDiagnostics>,
        val reliefOverlayNamespaceCount: Int,
        val reliefOverlayCacheSizeBytes: Long,
        val bundledThemeCacheDirCount: Int,
        val bundledThemeCacheTotalSizeBytes: Long,
    )

    data class CacheBucketDiagnostics(
        val id: String,
        val sizeBytes: Long,
        val lastUsedMs: Long?,
        val active: Boolean,
    )

    companion object {
        private const val TAG = "MapRenderer"

        private const val FIRST_LEVEL_MIN_TILES = 64
        private const val FIRST_LEVEL_MAX_TILES = 256

        private const val MEMORY_BUDGET_FRACTION = 1.0 / 16.0
        private const val MEMORY_BUDGET_CAP_BYTES = 32L * 1024L * 1024L // 32MB
        private const val CONSTRAINED_MEMORY_CLASS_MB = 128
        private const val CONSTRAINED_MAX_HEAP_BYTES = 160L * 1024L * 1024L // 160MB
        private const val CONSTRAINED_FIRST_LEVEL_MIN_TILES = 24
        private const val CONSTRAINED_FIRST_LEVEL_MAX_TILES = 80
        private const val CONSTRAINED_MEMORY_BUDGET_FRACTION = 1.0 / 20.0
        private const val CONSTRAINED_MEMORY_BUDGET_CAP_BYTES = 8L * 1024L * 1024L // 8MB
        private const val DEM_SCAN_MAX_DEPTH = 6
        private const val STARTUP_PREWARM_ZOOM_STEPS = 2
        private const val STARTUP_PREWARM_TILE_MARGIN = 1
        private const val STARTUP_PREWARM_DURATION_MS = 8_000L
        private const val STARTUP_PREWARM_ARM_DELAY_MS = 1_500L
        private const val CONSTRAINED_STARTUP_PREWARM_ZOOM_STEPS = 2
        private const val CONSTRAINED_STARTUP_PREWARM_TILE_MARGIN = 0
        private const val CONSTRAINED_STARTUP_PREWARM_DURATION_MS = 4_000L
        private const val FIRST_VISIBLE_MAP_TRACE_STAGE = "mapRenderer.firstVisibleMap"
        private const val FIRST_VISIBLE_MAP_DIAGNOSTIC_STAGE = "mapRenderer.firstVisibleMap"
        private const val FIRST_VISIBLE_MAP_TIMING_TIMEOUT_MS = 10_000L
        private const val FIRST_VISIBLE_HILLSHADE_TRACE_STAGE = "mapRenderer.firstVisibleHillshade"
        private const val FIRST_VISIBLE_HILLSHADE_DIAGNOSTIC_STAGE = "mapRenderer.firstVisibleHillshade"
        private const val FIRST_VISIBLE_HILLSHADE_TIMING_TIMEOUT_MS = 30_000L
        private const val HILLSHADE_FIRST_LEVEL_TILES = 16

        fun captureCacheDiagnostics(context: Context): CacheDiagnosticsSnapshot = captureMapRendererCacheDiagnostics(context)
    }

    private var currentMapPath: String? = null
    private var currentMapSignature: String? = null
    private var currentThemeFile: File? = null
    private var currentMapsforgeThemeName: String? = null
    private var currentBundledThemeId: String = MapsforgeThemeCatalog.ELEVATE_THEME_ID
    private var currentHillShadingEnabled: Boolean = true
    private var currentReliefOverlayEnabled: Boolean = false
    private var currentDemSource: DemSource = DemSource.DEFAULT
    private var currentElevationLabelsMetric: Boolean = true

    // Signature to detect changes even if same File path is reused
    private var currentThemeSignature: String = ""
    private var currentDemSignature: String? = null

    private val demRootDir: File
        get() = Dem3CoverageUtils.demRootDir(context, currentDemSource)
    private val demRootDirs: List<File>
        get() = currentDemSource.readFallbackOrder().map { source -> Dem3CoverageUtils.demRootDir(context, source) }
    private val hillshadeDemRootDirs: List<File>
        get() = DemSource.LOAD_PRIORITY.map { source -> Dem3CoverageUtils.demRootDir(context, source) }
    private val reliefOverlayCacheRootDir: File by lazy {
        val root = context.externalCacheDir ?: context.cacheDir
        File(root, RELIEF_OVERLAY_CACHE_DIR_NAME)
    }
    private var hillsRenderConfig: HillsRenderConfig? = null
    private var hillsRenderConfigDemSignature: String? = null
    private var activeHillshadeDemRootDirs: List<File> = emptyList()
    private var rebuildTileCacheRequested: Boolean = false
    private var currentTileCacheId: String = "$CACHE_ID_PREFIX-bootstrap"
    private var skipNextStartupTilePrewarm: Boolean = false
    private var cleanLayerSwapRequested: Boolean = false

    @Volatile private var cacheCleanupInProgress: Boolean = false
    private val tileCacheUpdateCounter = AtomicLong(0L)
    private val tileCacheUpdateVersion = MutableStateFlow(0L)
    private val firstVisibleMapCounter = AtomicLong(0L)
    private val firstVisibleMapEvent = MutableStateFlow<FirstVisibleMapEvent?>(null)
    private val _hillshadeTerrainUnavailableEvent =
        MutableStateFlow<HillshadeTerrainUnavailableEvent?>(null)
    val hillshadeTerrainUnavailableEvent: StateFlow<HillshadeTerrainUnavailableEvent?> =
        _hillshadeTerrainUnavailableEvent.asStateFlow()
    private var nextFirstVisibleMapRequestId: Long = 0L
    private var pendingFirstVisibleMapTiming: PendingFirstVisibleMapTiming? = null
    private var nextFirstVisibleHillshadeRequestId: Long = 0L
    private var pendingFirstVisibleHillshadeTiming: PendingFirstVisibleHillshadeTiming? = null
    private val activityManager: ActivityManager? by lazy {
        context.getSystemService(ActivityManager::class.java)
    }
    private val tileCacheConfig: TileCacheConfig by lazy {
        buildTileCacheConfig()
    }
    private val tileCacheObserver =
        object : MapsforgeObserver {
            override fun onChange() {
                val nextVersion = tileCacheUpdateCounter.incrementAndGet()
                tileCacheUpdateVersion.value = nextVersion
            }
        }
    private val cacheMaintenancePrefs by lazy {
        context.getSharedPreferences(CACHE_CLEANUP_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var tileCache: TileCache = createTileCache(cacheId = currentTileCacheId)

    private fun createTileCache(cacheId: String): TileCache {
        val tileSize = mapView.model.displayModel.tileSize
        val config = tileCacheConfig

        val cache =
            MapHotPathDiagnostics.measure(
                stage = "mapRenderer.openTileCache",
                detail = "cacheId=$cacheId",
            ) {
                AndroidUtil.createExternalStorageTileCache(
                    context,
                    cacheId,
                    config.firstLevelTiles,
                    tileSize,
                    true,
                )
            }
        cache.addObserver(tileCacheObserver)
        Log.i(
            TAG,
            "createTileCache: cacheId=$cacheId tiles=${config.firstLevelTiles} " +
                "budgetMb=${"%.1f".format(Locale.US, config.memoryBudgetBytes / (1024f * 1024f))} " +
                "memoryClassMb=${config.memoryClassMb} " +
                "maxHeapMb=${"%.1f".format(Locale.US, config.maxHeapBytes / (1024f * 1024f))} " +
                "constrained=${config.constrainedMemory} " +
                "prewarm=${config.startupPrewarmEnabled} " +
                "prewarmZoom=${config.startupPrewarmZoomMinus}/${config.startupPrewarmZoomPlus} " +
                "prewarmMs=${config.startupPrewarmDurationMs}",
        )
        markMapRendererCacheBucketUsed(context, cacheMaintenancePrefs, cacheId)
        return cache
    }

    private fun buildTileCacheConfig(): TileCacheConfig {
        val tileSize = mapView.model.displayModel.tileSize
        val bytesPerPixel =
            when (AndroidGraphicFactory.INSTANCE.nonTransparentBitmapConfig) {
                android.graphics.Bitmap.Config.RGB_565 -> 2
                else -> 4
            }
        val approxTileBytes = tileSize.toLong() * tileSize.toLong() * bytesPerPixel.toLong()
        val maxHeap = Runtime.getRuntime().maxMemory()
        val memoryClassMb = activityManager?.memoryClass ?: 0
        val constrainedMemory =
            (memoryClassMb in 1..CONSTRAINED_MEMORY_CLASS_MB) ||
                maxHeap <= CONSTRAINED_MAX_HEAP_BYTES
        val memoryBudgetFraction =
            if (constrainedMemory) {
                CONSTRAINED_MEMORY_BUDGET_FRACTION
            } else {
                MEMORY_BUDGET_FRACTION
            }
        val memoryBudgetCapBytes =
            if (constrainedMemory) {
                CONSTRAINED_MEMORY_BUDGET_CAP_BYTES
            } else {
                MEMORY_BUDGET_CAP_BYTES
            }
        val memoryBudget = min((maxHeap * memoryBudgetFraction).toLong(), memoryBudgetCapBytes)
        val minTiles =
            if (constrainedMemory) {
                CONSTRAINED_FIRST_LEVEL_MIN_TILES
            } else {
                FIRST_LEVEL_MIN_TILES
            }
        val maxTiles =
            if (constrainedMemory) {
                CONSTRAINED_FIRST_LEVEL_MAX_TILES
            } else {
                FIRST_LEVEL_MAX_TILES
            }
        val computedTiles =
            if (approxTileBytes > 0) (memoryBudget / approxTileBytes).toInt() else minTiles
        val firstLevelTiles = computedTiles.coerceIn(minTiles, maxTiles)
        val startupPrewarmZoomSteps =
            if (constrainedMemory) {
                CONSTRAINED_STARTUP_PREWARM_ZOOM_STEPS
            } else {
                STARTUP_PREWARM_ZOOM_STEPS
            }
        val startupPrewarmTileMargin =
            if (constrainedMemory) {
                CONSTRAINED_STARTUP_PREWARM_TILE_MARGIN
            } else {
                STARTUP_PREWARM_TILE_MARGIN
            }
        val startupPrewarmDurationMs =
            if (constrainedMemory) {
                CONSTRAINED_STARTUP_PREWARM_DURATION_MS
            } else {
                STARTUP_PREWARM_DURATION_MS
            }
        return TileCacheConfig(
            firstLevelTiles = firstLevelTiles,
            memoryBudgetBytes = memoryBudget,
            constrainedMemory = constrainedMemory,
            startupPrewarmEnabled = true,
            startupPrewarmZoomPlus = startupPrewarmZoomSteps,
            startupPrewarmZoomMinus = startupPrewarmZoomSteps,
            startupPrewarmTileMargin = startupPrewarmTileMargin,
            startupPrewarmDurationMs = startupPrewarmDurationMs,
            memoryClassMb = memoryClassMb,
            maxHeapBytes = maxHeap,
        )
    }

    private var currentLayer: TileRendererLayer? = null
    private var hillshadeLayer: FirstVisibleHillshadeTileRendererLayer? = null
    private var hillshadeTileCache: TileCache? = null
    private var currentHillshadeTileCacheId: String? = null
    private var reliefOverlayLayer: ReliefOverlayLayer? = null
    private var liveElevationSampler: ReliefOverlayLayer? = null
    private var currentStore: MapDataStore? = null
    private val reliefOverlayStateListeners = CopyOnWriteArraySet<(ReliefOverlayState) -> Unit>()

    @Volatile
    private var lastPublishedReliefOverlayState: ReliefOverlayState? = null
    private val elevationLabelThemeCallback =
        ElevationLabelThemeCallback {
            currentElevationLabelsMetric
        }

    init {
        mapView.model.displayModel.backgroundColor = android.graphics.Color.BLACK
        mapView.model.displayModel.setThemeCallback(elevationLabelThemeCallback)
    }

    fun setThemeConfig(
        themeFile: File?,
        mapsforgeThemeName: String?,
        bundledThemeId: String,
        hillShadingEnabled: Boolean,
        reliefOverlayEnabled: Boolean,
        demSource: DemSource = DemSource.DEFAULT,
    ): ThemeApplyResult {
        val timingMarker = MapHotPathDiagnostics.begin("mapRenderer.setThemeConfig")
        var timingStatus = "ok"
        var usedLightweightReload = false
        var demChanged = false
        var themeApplyResult = ThemeApplyResult()
        val normalizedMapsforge =
            mapsforgeThemeName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.uppercase(Locale.ROOT)
        val normalizedBundledThemeId =
            if (MapsforgeThemeCatalog.isBundledAssetTheme(bundledThemeId)) {
                bundledThemeId
            } else {
                MapsforgeThemeCatalog.ELEVATE_THEME_ID
            }
        val request =
            ThemeConfigRequest(
                themeFile = themeFile,
                mapsforgeThemeName = normalizedMapsforge,
                bundledThemeId = normalizedBundledThemeId,
                hillShadingEnabled = hillShadingEnabled,
                reliefOverlayEnabled = reliefOverlayEnabled,
                demSource = demSource,
            )
        val newSignature =
            computeMapRendererThemeSignature(
                file = themeFile,
                mapsforgeThemeName = normalizedMapsforge,
                bundledThemeId = normalizedBundledThemeId,
                hillShadingEnabled = false,
            )

        // Hillshade is an independent transparent layer. Updating it must not purge, remove, or
        // rebuild the already-visible base map.
        if (currentThemeSignature == newSignature) {
            return applyLayerOnlyThemeConfig(
                request = request,
                timingMarker = timingMarker,
            )
        }

        val demSourceChanged = currentDemSource != demSource
        try {
            val theme =
                MapHotPathDiagnostics.measure(
                    stage = "mapRenderer.buildRenderThemeOrNull",
                    detail = "mapsforge=${normalizedMapsforge != null} bundled=$normalizedBundledThemeId",
                ) {
                    buildMapRendererThemeOrNull(
                        context = context,
                        themeFile = themeFile,
                        mapsforgeThemeName = normalizedMapsforge,
                        bundledThemeId = normalizedBundledThemeId,
                    )
                } ?: run {
                    timingStatus = "theme_unavailable"
                    Log.w(TAG, "setThemeConfig: theme is null")
                    return themeApplyResult
                }

            currentThemeFile = themeFile
            currentMapsforgeThemeName = normalizedMapsforge
            currentBundledThemeId = normalizedBundledThemeId
            currentHillShadingEnabled = hillShadingEnabled
            currentReliefOverlayEnabled = reliefOverlayEnabled
            currentDemSource = demSource
            currentThemeSignature = newSignature
            if (demSourceChanged) {
                liveElevationSampler?.let { sampler ->
                    runCatching { sampler.onDestroy() }
                }
                liveElevationSampler = null
            }
            if (!currentHillShadingEnabled || demSourceChanged) {
                clearHillshadeLayer(reason = "hill_config_changed")
                destroyHillsRenderConfig()
            }

            // Clear rendered tiles so the old theme cannot survive the full theme reload.
            purgeTileCache(reason = "theme_pre_reload")

            val newDemSignature = computeDemSignatureOrNull()
            demChanged = newDemSignature != currentDemSignature
            val currentPath = currentMapPath
            if (currentPath.isNullOrBlank()) {
                timingStatus = "applied_without_map_reload"
                currentDemSignature = newDemSignature
                currentLayer?.setXmlRenderTheme(theme)
                updateReliefOverlayLayer()
                publishReliefOverlayState(force = true)
                forceRedraw()
                return themeApplyResult
            }

            timingStatus = if (demChanged) "full_reload_dem_changed" else "full_reload_theme_changed"
            skipNextStartupTilePrewarm = true
            cleanLayerSwapRequested = true
            // Older Mapsforge builds showed incomplete viewport rendering when reusing the same
            // MapDataStore across TileRendererLayer theme swaps, so prefer a clean layer rebuild.
            rebuildTileCacheRequested = false
            themeApplyResult =
                ThemeApplyResult(
                    requiresVisibleTileWait = true,
                    tileUpdateBaselineVersion = tileCacheUpdateCounter.get(),
                )
            forceReloadCurrentMapLayer(currentPath)
        } finally {
            MapHotPathDiagnostics.end(
                marker = timingMarker,
                status = timingStatus,
                detail =
                    buildString {
                        append("mapsforge=").append(normalizedMapsforge != null)
                        append(" hill=").append(hillShadingEnabled)
                        append(" relief=").append(reliefOverlayEnabled)
                        append(" demSource=").append(demSource.id)
                        append(" demSourceChanged=").append(demSourceChanged)
                        append(" demChanged=").append(demChanged)
                        append(" lightweight=").append(usedLightweightReload)
                    },
            )
        }
        return themeApplyResult
    }

    private fun applyLayerOnlyThemeConfig(
        request: ThemeConfigRequest,
        timingMarker: MapHotPathDiagnostics.Marker?,
    ): ThemeApplyResult {
        val hillShadingChanged = currentHillShadingEnabled != request.hillShadingEnabled
        val reliefOverlayChanged = currentReliefOverlayEnabled != request.reliefOverlayEnabled
        val demSourceChanged = currentDemSource != request.demSource
        val hillshadeLayerChanged = hillShadingChanged || demSourceChanged
        val reliefLayerChanged = reliefOverlayChanged || demSourceChanged
        if (!hillShadingChanged && !reliefOverlayChanged && !demSourceChanged) {
            MapHotPathDiagnostics.end(
                marker = timingMarker,
                status = "no_change",
                detail =
                    "mapsforge=${request.mapsforgeThemeName != null} " +
                        "hill=${request.hillShadingEnabled} reliefChanged=false",
            )
            return ThemeApplyResult()
        }

        currentThemeFile = request.themeFile
        currentMapsforgeThemeName = request.mapsforgeThemeName
        currentBundledThemeId = request.bundledThemeId
        currentHillShadingEnabled = request.hillShadingEnabled
        currentReliefOverlayEnabled = request.reliefOverlayEnabled
        currentDemSource = request.demSource

        if (demSourceChanged) {
            clearReliefOverlayLayer()
            liveElevationSampler?.let { sampler ->
                runCatching { sampler.onDestroy() }
            }
            liveElevationSampler = null
        }
        if (hillshadeLayerChanged) {
            clearHillshadeLayer(reason = "hill_config_changed")
            destroyHillsRenderConfig()
        }

        val newDemSignature = computeDemSignatureOrNull()
        currentDemSignature = newDemSignature
        if (hillshadeLayerChanged) {
            updateHillshadeLayerForCurrentMap(newDemSignature)
        }
        if (reliefLayerChanged) {
            updateReliefOverlayLayer()
            publishReliefOverlayState(force = true)
        }
        forceRedraw()

        val timingStatus =
            when {
                demSourceChanged -> "dem_layer_only"
                hillShadingChanged -> "hillshade_layer_only"
                else -> "relief_overlay_only"
            }
        MapHotPathDiagnostics.end(
            marker = timingMarker,
            status = timingStatus,
            detail =
                "mapsforge=${request.mapsforgeThemeName != null} hill=${request.hillShadingEnabled} " +
                    "hillChanged=$hillShadingChanged reliefChanged=$reliefOverlayChanged " +
                    "demSourceChanged=$demSourceChanged",
        )
        return ThemeApplyResult()
    }

    private fun updateHillshadeLayerForCurrentMap(demSignature: String?) {
        currentMapPath
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?.let { mapFile ->
                updateHillshadeLayer(
                    mapFile = mapFile,
                    demSignature = demSignature,
                    requiredDemTileIds =
                        if (currentHillShadingEnabled) {
                            Dem3CoverageUtils.requiredTileIdsForMap(mapFile)
                        } else {
                            null
                        },
                )
            }
    }

    suspend fun awaitTileCacheUpdateAfter(
        baselineVersion: Long,
        timeoutMs: Long,
    ): Boolean {
        if (tileCacheUpdateCounter.get() > baselineVersion) return true
        return withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            tileCacheUpdateVersion.first { it > baselineVersion }
        } != null
    }

    fun currentTileCacheUpdateVersion(): Long = tileCacheUpdateCounter.get()

    /** Debug-only snapshot taken after a map-position observer reports a completed zoom change. */
    fun recordCompletedZoomChange(
        oldZoom: Int,
        newZoom: Int,
        inputSource: String,
    ) {
        if (!DebugTelemetry.isFullDiagnosticsCaptureEnabled()) return
        val center = mapView.model.mapViewPosition.center
        val runtime = Runtime.getRuntime()
        val totalHeapBytes = runtime.totalMemory()
        val usedHeapBytes = totalHeapBytes - runtime.freeMemory()
        MapHotPathDiagnostics.recordEvent(
            stage = "mapRenderer.viewportZoom",
            status = "completed",
            detail =
                "input=$inputSource oldZoom=$oldZoom newZoom=$newZoom " +
                    "center=${center.latitude},${center.longitude} " +
                    "rotation=${mapView.mapRotation.degrees} " +
                    "attached=${mapView.isAttachedToWindow} size=${mapView.width}x${mapView.height} " +
                    "renderer=${System.identityHashCode(this)} " +
                    "mapView=${System.identityHashCode(mapView)} " +
                    "layer=${currentLayer?.let(System::identityHashCode) ?: 0} " +
                    "cacheId=$currentTileCacheId cache=${System.identityHashCode(tileCache)} " +
                    "heapUsedKb=${usedHeapBytes / 1024L} heapTotalKb=${totalHeapBytes / 1024L} " +
                    "nativeAllocatedKb=${Debug.getNativeHeapAllocatedSize() / 1024L}",
        )
        (currentLayer as? FirstVisibleTileRendererLayer)
            ?.requestVisibleTileDiagnosticSnapshot("zoom_completed")
    }

    /** Requests one FULL-diagnostics visibility snapshot after a completed user pan. */
    fun recordCompletedPan() {
        if (!DebugTelemetry.isFullDiagnosticsCaptureEnabled()) return
        (currentLayer as? FirstVisibleTileRendererLayer)
            ?.requestVisibleTileDiagnosticSnapshot("pan_completed")
    }

    internal suspend fun awaitFirstVisibleMapAfter(
        baselineVersion: Long,
        timeoutMs: Long,
    ): FirstVisibleMapEvent? {
        firstVisibleMapEvent.value?.let { event ->
            if (event.version > baselineVersion) return event
        }
        return withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            firstVisibleMapEvent
                .filterNotNull()
                .first { event -> event.version > baselineVersion }
        }
    }

    internal fun armInitialViewportReadiness(): VisibleTileViewportReadinessRequest? {
        val layer = currentLayer as? FirstVisibleTileRendererLayer ?: return null
        val request = layer.armCurrentViewportReadiness()
        return request.takeIf { currentLayer === layer }
    }

    internal suspend fun awaitInitialViewportReadiness(
        request: VisibleTileViewportReadinessRequest,
        timeoutMs: Long,
    ): VisibleTileViewportReadinessEvent? {
        val layer = currentLayer as? FirstVisibleTileRendererLayer ?: return null
        val activeRequest =
            request.takeIf { System.identityHashCode(layer) == it.layerId }
                ?: layer.armCurrentViewportReadiness()
        return layer
            .awaitViewportReadiness(activeRequest, timeoutMs)
            ?.takeIf { currentLayer === layer }
    }

    internal fun currentFirstVisibleMapVersion(): Long = firstVisibleMapCounter.get()

    fun setElevationLabelUnitsMetric(isMetric: Boolean) {
        if (currentElevationLabelsMetric == isMetric) return

        currentElevationLabelsMetric = isMetric
        mapView.model.displayModel.setThemeCallback(elevationLabelThemeCallback)
        rebuildTileCacheRequested = true
        skipNextStartupTilePrewarm = true
        updateMapLayer(currentMapPath)
    }

    fun updateMapLayer(mapPath: String?) {
        val timingMarker = MapHotPathDiagnostics.begin("mapRenderer.updateMapLayer")
        var timingStatus = "ok"
        var desiredCacheIdForTiming: String? = null
        var cacheRecreated = false
        var warmStartupCache = false
        var cleanLayerSwap = false
        val newMapSignature = computeMapRendererMapSignature(mapPath)
        val newDemSignature = computeDemSignatureOrNull()
        val desiredCacheId =
            resolveMapRendererDesiredCacheId(
                mapSignature = newMapSignature,
                themeSignature = currentThemeSignature,
                elevationLabelsMetric = currentElevationLabelsMetric,
            )
        desiredCacheIdForTiming = desiredCacheId
        if (
            mapPath == currentMapPath &&
            newMapSignature == currentMapSignature &&
            newDemSignature == currentDemSignature &&
            !rebuildTileCacheRequested &&
            desiredCacheId == currentTileCacheId
        ) {
            timingStatus = "no_change"
            MapHotPathDiagnostics.end(
                marker = timingMarker,
                status = timingStatus,
                detail = "cacheId=$desiredCacheId",
            )
            return
        }

        prepareFirstVisibleMapTiming(
            mapPath = mapPath,
            cacheId = desiredCacheId,
        )

        try {
            cleanLayerSwap = consumeCleanLayerSwap()
            if (rebuildTileCacheRequested || desiredCacheId != currentTileCacheId) {
                recreateTileCache(newCacheId = desiredCacheId)
                cacheRecreated = true
                rebuildTileCacheRequested = false
            }

            if (mapPath.isNullOrBlank()) {
                timingStatus = "map_disabled"
                currentMapPath = null
                currentMapSignature = null
                currentDemSignature = newDemSignature
                // Explicitly purge cache so disabling a map never leaves stale tiles visible.
                updateReliefOverlayLayer()
                purgeTileCache(reason = "map_disabled")
                forceRedraw()
                return
            }

            val mapFile = File(mapPath)
            if (!mapFile.exists()) {
                timingStatus = "missing_map_file"
                cancelFirstVisibleMapTiming(reason = timingStatus)
                currentMapPath = null
                currentMapSignature = null
                currentDemSignature = newDemSignature
                rebuildTileCacheRequested = true
                Log.w(TAG, "updateMapLayer: Map file does not exist: $mapPath")
                updateReliefOverlayLayer()
                purgeTileCache(reason = "missing_map_file")
                forceRedraw()
                return
            }

            val theme =
                MapHotPathDiagnostics.measure(
                    stage = "mapRenderer.buildRenderThemeOrNull",
                    detail = "mapsforge=${!currentMapsforgeThemeName.isNullOrBlank()} bundled=$currentBundledThemeId",
                ) {
                    buildMapRendererThemeOrNull(
                        context = context,
                        themeFile = currentThemeFile,
                        mapsforgeThemeName = currentMapsforgeThemeName,
                        bundledThemeId = currentBundledThemeId,
                    )
                }
            if (theme == null) {
                timingStatus = "theme_unavailable"
                cancelFirstVisibleMapTiming(reason = timingStatus)
                currentMapPath = null
                currentMapSignature = null
                currentDemSignature = newDemSignature
                rebuildTileCacheRequested = true
                Log.w(TAG, "updateMapLayer: theme is null, cannot render map.")
                updateReliefOverlayLayer()
                forceRedraw()
                return
            }

            val mapDataStore: MapDataStore =
                MapHotPathDiagnostics.measure(
                    stage = "mapRenderer.openMapFile",
                    detail = "file=${mapFile.name}",
                ) {
                    MapFile(mapFile)
                }
            currentStore = mapDataStore
            warmStartupCache =
                shouldWarmMapStartupTileCache(
                    prewarmingEnabled = tileCacheConfig.startupPrewarmEnabled,
                    skipNextStartupPrewarm = skipNextStartupTilePrewarm,
                    hillshadeEnabled = currentHillShadingEnabled,
                )
            val tileRendererLayer =
                createTileRendererLayer(
                    mapDataStore = mapDataStore,
                    theme = theme,
                    warmStartupCache = warmStartupCache,
                )
            skipNextStartupTilePrewarm = false
            currentLayer = tileRendererLayer
            mapView.mutateLayers { layers -> layers.add(0, tileRendererLayer) }
            MapHotPathDiagnostics.recordEvent(
                stage = "mapRenderer.rendererLayerAttached",
                detail =
                    "cacheId=$currentTileCacheId zoom=${mapView.model.mapViewPosition.zoomLevel} " +
                        "center=${mapView.model.mapViewPosition.center.latitude}," +
                        mapView.model.mapViewPosition.center.longitude,
            )
            currentMapPath = mapPath
            currentMapSignature = newMapSignature
            currentDemSignature = newDemSignature
            updateHillshadeLayer(
                mapFile = mapFile,
                demSignature = newDemSignature,
                requiredDemTileIds =
                    if (currentHillShadingEnabled) {
                        Dem3CoverageUtils.requiredTileIdsForMap(mapFile)
                    } else {
                        null
                    },
            )
            updateReliefOverlayLayer()

            forceRedraw()
            timingStatus = "loaded"
        } catch (e: Exception) {
            timingStatus = "error_${e.javaClass.simpleName}"
            cancelFirstVisibleMapTiming(reason = timingStatus)
            Log.e(TAG, "updateMapLayer: Error loading map file: $mapPath", e)
            clearCurrentLayer()
            currentMapPath = null
            currentMapSignature = null
            currentDemSignature = newDemSignature
            rebuildTileCacheRequested = true
            updateReliefOverlayLayer()
            purgeTileCache(reason = "map_load_error")
            forceRedraw()
        } finally {
            MapHotPathDiagnostics.end(
                marker = timingMarker,
                status = timingStatus,
                detail =
                    buildString {
                        append("mapPresent=").append(!mapPath.isNullOrBlank())
                        append(" cacheId=").append(desiredCacheIdForTiming)
                        append(" cacheRecreated=").append(cacheRecreated)
                        append(" warmStartupCache=").append(warmStartupCache)
                        append(" cleanLayerSwap=").append(cleanLayerSwap)
                    },
            )
        }
    }

    private fun consumeCleanLayerSwap(): Boolean {
        val cleanLayerSwap = cleanLayerSwapRequested
        cleanLayerSwapRequested = false
        val cleared =
            clearCurrentLayer(
                reason =
                    if (cleanLayerSwap) {
                        "clean_theme_reload"
                    } else {
                        "map_reload"
                    },
            )
        if (cleanLayerSwap && cleared) {
            purgeTileCache(reason = "theme_after_layer_clear")
            forceRedraw()
        }
        return cleanLayerSwap
    }

    fun invalidateTileCache() {
        purgeTileCache(reason = "invalidate")
        purgeHillshadeTileCache(reason = "invalidate")
        forceRedraw()
    }

    fun onExternalCachesCleared() {
        clearCurrentLayer()
        destroyHillsRenderConfig()
        runCatching { tileCache.removeObserver(tileCacheObserver) }
        runCatching { tileCache.destroy() }
            .onFailure { Log.w(TAG, "onExternalCachesCleared: tileCache.destroy() failed", it) }
        currentTileCacheId = "$CACHE_ID_PREFIX-bootstrap"
        tileCache = createTileCache(cacheId = currentTileCacheId)
        rebuildTileCacheRequested = true
        currentDemSignature = null

        val mapPath = currentMapPath
        if (mapPath.isNullOrBlank()) {
            updateReliefOverlayLayer()
            publishReliefOverlayState(force = true)
            forceRedraw()
        } else {
            updateMapLayer(mapPath)
        }
    }

    fun isReliefOverlayEnabled(): Boolean = computeReliefOverlayState().enabled

    fun isReliefOverlayProcessing(): Boolean = computeReliefOverlayState().processing

    fun reliefOverlayProgressPercent(): Int? = computeReliefOverlayState().progressPercent

    fun addReliefOverlayStateListener(listener: (ReliefOverlayState) -> Unit) {
        reliefOverlayStateListeners.add(listener)
        publishReliefOverlayState(force = true)
    }

    fun removeReliefOverlayStateListener(listener: (ReliefOverlayState) -> Unit) {
        reliefOverlayStateListeners.remove(listener)
    }

    fun sampleElevationMeters(
        lat: Double,
        lon: Double,
    ): Double? {
        if (currentMapPath.isNullOrBlank()) return null
        val sampler = reliefOverlayLayer ?: getOrCreateLiveElevationSampler()
        return sampler?.sampleElevationMeters(lat, lon)
    }

    fun destroy() {
        cancelFirstVisibleMapTiming(reason = "destroyed")
        clearCurrentLayer()
        destroyHillsRenderConfig()
        runCatching { tileCache.removeObserver(tileCacheObserver) }
        runCatching { tileCache.destroy() }
            .onFailure { Log.w(TAG, "destroy: tileCache.destroy() failed", it) }
        reliefOverlayStateListeners.clear()
        lastPublishedReliefOverlayState = null
    }

    private fun clearCurrentLayer(reason: String = "unspecified"): Boolean {
        val hadCurrentLayer = currentLayer != null
        val storeOwnedByCurrentLayer = currentLayer?.mapDataStore === currentStore

        clearHillshadeLayer(reason = reason)

        currentLayer?.let { layer ->
            disableLayerTileExpansion(layer, reason)
            mapView.mutateLayers { layers ->
                layers.remove(layer)
                runCatching { layer.onDestroy() }
                    .onFailure { Log.w(TAG, "clearCurrentLayer: Failed to destroy TileRendererLayer", it) }
            }
        }
        currentLayer = null

        reliefOverlayLayer?.let { layer ->
            mapView.mutateLayers { layers ->
                layers.remove(layer)
                runCatching { layer.onDestroy() }
            }
        }
        reliefOverlayLayer = null

        liveElevationSampler?.let { sampler ->
            runCatching { sampler.onDestroy() }
        }
        liveElevationSampler = null

        if (!storeOwnedByCurrentLayer) {
            currentStore?.let { store ->
                runCatching { store.close() }
                    .onFailure { e -> Log.w(TAG, "clearCurrentLayer: Failed to close MapDataStore", e) }
            }
        }
        currentStore = null
        publishReliefOverlayState(force = true)

        return hadCurrentLayer
    }

    private fun disableLayerTileExpansion(
        layer: TileRendererLayer,
        reason: String,
    ) {
        runCatching {
            layer.setCacheZoomPlus(0)
            layer.setCacheZoomMinus(0)
            layer.setCacheTileMargin(0)
        }.onFailure { error ->
            Log.w(TAG, "disableLayerTileExpansion: failed reason=$reason", error)
        }
    }

    private fun forceRedraw() {
        val timingMarker = MapHotPathDiagnostics.begin("mapRenderer.forceRedraw")
        var timingStatus = "redraw_layers"
        try {
            mapView.layerManager.redrawLayers()
        } catch (_: Throwable) {
            timingStatus = "post_invalidate_fallback"
            mapView.postInvalidate()
        } finally {
            MapHotPathDiagnostics.end(
                marker = timingMarker,
                status = timingStatus,
            )
        }
    }

    private fun forceReloadCurrentMapLayer(mapPath: String) {
        currentMapPath = null
        currentMapSignature = null
        currentDemSignature = null
        updateMapLayer(mapPath)
    }

    private fun armStartupTilePrewarm(layer: TileRendererLayer) {
        val config = tileCacheConfig
        if (
            !shouldWarmMapStartupTileCache(
                prewarmingEnabled = config.startupPrewarmEnabled,
                skipNextStartupPrewarm = false,
                hillshadeEnabled = currentHillShadingEnabled,
            )
        ) {
            return
        }
        if (config.startupPrewarmZoomPlus <= 0 &&
            config.startupPrewarmZoomMinus <= 0 &&
            config.startupPrewarmTileMargin <= 0
        ) {
            return
        }

        mapView.postDelayed(
            {
                if (currentLayer !== layer || currentHillShadingEnabled) return@postDelayed
                // Warm adjacent zoom levels once startup rendering has had a chance to settle.
                layer.setCacheZoomPlus(config.startupPrewarmZoomPlus)
                layer.setCacheZoomMinus(config.startupPrewarmZoomMinus)
                layer.setCacheTileMargin(config.startupPrewarmTileMargin)

                mapView.postDelayed(
                    {
                        if (currentLayer !== layer) return@postDelayed
                        layer.setCacheZoomPlus(0)
                        layer.setCacheZoomMinus(0)
                        layer.setCacheTileMargin(0)
                    },
                    config.startupPrewarmDurationMs,
                )
            },
            STARTUP_PREWARM_ARM_DELAY_MS,
        )
    }

    @Suppress("LongMethod", "ReturnCount")
    private fun buildHillsRenderConfigOrNull(
        demSignature: String?,
        requiredDemTileIds: Set<String>?,
    ): HillsRenderConfig? {
        val timingMarker = MapHotPathDiagnostics.begin("mapRenderer.buildHillsRenderConfigOrNull")
        var timingStatus = "ok"
        return try {
            if (!currentHillShadingEnabled) {
                timingStatus = "hill_shading_disabled"
                return null
            }
            if (demSignature == null) {
                timingStatus = "missing_dem"
                Log.d(
                    TAG,
                    "Hill shading enabled but no DEM files found in " +
                        hillshadeDemRootDirs.joinToString { it.absolutePath },
                )
                destroyHillsRenderConfig()
                return null
            }
            val effectiveDemRootDirs =
                resolveHillshadeDemRootDirs(hillshadeDemRootDirs, requiredDemTileIds)
            if (effectiveDemRootDirs.isEmpty()) {
                timingStatus = "missing_renderable_dem"
                Log.d(
                    TAG,
                    "Hill shading enabled but no renderable DEM files found in " +
                        hillshadeDemRootDirs.joinToString { it.absolutePath },
                )
                destroyHillsRenderConfig()
                return null
            }
            val effectiveDemSignature =
                buildString {
                    append(demSignature)
                    append("|ROOTS:")
                    append(effectiveDemRootDirs.joinToString("|") { it.absolutePath })
                    append("|TILES:")
                    append(
                        requiredDemTileIds
                            ?.asSequence()
                            ?.map { tileId -> tileId.uppercase(Locale.ROOT) }
                            ?.sorted()
                            ?.joinToString(",")
                            ?: "UNKNOWN",
                    )
                }
            hillsRenderConfig?.let { existing ->
                if (hillsRenderConfigDemSignature == effectiveDemSignature) {
                    timingStatus = "reuse_cached_config"
                    activeHillshadeDemRootDirs = effectiveDemRootDirs
                    return existing
                }
            }

            destroyHillsRenderConfig()

            val config =
                runCatching {
                    val demFolder =
                        MapsforgeHillshadeDemFolder(
                            demRootDirs = effectiveDemRootDirs,
                            requiredTileIds = requiredDemTileIds,
                        )
                    val tileSource =
                        MemoryCachingHgtReaderTileSource(
                            demFolder,
                            createWearHillShadingAlgorithm(),
                            AndroidGraphicFactory.INSTANCE,
                        )
                    HillsRenderConfig(tileSource)
                        .setMagnitudeScaleFactor(1f)
                        .setExternal(true)
                        .indexOnThread()
                }.getOrElse { e ->
                    timingStatus = "error_${e.javaClass.simpleName}"
                    Log.w(
                        TAG,
                        "Failed to initialize DEM hillshading from " +
                            hillshadeDemRootDirs.joinToString { it.absolutePath },
                        e,
                    )
                    return null
                }

            timingStatus = "built_new_config"
            hillsRenderConfig = config
            hillsRenderConfigDemSignature = effectiveDemSignature
            activeHillshadeDemRootDirs = effectiveDemRootDirs
            config
        } finally {
            MapHotPathDiagnostics.end(
                marker = timingMarker,
                status = timingStatus,
                detail = "demPresent=${demSignature != null}",
            )
        }
    }

    private fun createTileRendererLayer(
        mapDataStore: MapDataStore,
        theme: XmlRenderTheme,
        warmStartupCache: Boolean,
    ): TileRendererLayer =
        MapHotPathDiagnostics.measure(
            stage = "mapRenderer.createTileRendererLayer",
            detail = "warmStartupCache=$warmStartupCache externalHillshade=true",
        ) {
            FirstVisibleTileRendererLayer(
                tileCache,
                mapDataStore,
                mapView.model.mapViewPosition,
                AndroidGraphicFactory.INSTANCE,
                VisibleTileDiagnosticsContext(
                    mapView = mapView,
                    rendererId = System.identityHashCode(this@MapRenderer),
                    cacheId = currentTileCacheId,
                ),
                onFirstVisibleBaseTile = { layer, source ->
                    val visibleAtElapsedMs = SystemClock.elapsedRealtime()
                    mapView.post {
                        handleFirstVisibleBaseTile(
                            layer = layer,
                            source = source,
                            visibleAtElapsedMs = visibleAtElapsedMs,
                        )
                    }
                },
            ).apply {
                setXmlRenderTheme(theme)
                trySetThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                if (warmStartupCache) {
                    armStartupTilePrewarm(this)
                }
            }
        }

    @Suppress("LongMethod", "ReturnCount")
    private fun updateHillshadeLayer(
        mapFile: File,
        demSignature: String?,
        requiredDemTileIds: Set<String>?,
    ) {
        clearHillshadeLayer(reason = "replace")
        if (!currentHillShadingEnabled) return
        if (demSignature == null) {
            publishHillshadeTerrainUnavailable(
                mapFile = mapFile,
                zoomLevel = mapView.model.mapViewPosition.zoomLevel,
                missingTileCount = requiredDemTileIds?.size ?: 0,
                areaKey = "map:${mapFile.name}",
            )
            return
        }

        val hillsConfig =
            buildHillsRenderConfigOrNull(demSignature, requiredDemTileIds)
                ?: run {
                    publishHillshadeTerrainUnavailable(
                        mapFile = mapFile,
                        zoomLevel = mapView.model.mapViewPosition.zoomLevel,
                        missingTileCount = requiredDemTileIds?.size ?: 0,
                        areaKey = "map:${mapFile.name}",
                    )
                    return
                }
        val cacheId =
            resolveMapRendererHillshadeCacheId(
                baseCacheId = currentTileCacheId,
                demSourceId = DemSource.LOAD_PRIORITY.joinToString(">") { source -> source.id },
                demSignature = demSignature,
            )
        val cache = createHillshadeTileCache(cacheId)
        val hillshadeMapStore =
            runCatching {
                MapHotPathDiagnostics.measure(
                    stage = "mapRenderer.openHillshadeMapFile",
                    detail = "file=${mapFile.name}",
                ) { MapFile(mapFile) }
            }.getOrElse { error ->
                runCatching { cache.destroy() }
                Log.w(TAG, "updateHillshadeLayer: Failed opening map store", error)
                return
            }
        var cachedVisibleTerrainCoverage: VisibleHillshadeTerrainCoverage? = null
        val layer =
            runCatching {
                FirstVisibleHillshadeTileRendererLayer(
                    tileCache = cache,
                    mapDataStore = hillshadeMapStore,
                    mapViewPosition = mapView.model.mapViewPosition,
                    graphicFactory = AndroidGraphicFactory.INSTANCE,
                    hillsRenderConfig = hillsConfig,
                    callbacks =
                        HillshadeLayerCallbacks(
                            onWorkStarted = {
                                candidate,
                                zoomLevel,
                                visibleTileCount,
                                terrainCoverage,
                                ->
                                _hillshadeTerrainUnavailableEvent.value = null
                                startFirstVisibleHillshadeTiming(
                                    layer = candidate,
                                    mapFile = mapFile,
                                    zoomLevel = zoomLevel,
                                    visibleTileCount = visibleTileCount,
                                    terrainCoverage = terrainCoverage,
                                )
                            },
                            onWorkPaused = { candidate, reason ->
                                if (hillshadeLayer === candidate) {
                                    cancelFirstVisibleHillshadeTiming(reason = reason)
                                }
                            },
                            resolveVisibleTerrainCoverage = { boundingBox ->
                                val tileIds =
                                    Dem3CoverageUtils.tileIdsForBounds(
                                        minLat = boundingBox.minLatitude,
                                        minLon = boundingBox.minLongitude,
                                        maxLat = boundingBox.maxLatitude,
                                        maxLon = boundingBox.maxLongitude,
                                    )
                                cachedVisibleTerrainCoverage
                                    ?.takeIf { coverage -> coverage.requiredTileIds == tileIds }
                                    ?: resolveVisibleHillshadeTerrainCoverage(
                                        demRootDirs = activeHillshadeDemRootDirs,
                                        requiredTileIds = tileIds,
                                    ).also { coverage ->
                                        cachedVisibleTerrainCoverage = coverage
                                    }
                            },
                            onTerrainUnavailable = { candidate, zoomLevel, terrainCoverage ->
                                if (hillshadeLayer === candidate) {
                                    publishHillshadeTerrainUnavailable(
                                        mapFile = mapFile,
                                        zoomLevel = zoomLevel,
                                        missingTileCount = terrainCoverage.missingTileCount,
                                        areaKey = terrainCoverage.diagnosticKey,
                                    )
                                }
                            },
                            onFirstVisibleTile = ::handleFirstVisibleHillshadeTile,
                        ),
                ).apply {
                    setXmlRenderTheme(MapsforgeThemes.HILLSHADING)
                    setCacheZoomPlus(0)
                    setCacheZoomMinus(0)
                    setCacheTileMargin(0)
                    trySetThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                }
            }.getOrElse { error ->
                runCatching { hillshadeMapStore.close() }
                runCatching { cache.destroy() }
                Log.w(TAG, "updateHillshadeLayer: Failed creating external hillshade layer", error)
                return
            }

        hillshadeTileCache = cache
        currentHillshadeTileCacheId = cacheId
        hillshadeLayer = layer
        mapView.mutateLayers { layers ->
            val index = if (currentLayer != null && layers.size() > 0) 1 else 0
            layers.add(index, layer)
        }
    }

    private fun createHillshadeTileCache(cacheId: String): TileCache =
        AndroidUtil.createExternalStorageTileCache(
            context,
            cacheId,
            HILLSHADE_FIRST_LEVEL_TILES,
            mapView.model.displayModel.tileSize,
            true,
        )

    private fun clearHillshadeLayer(reason: String) {
        cancelFirstVisibleHillshadeTiming(reason)
        _hillshadeTerrainUnavailableEvent.value = null
        hillshadeLayer?.let { layer ->
            disableLayerTileExpansion(layer, reason)
            mapView.mutateLayers { layers ->
                layers.remove(layer)
                runCatching { layer.onDestroy() }
                    .onFailure { Log.w(TAG, "clearHillshadeLayer: Failed to destroy layer", it) }
            }
        }
        hillshadeLayer = null
        hillshadeTileCache?.let { cache ->
            runCatching { cache.destroy() }
                .onFailure { Log.w(TAG, "clearHillshadeLayer: Failed to destroy tile cache", it) }
        }
        hillshadeTileCache = null
        currentHillshadeTileCacheId = null
    }

    private fun startFirstVisibleMapTiming(
        mapPath: String,
        cacheId: String,
    ) {
        cancelFirstVisibleMapTiming(reason = "superseded")
        val requestId = ++nextFirstVisibleMapRequestId
        val startedAtElapsedMs = SystemClock.elapsedRealtime()
        pendingFirstVisibleMapTiming =
            PendingFirstVisibleMapTiming(
                requestId = requestId,
                mapName = File(mapPath).name,
                cacheId = cacheId,
                startedAtElapsedMs = startedAtElapsedMs,
                traceMarker = BenchmarkTrace.beginAsync(FIRST_VISIBLE_MAP_TRACE_STAGE),
            )
        mapView.postDelayed(
            {
                if (pendingFirstVisibleMapTiming?.requestId == requestId) {
                    cancelFirstVisibleMapTiming(reason = "timeout")
                }
            },
            FIRST_VISIBLE_MAP_TIMING_TIMEOUT_MS,
        )
    }

    private fun prepareFirstVisibleMapTiming(
        mapPath: String?,
        cacheId: String,
    ) {
        if (mapPath.isNullOrBlank()) {
            cancelFirstVisibleMapTiming(reason = "map_disabled")
        } else {
            startFirstVisibleMapTiming(
                mapPath = mapPath,
                cacheId = cacheId,
            )
        }
    }

    private fun handleFirstVisibleBaseTile(
        layer: FirstVisibleTileRendererLayer,
        source: FirstVisibleBaseTileSource,
        visibleAtElapsedMs: Long,
    ) {
        if (currentLayer !== layer) return
        val timing = pendingFirstVisibleMapTiming ?: return
        pendingFirstVisibleMapTiming = null
        BenchmarkTrace.endAsync(timing.traceMarker)
        val version = firstVisibleMapCounter.incrementAndGet()
        firstVisibleMapEvent.value =
            FirstVisibleMapEvent(
                version = version,
                source = source,
            )
        MapHotPathDiagnostics.recordInterval(
            stage = "$FIRST_VISIBLE_MAP_DIAGNOSTIC_STAGE.${source.telemetryToken}",
            startedAtElapsedMs = timing.startedAtElapsedMs,
            completedAtElapsedMs = visibleAtElapsedMs,
            status = source.telemetryToken,
            detail = "map=${timing.mapName} cacheId=${timing.cacheId}",
        )
    }

    private fun cancelFirstVisibleMapTiming(reason: String) {
        val timing = pendingFirstVisibleMapTiming ?: return
        pendingFirstVisibleMapTiming = null
        BenchmarkTrace.endAsync(timing.traceMarker)
        MapHotPathDiagnostics.recordInterval(
            stage = "$FIRST_VISIBLE_MAP_DIAGNOSTIC_STAGE.cancelled",
            startedAtElapsedMs = timing.startedAtElapsedMs,
            completedAtElapsedMs = SystemClock.elapsedRealtime(),
            status = reason,
            detail = "map=${timing.mapName} cacheId=${timing.cacheId}",
        )
    }

    private fun startFirstVisibleHillshadeTiming(
        layer: FirstVisibleHillshadeTileRendererLayer,
        mapFile: File,
        zoomLevel: Byte,
        visibleTileCount: Int,
        terrainCoverage: VisibleHillshadeTerrainCoverage,
    ) {
        if (hillshadeLayer !== layer || pendingFirstVisibleHillshadeTiming != null) return
        cancelFirstVisibleHillshadeTiming(reason = "superseded")
        val requestId = ++nextFirstVisibleHillshadeRequestId
        val startedAtElapsedMs = SystemClock.elapsedRealtime()
        pendingFirstVisibleHillshadeTiming =
            PendingFirstVisibleHillshadeTiming(
                requestId = requestId,
                mapName = mapFile.name,
                demSources = DemSource.LOAD_PRIORITY.joinToString(">") { source -> source.id },
                zoomLevel = zoomLevel.toInt(),
                visibleTileCount = visibleTileCount,
                detailedDemTileCount = terrainCoverage.detailedTileCount,
                standardFallbackDemTileCount = terrainCoverage.standardFallbackTileCount,
                missingDemTileCount = terrainCoverage.missingTileCount,
                startedAtElapsedMs = startedAtElapsedMs,
                traceMarker = BenchmarkTrace.beginAsync(FIRST_VISIBLE_HILLSHADE_TRACE_STAGE),
            )
        mapView.postDelayed(
            {
                val timing = pendingFirstVisibleHillshadeTiming
                if (timing?.requestId != requestId || hillshadeLayer !== layer) return@postDelayed
                pendingFirstVisibleHillshadeTiming = null
                BenchmarkTrace.endAsync(timing.traceMarker)
                MapHotPathDiagnostics.recordInterval(
                    stage = "$FIRST_VISIBLE_HILLSHADE_DIAGNOSTIC_STAGE.cancelled",
                    startedAtElapsedMs = timing.startedAtElapsedMs,
                    completedAtElapsedMs = SystemClock.elapsedRealtime(),
                    status = "timeout",
                    detail = timing.hillshadeDiagnosticDetail(),
                )
                clearHillshadeLayer(reason = "first_tile_timeout")
                destroyHillsRenderConfig()
                forceRedraw()
            },
            FIRST_VISIBLE_HILLSHADE_TIMING_TIMEOUT_MS,
        )
    }

    private fun handleFirstVisibleHillshadeTile(layer: FirstVisibleHillshadeTileRendererLayer) {
        if (hillshadeLayer !== layer) return
        val timing = pendingFirstVisibleHillshadeTiming ?: return
        pendingFirstVisibleHillshadeTiming = null
        BenchmarkTrace.endAsync(timing.traceMarker)
        MapHotPathDiagnostics.recordInterval(
            stage = "$FIRST_VISIBLE_HILLSHADE_DIAGNOSTIC_STAGE.ready",
            startedAtElapsedMs = timing.startedAtElapsedMs,
            completedAtElapsedMs = SystemClock.elapsedRealtime(),
            status = "ready",
            detail = timing.hillshadeDiagnosticDetail(),
        )
    }

    private fun cancelFirstVisibleHillshadeTiming(reason: String) {
        val timing = pendingFirstVisibleHillshadeTiming ?: return
        pendingFirstVisibleHillshadeTiming = null
        BenchmarkTrace.endAsync(timing.traceMarker)
        MapHotPathDiagnostics.recordInterval(
            stage = "$FIRST_VISIBLE_HILLSHADE_DIAGNOSTIC_STAGE.cancelled",
            startedAtElapsedMs = timing.startedAtElapsedMs,
            completedAtElapsedMs = SystemClock.elapsedRealtime(),
            status = reason,
            detail = timing.hillshadeDiagnosticDetail(),
        )
    }

    private fun PendingFirstVisibleHillshadeTiming.hillshadeDiagnosticDetail(): String =
        "map=$mapName demSources=$demSources zoom=$zoomLevel visibleTiles=$visibleTileCount " +
            "detailedDemTiles=$detailedDemTileCount standardFallbackDemTiles=$standardFallbackDemTileCount " +
            "missingDemTiles=$missingDemTileCount"

    private fun publishHillshadeTerrainUnavailable(
        mapFile: File,
        zoomLevel: Byte,
        missingTileCount: Int,
        areaKey: String,
    ) {
        Log.w(
            TAG,
            "No Detailed or Standard hillshade terrain available for visible area " +
                "map=${mapFile.name} zoom=$zoomLevel missingTiles=$missingTileCount",
        )
        _hillshadeTerrainUnavailableEvent.value =
            HillshadeTerrainUnavailableEvent(
                mapName = mapFile.name,
                zoomLevel = zoomLevel.toInt(),
                missingTileCount = missingTileCount,
                areaKey = areaKey,
            )
    }

    private fun destroyHillsRenderConfig() {
        hillsRenderConfig?.interruptAndDestroy()
        hillsRenderConfig = null
        hillsRenderConfigDemSignature = null
        activeHillshadeDemRootDirs = emptyList()
    }

    private fun recreateTileCache(newCacheId: String) {
        val timingMarker = MapHotPathDiagnostics.begin("mapRenderer.recreateTileCache")
        try {
            runCatching { tileCache.removeObserver(tileCacheObserver) }
            purgeTileCache(reason = "recreate_before_destroy")
            runCatching { tileCache.destroy() }
                .onFailure { Log.w(TAG, "recreateTileCache: failed to destroy previous cache", it) }
            currentTileCacheId = newCacheId
            tileCache = createTileCache(cacheId = currentTileCacheId)
            maybeCleanupPersistentCachesAsync()
        } finally {
            MapHotPathDiagnostics.end(
                marker = timingMarker,
                detail = "cacheId=$newCacheId",
            )
        }
    }

    private fun purgeTileCache(reason: String) {
        MapHotPathDiagnostics.measure(
            stage = "mapRenderer.purgeTileCache",
            detail = "reason=$reason cacheId=$currentTileCacheId",
        ) {
            tileCache.tryPurge()
        }
    }

    private fun purgeHillshadeTileCache(reason: String) {
        val cache = hillshadeTileCache ?: return
        MapHotPathDiagnostics.measure(
            stage = "mapRenderer.purgeHillshadeTileCache",
            detail = "reason=$reason cacheId=$currentHillshadeTileCacheId",
        ) {
            cache.tryPurge()
        }
    }

    private fun maybeCleanupPersistentCachesAsync() {
        val cacheRoot = context.externalCacheDir ?: return
        if (!cacheRoot.exists() || !cacheRoot.isDirectory) return

        val now = System.currentTimeMillis()
        val lastCleanupMs = cacheMaintenancePrefs.getLong(KEY_CACHE_LAST_CLEANUP_MS, 0L)
        if ((now - lastCleanupMs) < CACHE_CLEANUP_INTERVAL_MS) return
        if (cacheCleanupInProgress) return

        cacheCleanupInProgress = true
        val keepCacheIds =
            buildSet {
                add(currentTileCacheId)
                currentHillshadeTileCacheId?.let(::add)
            }
        Thread(
            {
                try {
                    cleanupMapRendererPersistentCacheBuckets(
                        cacheRoot = cacheRoot,
                        nowMs = now,
                        keepIds = keepCacheIds,
                    )
                    cacheMaintenancePrefs
                        .edit()
                        .putLong(KEY_CACHE_LAST_CLEANUP_MS, now)
                        .apply()
                } catch (e: Exception) {
                    Log.w(TAG, "Cache cleanup failed", e)
                } finally {
                    cacheCleanupInProgress = false
                }
            },
            "MapCacheCleanup",
        ).start()
    }

    private fun computeDemSignatureOrNull(): String? {
        if (!currentHillShadingEnabled && !currentReliefOverlayEnabled) return null
        return DemSignatureStore.resolveSignature(
            context = context,
            demRootDirs = demRootDirs,
            maxDepth = DEM_SCAN_MAX_DEPTH,
        )
    }

    private fun updateReliefOverlayLayer() {
        if (!currentReliefOverlayEnabled || currentMapPath.isNullOrBlank()) {
            clearReliefOverlayLayer()
            publishReliefOverlayState(force = true)
            return
        }

        if (reliefOverlayLayer == null) {
            val cacheNamespace = resolveMapRendererReliefOverlayCacheNamespace(currentDemSignature)
            reliefOverlayLayer =
                ReliefOverlayLayer(
                    demRootDir = demRootDir,
                    demRootDirs = demRootDirs,
                    diskCacheRootDir = reliefOverlayCacheRootDir,
                    cacheNamespace = cacheNamespace,
                    onProcessingStateChanged = { publishReliefOverlayState() },
                ).also { layer ->
                    // Keep above rendered map but below interactive overlays.
                    mapView.mutateLayers { layers ->
                        val index =
                            when {
                                currentLayer == null || layers.size() == 0 -> 0
                                hillshadeLayer != null && layers.size() > 1 -> 2
                                else -> 1
                            }
                        layers.add(index, layer)
                    }
                }
        }
        publishReliefOverlayState(force = true)
    }

    private fun clearReliefOverlayLayer() {
        reliefOverlayLayer?.let { existing ->
            mapView.mutateLayers { layers ->
                layers.remove(existing)
                runCatching { existing.onDestroy() }
            }
        }
        reliefOverlayLayer = null
    }

    private fun getOrCreateLiveElevationSampler(): ReliefOverlayLayer? {
        if (liveElevationSampler == null) {
            liveElevationSampler = ReliefOverlayLayer(demRootDir = demRootDir, demRootDirs = demRootDirs)
        }
        return liveElevationSampler
    }

    private fun TileRendererLayer.trySetThreadPriority(priority: Int) {
        runCatching {
            val m = this.javaClass.getMethod("setThreadPriority", Int::class.javaPrimitiveType)
            m.invoke(this, priority)
        }.onFailure {
            Log.d(TAG, "TileRendererLayer.setThreadPriority not available, skipping")
        }
    }

    private fun TileCache.tryPurge() {
        // Some Mapsforge versions have purge(), others don’t. Reflection keeps you safe.
        runCatching {
            val m = this.javaClass.getMethod("purge")
            m.invoke(this)
        }.onFailure {
            // if purge doesn't exist, it's fine (still redraw, but may show cached tiles briefly)
            Log.d(TAG, "TileCache.purge not available, skipping")
        }
    }

    private fun computeReliefOverlayState(): ReliefOverlayState {
        val enabled = currentReliefOverlayEnabled && !currentMapPath.isNullOrBlank()
        if (!enabled) {
            return ReliefOverlayState(
                enabled = false,
                processing = false,
                progressPercent = null,
            )
        }
        return ReliefOverlayState(
            enabled = true,
            processing = reliefOverlayLayer?.isProcessing() == true,
            progressPercent = reliefOverlayLayer?.progressPercent(),
        )
    }

    private fun publishReliefOverlayState(force: Boolean = false) {
        val listeners = reliefOverlayStateListeners.toList()
        if (listeners.isEmpty()) return

        val state = computeReliefOverlayState()
        if (!force && state == lastPublishedReliefOverlayState) return
        lastPublishedReliefOverlayState = state

        mapView.post {
            listeners.forEach { listener ->
                runCatching { listener(state) }
            }
        }
    }
}
