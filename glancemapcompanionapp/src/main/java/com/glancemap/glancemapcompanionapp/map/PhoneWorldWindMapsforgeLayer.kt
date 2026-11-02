package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapcompanionapp.BuildConfig
import earth.worldwind.layer.mercator.MercatorTileFactory
import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.render.image.ImageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.labels.TileBasedLabelStore
import org.mapsforge.map.layer.renderer.DatabaseRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

private const val PHONE_WORLDWIND_TILE_CACHE_DIRECTORY = "worldwind-mapsforge-tiles"

/** One datastore and bounded single-worker renderer per WorldWind view. */
internal class PhoneWorldWindMapsforgeLayer(
    context: Context,
    private val map: PhoneOfflineMap,
    private val themeConfig: PhoneOfflineThemeConfig,
    private val diagnostics: PhoneWorldWindDiagnostics,
    private val activity: PhoneWorldWindActivity,
) : Closeable {
    private data class Style(
        val display: DisplayModel,
        val theme: RenderThemeFuture,
    )

    private val heap = Runtime.getRuntime().maxMemory()
    private val density = context.resources.displayMetrics.density
    private val cache =
        PhoneWorldWindDiskTileCache(
            File(context.applicationContext.cacheDir, PHONE_WORLDWIND_TILE_CACHE_DIRECTORY),
            BuildConfig.VERSION_CODE.toString(),
            onEviction = { diagnostics.evicted.incrementAndGet() },
        )
    private val worker =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "worldwind-mapsforge") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val retry = phoneWorldWindTileRetry(scope, diagnostics)
    private val coalescer =
        PhoneWorldWindTileCoalescer<PhoneWorldWindTileKey, ByteArray>(
            scope,
            onCoalesced = { diagnostics.coalesced.incrementAndGet() },
            onCancelled = { diagnostics.cancelled.incrementAndGet() },
        )
    private val renderLock = Any()
    private val mapFile = MapFile(map.file)
    val bounds = mapFile.boundingBox()
    private val mapsforgeCache = InMemoryTileCache(4)
    private val labelStore = TileBasedLabelStore(16)
    private val theme = PhoneOfflineThemeCatalog.renderTheme(themeConfig, context).theme
    private val base = createStyle(256)
    private var detailed: Style? = null
    private val renderer =
        DatabaseRenderer(mapFile, AndroidGraphicFactory.INSTANCE, mapsforgeCache, labelStore, true, true, null)
    private var closed = false
    private val themeIdentity =
        "${themeConfig.themeId}:${themeConfig.styleId}:${themeConfig.externalRevision}:${themeConfig.enabledOverlayLayerIds?.sorted()}"

    init {
        diagnostics.setSummaryProvider {
            "disk_cache_bytes=${cache.usedBytes()} disk_cache_max=${cache.maximumBytes} " +
                "pending=${coalescer.pendingCount()}"
        }
    }

    private fun createStyle(resolution: Int): Style {
        val display =
            DisplayModel().apply {
                setFixedTileSize(resolution)
                if (resolution == 512) setUserScaleFactor(userScaleFactor * 2f)
            }
        val future = RenderThemeFuture(AndroidGraphicFactory.INSTANCE, theme, display)
        try {
            future.run()
            future.get()
        } catch (error: Exception) {
            if (resolution == 256) {
                mapsforgeCache.destroy()
                labelStore.destroy()
                mapFile.close()
                scope.cancel()
                worker.close()
            }
            throw error
        }
        return Style(display, future)
    }

    val layer: MercatorTiledImageLayer =
        MercatorTiledImageLayer(
            "Offline Mapsforge",
            MercatorTiledImageLayer.buildTiledSurfaceImage(
                object : MercatorTileFactory {
                    override val contentType = "Offline Mapsforge"

                    override fun getImageSource(
                        x: Int,
                        y: Int,
                        z: Int,
                    ): ImageSource? {
                        if (z !in 0..20) return null
                        val count = 1 shl z
                        if (x !in 0 until count || y !in 0 until count) return null
                        val tile = PhoneWorldWindTile(z, x, y)
                        val region = phoneWorldWindTileBounds(tile)
                        if (!region.intersects(
                                bounds.minLatitude,
                                bounds.minLongitude,
                                bounds.maxLatitude,
                                bounds.maxLongitude,
                            )
                        ) {
                            return null
                        }
                        val resolution = phoneWorldWindTileResolution(z, density, heap)
                        val key =
                            PhoneWorldWindTileKey(
                                map.rendererIdentity,
                                themeIdentity,
                                base.display.scaleFactor,
                                tile,
                                resolution,
                            )
                        return phoneWorldWindRetryingImage(retry) { onTransientFailure ->
                            bitmap(key).also { bitmap ->
                                if (bitmap == null) {
                                    diagnostics.tileFailed("mapsforge", "no_bitmap_z${key.tile.z}")
                                    onTransientFailure()
                                }
                            }
                        }
                    }
                },
                maxZoom = 20,
                minZoom = 0,
            ),
        )

    private suspend fun bitmap(key: PhoneWorldWindTileKey): Bitmap? {
        val cached = cache.get(key)
        val decoded = cached?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (decoded != null) {
            diagnostics.hits.incrementAndGet()
            diagnostics.tile("disk", key, 0)
            return decoded
        }
        // A tile file that no longer decodes is dropped and rendered again rather than failing forever.
        if (cached != null) cache.remove(key)
        diagnostics.misses.incrementAndGet()
        diagnostics.tile("miss", key, 0)
        val bytes =
            activity.track {
                coalescer.get(key) {
                    activity.track {
                        cache.get(key) ?: render(key)?.also {
                            currentCoroutineContext().ensureActive()
                            cache.put(key, it)
                        }
                    }
                }
            } ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun render(key: PhoneWorldWindTileKey): ByteArray? =
        synchronized(renderLock) {
            if (closed) return null
            val start = SystemClock.elapsedRealtime()
            try {
                val style = if (key.resolution == 256) base else detailed ?: createStyle(512).also { detailed = it }
                val tile = key.tile
                val job =
                    RendererJob(
                        Tile(tile.x, tile.y, tile.z.toByte(), key.resolution),
                        mapFile,
                        style.theme,
                        style.display,
                        1f,
                        false,
                        false,
                    )
                val rendered = renderer.executeJob(job) ?: return null
                try {
                    val bytes =
                        ByteArrayOutputStream().use { out ->
                            rendered.compress(out)
                            out.toByteArray()
                        }
                    diagnostics.rendered(key, SystemClock.elapsedRealtime() - start)
                    bytes
                } finally {
                    rendered.decrementRefCount()
                }
            } catch (error: Exception) {
                Log.w("PhoneWorldWind", "Mapsforge tile failed z=${key.tile.z}", error)
                diagnostics.event("mapsforge_error", "z=${key.tile.z} resolution=${key.resolution} error=${error::class.simpleName}")
                null
            }
        }

    override fun close() {
        scope.cancel()
        synchronized(renderLock) {
            if (closed) return
            closed = true
            mapsforgeCache.destroy()
            labelStore.destroy()
            detailed?.theme?.decrementRefCount()
            base.theme.decrementRefCount()
            mapFile.close()
        }
        worker.close()
    }
}
