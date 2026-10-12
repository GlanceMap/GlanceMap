package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private const val ONLINE_DEM_DISK_CACHE_BYTES = 64L * 1024L * 1024L
private const val ONLINE_DEM_MAX_RESPONSE_BYTES = 1024 * 1024
private const val ONLINE_DEM_REQUEST_TIMEOUT_SECONDS = 8L

/** Shared HTTP cache survives map recreation; cancellation also cancels the underlying request. */
internal object PhoneOnlineDemHttp {
    private var client: OkHttpClient? = null

    private fun httpClient(context: Context): OkHttpClient =
        synchronized(this) {
            client ?: OkHttpClient
                .Builder()
                .cache(Cache(File(context.cacheDir, "online-elevation"), ONLINE_DEM_DISK_CACHE_BYTES))
                .callTimeout(ONLINE_DEM_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
                .also { client = it }
        }

    suspend fun loadTile(
        context: Context,
        tile: PhoneOnlineDemTileId,
    ): PhoneTerrariumTile? {
        val request =
            Request
                .Builder()
                .url(tile.url())
                .header("User-Agent", "GlanceMapCompanion/1")
                .build()
        val client = httpClient(context)
        val cachedRequest =
            request
                .newBuilder()
                .cacheControl(
                    CacheControl
                        .Builder()
                        .onlyIfCached()
                        .maxStale(Int.MAX_VALUE, TimeUnit.SECONDS)
                        .build(),
                ).build()
        val cachedBytes = client.newCall(cachedRequest).readDemBytes()
        val cachedTile = cachedBytes?.let { decodeTile(it) }
        if (cachedTile != null) return cachedTile
        val networkRequest =
            if (cachedBytes == null) {
                request
            } else {
                request.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build()
            }
        return client.newCall(networkRequest).readDemBytes()?.let { decodeTile(it) }
    }

    private suspend fun decodeTile(bytes: ByteArray): PhoneTerrariumTile? =
        withContext(Dispatchers.IO) {
            runCatching { decodePhoneTerrariumPng(bytes) }.getOrNull().also { decoded ->
                PhoneDebugCapture.log("PhoneOnlineDem", "event=tile_decode success=${decoded != null}")
            }
        }
}

internal suspend fun Call.readDemBytes(): ByteArray? =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    PhoneDebugCapture.log(
                        "PhoneOnlineDem",
                        "event=tile_read success=false category=${if (call.isCanceled()) "cancelled" else "network"}",
                    )
                    continuation.resume(null)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    val bytes =
                        response.use {
                            runCatching { response.boundedDemBytes() }.getOrNull()
                        }
                    PhoneDebugCapture.log(
                        "PhoneOnlineDem",
                        "event=tile_read success=${bytes != null} disk_cache=${response.cacheResponse != null} " +
                            "status=${response.code}",
                    )
                    continuation.resume(bytes)
                }
            },
        )
    }

internal fun Response.boundedDemBytes(): ByteArray? {
    if (!isSuccessful || body.contentLength() > ONLINE_DEM_MAX_RESPONSE_BYTES) return null
    val output = ByteArrayOutputStream()
    body.byteStream().use { input ->
        val buffer = ByteArray(8192)
        var count = input.read(buffer)
        while (count != -1) {
            if (output.size() + count > ONLINE_DEM_MAX_RESPONSE_BYTES) throw IOException("DEM response exceeds limit")
            output.write(buffer, 0, count)
            count = input.read(buffer)
        }
    }
    return output.toByteArray()
}

internal fun decodePhoneTerrariumPng(bytes: ByteArray): PhoneTerrariumTile? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options =
        BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    val bitmap =
        if (bounds.outWidth == PHONE_ONLINE_DEM_TILE_SIZE && bounds.outHeight == PHONE_ONLINE_DEM_TILE_SIZE) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } else {
            null
        } ?: return null
    return try {
        val pixels = IntArray(PHONE_ONLINE_DEM_TILE_SIZE * PHONE_ONLINE_DEM_TILE_SIZE)
        bitmap.getPixels(
            pixels,
            0,
            PHONE_ONLINE_DEM_TILE_SIZE,
            0,
            0,
            PHONE_ONLINE_DEM_TILE_SIZE,
            PHONE_ONLINE_DEM_TILE_SIZE,
        )
        PhoneTerrariumTile(pixels)
    } finally {
        bitmap.recycle()
    }
}
