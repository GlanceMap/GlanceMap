package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.glancemap.glancemapcompanionapp.BuildConfig
import earth.worldwind.layer.mercator.MercatorTileFactory
import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.render.image.ImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File

internal fun phoneWorldWindOnlineTileUrl(
    provider: OnlineMapProvider,
    tile: PhoneWorldWindTile,
): String? {
    if (provider.rasterTileUrlTemplate.isBlank() || tile.z !in provider.minimumZoom..provider.maximumZoom) return null
    val count = 1 shl tile.z
    if (tile.x !in 0 until count || tile.y !in 0 until count) return null
    provider.bounds?.let { bounds ->
        if (!phoneWorldWindTileBounds(tile).intersects(bounds[1], bounds[0], bounds[3], bounds[2])) return null
    }
    return provider.rasterTileUrlTemplate
        .replace("{z}", tile.z.toString())
        .replace("{x}", tile.x.toString())
        .replace("{y}", tile.y.toString())
}

internal fun phoneWorldWindOnlineLayer(
    context: Context,
    provider: OnlineMapProvider,
): MercatorTiledImageLayer =
    MercatorTiledImageLayer(
        provider.displayName,
        MercatorTiledImageLayer.buildTiledSurfaceImage(
            object : MercatorTileFactory {
                override val contentType = "Online raster"

                override fun getImageSource(
                    x: Int,
                    y: Int,
                    z: Int,
                ): ImageSource? {
                    val url = phoneWorldWindOnlineTileUrl(provider, PhoneWorldWindTile(z, x, y)) ?: return null
                    return ImageSource.fromImageFactory(
                        object : ImageSource.ImageFactory {
                            override suspend fun createBitmap(): Bitmap? = withContext(Dispatchers.IO) { phoneWorldWindDownloadTile(context, url) }
                        },
                    )
                }
            },
            maxZoom = provider.maximumZoom,
            minZoom = provider.minimumZoom,
        ),
    )

private object PhoneWorldWindHttp {
    @Volatile private var client: OkHttpClient? = null

    fun client(context: Context): OkHttpClient =
        client ?: synchronized(this) {
            client ?: OkHttpClient
                .Builder()
                .cache(Cache(File(context.applicationContext.cacheDir, "worldwind-online-tiles"), 32L * 1024 * 1024))
                .addNetworkInterceptor { chain -> phoneWorldWindCacheFallback(chain.proceed(chain.request())) }
                .build()
                .also { client = it }
        }
}

private fun phoneWorldWindDownloadTile(
    context: Context,
    url: String,
): Bitmap? =
    runCatching {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", "GlanceMap/${BuildConfig.VERSION_NAME} (Android)")
                .header("Accept", "image/*")
                .build()
        PhoneWorldWindHttp.client(context).newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.byteStream().use(BitmapFactory::decodeStream) else null
        }
    }.getOrNull()

internal fun phoneWorldWindCacheFallback(response: Response): Response =
    if (
        response.request.url.host == "tile.openstreetmap.org" &&
        response.isSuccessful &&
        response.header("Cache-Control") == null &&
        response.header("Expires") == null
    ) {
        response.newBuilder().header("Cache-Control", "public, max-age=604800").build()
    } else {
        response
    }
