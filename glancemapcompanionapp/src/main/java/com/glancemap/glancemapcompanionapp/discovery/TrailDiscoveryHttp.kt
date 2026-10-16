package com.glancemap.glancemapcompanionapp.discovery

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class TrailDiscoveryHttp {
    private val client =
        OkHttpClient
            .Builder()
            .callTimeout(22, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    suspend fun read(request: Request): String {
        require(request.url.isHttps && request.url.username.isEmpty() && request.url.password.isEmpty())
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request.newBuilder().header("User-Agent", "GlanceMapCompanion/1").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        // Do not expose URLs, response bodies, or credential headers in diagnostics/UI.
                        continuation.resumeWithException(IOException("Route source unavailable"))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result = response.use { runCatching { it.boundedTrailResponse() } }
                        result.fold(continuation::resume, continuation::resumeWithException)
                    }
                },
            )
        }
    }
}

internal fun Response.boundedTrailResponse(): String {
    if (!isSuccessful || body.contentLength() > TRAIL_RESPONSE_LIMIT) throw IOException("Route source unavailable")
    val output = ByteArrayOutputStream()
    body.byteStream().use { input ->
        val buffer = ByteArray(8192)
        var count = input.read(buffer)
        while (count != -1) {
            if (output.size() + count > TRAIL_RESPONSE_LIMIT) throw IOException("Route response too large")
            output.write(buffer, 0, count)
            count = input.read(buffer)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}

private const val TRAIL_RESPONSE_LIMIT = 4 * 1024 * 1024
