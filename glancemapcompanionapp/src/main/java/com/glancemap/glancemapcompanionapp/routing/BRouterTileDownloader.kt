package com.glancemap.glancemapcompanionapp.routing

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDownloadDiagnostics
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import com.glancemap.glancemapcompanionapp.refuges.sanitizeRemoteHttpDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

internal const val ROUTING_SEGMENTS_DIRECTORY_NAME = "routing-segments"

internal data class RoutingBBox(
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
) {
    fun asQueryString(): String = "${formatCoord(minLon)},${formatCoord(minLat)},${formatCoord(maxLon)},${formatCoord(maxLat)}"
}

internal data class RoutingTileDownloadResult(
    val tileUris: List<Uri>,
    val tileNames: List<String>,
    val bbox: String,
    val downloadedCount: Int,
    val skippedCount: Int,
)

data class RoutingDownloadRequest(
    val bbox: String,
)

internal object BRouterTileMath {
    private const val TILE_DEGREES = 5
    private const val BBOX_EPSILON = 1e-9

    fun parseBbox(input: String): RoutingBBox {
        val parts =
            input
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        require(parts.size == 4) {
            "BBox must be west,south,east,north."
        }

        val minLon = parts[0].toDoubleOrNull() ?: error("Invalid west value in bbox.")
        val minLat = parts[1].toDoubleOrNull() ?: error("Invalid south value in bbox.")
        val maxLon = parts[2].toDoubleOrNull() ?: error("Invalid east value in bbox.")
        val maxLat = parts[3].toDoubleOrNull() ?: error("Invalid north value in bbox.")

        require(minLon < maxLon && minLat < maxLat) {
            "BBox is invalid. West/south must be smaller than east/north."
        }

        return RoutingBBox(
            minLon = minLon.coerceIn(-180.0, 180.0),
            minLat = minLat.coerceIn(-90.0, 90.0),
            maxLon = maxLon.coerceIn(-180.0, 180.0),
            maxLat = maxLat.coerceIn(-90.0, 90.0),
        )
    }

    fun tileFileNamesForBbox(bboxInput: String): List<String> = tileFileNamesForBbox(parseBbox(bboxInput))

    fun tileFileNamesForBbox(bbox: RoutingBBox): List<String> {
        val lonStart = tileOrigin(bbox.minLon)
        val lonEnd = tileOrigin(bbox.maxLon - BBOX_EPSILON)
        val latStart = tileOrigin(bbox.minLat)
        val latEnd = tileOrigin(bbox.maxLat - BBOX_EPSILON)

        val result = linkedSetOf<String>()
        var lat = latStart
        while (lat <= latEnd) {
            var lon = lonStart
            while (lon <= lonEnd) {
                result += tileFileName(swLon = lon, swLat = lat)
                lon += TILE_DEGREES
            }
            lat += TILE_DEGREES
        }
        return result.toList()
    }

    private fun tileOrigin(coordinate: Double): Int = floor(coordinate / TILE_DEGREES.toDouble()).toInt() * TILE_DEGREES

    private fun tileFileName(
        swLon: Int,
        swLat: Int,
    ): String = "${formatTileCoord(swLon, 'E', 'W')}_${formatTileCoord(swLat, 'N', 'S')}.rd5"

    private fun formatTileCoord(
        value: Int,
        positivePrefix: Char,
        negativePrefix: Char,
    ): String {
        val prefix = if (value < 0) negativePrefix else positivePrefix
        return "$prefix${abs(value)}"
    }
}

private fun formatCoord(value: Double): String = "%1$.5f".format(value)

internal const val SUPPORTED_ROUTING_PACK_LOOKUP_VERSION = 11

internal fun readRoutingPackLookupVersion(file: File): Int? {
    if (!file.exists() || !file.isFile || file.length() < 2L) return null
    return file.inputStream().use { input ->
        val high = input.read()
        val low = input.read()
        if (high < 0 || low < 0) {
            null
        } else {
            (high shl 8) or low
        }
    }
}

internal fun isUsableRoutingPackCache(file: File): Boolean =
    file.exists() &&
        file.length() > 0L &&
        readRoutingPackLookupVersion(file) == SUPPORTED_ROUTING_PACK_LOOKUP_VERSION

