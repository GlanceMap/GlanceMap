package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.glancemap.glancemapwearos.presentation.features.maps.TerrainSource
import earth.worldwind.layer.mercator.MercatorTileFactory
import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.render.image.ImageSource
import kotlinx.coroutines.CancellationException
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
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Session-owned, single-worker Mapsforge tile provider; no disk cache. */
internal class WorldWindMapsforge(
    source: TerrainSource,
    theme: XmlRenderTheme,
) : Closeable {
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    val tileRequests = AtomicLong()
    val tileRenders = AtomicLong()
    val tileImagesAvailable = AtomicLong()
    val tileRenderMs = AtomicLong()
    val tileFailures = AtomicLong()
    val tileCoalesced = AtomicLong()
    val tileCancelled = AtomicLong()
    private val coalescer =
        WorldWindCoalescer<WorldWindTileKey, ByteArray>(
            scope,
            { tileCoalesced.incrementAndGet() },
            { tileCancelled.incrementAndGet() },
        )
    private val lock = Any()
    private val store = MapFile(source.mapFile)
    val bounds = store.boundingBox()
    private val cache = InMemoryTileCache(4)
    private val labels = TileBasedLabelStore(16)
    private val display = DisplayModel().apply { setFixedTileSize(TILE_SIZE) }
    private val themeFuture = RenderThemeFuture(AndroidGraphicFactory.INSTANCE, theme, display)
    private val renderer = DatabaseRenderer(store, AndroidGraphicFactory.INSTANCE, cache, labels, true, true, null)
    private val mapIdentity =
        "${source.mapFile.absolutePath}:${source.mapFile.lastModified()}:${source.mapFile.length()}"
    private val themeIdentity =
        "${source.themeFile}:${source.themeFile?.lastModified()}:${source.mapsforgeThemeName}:" +
            "${source.bundledThemeId}:${source.externalTheme}"
    private val textScale = source.textScale

    @Volatile private var closed = false

    init {
        var initialized = false
        try {
            themeFuture.run()
            themeFuture.get()
            initialized = true
        } finally {
            if (!initialized) close()
        }
    }

    val layer =
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
                        tileRequests.incrementAndGet()
                        if (closed || !validTile(z, x, y)) return null
                        val tile = worldWindTileBounds(z, x, y)
                        val covered =
                            tile.intersects(
                                bounds.minLatitude,
                                bounds.minLongitude,
                                bounds.maxLatitude,
                                bounds.maxLongitude,
                            )
                        val key = WorldWindTileKey(mapIdentity, themeIdentity, textScale, z, x, y, TILE_SIZE)
                        return if (covered) imageFor(key) else null
                    }
                },
                maxZoom = 20,
                minZoom = 0,
            ),
        )

    private fun validTile(
        z: Int,
        x: Int,
        y: Int,
    ): Boolean {
        if (z !in 0..20) return false
        val count = 1 shl z
        return x in 0 until count && y in 0 until count
    }

    private fun imageFor(key: WorldWindTileKey): ImageSource =
        ImageSource.fromImageFactory(
            object : ImageSource.ImageFactory {
                override suspend fun createBitmap(): Bitmap? {
                    val bytes = coalescer.get(key) { render(key) } ?: return null
                    currentCoroutineContext().ensureActive()
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    return if (closed) {
                        decoded?.recycle()
                        null
                    } else {
                        if (decoded == null) tileFailures.incrementAndGet() else tileImagesAvailable.incrementAndGet()
                        decoded
                    }
                }
            },
        )

    @Suppress("TooGenericExceptionCaught")
    private fun render(key: WorldWindTileKey): ByteArray? =
        synchronized(lock) {
            if (closed) return null
            val tile = Tile(key.x, key.y, key.z.toByte(), key.resolution)
            val job = RendererJob(tile, store, themeFuture, display, textScale, false, false)
            val started = SystemClock.elapsedRealtime()
            try {
                val bitmap =
                    renderer.executeJob(job) ?: run {
                        tileFailures.incrementAndGet()
                        return null
                    }
                val bytes =
                    try {
                        ByteArrayOutputStream().use { output ->
                            bitmap.compress(output)
                            output.toByteArray()
                        }
                    } finally {
                        bitmap.decrementRefCount()
                    }
                tileRenders.incrementAndGet()
                tileRenderMs.addAndGet(SystemClock.elapsedRealtime() - started)
                bytes
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                tileFailures.incrementAndGet()
                throw error
            }
        }

    fun outstandingRequests(): Int = coalescer.pendingCount()

    override fun close() {
        scope.cancel()
        synchronized(lock) {
            if (closed) return
            closed = true
            cache.destroy()
            labels.destroy()
            themeFuture.decrementRefCount()
            store.close()
        }
        worker.close()
    }

    private companion object {
        const val TILE_SIZE = 256
    }
}
