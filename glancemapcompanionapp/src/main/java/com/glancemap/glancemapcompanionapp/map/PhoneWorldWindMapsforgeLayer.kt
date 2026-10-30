package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
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
import java.util.concurrent.Executors

/** One datastore and bounded single-worker renderer per WorldWind view. */
internal class PhoneWorldWindMapsforgeLayer(
    context: Context,
    private val map: PhoneOfflineMap,
    private val themeConfig: PhoneOfflineThemeConfig,
    private val diagnostics: PhoneWorldWindDiagnostics,
) : Closeable {
    private data class Style(
        val display: DisplayModel,
        val theme: RenderThemeFuture,
    )

    private val heap = Runtime.getRuntime().maxMemory()
    private val density = context.resources.displayMetrics.density
    private val cache =
        PhoneWorldWindTileCache(
            phoneWorldWindCacheCapacity(heap),
            cost = { key, bytes -> maxOf(bytes.size, key.resolution * key.resolution * 4) },
            onEviction = { diagnostics.evicted.incrementAndGet() },
        )
    private val worker =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "worldwind-mapsforge") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
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
            "cache_charged_bytes=${cache.usedBytes()} cache_encoded_bytes=${cache.storedBytes()} " +
                "cache_max=${cache.maximumBytes} pending=${coalescer.pendingCount()}"
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
                        return ImageSource.fromImageFactory(
                            object : ImageSource.ImageFactory {
                                override suspend fun createBitmap(): Bitmap? = bitmap(key)
                            },
                        )
                    }
                },
                maxZoom = 20,
                minZoom = 0,
            ),
        )

    private suspend fun bitmap(key: PhoneWorldWindTileKey): Bitmap? {
        val cached = cache.get(key)
        if (cached != null) {
            diagnostics.hits.incrementAndGet()
            diagnostics.tile("memory", key, 0)
            return BitmapFactory.decodeByteArray(cached, 0, cached.size)
        }
        diagnostics.misses.incrementAndGet()
        diagnostics.tile("miss", key, 0)
        val bytes =
            coalescer.get(key) {
                cache.get(key) ?: render(key)?.also {
                    val context = currentCoroutineContext()
                    synchronized(renderLock) {
                        context.ensureActive()
                        if (!closed) cache.put(key, it)
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
                null
            }
        }

    override fun close() {
        scope.cancel()
        synchronized(renderLock) {
            if (closed) return
            closed = true
            cache.clear()
            mapsforgeCache.destroy()
            labelStore.destroy()
            detailed?.theme?.decrementRefCount()
            base.theme.decrementRefCount()
            mapFile.close()
        }
        worker.close()
    }
}