internal class BRouterTileDownloader(
    private val context: Context,
) {
    private val storage = PhoneOfflineStorage(context)

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var cancelRequested: Boolean = false

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun cancelActiveDownload() {
        cancelRequested = true
        activeConnection?.disconnect()
    }

    suspend fun downloadForBbox(
        bboxInput: String,
        reportProgress: (percent: Int, status: String, detail: String) -> Unit,
        forceRefresh: Boolean = false,
    ): RoutingTileDownloadResult =
        withContext(Dispatchers.IO) {
            cancelRequested = false
            val bbox = BRouterTileMath.parseBbox(bboxInput)
            val normalizedBbox = bbox.asQueryString()
            val tileNames =
                BRouterTileMath
                    .tileFileNamesForBbox(bbox)
                    .sorted()
            require(tileNames.isNotEmpty()) {
                "No routing packs are required for this bbox."
            }
            val startedAtMs = SystemClock.elapsedRealtime()
            PhoneDownloadDiagnostics.log(
                "BRouter",
                "Start bbox=$normalizedBbox tileCount=${tileNames.size} forceRefresh=$forceRefresh",
            )

            val outputDir = storage.routingDirectory().apply { mkdirs() }

            var downloaded = 0
            var skipped = 0
            val uris = ArrayList<Uri>(tileNames.size)

            try {
                tileNames.forEachIndexed { index, tileName ->
                    currentCoroutineContext().ensureActive()
                    throwIfCancellationRequested()

                    val step = index + 1
                    reportProgress(
                        overallRoutingDownloadProgress(index, tileNames.size, 0.0),
                        if (forceRefresh) {
                            "Refreshing routing pack $step/${tileNames.size}…"
                        } else {
                            "Downloading routing pack $step/${tileNames.size}…"
                        },
                        "",
                    )

                    val target = File(outputDir, tileName)
                    if (!forceRefresh && isUsableRoutingPackCache(target)) {
                        skipped += 1
                        PhoneDownloadDiagnostics.log(
                            "BRouter",
                            "Cache hit tile=$tileName step=$step/${tileNames.size} bytes=${target.length()}",
                        )
                        reportProgress(
                            overallRoutingDownloadProgress(index, tileNames.size, 1.0),
                            "Routing pack $step/${tileNames.size} already on phone.",
                            formatRoutingProgressDetail(
                                downloadedBytes = target.length(),
                                totalBytes = target.length(),
                            ),
                        )
                    } else {
                        if (!forceRefresh && target.exists() && target.length() > 0L) {
                            val cachedLookupVersion = readRoutingPackLookupVersion(target)
                            PhoneDownloadDiagnostics.log(
                                "BRouter",
                                "Cache refresh tile=$tileName step=$step/${tileNames.size} " +
                                    "lookupVersion=$cachedLookupVersion " +
                                    "supported=$SUPPORTED_ROUTING_PACK_LOOKUP_VERSION",
                            )
                            reportProgress(
                                overallRoutingDownloadProgress(index, tileNames.size, 0.0),
                                "Refreshing routing pack $step/${tileNames.size}…",
                                "",
                            )
                        }
                        downloadTile(
                            tileName = tileName,
                            target = target,
                            forceRefresh = forceRefresh,
                            step = step,
                            totalSteps = tileNames.size,
                            reportProgress = { tileFraction, status, detail ->
                                reportProgress(
                                    overallRoutingDownloadProgress(index, tileNames.size, tileFraction),
                                    status,
                                    detail,
                                )
                            },
                        )
                        downloaded += 1
                    }

                    uris +=
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            target,
                        )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    PhoneDownloadDiagnostics.warn(
                        "BRouter",
                        "Cancelled bbox=$normalizedBbox downloaded=$downloaded skipped=$skipped",
                    )
                } else {
                    PhoneDownloadDiagnostics.error(
                        "BRouter",
                        "Failed bbox=$normalizedBbox downloaded=$downloaded skipped=$skipped",
                        error,
                    )
                }
                throw error
            } finally {
                activeConnection?.disconnect()
                activeConnection = null
                cancelRequested = false
            }

            persistLastRequest(normalizedBbox)
            val durationMs = SystemClock.elapsedRealtime() - startedAtMs
            PhoneDownloadDiagnostics.log(
                "BRouter",
                "Complete bbox=$normalizedBbox tileCount=${tileNames.size} downloaded=$downloaded skipped=$skipped durationMs=$durationMs",
            )

            RoutingTileDownloadResult(
                tileUris = uris,
                tileNames = tileNames,
                bbox = normalizedBbox,
                downloadedCount = downloaded,
                skippedCount = skipped,
            )
        }

    fun getLastRequest(): RoutingDownloadRequest? {
        val bbox =
            prefs
                .getString(KEY_LAST_BBOX, null)
                ?.trim()
                .orEmpty()
        if (bbox.isBlank()) return null
        return RoutingDownloadRequest(bbox = bbox)
    }

    private fun persistLastRequest(bbox: String) {
        prefs
            .edit()
            .putString(KEY_LAST_BBOX, bbox)
            .apply()
    }

    private suspend fun downloadTile(
        tileName: String,
        target: File,
        forceRefresh: Boolean,
        step: Int,
        totalSteps: Int,
        reportProgress: (fraction: Double, status: String, detail: String) -> Unit,
    ) {
        var lastFailure: Throwable? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attemptIndex ->
            currentCoroutineContext().ensureActive()
            throwIfCancellationRequested()
            val attempt = attemptIndex + 1
            val attemptStartedAtMs = SystemClock.elapsedRealtime()
            reportProgress(
                0.0,
                buildRoutingStatus(
                    forceRefresh = forceRefresh,
                    step = step,
                    totalSteps = totalSteps,
                    tileName = tileName,
                    attempt = attempt,
                ),
                "",
            )
            try {
                downloadTileOnce(
                    tileName = tileName,
                    target = target,
                    forceRefresh = forceRefresh,
                    step = step,
                    totalSteps = totalSteps,
                    reportProgress = reportProgress,
                )
                val durationMs = SystemClock.elapsedRealtime() - attemptStartedAtMs
                PhoneDownloadDiagnostics.log(
                    "BRouter",
                    "Tile complete tile=$tileName step=$step/$totalSteps attempt=$attempt bytes=${target.length()} durationMs=$durationMs",
                )
                return
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastFailure = error
                if (!shouldRetryDownload(error) || attempt >= MAX_DOWNLOAD_ATTEMPTS) {
                    throw error
                }
                PhoneDownloadDiagnostics.warn(
                    "BRouter",
                    "Retry tile=$tileName step=$step/$totalSteps attempt=$attempt/$MAX_DOWNLOAD_ATTEMPTS reason=${summarizeThrowable(error)}",
                )
                delay(RETRY_BACKOFF_BASE_MS * attempt.toLong())
            }
        }
        throw lastFailure ?: IllegalStateException("Routing download failed for $tileName.")
    }

    private fun downloadTileOnce(
        tileName: String,
        target: File,
        forceRefresh: Boolean,
        step: Int,
        totalSteps: Int,
        reportProgress: (fraction: Double, status: String, detail: String) -> Unit,
    ) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        if (temp.exists()) temp.delete()
        throwIfCancellationRequested()
        var completedSuccessfully = false

        val url = "$SEGMENTS_BASE_URL/$tileName"
        val connection =
            (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Connection", "Keep-Alive")
            }
        activeConnection = connection

        try {
            val code =
                try {
                    connection.responseCode
                } catch (error: IOException) {
                    if (cancelRequested) throw CancellationException("Cancelled by user")
                    throw error
                }
            throwIfCancellationRequested()
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw FileNotFoundException("Routing pack not found: $tileName")
            }
            if (code !in 200..299) {
                val detail = readResponseText(connection.errorStream, MAX_ERROR_RESPONSE_BYTES)
                throw RoutingDownloadHttpException(
                    summarizeRoutingFailure(tileName, code, detail),
                    code,
                )
            }
            val contentLength = connection.contentLengthLong.takeIf { it > 0L }
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    copyWithProgress(
                        input = input,
                        totalBytes = contentLength,
                        onProgress = { copiedBytes, fraction ->
                            reportProgress(
                                fraction,
                                buildRoutingStatus(
                                    forceRefresh = forceRefresh,
                                    step = step,
                                    totalSteps = totalSteps,
                                    tileName = tileName,
                                ),
                                formatRoutingProgressDetail(
                                    downloadedBytes = copiedBytes,
                                    totalBytes = contentLength,
                                ),
                            )
                        },
                    ) { buffer, length ->
                        output.write(buffer, 0, length)
                    }
                }
            }
            if (temp.length() <= 0L) {
                throw IllegalStateException("Downloaded routing pack is empty: $tileName")
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            completedSuccessfully = true
        } finally {
            if (!completedSuccessfully || cancelRequested) {
                connection.disconnect()
            }
            if (activeConnection === connection) {
                activeConnection = null
            }
            if (temp.exists()) temp.delete()
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        totalBytes: Long?,
        onProgress: (copiedBytes: Long, fraction: Double) -> Unit,
        writeChunk: (buffer: ByteArray, length: Int) -> Unit,
    ) {
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        var copied = 0L
        var lastReportedBytes = -1L
        try {
            while (true) {
                throwIfCancellationRequested()
                val read = input.read(buffer)
                if (read < 0) break
                writeChunk(buffer, read)
                copied += read
                val shouldReport =
                    totalBytes == null ||
                        copied - lastReportedBytes >= DOWNLOAD_PROGRESS_STEP_BYTES ||
                        (totalBytes > 0L && copied >= totalBytes)
                if (shouldReport) {
                    val fraction =
                        if (totalBytes != null && totalBytes > 0L) {
                            (copied.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
                        } else {
                            0.0
                        }
                    onProgress(copied, fraction)
                    lastReportedBytes = copied
                }
            }
            throwIfCancellationRequested()
            onProgress(copied, 1.0)
        } catch (error: IOException) {
            if (cancelRequested) {
                throw CancellationException("Cancelled by user")
            }
            throw error
        }
    }

    private fun throwIfCancellationRequested() {
        if (cancelRequested) {
            throw CancellationException("Cancelled by user")
        }
    }

    private fun shouldRetryDownload(error: Throwable): Boolean =
        when (error) {
            is RoutingDownloadHttpException -> isRetriableRoutingStatus(error.statusCode)
            is SocketTimeoutException -> true
            is IOException -> true
            else -> false
        }

    private fun buildRoutingStatus(
        forceRefresh: Boolean,
        step: Int,
        totalSteps: Int,
        tileName: String,
        attempt: Int? = null,
    ): String {
        val verb = if (forceRefresh) "Refreshing" else "Downloading"
        val base =
            if (attempt != null && attempt > 1) {
                "$verb routing pack $step/$totalSteps (attempt $attempt/$MAX_DOWNLOAD_ATTEMPTS)…"
            } else {
                "$verb routing pack $step/$totalSteps…"
            }
        return "$base $tileName"
    }

    private fun summarizeRoutingFailure(
        tileName: String,
        code: Int,
        body: String,
    ): String {
        val detail = sanitizeRemoteHttpDetail(body)
        val fallback =
            when (code) {
                408 -> "Routing download timed out. Please try again."
                429 -> "Routing server rate limit reached. Please wait a moment and retry."
                502 -> "Routing server returned an invalid gateway response. Please try again."
                503 -> "Routing server is temporarily unavailable. Please try again."
                504 -> "Routing server timed out. Please try again."
                else -> "Please try again."
            }
        return if (detail.isBlank()) {
            "Routing pack download failed for $tileName (HTTP $code). $fallback"
        } else {
            "Routing pack download failed for $tileName (HTTP $code). $detail"
        }
    }

    private fun readResponseText(
        stream: InputStream?,
        maxBytes: Int,
    ): String {
        if (stream == null) return ""
        stream.use { input ->
            val buffer = ByteArray(16 * 1024)
            val out = ByteArray(maxBytes.coerceAtLeast(1))
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val allowed = (maxBytes - total).coerceAtLeast(0)
                if (allowed <= 0) break
                val copyLength = read.coerceAtMost(allowed)
                System.arraycopy(buffer, 0, out, total, copyLength)
                total += copyLength
                if (copyLength < read) break
            }
            return String(out, 0, total, Charsets.UTF_8)
        }
    }

    private fun summarizeThrowable(error: Throwable): String =
        error.localizedMessage
            ?.trim()
            .orEmpty()
            .ifBlank { error::class.java.simpleName }

    private fun formatRoutingProgressDetail(
        downloadedBytes: Long,
        totalBytes: Long?,
    ): String {
        val downloaded = formatMegabytes(downloadedBytes)
        val total = totalBytes?.takeIf { it > 0L }?.let(::formatMegabytes)
        return if (total != null) {
            "$downloaded / $total"
        } else {
            "$downloaded downloaded"
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        val megabytes = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.getDefault(), "%.1f MB", megabytes)
    }

    private companion object {
        const val PREFS_NAME = "routing_download"
        const val KEY_LAST_BBOX = "last_bbox"
        const val SEGMENTS_BASE_URL = "https://brouter.de/brouter/segments4"
        const val USER_AGENT = "GlanceMap-Routing/1.0"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
        const val MAX_ERROR_RESPONSE_BYTES = 120_000
        const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
        const val DOWNLOAD_PROGRESS_STEP_BYTES = 1_048_576L
        const val MAX_DOWNLOAD_ATTEMPTS = 3
        const val RETRY_BACKOFF_BASE_MS = 1_500L
    }

    private class RoutingDownloadHttpException(
        message: String,
        val statusCode: Int,
    ) : IOException(message)
}

internal fun overallRoutingDownloadProgress(
    stepIndex: Int,
    totalSteps: Int,
    stepFraction: Double,
): Int {
    if (totalSteps <= 0) return 0
    val clamped = stepFraction.coerceIn(0.0, 1.0)
    return ((((stepIndex + clamped) / totalSteps.toDouble()) * 85.0))
        .roundToInt()
        .coerceIn(0, 85)
}

internal fun isRetriableRoutingStatus(code: Int): Boolean = code == 408 || code == 429 || code in 500..599
