package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.glancemap.glancemapwearos.BuildConfig
import com.glancemap.glancemapwearos.presentation.features.maps.TerrainSource
import earth.worldwind.layer.mercator.MercatorTileFactory
import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.layer.mercator.MercatorTiledSurfaceImage
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.util.concurrent.atomic.AtomicReference

private const val TILE_SIZE = 256

/**
 * Session-owned, single-worker Mapsforge tile provider. With [diskCache], drawn images are saved
 * as PNG and later requests load them instead of drawing again; [themeVariant] names the theme
 * choice that the source's theme fields do not describe (the dedicated 3D hiking theme).
 */
internal class WorldWindMapsforge(
    source: TerrainSource,
    theme: XmlRenderTheme,
    private val diskCache: WorldWindTileDiskCache? = null,
    themeVariant: String = "selected",
) : Closeable {
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)

    /** Saves finish after the session closes; they only use already drawn pixels. */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    val tileRequests = AtomicLong()
    val tileRenders = AtomicLong()
    val tileImagesAvailable = AtomicLong()
    val tileRenderMs = AtomicLong()
    val tileFailures = AtomicLong()
    val tileCoalesced = AtomicLong()
    val tileCancelled = AtomicLong()
    val tileSkipped = AtomicLong()
    val tileDiskHits = AtomicLong()
    val tileDiskWrites = AtomicLong()
    val tileDiskWriteBytes = AtomicLong()
    val visibleTiles = AtomicReference<WorldWindVisibleTiles?>(null)

    /** Tiles WorldWind asked for in its latest frame; null until the first frame. */
    private val wantedTiles = AtomicReference<Set<WorldWindTileKey>?>(null)

    /** Increments whenever the set of wanted tiles changes, i.e. the view moved. */
    private val viewGeneration = AtomicLong()
    private val renderGate = WorldWindViewPriorityGate()
    private val coalescer =
        WorldWindCoalescer<WorldWindTileKey, IntArray>(
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
            "${source.bundledThemeId}:${source.externalTheme}:$themeVariant:" +
            "${BuildConfig.VERSION_CODE}:${BuildConfig.GIT_SHA}"
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
            WorldWindVisibleTileImage(
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
                visibleTiles,
                wantedTiles,
                viewGeneration,
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
            object : WorldWindTileImageFactory {
                override val key = key

                override suspend fun createBitmap(): Bitmap? {
                    val bitmap =
                        readSavedTile(key) ?: run {
                            val view = viewGeneration.get()
                            coalescer
                                .get(key) { renderGate.withPermit(view) { renderIfWanted(key) } }
                                ?.let { pixels ->
                                    currentCoroutineContext().ensureActive()
                                    worldWindBitmap(pixels)
                                }
                        }
                    return if (bitmap == null || closed) {
                        bitmap?.recycle()
                        null
                    } else {
                        tileImagesAvailable.incrementAndGet()
                        bitmap
                    }
                }
            },
        )

    /**
     * Skips a tile that WorldWind no longer requests. Cancellation, unlike a null image, does not
     * mark the tile absent, so it is drawn normally if it comes back into view.
     */
    private fun renderIfWanted(key: WorldWindTileKey): IntArray? {
        if (!worldWindTileWanted(wantedTiles.get(), key)) {
            tileSkipped.incrementAndGet()
            throw CancellationException("Tile left the view")
        }
        return render(key)?.also { pixels -> save(key, pixels) }
    }

    private suspend fun readSavedTile(key: WorldWindTileKey): Bitmap? {
        val cache = diskCache ?: return null
        return withContext(Dispatchers.IO) {
            val bytes = cache.read(key)
            val decoded =
                bytes?.let {
                    BitmapFactory.decodeByteArray(
                        it,
                        0,
                        it.size,
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                    )
                }
            val usable = decoded?.takeIf { it.width == key.resolution && it.height == key.resolution }
            if (usable != null) {
                tileDiskHits.incrementAndGet()
            } else if (bytes != null) {
                decoded?.recycle()
                cache.delete(key)
            }
            usable
        }
    }

    private fun save(
        key: WorldWindTileKey,
        pixels: IntArray,
    ) {
        val cache = diskCache ?: return
        saveScope.launch {
            val bitmap = worldWindBitmap(pixels)
            val bytes =
                try {
                    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                } finally {
                    bitmap.recycle()
                }
            val written = cache.write(key, bytes)
            if (written > 0) {
                tileDiskWrites.incrementAndGet()
                tileDiskWriteBytes.addAndGet(written)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun render(key: WorldWindTileKey): IntArray? =
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
                val pixels =
                    try {
                        IntArray(key.resolution * key.resolution).also { pixels ->
                            AndroidGraphicFactory
                                .getBitmap(bitmap)
                                .getPixels(pixels, 0, key.resolution, 0, 0, key.resolution, key.resolution)
                        }
                    } finally {
                        bitmap.decrementRefCount()
                    }
                tileRenders.incrementAndGet()
                tileRenderMs.addAndGet(SystemClock.elapsedRealtime() - started)
                pixels
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
}

/** Image factory that exposes its tile key, so a frame can report which tiles it still wants. */
internal interface WorldWindTileImageFactory : ImageSource.ImageFactory {
    val key: WorldWindTileKey
}

internal fun worldWindTileWanted(
    wanted: Set<WorldWindTileKey>?,
    key: WorldWindTileKey,
): Boolean = wanted == null || key in wanted

internal class WorldWindVisibleTileImage(
    image: MercatorTiledSurfaceImage,
    private val visibleTiles: AtomicReference<WorldWindVisibleTiles?>,
    private val wantedTiles: AtomicReference<Set<WorldWindTileKey>?>? = null,
    private val viewGeneration: AtomicLong? = null,
) : MercatorTiledSurfaceImage(image.tileFactory, image.levelSet) {
    private var total = 0
    private var ready = 0
    private var failed = 0
    private val frameWanted = HashSet<WorldWindTileKey>()

    init {
        imageOptions = image.imageOptions
    }

    override fun assembleTiles(rc: RenderContext) {
        total = 0
        ready = 0
        failed = 0
        frameWanted.clear()
        super.assembleTiles(rc)
        if (!rc.isPickMode) {
            if (wantedTiles?.get() != frameWanted) {
                wantedTiles?.set(frameWanted.toSet())
                viewGeneration?.incrementAndGet()
            }
            val previous = visibleTiles.get()
            if (previous?.ready != ready || previous.total != total || previous.failed != failed) {
                visibleTiles.set(WorldWindVisibleTiles(ready, total, failed))
            }
        }
    }

    override fun getTexture(
        rc: RenderContext,
        tile: ImageTile,
        retrieve: Boolean,
    ): Texture? {
        if (retrieve && !rc.isPickMode) {
            val source = tile.imageSource?.takeIf { it.isImageFactory }
            (source?.asImageFactory() as? WorldWindTileImageFactory)?.let { frameWanted += it.key }
        }
        return super.getTexture(rc, tile, retrieve)
    }

    override fun addTile(
        rc: RenderContext,
        tile: ImageTile,
    ) {
        super.addTile(rc, tile)
        val source = tile.imageSource
        if (source != null) {
            total++
            if (getTexture(rc, tile, false) != null) {
                ready++
            } else if (rc.renderResourceCache.absentResourceList.isResourceAbsent(source.hashCode())) {
                failed++
            }
        }
    }
}

internal fun worldWindBitmap(
    pixels: IntArray,
): Bitmap = Bitmap.createBitmap(pixels, TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
