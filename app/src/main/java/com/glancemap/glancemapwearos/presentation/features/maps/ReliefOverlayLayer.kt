package com.glancemap.glancemapwearos.presentation.features.maps

import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.core.service.diagnostics.BenchmarkTrace
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.layer.Layer
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * DEM-driven relief overlay rendered above map tiles.
 *
 * Watch optimization strategy:
 * - only compute at higher zoom levels,
 * - compute per map tile in a background worker,
 * - draw only already-cached overlay tiles on the render thread.
 */
internal class ReliefOverlayLayer(
    private val demRootDir: File,
    private val demRootDirs: List<File> = listOf(demRootDir),
    private val diskCacheRootDir: File? = null,
    cacheNamespace: String = "default",
    private val onProcessingStateChanged: () -> Unit = {},
) : Layer() {
    companion object {
        private const val TAG = "ReliefOverlayLayer"

        // Relief overlay is shown only from ~1000m scale and closer.
        internal const val MIN_OVERLAY_ZOOM = 13

        private const val DRAW_FAILURE_COOLDOWN_MS = 15_000L
        private const val MAX_VISIBLE_OVERLAY_TILES = 20
        private const val MAX_PENDING_OVERLAY_JOBS = 64
        private const val VIEWPORT_RESET_TILE_DISTANCE = 12L

        // Keep enough tiles for current viewport + recent adjacent zoom levels.
        private const val MAX_OVERLAY_TILE_CACHE_ENTRIES = (MAX_VISIBLE_OVERLAY_TILES * 3) + 12
        private const val RETRY_FAILED_TILE_AFTER_MS = 3_000L
        private const val MAX_LIVE_ELEVATION_SAMPLE_CACHE_ENTRIES = 512
        private const val LIVE_ELEVATION_SAMPLE_CACHE_TTL_MS = 8_000L
        private const val LIVE_ELEVATION_SAMPLE_QUANTIZATION_FACTOR = 10_000.0
        private const val MAX_ZOOM_IN_FALLBACK_LEVELS = 2
        private const val PREFETCH_RING_MARGIN_TILES = 1
        private const val MAX_PREFETCH_OVERLAY_TILES = 12
        private const val READY_TILE_FADE_IN_MS = 220L
        private const val SETTLING_WINDOW_MS = 500L
        private const val INTERACTION_MOVE_THRESHOLD_PX = 48.0
        private const val OVERLOAD_SLOW_DRAW_THRESHOLD_MS = 28L
        private const val OVERLOAD_SLOW_DRAW_STREAK_TRIGGER = 3
        private const val OVERLOAD_COOLDOWN_MS = 4_000L
        private const val IDLE_FINE_PRIORITY_TILES = 8
    }

    private val demRepository = ReliefDemRepository(demRootDirs = demRootDirs, tag = TAG)
    private val diskCache =
        ReliefOverlayDiskCache(
            diskCacheRootDir = diskCacheRootDir,
            cacheNamespace = cacheNamespace,
            tag = TAG,
        )

    @Volatile
    private var lastNotifiedProcessingState: Boolean? = null

    @Volatile
    private var lastNotifiedProgressPercent: Int? = null

    private val elevationSampleCache =
        object :
            LinkedHashMap<ElevationSampleKey, ElevationSampleCacheEntry>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<ElevationSampleKey, ElevationSampleCacheEntry>?,
            ): Boolean = size > MAX_LIVE_ELEVATION_SAMPLE_CACHE_ENTRIES
        }

    private val overlayTileCache =
        object : LinkedHashMap<OverlayTileKey, OverlayTileEntry>(
            24,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<OverlayTileKey, OverlayTileEntry>?): Boolean {
                val shouldRemove = size > MAX_OVERLAY_TILE_CACHE_ENTRIES
                if (shouldRemove) {
                    eldest?.value?.bitmap?.decrementRefCount()
                }
                return shouldRemove
            }
        }

    private val pendingOverlayJobs = linkedSetOf<OverlayBuildRequest>()

    private val overlayWorker =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            { runnable ->
                Thread(runnable, "relief-overlay-worker").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            },
        )
    private var lastViewportAnchorKey: OverlayTileKey? = null
    private var lastInteractionElapsedMs: Long = SystemClock.elapsedRealtime()
    private var hasInteractionAnchor: Boolean = false
    private var lastInteractionZoom: Byte = MIN_OVERLAY_ZOOM.toByte()
    private var lastInteractionTopLeftX: Double = 0.0
    private var lastInteractionTopLeftY: Double = 0.0
    private var lastRuntimeProfile: RuntimeProfile = RuntimeProfile.IDLE
    private var overloadUntilElapsedMs: Long = 0L
    private var slowDrawStreak: Int = 0

    @Volatile
    private var isDestroyed: Boolean = false

    @Volatile
    private var overlayDisabledUntilElapsedMs: Long = 0L

    @Volatile
    private var overlayWorkEnabled: Boolean = false

    private val tileRenderer =
        ReliefTileRenderer(
            terrainRepository = demRepository,
            isBuildEnabled = { !isDestroyed && overlayWorkEnabled },
        )

    @Volatile
    private var lastRenderTotalTiles: Int = 0

    @Volatile
    private var lastRenderCompletedTiles: Int = 0

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeftPoint: Point,
        rotation: Rotation,
    ) {
        if (!isVisible) {
            suspendOverlayWork()
            return
        }
        if (isDestroyed) {
            suspendOverlayWork()
            return
        }
        if (zoomLevel.toInt() < MIN_OVERLAY_ZOOM) {
            suspendOverlayWork()
            return
        }
        if (demRootDirs.none { it.exists() && it.isDirectory }) {
            suspendOverlayWork()
            return
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (nowElapsedMs < overlayDisabledUntilElapsedMs) {
            suspendOverlayWork()
            return
        }
        overlayWorkEnabled = true
        val drawStartElapsedMs = nowElapsedMs

        try {
            val tileSize =
                displayModel?.tileSize ?: run {
                    suspendOverlayWork()
                    return
                }
            val runtimePolicy =
                resolveRuntimePolicy(
                    nowElapsedMs = nowElapsedMs,
                    zoomLevel = zoomLevel,
                    topLeftPoint = topLeftPoint,
                    tileSize = tileSize,
                )
            applyRuntimePolicyTransition(runtimePolicy)
            val mapSize = MercatorProjection.getMapSize(zoomLevel, tileSize)
            val visibleTiles =
                computeVisibleTiles(
                    boundingBox = boundingBox,
                    zoomLevel = zoomLevel,
                    mapSize = mapSize,
                    tileSize = tileSize,
                    topLeftPoint = topLeftPoint,
                    marginTiles = 0,
                )
            if (visibleTiles.isEmpty()) {
                suspendOverlayWork()
                return
            }

            val viewportTiles =
                visibleTiles.filter { tile ->
                    tileIntersectsViewport(
                        tile = tile,
                        viewportWidth = canvas.width,
                        viewportHeight = canvas.height,
                    )
                }
            val candidateTiles = if (viewportTiles.isNotEmpty()) viewportTiles else visibleTiles
            val renderTiles =
                prioritizeVisibleTiles(
                    tiles = candidateTiles,
                    viewportWidth = canvas.width,
                    viewportHeight = canvas.height,
                    maxTiles = runtimePolicy.maxRenderTiles,
                )
            if (renderTiles.isEmpty()) {
                resetRenderProgress()
                return
            }
            val renderKeys = renderTiles.asSequence().map { it.key }.toSet()
            val prefetchTiles =
                computeVisibleTiles(
                    boundingBox = boundingBox,
                    zoomLevel = zoomLevel,
                    mapSize = mapSize,
                    tileSize = tileSize,
                    topLeftPoint = topLeftPoint,
                    marginTiles = PREFETCH_RING_MARGIN_TILES,
                ).asSequence()
                    .filter { tile ->
                        tile.key !in renderKeys &&
                            !tileIntersectsViewport(
                                tile = tile,
                                viewportWidth = canvas.width,
                                viewportHeight = canvas.height,
                            )
                    }.toList()
                    .let { ringTiles ->
                        prioritizeVisibleTiles(
                            tiles = ringTiles,
                            viewportWidth = canvas.width,
                            viewportHeight = canvas.height,
                            maxTiles = runtimePolicy.maxPrefetchTiles,
                        )
                    }
            maybeResetPendingWorkForViewport(renderTiles)
            lastRenderTotalTiles = renderTiles.size
            var completedTiles = 0
            val finePriorityState =
                computeFinePriorityState(
                    renderTiles = renderTiles,
                    policy = runtimePolicy,
                )

            val filterBefore = canvas.isFilterBitmap()
            if (filterBefore) {
                canvas.setFilterBitmap(false)
            }
            try {
                renderTiles.forEachIndexed { index, tile ->
                    val desiredQuality =
                        desiredVisibleQualityForTile(
                            tileIndex = index,
                            policy = runtimePolicy,
                            finePriorityState = finePriorityState,
                        )
                    val entry = synchronized(overlayTileCache) { overlayTileCache[tile.key] }
                    if (entry == null) {
                        scheduleOverlayTileBuild(
                            key = tile.key,
                            quality = desiredQuality,
                            maxPendingJobs = runtimePolicy.maxPendingJobs,
                        )
                        drawFallbackFromAncestor(tile, canvas)
                        return@forEachIndexed
                    }

                    if (entry.status == OverlayTileStatus.FAILED) {
                        if ((nowElapsedMs - entry.builtElapsedMs) >= RETRY_FAILED_TILE_AFTER_MS) {
                            scheduleOverlayTileBuild(
                                key = tile.key,
                                quality = desiredQuality,
                                maxPendingJobs = runtimePolicy.maxPendingJobs,
                            )
                        }
                        drawFallbackFromAncestor(tile, canvas)
                        return@forEachIndexed
                    }

                    if (entry.status == OverlayTileStatus.NO_DATA) {
                        completedTiles += 1
                        return@forEachIndexed
                    }
                    if (entry.status != OverlayTileStatus.READY) {
                        drawFallbackFromAncestor(tile, canvas)
                        return@forEachIndexed
                    }

                    if (entry.quality.rank < desiredQuality.rank) {
                        scheduleOverlayTileBuild(
                            key = tile.key,
                            quality = desiredQuality,
                            maxPendingJobs = runtimePolicy.maxPendingJobs,
                        )
                    }

                    val bitmap =
                        entry.bitmap ?: run {
                            scheduleOverlayTileBuild(
                                key = tile.key,
                                quality = desiredQuality,
                                maxPendingJobs = runtimePolicy.maxPendingJobs,
                            )
                            drawFallbackFromAncestor(tile, canvas)
                            return@forEachIndexed
                        }

                    val transitionAlpha =
                        readyTileAlpha(
                            entry = entry,
                            nowElapsedMs = nowElapsedMs,
                            fadeInMs = READY_TILE_FADE_IN_MS,
                        )
                    val fallbackDrawn =
                        if (transitionAlpha < 1f) {
                            drawFallbackFromAncestor(tile, canvas)
                        } else {
                            false
                        }
                    val drawAlpha = if (fallbackDrawn) transitionAlpha else 1f
                    completedTiles += 1
                    if (drawAlpha >= 0.999f) {
                        canvas.drawBitmap(
                            bitmap,
                            0,
                            0,
                            bitmap.width,
                            bitmap.height,
                            tile.left,
                            tile.top,
                            tile.right,
                            tile.bottom,
                        )
                    } else {
                        canvas.drawBitmap(
                            bitmap,
                            0,
                            0,
                            bitmap.width,
                            bitmap.height,
                            tile.left,
                            tile.top,
                            tile.right,
                            tile.bottom,
                            drawAlpha,
                        )
                    }
                    if (entry.drawMode == OverlayTileDrawMode.FADE_FROM_FALLBACK && (drawAlpha >= 0.999f || !fallbackDrawn)) {
                        promoteTileToSteadyDrawMode(tile.key)
                    }
                }
                lastRenderCompletedTiles = completedTiles
                notifyProcessingStateChangedIfNeeded()
            } finally {
                if (filterBefore) {
                    canvas.setFilterBitmap(true)
                }
            }

            prefetchTiles.forEach { tile ->
                maybeSchedulePrefetchTile(
                    key = tile.key,
                    nowElapsedMs = nowElapsedMs,
                    desiredQuality = runtimePolicy.prefetchQuality,
                    maxPendingJobs = runtimePolicy.maxPendingJobs,
                )
            }
        } catch (error: Throwable) {
            // Never let relief rendering failures crash the app on constrained watch hardware.
            overlayDisabledUntilElapsedMs = nowElapsedMs + DRAW_FAILURE_COOLDOWN_MS
            Log.e(
                TAG,
                "Disabling relief overlay for ${DRAW_FAILURE_COOLDOWN_MS}ms after draw failure.",
                error,
            )
            clearOverlayTileCache()
        } finally {
            updateLoadSheddingState(drawStartElapsedMs = drawStartElapsedMs)
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        overlayDisabledUntilElapsedMs = 0L
        overlayWorkEnabled = false

        demRepository.clear()
        synchronized(elevationSampleCache) {
            elevationSampleCache.clear()
        }
        clearOverlayTileCache()

        runCatching {
            overlayWorker.shutdownNow()
        }
        clearPendingOverlayQueue()
        notifyProcessingStateChangedIfNeeded(force = true)
    }

    private fun clearOverlayTileCache() {
        synchronized(overlayTileCache) {
            overlayTileCache.values.forEach { entry ->
                entry.bitmap?.decrementRefCount()
            }
            overlayTileCache.clear()
        }
        synchronized(pendingOverlayJobs) {
            pendingOverlayJobs.clear()
        }
        overlayWorker.queue.clear()
        resetRenderProgress()
    }

    fun isProcessing(): Boolean {
        val hasPending = synchronized(pendingOverlayJobs) { pendingOverlayJobs.isNotEmpty() }
        return hasPending || (lastRenderTotalTiles > 0 && lastRenderCompletedTiles < lastRenderTotalTiles)
    }

    fun progressPercent(): Int? {
        val total = lastRenderTotalTiles
        if (total <= 0) return null
        val done = lastRenderCompletedTiles.coerceIn(0, total)
        return ((done * 100f) / total.toFloat()).toInt().coerceIn(0, 100)
    }

    fun sampleElevationMeters(
        lat: Double,
        lon: Double,
    ): Double? {
        if (isDestroyed) return null
        if (demRootDirs.none { it.exists() && it.isDirectory }) return null
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val key =
            quantizedElevationSampleKey(
                lat = lat,
                lon = lon,
                quantizationFactor = LIVE_ELEVATION_SAMPLE_QUANTIZATION_FACTOR,
            )
        synchronized(elevationSampleCache) {
            val cached = elevationSampleCache[key]
            if (cached != null && (nowElapsedMs - cached.sampledAtElapsedMs) <= LIVE_ELEVATION_SAMPLE_CACHE_TTL_MS) {
                return cached.valueMeters
            }
        }

        val sampled =
            runCatching {
                demRepository.elevationAt(lat, lon)
            }.getOrNull()
        synchronized(elevationSampleCache) {
            elevationSampleCache[key] =
                ElevationSampleCacheEntry(
                    valueMeters = sampled,
                    sampledAtElapsedMs = nowElapsedMs,
                )
        }
        return sampled
    }

    private fun resolveRuntimePolicy(
        nowElapsedMs: Long,
        zoomLevel: Byte,
        topLeftPoint: Point,
        tileSize: Int,
    ): RuntimePolicy {
        if (nowElapsedMs < overloadUntilElapsedMs) {
            return RuntimePolicy(
                profile = RuntimeProfile.OVERLOADED,
                visibleQuality = OverlayBuildQuality.COARSE,
                prefetchQuality = OverlayBuildQuality.COARSE,
                maxRenderTiles = min(MAX_VISIBLE_OVERLAY_TILES, 12),
                maxPrefetchTiles = min(MAX_PREFETCH_OVERLAY_TILES, 1),
                maxPendingJobs = min(MAX_PENDING_OVERLAY_JOBS, 16),
            )
        }
        val interacted = detectCameraInteraction(zoomLevel = zoomLevel, topLeftPoint = topLeftPoint, tileSize = tileSize)
        if (interacted) {
            lastInteractionElapsedMs = nowElapsedMs
            return RuntimePolicy(
                profile = RuntimeProfile.MOVING,
                visibleQuality = OverlayBuildQuality.COARSE,
                prefetchQuality = OverlayBuildQuality.COARSE,
                maxRenderTiles = min(MAX_VISIBLE_OVERLAY_TILES, 14),
                maxPrefetchTiles = min(MAX_PREFETCH_OVERLAY_TILES, 2),
                maxPendingJobs = min(MAX_PENDING_OVERLAY_JOBS, 24),
            )
        }
        val idleElapsed = nowElapsedMs - lastInteractionElapsedMs
        return if (idleElapsed >= SETTLING_WINDOW_MS) {
            RuntimePolicy(
                profile = RuntimeProfile.IDLE,
                visibleQuality = OverlayBuildQuality.FINE,
                prefetchQuality = OverlayBuildQuality.FINE,
                maxRenderTiles = MAX_VISIBLE_OVERLAY_TILES,
                maxPrefetchTiles = MAX_PREFETCH_OVERLAY_TILES,
                maxPendingJobs = MAX_PENDING_OVERLAY_JOBS,
            )
        } else {
            RuntimePolicy(
                profile = RuntimeProfile.SETTLING,
                visibleQuality = OverlayBuildQuality.COARSE,
                prefetchQuality = OverlayBuildQuality.COARSE,
                maxRenderTiles = min(MAX_VISIBLE_OVERLAY_TILES, 18),
                maxPrefetchTiles = min(MAX_PREFETCH_OVERLAY_TILES, 4),
                maxPendingJobs = min(MAX_PENDING_OVERLAY_JOBS, 40),
            )
        }
    }

    private fun detectCameraInteraction(
        zoomLevel: Byte,
        topLeftPoint: Point,
        tileSize: Int,
    ): Boolean {
        if (!hasInteractionAnchor) {
            hasInteractionAnchor = true
            lastInteractionZoom = zoomLevel
            lastInteractionTopLeftX = topLeftPoint.x
            lastInteractionTopLeftY = topLeftPoint.y
            return true
        }

        val movedByZoom = zoomLevel != lastInteractionZoom
        val dx = abs(topLeftPoint.x - lastInteractionTopLeftX)
        val dy = abs(topLeftPoint.y - lastInteractionTopLeftY)
        val threshold = max(INTERACTION_MOVE_THRESHOLD_PX, tileSize * 0.12)
        val movedByPan = dx >= threshold || dy >= threshold

        if (movedByZoom || movedByPan) {
            lastInteractionZoom = zoomLevel
            lastInteractionTopLeftX = topLeftPoint.x
            lastInteractionTopLeftY = topLeftPoint.y
            return true
        }

        return false
    }

    private fun applyRuntimePolicyTransition(policy: RuntimePolicy) {
        if (policy.profile == lastRuntimeProfile) return
        val previous = lastRuntimeProfile
        lastRuntimeProfile = policy.profile
        // Drop stale queued work when interaction resumes so near tiles stay responsive.
        if (
            (policy.profile == RuntimeProfile.MOVING && previous != RuntimeProfile.MOVING) ||
            (policy.profile == RuntimeProfile.OVERLOADED && previous != RuntimeProfile.OVERLOADED)
        ) {
            clearPendingOverlayQueue()
        }
    }

    private fun computeFinePriorityState(
        renderTiles: List<VisibleTile>,
        policy: RuntimePolicy,
    ): FinePriorityState =
        buildReliefFinePriorityState(
            renderTiles = renderTiles,
            policy = policy,
            idleFinePriorityTiles = IDLE_FINE_PRIORITY_TILES,
            entryForKey = { key ->
                synchronized(overlayTileCache) { overlayTileCache[key] }
            },
        )

    private fun desiredVisibleQualityForTile(
        tileIndex: Int,
        policy: RuntimePolicy,
        finePriorityState: FinePriorityState,
    ): OverlayBuildQuality =
        resolveReliefVisibleQualityForTile(
            tileIndex = tileIndex,
            policy = policy,
            finePriorityState = finePriorityState,
        )

    private fun maybeSchedulePrefetchTile(
        key: OverlayTileKey,
        nowElapsedMs: Long,
        desiredQuality: OverlayBuildQuality,
        maxPendingJobs: Int,
    ) {
        val entry = synchronized(overlayTileCache) { overlayTileCache[key] }
        if (entry == null) {
            scheduleOverlayTileBuild(key, desiredQuality, maxPendingJobs)
            return
        }
        if (entry.status == OverlayTileStatus.READY && entry.quality.rank < desiredQuality.rank) {
            scheduleOverlayTileBuild(key, desiredQuality, maxPendingJobs)
            return
        }
        if (entry.status == OverlayTileStatus.FAILED && (nowElapsedMs - entry.builtElapsedMs) >= RETRY_FAILED_TILE_AFTER_MS) {
            scheduleOverlayTileBuild(key, desiredQuality, maxPendingJobs)
        }
    }

    private fun promoteTileToSteadyDrawMode(key: OverlayTileKey) {
        synchronized(overlayTileCache) {
            val current = overlayTileCache[key] ?: return
            if (current.drawMode != OverlayTileDrawMode.FADE_FROM_FALLBACK) return
            overlayTileCache[key] = current.copy(drawMode = OverlayTileDrawMode.STEADY)
        }
    }

    private fun maybeResetPendingWorkForViewport(renderTiles: List<VisibleTile>) {
        val anchor = renderTiles.firstOrNull()?.key ?: return
        val previous = lastViewportAnchorKey
        lastViewportAnchorKey = anchor
        if (previous == null) return

        // Keep pending jobs across zoom changes so previously scheduled work is not discarded.
        if (anchor.zoom != previous.zoom) return

        val tileCountPerAxis =
            (
                MercatorProjection.getMapSize(anchor.zoom, anchor.tileSize) /
                    anchor.tileSize.toLong()
            ).coerceAtLeast(1L)

        val directDx = abs(anchor.tileX - previous.tileX)
        val wrappedDx = (tileCountPerAxis - directDx).coerceAtLeast(0L)
        val dx = min(directDx, wrappedDx)
        val dy = abs(anchor.tileY - previous.tileY)

        if (dx > VIEWPORT_RESET_TILE_DISTANCE || dy > VIEWPORT_RESET_TILE_DISTANCE) {
            clearPendingOverlayQueue()
        }
    }

    private fun clearPendingOverlayQueue() {
        synchronized(pendingOverlayJobs) {
            pendingOverlayJobs.clear()
        }
        overlayWorker.queue.clear()
        notifyProcessingStateChangedIfNeeded()
    }

    private fun updateLoadSheddingState(drawStartElapsedMs: Long) {
        val drawElapsedMs = (SystemClock.elapsedRealtime() - drawStartElapsedMs).coerceAtLeast(0L)
        if (drawElapsedMs >= OVERLOAD_SLOW_DRAW_THRESHOLD_MS) {
            slowDrawStreak = (slowDrawStreak + 1).coerceAtMost(OVERLOAD_SLOW_DRAW_STREAK_TRIGGER + 1)
            if (slowDrawStreak >= OVERLOAD_SLOW_DRAW_STREAK_TRIGGER) {
                overloadUntilElapsedMs = SystemClock.elapsedRealtime() + OVERLOAD_COOLDOWN_MS
                slowDrawStreak = 0
                clearPendingOverlayQueue()
                Log.d(TAG, "Entering relief load-shed mode for ${OVERLOAD_COOLDOWN_MS}ms")
            }
        } else {
            slowDrawStreak = (slowDrawStreak - 1).coerceAtLeast(0)
        }
    }

    private fun drawFallbackFromAncestor(
        tile: VisibleTile,
        canvas: Canvas,
    ): Boolean {
        val fallback = findFallbackTileSource(tile.key) ?: return false
        canvas.drawBitmap(
            fallback.bitmap,
            fallback.srcLeft,
            fallback.srcTop,
            fallback.srcRight,
            fallback.srcBottom,
            tile.left,
            tile.top,
            tile.right,
            tile.bottom,
        )
        return true
    }

    private fun findFallbackTileSource(key: OverlayTileKey): FallbackTileSource? {
        val zoomInt = key.zoom.toInt()
        if (zoomInt <= MIN_OVERLAY_ZOOM) return null
        val tileSize = key.tileSize
        if (tileSize <= 0) return null

        val maxLevels = min(MAX_ZOOM_IN_FALLBACK_LEVELS, zoomInt - MIN_OVERLAY_ZOOM)
        if (maxLevels <= 0) return null

        for (level in 1..maxLevels) {
            val scale = 1 shl level
            val scaleLong = scale.toLong()
            val parentZoom = (zoomInt - level).toByte()
            val parentTileX = key.tileX / scaleLong
            val parentTileY = key.tileY / scaleLong
            val parentKey =
                OverlayTileKey(
                    zoom = parentZoom,
                    tileX = parentTileX,
                    tileY = parentTileY,
                    tileSize = tileSize,
                )
            val parentEntry = synchronized(overlayTileCache) { overlayTileCache[parentKey] }
            if (parentEntry?.status != OverlayTileStatus.READY) continue
            val parentBitmap = parentEntry.bitmap ?: continue

            val childOffsetX = (key.tileX - (parentTileX * scaleLong)).toInt()
            val childOffsetY = (key.tileY - (parentTileY * scaleLong)).toInt()
            if (childOffsetX !in 0 until scale || childOffsetY !in 0 until scale) continue

            val srcLeft = (childOffsetX * tileSize) / scale
            val srcTop = (childOffsetY * tileSize) / scale
            val srcRight = ((childOffsetX + 1) * tileSize) / scale
            val srcBottom = ((childOffsetY + 1) * tileSize) / scale
            if (srcRight <= srcLeft || srcBottom <= srcTop) continue

            return FallbackTileSource(
                bitmap = parentBitmap,
                srcLeft = srcLeft,
                srcTop = srcTop,
                srcRight = srcRight,
                srcBottom = srcBottom,
            )
        }
        return null
    }

    private fun scheduleOverlayTileBuild(
        key: OverlayTileKey,
        quality: OverlayBuildQuality,
        maxPendingJobs: Int,
    ) {
        if (isDestroyed || !overlayWorkEnabled) return
        val request = OverlayBuildRequest(key = key, quality = quality)
        val pendingCap = maxPendingJobs.coerceIn(1, MAX_PENDING_OVERLAY_JOBS)

        if (!enqueueOverlayTileBuild(request, pendingCap)) return
        notifyProcessingStateChangedIfNeeded()

        overlayWorker.execute {
            try {
                if (isDestroyed || !overlayWorkEnabled) return@execute

                val entry = buildOverlayTileEntry(key, quality)

                if (!publishOverlayTileEntry(key, entry)) return@execute

                notifyProcessingStateChangedIfNeeded()
                requestRedrawSafely()
            } finally {
                removePendingOverlayTileBuild(request)
                notifyProcessingStateChangedIfNeeded()
            }
        }
    }

    private fun enqueueOverlayTileBuild(
        request: OverlayBuildRequest,
        pendingCap: Int,
    ): Boolean =
        synchronized(pendingOverlayJobs) {
            if (pendingOverlayJobs.contains(request)) return@synchronized false
            if (request.quality == OverlayBuildQuality.COARSE && hasPendingFineOverlayTile(request.key)) {
                return@synchronized false
            }
            if (pendingOverlayJobs.size >= pendingCap) return@synchronized false
            pendingOverlayJobs.add(request)
        }

    private fun hasPendingFineOverlayTile(key: OverlayTileKey): Boolean =
        pendingOverlayJobs.any { pending ->
            pending.key == key && pending.quality == OverlayBuildQuality.FINE
        }

    private fun buildOverlayTileEntry(
        key: OverlayTileKey,
        quality: OverlayBuildQuality,
    ): OverlayTileEntry =
        runCatching {
            diskCache.loadOverlayTileEntryFromDisk(key) ?: buildAndPersistOverlayTileEntry(key, quality)
        }.onFailure { error ->
            Log.w(
                TAG,
                "Failed building overlay tile z=${key.zoom} x=${key.tileX} y=${key.tileY} q=$quality",
                error,
            )
        }.getOrElse {
            failedOverlayTileEntry(quality)
        }

    private fun buildAndPersistOverlayTileEntry(
        key: OverlayTileKey,
        quality: OverlayBuildQuality,
    ): OverlayTileEntry {
        val built =
            BenchmarkTrace.section("relief.overlayTileBuild") {
                tileRenderer.buildOverlayTile(key, quality)
            }
        diskCache.persistOverlayTileEntryToDisk(key = key, entry = built)
        return built
    }

    private fun failedOverlayTileEntry(quality: OverlayBuildQuality): OverlayTileEntry =
        OverlayTileEntry(
            bitmap = null,
            builtElapsedMs = SystemClock.elapsedRealtime(),
            status = OverlayTileStatus.FAILED,
            drawMode = OverlayTileDrawMode.STEADY,
            quality = quality,
        )

    private fun publishOverlayTileEntry(
        key: OverlayTileKey,
        entry: OverlayTileEntry,
    ): Boolean {
        synchronized(overlayTileCache) {
            if (isDestroyed) {
                entry.bitmap?.decrementRefCount()
                return false
            }
            overlayTileCache[key]?.bitmap?.decrementRefCount()
            overlayTileCache[key] = entry
        }
        return true
    }

    private fun removePendingOverlayTileBuild(request: OverlayBuildRequest) {
        synchronized(pendingOverlayJobs) {
            pendingOverlayJobs.remove(request)
        }
    }

    private fun requestRedrawSafely() {
        runCatching { requestRedraw() }
    }

    private fun notifyProcessingStateChangedIfNeeded(force: Boolean = false) {
        if (isDestroyed && !force) return
        val processing = isProcessing()
        val progress = progressPercent()
        if (!force && processing == lastNotifiedProcessingState && progress == lastNotifiedProgressPercent) {
            return
        }
        lastNotifiedProcessingState = processing
        lastNotifiedProgressPercent = progress
        runCatching { onProcessingStateChanged() }
    }

    private fun resetRenderProgress() {
        lastRenderTotalTiles = 0
        lastRenderCompletedTiles = 0
        notifyProcessingStateChangedIfNeeded()
    }

    private fun suspendOverlayWork() {
        overlayWorkEnabled = false
        clearPendingOverlayQueue()
        resetRenderProgress()
    }
}
