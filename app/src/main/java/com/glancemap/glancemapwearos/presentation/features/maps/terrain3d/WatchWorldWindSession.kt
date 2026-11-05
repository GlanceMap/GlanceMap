package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.glancemap.glancemapwearos.core.maps.Dem3CoverageUtils
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.presentation.features.maps.MapHolder
import com.glancemap.glancemapwearos.presentation.features.maps.buildMapRendererThemeOrNull
import com.glancemap.glancemapwearos.presentation.features.maps.resolveDemFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import java.util.UUID

internal class WatchWorldWindSession {
    var view by mutableStateOf<WatchWorldWindWindow?>(null)
    var center by mutableStateOf<LatLong?>(null)
    var initializing by mutableStateOf(false)
    var running by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var onUserPanStarted: () -> Unit = {}
}

@Suppress("LongParameterList")
@Composable
internal fun rememberWatchWorldWindSession(
    session: WatchWorldWindSession,
    enabled: Boolean,
    holder: MapHolder?,
    mapPath: String?,
    appearanceInProgress: Boolean,
    interactive: Boolean,
    angle: Int,
    bearing: Float,
): WatchWorldWindSession {
    val context = LocalContext.current
    LaunchedEffect(enabled, holder, mapPath, appearanceInProgress, interactive) {
        if (!enabled || appearanceInProgress || !interactive) return@LaunchedEffect
        if (holder == null || mapPath.isNullOrEmpty()) {
            return@LaunchedEffect
        }
        runWatchWorldWindSession(session, holder, mapPath, context, angle, bearing)
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
) {
    val sessionId = UUID.randomUUID().toString()
    val started = SystemClock.elapsedRealtime()
    var imagery: WorldWindMapsforge? = null
    var elevation: WorldWindElevation? = null
    var window: WatchWorldWindWindow? = null
    var demContext = "dem_actual=unavailable axis=unavailable"
    session.failed = false
    session.running = true
    session.initializing = true
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
        val center = holder.mapView.model.mapViewPosition.center
        withContext(Dispatchers.IO) {
            val theme =
                checkNotNull(
                    buildMapRendererThemeOrNull(
                        context,
                        currentSource.themeFile,
                        currentSource.mapsforgeThemeName,
                        currentSource.bundledThemeId,
                        currentSource.externalTheme,
                    ),
                ) { "Selected theme unavailable" }
            imagery = WorldWindMapsforge(currentSource, theme)
            val mapBounds = checkNotNull(imagery).bounds
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
        }
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
        currentWindow.engine.globe.elevationModel
            .addCoverage(checkNotNull(elevation).coverage)
        currentWindow.initialize(center, angle, bearing)
        session.center = center
        session.view = currentWindow
        val elapsed = SystemClock.elapsedRealtime() - started
        val viewMessage = "event=view_created renderer=worldwind session_id=$sessionId elapsed_ms=$elapsed"
        Log.i(TERRAIN_LOG_TAG, viewMessage)
        DebugTelemetry.log(TERRAIN_LOG_TAG, viewMessage)
        val imageryProvider = checkNotNull(imagery)
        val elevationProvider = checkNotNull(elevation)
        waitForWorldWindReady(imageryProvider, currentWindow)
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
                    "cancelled=${imageryProvider.tileCancelled.get()} " +
                    "failed=${imageryProvider.tileFailures.get()} " +
                    "outstanding=${imageryProvider.outstandingRequests()} " +
                    "dem_grids=${elevationProvider.gridCount.get()} " +
                    "dem_samples=${elevationProvider.sampleCount.get()} " +
                    "dem_voids=${elevationProvider.voidCount.get()} dem_ms=${elevationProvider.sampleMs.get()} " +
                    "dem_skipped_grids=${elevationProvider.skippedGridCount.get()} " +
                    "dem_failed_grids=${elevationProvider.failedGridCount.get()} " +
                    "dem_cache_hits=${elevationProvider.cacheStats.hits.get()} " +
                    "dem_cache_misses=${elevationProvider.cacheStats.misses.get()} " +
                    "dem_hgt_loads=${elevationProvider.cacheStats.loads.get()} " +
                    "dem_hgt_decoded_bytes=${elevationProvider.cacheStats.decodedBytes.get()} " +
                    "dem_hgt_load_ms=${elevationProvider.cacheStats.loadNanos.get() / 1_000_000} " +
                    "pan=${currentWindow.panCount.get()} zoom=${currentWindow.zoomCount.get()} " +
                    "orbit=${currentWindow.orbitCount.get()} recenter=${currentWindow.recenterCount.get()} " +
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
        session.initializing = false
        session.view = null
        window?.let { view ->
            view.onPause()
            (view.parent as? ViewGroup)?.removeView(view)
        }
        finishWorldWindResources(
            close = {
                elevation?.close()
                imagery?.close()
            },
            onFinished = {
                session.running = false
                val endMessage = worldWindEndMessage(sessionId, started, demContext, window, imagery, elevation)
                Log.i(TERRAIN_LOG_TAG, endMessage)
                DebugTelemetry.log(TERRAIN_LOG_TAG, endMessage)
            },
        )
    }
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
        "track_rebuilds=${window?.trackRebuildCount?.get() ?: 0} " +
        "marker_updates=${window?.markerUpdateCount?.get() ?: 0} " +
        "requests=${imagery?.tileRequests?.get() ?: 0} renders=${imagery?.tileRenders?.get() ?: 0} " +
        "images=${imagery?.tileImagesAvailable?.get() ?: 0} failed=${imagery?.tileFailures?.get() ?: 0} " +
        "coalesced=${imagery?.tileCoalesced?.get() ?: 0} cancelled=${imagery?.tileCancelled?.get() ?: 0} " +
        "render_ms=${imagery?.tileRenderMs?.get() ?: 0} dem_grids=${elevation?.gridCount?.get() ?: 0} " +
        "dem_samples=${elevation?.sampleCount?.get() ?: 0} dem_voids=${elevation?.voidCount?.get() ?: 0} " +
        "dem_ms=${elevation?.sampleMs?.get() ?: 0} " +
        "dem_skipped_grids=${elevation?.skippedGridCount?.get() ?: 0} " +
        "dem_failed_grids=${elevation?.failedGridCount?.get() ?: 0} " +
        "dem_cache_hits=${elevation?.cacheStats?.hits?.get() ?: 0} " +
        "dem_cache_misses=${elevation?.cacheStats?.misses?.get() ?: 0} " +
        "dem_hgt_loads=${elevation?.cacheStats?.loads?.get() ?: 0} " +
        "dem_hgt_decoded_bytes=${elevation?.cacheStats?.decodedBytes?.get() ?: 0} " +
        "dem_hgt_load_ms=${(elevation?.cacheStats?.loadNanos?.get() ?: 0) / 1_000_000} " +
        "heap_used=${runtime.totalMemory() - runtime.freeMemory()} heap_max=${runtime.maxMemory()} " +
        "internal_gpu_bytes=unavailable first_useful_frame=unavailable"
}

private suspend fun waitForWorldWindReady(
    imagery: WorldWindMapsforge,
    window: WatchWorldWindWindow,
) {
    var waitedSeconds = 0
    while ((imagery.tileImagesAvailable.get() == 0L || window.draws.get() == 0L) && waitedSeconds < 30) {
        delay(1_000)
        waitedSeconds++
    }
    check(imagery.tileImagesAvailable.get() > 0L && window.draws.get() > 0L) {
        "WorldWind imagery or surface did not become ready"
    }
}
