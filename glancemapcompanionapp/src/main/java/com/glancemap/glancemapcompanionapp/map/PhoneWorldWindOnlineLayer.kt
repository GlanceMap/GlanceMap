package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.glancemap.glancemapcompanionapp.BuildConfig
import earth.worldwind.WorldWind
import earth.worldwind.layer.mercator.MercatorTileFactory
import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.render.image.ImageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.File
import java.io.IOException

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

internal fun phoneWorldWindTileFailureIsTransient(code: Int): Boolean = code >= 500 || code in setOf(408, 425, 429)

internal fun phoneWorldWindTileRetry(
    scope: CoroutineScope,
    diagnostics: PhoneWorldWindDiagnostics,
): PhoneWorldWindTileRetry =
    PhoneWorldWindTileRetry(scope, onScheduled = diagnostics::tileRetryScheduled) { resourceId ->
        diagnostics.tileRetryFired()
        WorldWind.unmarkResourceAbsent(resourceId)
        WorldWind.requestRedraw()
    }

/** The factory is the image source's identity, so its hash is the id WorldWind marks as absent. */
internal fun phoneWorldWindRetryingImage(
    retry: PhoneWorldWindTileRetry,
    load: suspend (onTransientFailure: () -> Unit) -> Bitmap?,
): ImageSource =
    ImageSource.fromImageFactory(
        object : ImageSource.ImageFactory {
            override suspend fun createBitmap(): Bitmap? = load { retry.schedule(hashCode()) }
        },
    )

/** Owns the pending tile retries for one WorldWind view. */
internal class PhoneWorldWindOnlineImagery(
    context: Context,
    provider: OnlineMapProvider,
    diagnostics: PhoneWorldWindDiagnostics,
    activity: PhoneWorldWindActivity,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val retry = phoneWorldWindTileRetry(scope, diagnostics)

    val layer: MercatorTiledImageLayer =
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
                        return phoneWorldWindRetryingImage(retry) { onTransientFailure ->
                            activity.track {
                                withContext(Dispatchers.IO) {
                                    phoneWorldWindDownloadTile(context, url, diagnostics, onTransientFailure)
                                }
                            }
                        }
                    }
                },
                maxZoom = provider.maximumZoom,
                minZoom = provider.minimumZoom,
            ),
        )

    override fun close() {
        scope.cancel()
    }
}

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
    diagnostics: PhoneWorldWindDiagnostics,
    onTransientFailure: () -> Unit,
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
            val recoverable = response.isSuccessful || phoneWorldWindTileFailureIsTransient(response.code)
            val bitmap =
                if (response.isSuccessful) {
                    response.body.byteStream().use(BitmapFactory::decodeStream)
                } else {
                    null
                }
            if (bitmap == null) {
                diagnostics.tileFailed("online", if (response.isSuccessful) "decode" else "http_${response.code}")
                if (recoverable) onTransientFailure()
            }
            bitmap
        }
    }.onFailure { error ->
        // Only the type is reported: exception messages can repeat the keyed tile URL.
        diagnostics.tileFailed("online", "error_${error::class.simpleName}")
        if (error is IOException) onTransientFailure()
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
