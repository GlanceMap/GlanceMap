package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.glancemap.glancemapwearos.core.maps.Dem3CoverageUtils
import com.glancemap.glancemapwearos.core.maps.theme.defaultRenderThemeMenuCallback
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.presentation.features.maps.MapHolder
import com.glancemap.glancemapwearos.presentation.features.maps.buildMapRendererThemeOrNull
import com.glancemap.glancemapwearos.presentation.features.maps.resolveDemFile
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme
import java.util.UUID

internal class WatchWorldWindSession {
    var view by mutableStateOf<WatchWorldWindWindow?>(null)
    var center by mutableStateOf<LatLong?>(null)
    var initializing by mutableStateOf(false)
    var rendering by mutableStateOf(false)
    var renderError by mutableStateOf(false)
    var renderProgress by mutableStateOf<Int?>(null)
    var failed by mutableStateOf(false)
    var onUserPanStarted: () -> Unit = {}
    var onScreenInteractiveChanged: (Boolean) -> Unit = {}
}

@Suppress("LongParameterList")
@Composable
internal fun rememberWatchWorldWindSession(
    session: WatchWorldWindSession,
    enabled: Boolean,
    hikingThemeEnabled: Boolean,
    holder: MapHolder?,
    mapPath: String?,
    appearanceInProgress: Boolean,
    interactive: Boolean,
    angle: Int,
    bearing: Float,
): WatchWorldWindSession {
    val context = LocalContext.current
    val currentInteractive = rememberUpdatedState(interactive)
    val currentBearing = rememberUpdatedState(bearing)
    LaunchedEffect(enabled, holder, mapPath, appearanceInProgress, hikingThemeEnabled) {
        if (!enabled || appearanceInProgress) return@LaunchedEffect
        if (holder == null || mapPath.isNullOrEmpty()) {
            return@LaunchedEffect
        }
        runWorldWindWithStandby(
            snapshotFlow { currentInteractive.value },
            WORLDWIND_STANDBY_MILLIS,
            onInteractiveChanged = { awake ->
                session.view?.let { view ->
                    if (view.setScreenInteractive(awake)) session.onScreenInteractiveChanged(awake)
                }
            },
        ) {
            runWatchWorldWindSession(
                session,
                holder,
                mapPath,
                context,
                angle,
                currentBearing.value,
                currentInteractive,
                hikingThemeEnabled,
            )
        }
    }
    return session
}

