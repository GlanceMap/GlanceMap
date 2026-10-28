package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.labels.TileBasedLabelStore
import org.mapsforge.map.layer.renderer.DatabaseRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import java.io.File
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt

internal fun renderTerrainTexture(
    mapFile: File,
    theme: XmlRenderTheme,
    area: TerrainArea,
    textScale: Float,
    checkCancelled: () -> Unit = {},
): Bitmap {
    val northWest = area.coordinate(0.0, 0.0)
    val southEast = area.coordinate(1.0, 1.0)
    val longitudeSpan = southEast.second - northWest.second
    val zoom = log2(360.0 * 512.0 / (longitudeSpan * 256.0)).roundToInt().coerceIn(0, 20).toByte()
    val mapSize = MercatorProjection.getMapSize(zoom, 256)
    val left = MercatorProjection.longitudeToPixelX(northWest.second, mapSize)
    val top = MercatorProjection.latitudeToPixelY(northWest.first, mapSize)
    val right = MercatorProjection.longitudeToPixelX(southEast.second, mapSize)
    val bottom = MercatorProjection.latitudeToPixelY(southEast.first, mapSize)
    val store = MapFile(mapFile)
    val display = DisplayModel().apply { setFixedTileSize(256) }
    val future = RenderThemeFuture(AndroidGraphicFactory.INSTANCE, theme, display)
    val cache = InMemoryTileCache(16)
    val labels = TileBasedLabelStore(16)
    var bitmap: Bitmap? = null
    var complete = false
    try {
        require(store.boundingBox().contains(northWest.first, northWest.second))
        require(store.boundingBox().contains(southEast.first, southEast.second))
        future.run()
        future.get()
        val renderer = DatabaseRenderer(store, AndroidGraphicFactory.INSTANCE, cache, labels, true, true, null)
        bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = 512.0 / (right - left)
        for (y in floor(top / 256).toInt()..floor((bottom - 0.001) / 256).toInt()) {
            for (x in floor(left / 256).toInt()..floor((right - 0.001) / 256).toInt()) {
                checkCancelled()
                check(!Thread.currentThread().isInterrupted)
                val tile = Tile(x, y, zoom, 256)
                val destination =
                    RectF(
                        ((x * 256 - left) * scale).toFloat(),
                        ((y * 256 - top) * scale).toFloat(),
                        (((x + 1) * 256 - left) * scale).toFloat(),
                        (((y + 1) * 256 - top) * scale).toFloat(),
                    )
                val job = RendererJob(tile, store, future, display, textScale, false, false)
                drawTerrainTile(canvas, renderer, job, destination)
            }
        }
        complete = true
        return bitmap
    } finally {
        if (!complete) bitmap?.recycle()
        cache.destroy()
        labels.destroy()
        future.decrementRefCount()
        store.close()
    }
}

private fun drawTerrainTile(
    canvas: Canvas,
    renderer: DatabaseRenderer,
    job: RendererJob,
    destination: RectF,
) {
    val tileBitmap = checkNotNull(renderer.executeJob(job)) { "Mapsforge tile render failed" }
    try {
        canvas.drawBitmap(AndroidGraphicFactory.getBitmap(tileBitmap), null, destination, null)
    } finally {
        tileBitmap.decrementRefCount()
    }
}
