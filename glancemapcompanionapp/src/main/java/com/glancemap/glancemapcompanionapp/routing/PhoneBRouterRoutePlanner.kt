package com.glancemap.glancemapcompanionapp.routing

import android.content.Context
import com.glancemap.glancemapcompanionapp.map.PhoneGeneralSettingsPreferences
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import com.glancemap.glancemapcompanionapp.routes.CompanionGpxRouteParser
import com.glancemap.trailcore.routing.BRouterEngine
import com.glancemap.trailcore.routing.BRouterFileLayout
import com.glancemap.trailcore.routing.BRouterRouteOutput
import com.glancemap.trailcore.routing.BRouterRoutePreset
import com.glancemap.trailcore.routing.BRouterRouteRequest
import com.glancemap.trailcore.routing.buildBRouterProfileParams
import com.glancemap.trailcore.routing.normalizeBRouterErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Android adapter for local routing packs or the hosted BRouter routing service. */
internal class PhoneBRouterRoutePlanner(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val storage = PhoneOfflineStorage(appContext)
    private val settings = PhoneGeneralSettingsPreferences(appContext)
    private val onlineClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(65, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @Suppress("TooGenericExceptionCaught") // BRouter and the hosted endpoint expose varied failure types.
    suspend fun createRoute(request: BRouterRouteRequest): BRouterRouteOutput =
        withContext(Dispatchers.IO) {
            val online = settings.isOnlineRoutingEnabled()
            try {
                if (online) createOnlineRoute(request) else createOfflineRoute(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message =
                    if (online) {
                        error.message?.takeIf(String::isNotBlank) ?: "Online BRouter routing failed."
                    } else {
                        normalizeBRouterErrorMessage(error.message.orEmpty())
                    }
                throw IllegalStateException(message, error)
            }
        }

    private fun createOfflineRoute(request: BRouterRouteRequest): BRouterRouteOutput {
        ensureBundledProfilesInstalled()
        val stamp = System.currentTimeMillis()
        val engine = createEngine()
        val attempt = engine.route(request)
        return engine.output(
            attempt = attempt,
            title = "BRouter route $stamp",
            fileName = "route-$stamp.gpx",
        )
    }

    private suspend fun createOnlineRoute(request: BRouterRouteRequest): BRouterRouteOutput {
        val stamp = System.currentTimeMillis()
        val title = "GlanceMap route $stamp"
        val gpxBytes =
            onlineClient
                .newCall(
                    Request
                        .Builder()
                        .url(onlineRouteUrl(request, title))
                        .header("User-Agent", "GlanceMapCompanion/1")
                        .header("Accept", "application/gpx+xml")
                        .get()
                        .build(),
                ).awaitRouteBytes()
        val parsed =
            try {
                ByteArrayInputStream(gpxBytes).use { source -> CompanionGpxRouteParser.parse(source) }
            } catch (error: Exception) {
                val responseText =
                    gpxBytes
                        .toString(Charsets.UTF_8)
                        .trim()
                        .replace('\n', ' ')
                        .take(MAX_ERROR_MESSAGE_CHARS)
                val message =
                    if (responseText.isBlank() || responseText.startsWith("<?xml", ignoreCase = true)) {
                        "BRouter returned an invalid GPX response."
                    } else {
                        responseText
                    }
                throw IOException(message, error)
            }
        return BRouterRouteOutput(
            fileName = "route-$stamp.gpx",
            title = parsed.title ?: title,
            gpxBytes = gpxBytes,
            points = parsed.points,
        )
    }

    private fun onlineRouteUrl(
        request: BRouterRouteRequest,
        title: String,
    ): HttpUrl {
        val waypoints = listOf(request.origin) + request.viaPoints + request.destination
        val builder =
            HttpUrl
                .Builder()
                .scheme("https")
                .host(ONLINE_BROUTER_HOST)
                .addPathSegment(ONLINE_BROUTER_PATH)
                .addQueryParameter(
                    "lonlats",
                    waypoints.joinToString("|") { point -> "${point.longitude},${point.latitude}" },
                ).addQueryParameter("profile", request.preset.onlineProfile())
                .addQueryParameter("format", "gpx")
                .addQueryParameter("trackname", title)
                .addQueryParameter("timode", "4")
                .addQueryParameter("exportWaypoints", "1")

        buildBRouterProfileParams(request).forEach { (name, value) ->
            builder.addQueryParameter("profile:$name", value)
        }
        return builder.build()
    }

    private fun BRouterRoutePreset.onlineProfile(): String =
        when (this) {
            BRouterRoutePreset.BALANCED_HIKE,
            BRouterRoutePreset.PREFER_TRAILS,
            BRouterRoutePreset.PREFER_EASIEST,
            BRouterRoutePreset.CUSTOM_HIKE,
            -> "hiking-mountain"

            BRouterRoutePreset.BIKE_TOURING -> "trekking"
            BRouterRoutePreset.BIKE_ROAD -> "fastbike"
            BRouterRoutePreset.BIKE_QUIET_ROAD -> "fastbike-verylowtraffic"
            BRouterRoutePreset.BIKE_GRAVEL -> "gravel"
            BRouterRoutePreset.BIKE_MTB -> "mtb"
        }

    private fun ensureBundledProfilesInstalled() {
        val profilesDirectory = storage.profilesDirectory()
        PROFILE_FILE_NAMES.forEach { fileName ->
            copyAssetIfNeeded(
                assetPath = "profiles2/$fileName",
                targetFile = File(profilesDirectory, fileName),
            )
        }
        copyAssetIfNeeded(
            assetPath = "profiles2/hiking-mountain.brf",
            targetFile = File(profilesDirectory, "dummy.brf"),
        )
    }

    private fun createEngine(): BRouterEngine =
        BRouterEngine(
            BRouterFileLayout(
                segmentsDirectory = storage.routingDirectory(),
                profilesDirectory = storage.profilesDirectory(),
            ),
        )

    private fun copyAssetIfNeeded(
        assetPath: String,
        targetFile: File,
    ) {
        if (targetFile.isFile && targetFile.length() > 0L) return
        targetFile.parentFile?.mkdirs()
        appContext.assets.open(assetPath).use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private suspend fun Call.awaitRouteBytes(): ByteArray =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        error: IOException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result =
                            runCatching {
                                response.use { current ->
                                    if (!current.isSuccessful) {
                                        val details =
                                            current
                                                .peekBody(MAX_ERROR_BODY_BYTES.toLong())
                                                .string()
                                                .trim()
                                                .replace('\n', ' ')
                                                .take(MAX_ERROR_MESSAGE_CHARS)
                                        val suffix = if (details.isBlank()) "" else ": $details"
                                        throw IOException("BRouter returned HTTP ${current.code}$suffix.")
                                    }
                                    current.readBoundedRouteBytes()
                                }
                            }
                        if (continuation.isActive) {
                            result.fold(
                                onSuccess = { bytes -> continuation.resume(bytes) },
                                onFailure = { error -> continuation.resumeWithException(error) },
                            )
                        }
                    }
                },
            )
        }

    private fun Response.readBoundedRouteBytes(): ByteArray {
        val responseBody = body
        val contentLength = responseBody.contentLength()
        if (contentLength > MAX_ROUTE_RESPONSE_BYTES) throw IOException("BRouter response is too large.")
        val output = ByteArrayOutputStream()
        responseBody.byteStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_ROUTE_RESPONSE_BYTES) {
                    throw IOException("BRouter response is too large.")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private companion object {
        const val ONLINE_BROUTER_HOST = "brouter.de"
        const val ONLINE_BROUTER_PATH = "brouter"
        const val BUFFER_BYTES = 8 * 1024
        const val MAX_ROUTE_RESPONSE_BYTES = 10 * 1024 * 1024
        const val MAX_ERROR_BODY_BYTES = 4 * 1024
        const val MAX_ERROR_MESSAGE_CHARS = 240
        val PROFILE_FILE_NAMES =
            listOf(
                "lookups.dat",
                "hiking-mountain.brf",
                "trekking.brf",
                "fastbike.brf",
                "fastbike-verylowtraffic.brf",
                "gravel.brf",
                "mtb.brf",
            )
    }
}