@Suppress("TooGenericExceptionCaught", "LongParameterList", "LongMethod")
private suspend fun runWatchWorldWindSession(
    session: WatchWorldWindSession,
    holder: MapHolder,
    mapPath: String,
    context: Context,
    angle: Int,
    bearing: Float,
    interactive: State<Boolean>,
    hikingThemeEnabled: Boolean,
) {
    val sessionId = UUID.randomUUID().toString()
    val started = SystemClock.elapsedRealtime()
    val renderTiming = WorldWindRenderTiming(started)
    var imagery: WorldWindMapsforge? = null
    var elevation: WorldWindElevation? = null
    var window: WatchWorldWindWindow? = null
    var progressJob: Job? = null
    var demContext = "dem_actual=unavailable axis=unavailable"
    var startGround: Double? = null
    var prewarmContext = "dem_prewarm_tiles=0 dem_prewarm_ms=0"
    session.failed = false
    session.initializing = true
    session.rendering = true
    session.renderError = false
    session.renderProgress = null
    val startMessage = "event=session_start renderer=worldwind session_id=$sessionId"
    Log.i(TERRAIN_LOG_TAG, startMessage)
    DebugTelemetry.log(TERRAIN_LOG_TAG, startMessage)
    try {
        var source = holder.renderer.terrainSource()
        repeat(40) {
            if (source?.mapFile?.absolutePath == mapPath) return@repeat
            delay(250)
            source = holder.renderer.terrainSource()
        }
        val currentSource =
            checkNotNull(source?.takeIf { it.mapFile.absolutePath == mapPath }) { "Selected map is not ready" }
        var center = holder.mapView.model.mapViewPosition.center
        withContext(Dispatchers.IO) {
            val theme =
                if (hikingThemeEnabled) {
                    AssetsRenderTheme(
                        context.assets,
                        "theme/hiking-3d/",
                        "hiking-3d.xml",
                        defaultRenderThemeMenuCallback(),
                    )
                } else {
                    checkNotNull(
                        buildMapRendererThemeOrNull(
                            context,
                            currentSource.themeFile,
                            currentSource.mapsforgeThemeName,
                            currentSource.bundledThemeId,
                            currentSource.externalTheme,
                        ),
                    ) { "Selected theme unavailable" }
                }
            val tileDiskCache = WorldWindTileDiskCache.shared(context).also { it.trim() }
            imagery =
                WorldWindMapsforge(
                    currentSource,
                    theme,
                    tileDiskCache,
                    themeVariant = worldWindThemeVariant(hikingThemeEnabled),
                )
            val mapBounds = checkNotNull(imagery).bounds
            center =
                awaitWorldWindStartCenter(mapBounds) {
                    withContext(Dispatchers.Main) { holder.mapView.model.mapViewPosition.center }
                }
            check(mapBounds.contains(center.latitude, center.longitude)) {
                "Current location is outside the selected map"
            }
            elevation = WorldWindElevation(context.applicationContext, currentSource, mapBounds)
            val sample =
                checkNotNull(elevation.sampleAt(center.latitude, center.longitude)) {
                    "No local terrain at map center"
                }
            val actual =
                currentSource.demSource.readFallbackOrder().firstOrNull { dem ->
                    resolveDemFile(listOf(Dem3CoverageUtils.demRootDir(context, dem)), sample.tileId) != null
                }
            demContext = "dem_actual=${actual?.id ?: "unavailable"} axis=${sample.axisLen}"
            startGround = sample.elevationMeters
            val prewarmStarted = SystemClock.elapsedRealtime()
            val prewarmed = elevation.prewarm(center.latitude, center.longitude)
            prewarmContext =
                "dem_prewarm_tiles=$prewarmed dem_prewarm_ms=${SystemClock.elapsedRealtime() - prewarmStarted}"
        }
        snapshotFlow { interactive.value }.first { it }
        val currentWindow = WatchWorldWindWindow(context, { session.center = it }, { session.onUserPanStarted() })
        window = currentWindow
        currentWindow.engine.layers.clearLayers()
        currentWindow.engine.layers.addLayer(currentWindow.globeBackground)
        currentWindow.engine.globe.elevationModel
            .clearCoverages()
        if (currentSource.hillShadingEnabled) {
            currentWindow.engine.lightDirectionProvider = { rc ->
                worldWindReliefDirection(
                    rc.camera.position.latitude.inRadians,
                    rc.camera.position.longitude.inRadians,
                    rc.lightDirection,
                )
            }
            currentWindow.engine.layers.addLayer(WorldWindReliefLayer())
        }
        currentWindow.engine.layers.addLayer(checkNotNull(imagery).layer)
        currentWindow.engine.layers.addLayer(
            object : AtmosphereLayer() {
                override fun renderGround(rc: RenderContext) = Unit
            },
        )
        currentWindow.engine.globe.elevationModel
            .addCoverage(checkNotNull(elevation).coverage)
        currentWindow.initialize(center, angle, bearing, startGround)
        session.center = center
        val imageryProvider = checkNotNull(imagery)
        val elevationProvider = checkNotNull(elevation)
        session.onScreenInteractiveChanged = { awake ->
            if (!awake) imageryProvider.visibleTiles.set(null)
            DebugTelemetry.log(
                TERRAIN_LOG_TAG,
                "event=standby renderer=worldwind session_id=$sessionId active=${!awake}",
            )
        }
        session.view = currentWindow
        val elapsed = SystemClock.elapsedRealtime() - started
        val viewMessage =
            "event=view_created renderer=worldwind session_id=$sessionId elapsed_ms=$elapsed $prewarmContext"
        Log.i(TERRAIN_LOG_TAG, viewMessage)
        DebugTelemetry.log(TERRAIN_LOG_TAG, viewMessage)
        progressJob =
            CoroutineScope(currentCoroutineContext()).launch {
                snapshotFlow { interactive.value }.collectLatest { awake ->
                    if (awake) {
                        observeWorldWindRenderProgress(
                            session,
                            imageryProvider,
                            elevationProvider,
                            sessionId,
                            renderTiming,
                        )
                    }
                }
            }
        waitForWorldWindReady(imageryProvider, currentWindow, interactive)
        session.initializing = false
        val surfaceReadyMs =
            currentWindow.surfaceReadyAt
                .get()
                .takeIf { it > 0 }
                ?.minus(started)
        val surfaceElapsed = surfaceReadyMs?.toString() ?: "unavailable"
        DebugTelemetry.log(
            TERRAIN_LOG_TAG,
            "event=surface_ready renderer=worldwind session_id=$sessionId elapsed_ms=$surfaceElapsed",
        )
        val readyMs = SystemClock.elapsedRealtime() - started
        DebugTelemetry.log(
            TERRAIN_LOG_TAG,
            "event=imagery_ready renderer=worldwind session_id=$sessionId " +
                "elapsed_ms=$readyMs first_useful_frame=unavailable",
        )
        val contextDetails =
            "map=${currentSource.mapFile.name} theme=${currentSource.bundledThemeId} " +
                "dem_selected=${currentSource.demSource.id} $demContext angle=$angle " +
                "hill=${currentSource.hillShadingEnabled}"
        var previousDraws = currentWindow.draws.get()
        var previousIdleDraws = currentWindow.idleDraws.get()
        var previousRedraws = currentWindow.redrawRequests.get()
        while (true) {
            delay(60_000)
            val drawsNow = currentWindow.draws.get()
            val idleDrawsNow = currentWindow.idleDraws.get()
            val redrawsNow = currentWindow.redrawRequests.get()
            val runtime = Runtime.getRuntime()
            DebugTelemetry.log(
                TERRAIN_LOG_TAG,
                "event=interval renderer=worldwind session_id=$sessionId $contextDetails gpx=${currentWindow.hasGpx} " +
                    "draws=${drawsNow - previousDraws} " +
                    "idle_draws=${idleDrawsNow - previousIdleDraws} redraws=${redrawsNow - previousRedraws} " +
                    "requests=${imageryProvider.tileRequests.get()} renders=${imageryProvider.tileRenders.get()} " +
                    "images=${imageryProvider.tileImagesAvailable.get()} " +
                    "render_ms=${imageryProvider.tileRenderMs.get()} " +
                    "heap_used=${runtime.totalMemory() - runtime.freeMemory()} " +
                    "heap_max=${runtime.maxMemory()} " +
                    "resource_cache_capacity=${worldWindCacheBudget(runtime.maxMemory())} " +
                    "resolution=256 coalesced=${imageryProvider.tileCoalesced.get()} " +
                    "cancelled=${imageryProvider.tileCancelled.get()} skipped=${imageryProvider.tileSkipped.get()} " +
                    "disk_hits=${imageryProvider.tileDiskHits.get()} " +
                    "disk_writes=${imageryProvider.tileDiskWrites.get()} " +
                    "disk_write_bytes=${imageryProvider.tileDiskWriteBytes.get()} " +
                    "failed=${imageryProvider.tileFailures.get()} " +
                    "outstanding=${imageryProvider.outstandingRequests()} " +
                    "dem_grids=${elevationProvider.gridCount.get()} " +
                    "dem_samples=${elevationProvider.sampleCount.get()} " +
                    "dem_voids=${elevationProvider.voidCount.get()} dem_ms=${elevationProvider.sampleMs.get()} " +
                    "dem_skipped_grids=${elevationProvider.skippedGridCount.get()} " +
                    "dem_failed_grids=${elevationProvider.failedGridCount.get()} " +
                    "dem_grid_cache_hits=${elevationProvider.gridCache.hits.get()} " +
                    "dem_grid_cache_misses=${elevationProvider.gridCache.misses.get()} " +
                    "dem_grid_cache_retained_bytes=${elevationProvider.gridCache.retainedBytes.get()} " +
                    "dem_cache_hits=${elevationProvider.cacheStats.hits.get()} " +
                    "dem_cache_misses=${elevationProvider.cacheStats.misses.get()} " +
                    "dem_cache_retained_bytes=${elevationProvider.cacheStats.retainedBytes.get()} " +
                    "dem_cache_peak_retained_bytes=${elevationProvider.cacheStats.peakRetainedBytes.get()} " +
                    "dem_hgt_loads=${elevationProvider.cacheStats.loads.get()} " +
                    "dem_hgt_decoded_bytes=${elevationProvider.cacheStats.decodedBytes.get()} " +
                    "dem_hgt_load_ms=${elevationProvider.cacheStats.loadNanos.get() / 1_000_000} " +
                    "${elevationProvider.diskLogFields()} " +
                    "pan=${currentWindow.panCount.get()} zoom=${currentWindow.zoomCount.get()} " +
                    "orbit=${currentWindow.orbitCount.get()} recenter=${currentWindow.recenterCount.get()} " +
                    "${currentWindow.holdMetrics.logFields()} " +
                    "${renderTiming.logFields()} " +
                    "track_rebuilds=${currentWindow.trackRebuildCount.get()} " +
                    "marker_updates=${currentWindow.markerUpdateCount.get()} " +
                    "internal_gpu_bytes=unavailable",
            )
            previousDraws = drawsNow
            previousIdleDraws = idleDrawsNow
            previousRedraws = redrawsNow
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        session.failed = true
        val reason = error.javaClass.simpleName
        val failure = "event=fallback_to_2d renderer=worldwind session_id=$sessionId reason=$reason"
        Log.e(TERRAIN_LOG_TAG, failure, error)
        DebugTelemetry.log(TERRAIN_LOG_TAG, failure)
    } catch (error: OutOfMemoryError) {
        session.failed = true
        val failure = "event=fallback_to_2d renderer=worldwind session_id=$sessionId reason=OutOfMemoryError"
        Log.e(TERRAIN_LOG_TAG, failure, error)
        DebugTelemetry.log(TERRAIN_LOG_TAG, failure)
    } finally {
        progressJob?.cancel()
        session.initializing = false
        session.rendering = false
        session.renderError = false
        session.renderProgress = null
        session.onScreenInteractiveChanged = {}
        session.view = null
        window?.let { view ->
            view.setScreenInteractive(false)
            (view.parent as? ViewGroup)?.removeView(view)
        }
        finishWorldWindResources(
            close = {
                elevation?.close()
                imagery?.close()
            },
            onFinished = {
                val endMessage =
                    worldWindEndMessage(sessionId, started, demContext, window, imagery, elevation) +
                        " ${renderTiming.logFields()}"
                Log.i(TERRAIN_LOG_TAG, endMessage)
                DebugTelemetry.log(TERRAIN_LOG_TAG, endMessage)
            },
        )
    }
}

private suspend fun observeWorldWindRenderProgress(
    session: WatchWorldWindSession,
    imagery: WorldWindMapsforge,
    elevation: WorldWindElevation,
    sessionId: String,
    timing: WorldWindRenderTiming,
) {
    val progress = WorldWindRenderProgress()
    var lastProgressLogAt: Long? = null
    try {
        while (currentCoroutineContext().isActive) {
            val now = SystemClock.elapsedRealtime()
            val visible = imagery.visibleTiles.get()
            val pendingElevation = elevation.pendingGrids.get()
            val pendingImagery = imagery.outstandingRequests()
            val percent = progress.update(visible, pendingElevation, pendingImagery, now)
            val renderError = (visible?.failed ?: 0) > 0
            val rendering = !renderError && percent != 100
            val busyMs = timing.update(complete = percent == 100, nowMs = now)
            val lastLog = lastProgressLogAt
            val activeReportDue = rendering && (lastLog == null || now - lastLog >= 5_000)
            if (session.rendering != rendering || session.renderError != renderError || activeReportDue) {
                DebugTelemetry.log(
                    TERRAIN_LOG_TAG,
                    "event=render_activity renderer=worldwind session_id=$sessionId active=$rendering " +
                        worldWindRenderActivityFields(visible, pendingImagery, pendingElevation, percent, busyMs),
                )
                lastProgressLogAt = now
            }
            session.renderProgress = percent
            session.renderError = renderError
            session.rendering = rendering
            delay(250)
        }
    } finally {
        timing.pause()
    }
}

private fun worldWindRenderActivityFields(
    visible: WorldWindVisibleTiles?,
    pendingImagery: Int,
    pendingElevation: Int,
    percent: Int?,
    busyMs: Long?,
): String =
    "visible_ready=${visible?.ready ?: 0} " +
        "visible_total=${visible?.total ?: 0} visible_failed=${visible?.failed ?: 0} " +
        "imagery_pending=$pendingImagery elevation_pending=$pendingElevation " +
        "progress_percent=${percent ?: "unavailable"}" +
        (busyMs?.let { " busy_ms=$it" } ?: "")

/**
 * Returns the 2D map center once it lies inside the selected map. On a cold start the map view is
 * briefly at 0,0 before its position is restored, which would otherwise fail 3D startup. After
 * [attempts] the last center is returned and the caller's map-coverage check reports the failure.
 */
internal suspend fun awaitWorldWindStartCenter(
    bounds: BoundingBox,
    attempts: Int = 40,
    intervalMs: Long = 250,
    readCenter: suspend () -> LatLong,
): LatLong {
    var center = readCenter()
    repeat(attempts) {
        if (bounds.contains(center.latitude, center.longitude)) return center
        delay(intervalMs)
        center = readCenter()
    }
    return center
}

/** Names the theme choice in saved 3D image keys; [TerrainSource] does not describe the hiking theme. */
internal fun worldWindThemeVariant(hikingThemeEnabled: Boolean): String =
    when (hikingThemeEnabled) {
        true -> "hiking-3d"
        false -> "selected"
    }

internal suspend fun finishWorldWindResources(
    close: suspend () -> Unit,
    onFinished: () -> Unit,
) {
    try {
        withContext(NonCancellable + Dispatchers.IO) { close() }
    } finally {
        onFinished()
    }
}

@Suppress("CyclomaticComplexMethod", "LongParameterList")
private fun worldWindEndMessage(
    sessionId: String,
    started: Long,
    demContext: String,
    window: WatchWorldWindWindow?,
    imagery: WorldWindMapsforge?,
    elevation: WorldWindElevation?,
): String {
    val draws = window?.draws?.get() ?: "unavailable"
    val runtime = Runtime.getRuntime()
    return "event=session_end renderer=worldwind session_id=$sessionId " +
        "elapsed_ms=${SystemClock.elapsedRealtime() - started} $demContext draws=$draws " +
        "idle_draws=${window?.idleDraws?.get() ?: 0} redraws=${window?.redrawRequests?.get() ?: 0} " +
        "pan=${window?.panCount?.get() ?: 0} zoom=${window?.zoomCount?.get() ?: 0} " +
        "orbit=${window?.orbitCount?.get() ?: 0} recenter=${window?.recenterCount?.get() ?: 0} " +
        "${window?.holdMetrics?.logFields() ?: "hold_metrics=unavailable"} " +
        "track_rebuilds=${window?.trackRebuildCount?.get() ?: 0} " +
        "marker_updates=${window?.markerUpdateCount?.get() ?: 0} " +
        "requests=${imagery?.tileRequests?.get() ?: 0} renders=${imagery?.tileRenders?.get() ?: 0} " +
        "images=${imagery?.tileImagesAvailable?.get() ?: 0} failed=${imagery?.tileFailures?.get() ?: 0} " +
        "coalesced=${imagery?.tileCoalesced?.get() ?: 0} cancelled=${imagery?.tileCancelled?.get() ?: 0} " +
        "skipped=${imagery?.tileSkipped?.get() ?: 0} disk_hits=${imagery?.tileDiskHits?.get() ?: 0} " +
        "disk_writes=${imagery?.tileDiskWrites?.get() ?: 0} " +
        "disk_write_bytes=${imagery?.tileDiskWriteBytes?.get() ?: 0} " +
        "render_ms=${imagery?.tileRenderMs?.get() ?: 0} dem_grids=${elevation?.gridCount?.get() ?: 0} " +
        "dem_samples=${elevation?.sampleCount?.get() ?: 0} dem_voids=${elevation?.voidCount?.get() ?: 0} " +
        "dem_ms=${elevation?.sampleMs?.get() ?: 0} " +
        "dem_skipped_grids=${elevation?.skippedGridCount?.get() ?: 0} " +
        "dem_failed_grids=${elevation?.failedGridCount?.get() ?: 0} " +
        "dem_grid_cache_hits=${elevation?.gridCache?.hits?.get() ?: 0} " +
        "dem_grid_cache_misses=${elevation?.gridCache?.misses?.get() ?: 0} " +
        "dem_grid_cache_retained_before_close_bytes=${elevation?.gridCacheRetainedBeforeClose?.get() ?: 0} " +
        "dem_cache_hits=${elevation?.cacheStats?.hits?.get() ?: 0} " +
        "dem_cache_misses=${elevation?.cacheStats?.misses?.get() ?: 0} " +
        "dem_cache_retained_before_close_bytes=${elevation?.cacheRetainedBeforeClose?.get() ?: "unavailable"} " +
        "dem_cache_peak_retained_bytes=${elevation?.cacheStats?.peakRetainedBytes?.get() ?: "unavailable"} " +
        "dem_hgt_loads=${elevation?.cacheStats?.loads?.get() ?: 0} " +
        "dem_hgt_decoded_bytes=${elevation?.cacheStats?.decodedBytes?.get() ?: 0} " +
        "dem_hgt_load_ms=${(elevation?.cacheStats?.loadNanos?.get() ?: 0) / 1_000_000} " +
        "${elevation?.diskLogFields() ?: "dem_disk=unavailable"} " +
        "heap_used=${runtime.totalMemory() - runtime.freeMemory()} heap_max=${runtime.maxMemory()} " +
        "internal_gpu_bytes=unavailable first_useful_frame=unavailable"
}

private suspend fun waitForWorldWindReady(
    imagery: WorldWindMapsforge,
    window: WatchWorldWindWindow,
    interactive: State<Boolean>,
) {
    var waitedSeconds = 0
    while ((imagery.tileImagesAvailable.get() == 0L || window.draws.get() == 0L) && waitedSeconds < 30) {
        snapshotFlow { interactive.value }.first { it }
        delay(1_000)
        if (interactive.value) waitedSeconds++
    }
    check(imagery.tileImagesAvailable.get() > 0L && window.draws.get() > 0L) {
        "WorldWind imagery or surface did not become ready"
    }
}
