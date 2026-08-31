package com.glancemap.glancemapcompanionapp.refuges

import android.content.Context
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDownloadDiagnostics
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

class OsmOverpassPoiImporter(
    private val context: Context,
) {
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var cancelRequested: Boolean = false

    companion object {
        private const val API_ENDPOINT = "https://overpass-api.de/api/interpreter"
        private const val MAX_RESPONSE_BYTES = 8_000_000
        private const val MAX_ERROR_RESPONSE_BYTES = 120_000
        private const val MAX_COUNT_RESPONSE_BYTES = 48_000
        private const val MAX_STATUS_RESPONSE_BYTES = 24_000
        private const val MAX_BBOX_AREA_DEGREES = 12.0
        private const val MAX_LON_SPAN_DEGREES = 5.0
        private const val MAX_LAT_SPAN_DEGREES = 4.0
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 90_000
        private const val COUNT_READ_TIMEOUT_MS = 45_000
        private const val STATUS_TIMEOUT_MS = 6_000
        private const val OVERPASS_TIMEOUT_SECONDS = 40
        private const val OVERPASS_COUNT_TIMEOUT_SECONDS = 25
        private const val OVERPASS_MAXSIZE_BYTES = 64L * 1024L * 1024L
        private const val MAX_REQUEST_ATTEMPTS = 3
        private const val RETRY_BACKOFF_BASE_MS = 1_500L
        private const val MAX_STATUS_RETRY_WAIT_SECONDS = 15
        private const val MAX_STATUS_WAIT_BEFORE_SPLIT_SECONDS = 12
        private const val MAX_OVERFLOW_SPLIT_DEPTH = 4
    }

    fun cancelActiveImport() {
        cancelRequested = true
        activeConnection?.disconnect()
    }

    suspend fun importFromBbox(
        bboxInput: String,
        fileNameInput: String,
        selectedCategoryIds: Set<String> = defaultOsmPoiCategoryIds(),
        reportProgress: ((percent: Int, status: String) -> Unit)? = null,
    ): RefugesImportResult =
        withContext(Dispatchers.IO) {
            cancelRequested = false
            val safeFileName = normalizeFileName(fileNameInput)
            val outputDir = PhoneOfflineStorage(context).poiDirectory().apply { mkdirs() }
            val outputFile = File(outputDir, safeFileName)
            val startedAtMs = SystemClock.elapsedRealtime()
            try {
                val bbox = parseBbox(bboxInput)
                val selectedCategories = resolveOsmPoiImportCategories(selectedCategoryIds)
                if (selectedCategories.isEmpty()) {
                    throw IllegalArgumentException("Select at least one OSM category.")
                }
                val bboxLabel = bbox.asRefugesQueryParam()
                PhoneDownloadDiagnostics.log(
                    "OSM",
                    "Start bbox=$bboxLabel file=$safeFileName categories=${selectedCategories.joinToString(",") { it.id }}",
                )

                reportProgress?.invoke(0, "Preparing OpenStreetMap import…")
                val estimate =
                    preflightPoiCount(
                        bbox = bbox,
                        selectedCategories = selectedCategories,
                        reportProgress = reportProgress,
                    )
                check(estimate.total != 0L) {
                    "No OpenStreetMap points found in this area."
                }
                require(estimate.total <= OSM_OVERPASS_PREFLIGHT_MAX_POI_COUNT) {
                    buildPoiCountTooLargeMessage(estimate.total)
                }
                reportProgress?.invoke(
                    8,
                    buildPoiCountPreflightMessage(estimate.total),
                )
                val summary =
                    try {
                        PoiSqliteCodec
                            .openStreamingWriter(
                                file = outputFile,
                                options =
                                    PoiSqliteWriteOptions(
                                        comment = "Data source: openstreetmap.org (Overpass API)",
                                        writer = "glancemap-osm-importer-2",
                                        extraMetadata =
                                            linkedMapOf(
                                                "osm_bbox_query" to bbox.asRefugesQueryParam(),
                                                "osm_category_ids" to selectedCategories.joinToString(",") { it.id },
                                            ),
                                    ),
                            ).use { writer ->
                                val importedCount =
                                    importTilesIntoWriter(
                                        bbox = bbox,
                                        selectedCategories = selectedCategories,
                                        writer = writer,
                                        reportProgress = reportProgress,
                                    )
                                if (importedCount == 0) {
                                    throw IllegalStateException("No OpenStreetMap points found in this area.")
                                }
                                throwIfCancellationRequested()
                                reportProgress?.invoke(94, "Finalizing OSM POI…")
                                writer.finish()
                            }
                    } catch (error: Throwable) {
                        runCatching { outputFile.delete() }
                        throw error
                    }

                throwIfCancellationRequested()
                val uri =
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        outputFile,
                    )

                reportProgress?.invoke(100, "OSM POI ready.")
                val result =
                    RefugesImportResult(
                        poiUri = uri,
                        fileName = outputFile.name,
                        pointCount = summary.pointCount,
                        categoryCount = summary.categoryCount,
                        bbox = bbox.asRefugesQueryParam(),
                    )
                val durationMs = SystemClock.elapsedRealtime() - startedAtMs
                PhoneDownloadDiagnostics.log(
                    "OSM",
                    "Complete bbox=$bboxLabel file=${result.fileName} points=${result.pointCount} categories=${result.categoryCount} durationMs=$durationMs",
                )
                result
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    PhoneDownloadDiagnostics.warn(
                        "OSM",
                        "Cancelled file=$safeFileName",
                    )
                } else {
                    PhoneDownloadDiagnostics.error(
                        "OSM",
                        "Failed file=$safeFileName",
                        error,
                    )
                }
                throw error
            } finally {
                activeConnection?.disconnect()
                activeConnection = null
                cancelRequested = false
            }
        }

    private suspend fun importTilesIntoWriter(
        bbox: BBox,
        selectedCategories: List<OsmPoiImportCategory>,
        writer: PoiSqliteStreamingWriter,
        reportProgress: ((percent: Int, status: String) -> Unit)?,
    ): Int {
        currentCoroutineContext().ensureActive()
        throwIfCancellationRequested()
        val tiles = splitBboxForOverpass(bbox)
        PhoneDownloadDiagnostics.log(
            "OSM",
            "Plan bbox=${bbox.asRefugesQueryParam()} tileCount=${tiles.size} categories=${selectedCategories.joinToString(",") { it.id }}",
        )
        val importSegment = ProgressSegment(start = 8.0, end = 92.0)
        var importedCount = 0

        tiles.forEachIndexed { index, tile ->
            currentCoroutineContext().ensureActive()
            throwIfCancellationRequested()
            val step = index + 1
            val tileLabel = buildTileLabel(step, tiles.size)
            val requestLabel = tileLabel
            val tileSegment = importSegment.split(index, tiles.size)
            PhoneDownloadDiagnostics.log(
                "OSM",
                "Tile start label=$requestLabel bbox=${tile.asRefugesQueryParam()}",
            )
            reportProgress?.invoke(tileSegment.percentAt(0.0), "Importing $tileLabel…")

            importedCount +=
                importTileWithAdaptiveSplit(
                    tile = tile,
                    tileLabel = tileLabel,
                    requestLabel = requestLabel,
                    tileIndex = step,
                    totalTiles = tiles.size,
                    selectedCategories = selectedCategories,
                    writer = writer,
                    segment = tileSegment,
                    reportProgress = reportProgress,
                )

            reportProgress?.invoke(
                tileSegment.percentAt(1.0),
                buildTileCompletedStatus(step, tiles.size, importedCount),
            )
        }

        return importedCount
    }

    private suspend fun preflightPoiCount(
        bbox: BBox,
        selectedCategories: List<OsmPoiImportCategory>,
        reportProgress: ((percent: Int, status: String) -> Unit)?,
    ): OsmOverpassPoiCountEstimate {
        var lastFailure: Throwable? = null
        repeat(MAX_REQUEST_ATTEMPTS) { attemptIndex ->
            currentCoroutineContext().ensureActive()
            throwIfCancellationRequested()
            val attempt = attemptIndex + 1
            try {
                reportProgress?.invoke(
                    if (attempt == 1) 2 else 4,
                    if (attempt == 1) {
                        "Estimating OpenStreetMap POI count…"
                    } else {
                        "Retrying OpenStreetMap POI estimate ($attempt/$MAX_REQUEST_ATTEMPTS)…"
                    },
                )
                return fetchPoiCountOnce(
                    bbox = bbox,
                    selectedCategories = selectedCategories,
                    requestLabel = "count",
                )
            } catch (error: Throwable) {
                lastFailure = error
                if (error is CancellationException || !shouldRetryRequest(error) || attempt >= MAX_REQUEST_ATTEMPTS) {
                    throw error
                }
                val busyStatus =
                    if (shouldInspectOverpassStatus(error)) {
                        fetchOverpassStatusSummary("count")
                    } else {
                        null
                    }
                delay(resolveRetryDelayMs(attempt, busyStatus))
            }
        }
        error(lastFailure?.localizedMessage ?: "OpenStreetMap POI estimate failed.")
    }

    private fun fetchPoiCountOnce(
        bbox: BBox,
        selectedCategories: List<OsmPoiImportCategory>,
        requestLabel: String,
    ): OsmOverpassPoiCountEstimate {
        throwIfCancellationRequested()
        val requestStartedAtMs = SystemClock.elapsedRealtime()
        var completedSuccessfully = false
        val query = buildOverpassCountQuery(bbox, selectedCategories)
        val bodyBytes = "data=${encode(query)}".toByteArray(Charsets.UTF_8)
        val connection = openOverpassPostConnection(readTimeoutMs = COUNT_READ_TIMEOUT_MS)
        activeConnection = connection

        try {
            PhoneDownloadDiagnostics.log(
                "OSM",
                "count_start bbox=${bbox.asRefugesQueryParam()} bodyBytes=${bodyBytes.size}",
            )
            writeOverpassRequestBody(connection, bodyBytes)
            val code = readOverpassResponseCode(connection, "Timed out waiting for OpenStreetMap POI estimate.")
            throwIfCancellationRequested()
            val responseBody = readOverpassResponseBody(connection, code, MAX_COUNT_RESPONSE_BYTES)
            throwIfOverpassHttpError(code, responseBody)

            val estimate = parseOverpassCountResponse(responseBody)
            PhoneDownloadDiagnostics.log(
                "OSM",
                buildCountCompletedLog(requestLabel, bbox, estimate, requestStartedAtMs),
            )
            completedSuccessfully = true
            return estimate
        } finally {
            if (!completedSuccessfully || cancelRequested) {
                connection.disconnect()
            }
            if (activeConnection === connection) {
                activeConnection = null
            }
        }
    }

    private fun openOverpassPostConnection(readTimeoutMs: Int): HttpURLConnection =
        (URL(API_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8",
            )
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "GlanceMap-Companion")
            setRequestProperty("Connection", "Keep-Alive")
        }

    private fun writeOverpassRequestBody(
        connection: HttpURLConnection,
        bodyBytes: ByteArray,
    ) {
        connection.outputStream.use { out ->
            out.write(bodyBytes)
            out.flush()
        }
    }

    private fun readOverpassResponseCode(
        connection: HttpURLConnection,
        timeoutMessage: String,
    ): Int =
        try {
            connection.responseCode
        } catch (error: SocketTimeoutException) {
            if (cancelRequested) throw CancellationException("Cancelled by user")
            throw OverpassHeaderWaitTimeoutException(timeoutMessage, error)
        } catch (error: IOException) {
            if (cancelRequested) throw CancellationException("Cancelled by user")
            throw error
        }

    private fun readOverpassResponseBody(
        connection: HttpURLConnection,
        code: Int,
        maxSuccessBytes: Int,
    ): String =
        try {
            if (code in 200..299) {
                readResponseText(connection.inputStream, maxSuccessBytes)
            } else {
                readResponseText(connection.errorStream, MAX_ERROR_RESPONSE_BYTES)
            }
        } catch (error: IOException) {
            if (cancelRequested) throw CancellationException("Cancelled by user")
            throw error
        }

    private fun throwIfOverpassHttpError(
        code: Int,
        responseBody: String,
    ) {
        if (code !in 200..299) {
            throw OverpassHttpException(
                summarizeOsmOverpassFailure(code, responseBody),
                code,
            )
        }
    }

    private fun buildCountCompletedLog(
        requestLabel: String,
        bbox: BBox,
        estimate: OsmOverpassPoiCountEstimate,
        requestStartedAtMs: Long,
    ): String =
        "count_complete label=$requestLabel bbox=${bbox.asRefugesQueryParam()} " +
            "total=${estimate.total} nodes=${estimate.nodes} ways=${estimate.ways} " +
            "relations=${estimate.relations} durationMs=${SystemClock.elapsedRealtime() - requestStartedAtMs}"

    private suspend fun importTileWithAdaptiveSplit(
        tile: BBox,
        tileLabel: String,
        requestLabel: String,
        tileIndex: Int,
        totalTiles: Int,
        selectedCategories: List<OsmPoiImportCategory>,
        writer: PoiSqliteStreamingWriter,
        segment: ProgressSegment,
        reportProgress: ((percent: Int, status: String) -> Unit)?,
        splitDepth: Int = 0,
    ): Int =
        try {
            val tilePoints =
                fetchTilePointsWithRetry(
                    tile = tile,
                    tileLabel = tileLabel,
                    requestLabel = requestLabel,
                    tileIndex = tileIndex,
                    totalTiles = totalTiles,
                    selectedCategories = selectedCategories,
                    segment = segment,
                    reportProgress = reportProgress,
                )
            reportProgress?.invoke(segment.percentAt(0.98), "Saving $tileLabel…")
            val appendStartedAtMs = SystemClock.elapsedRealtime()
            val insertedCount = writer.append(tilePoints)
            PhoneDownloadDiagnostics.log(
                "OSM",
                "writer_append_complete label=$requestLabel inserted=$insertedCount requested=${tilePoints.size} elapsedMs=${SystemClock.elapsedRealtime() - appendStartedAtMs}",
            )
            insertedCount
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            if (!shouldAdaptiveSplitOverpassFailure(error)) {
                throw error
            }
            PhoneDownloadDiagnostics.warn(
                "OSM",
                "Adaptive split label=$requestLabel depth=$splitDepth reason=${summarizeThrowable(error)}",
            )

            val overflowTiles = splitBboxForOverpassOverflow(tile)
            if (splitDepth >= MAX_OVERFLOW_SPLIT_DEPTH || overflowTiles.size <= 1) {
                val areaSuffix = if (totalTiles > 1) " Area $tileIndex/$totalTiles." else ""
                throw IllegalStateException(
                    buildAdaptiveSplitFailureMessage(areaSuffix),
                    error,
                )
            }

            var insertedCount = 0
            overflowTiles.forEachIndexed { overflowIndex, overflowTile ->
                val childRequestLabel = "$requestLabel part ${overflowIndex + 1}/${overflowTiles.size}"
                val childSegment = segment.split(overflowIndex, overflowTiles.size)
                reportProgress?.invoke(
                    childSegment.percentAt(0.0),
                    buildTileSplitStatus(tileLabel),
                )
                insertedCount +=
                    importTileWithAdaptiveSplit(
                        tile = overflowTile,
                        tileLabel = tileLabel,
                        requestLabel = childRequestLabel,
                        tileIndex = tileIndex,
                        totalTiles = totalTiles,
                        selectedCategories = selectedCategories,
                        writer = writer,
                        segment = childSegment,
                        reportProgress = reportProgress,
                        splitDepth = splitDepth + 1,
                    )
            }
            insertedCount
        }

    private suspend fun fetchTilePointsWithRetry(
        tile: BBox,
        tileLabel: String,
        requestLabel: String,
        tileIndex: Int,
        totalTiles: Int,
        selectedCategories: List<OsmPoiImportCategory>,
        segment: ProgressSegment,
        reportProgress: ((percent: Int, status: String) -> Unit)?,
    ): List<PoiSqlitePoint> {
        var lastFailure: Throwable? = null
        repeat(MAX_REQUEST_ATTEMPTS) { attemptIndex ->
            currentCoroutineContext().ensureActive()
            throwIfCancellationRequested()
            val attempt = attemptIndex + 1
            if (attempt > 1) {
                PhoneDownloadDiagnostics.warn(
                    "OSM",
                    "Retry label=$requestLabel attempt=$attempt/$MAX_REQUEST_ATTEMPTS",
                )
                reportProgress?.invoke(
                    segment.percentAt(0.0),
                    buildTileRetryStatus(tileLabel, attempt),
                )
            }
            try {
                return fetchTilePointsOnce(
                    bbox = tile,
                    tileLabel = tileLabel,
                    requestLabel = requestLabel,
                    selectedCategories = selectedCategories,
                    segment = segment,
                    reportProgress = reportProgress,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastFailure = error
                if (!shouldRetryRequest(error) || attempt >= MAX_REQUEST_ATTEMPTS) {
                    if (totalTiles > 1) {
                        val message =
                            error.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: "OSM Overpass import failed."
                        throw IllegalStateException("$message Area $tileIndex/$totalTiles.", error)
                    }
                    throw error
                }
                val busyStatus =
                    if (shouldInspectOverpassStatus(error)) {
                        fetchOverpassStatusSummary(requestLabel)
                    } else {
                        null
                    }
                if (shouldPreferAdaptiveSplit(tile, error, attempt, busyStatus)) {
                    PhoneDownloadDiagnostics.warn(
                        "OSM",
                        "Split preferred label=$requestLabel attempt=$attempt/$MAX_REQUEST_ATTEMPTS reason=${summarizeThrowable(error)} status=${busyStatus?.message ?: "unavailable"}",
                    )
                    throw error
                }
                val retryDelayMs = resolveRetryDelayMs(attempt, busyStatus)
                PhoneDownloadDiagnostics.warn(
                    "OSM",
                    "Retry scheduled label=$requestLabel attempt=$attempt/$MAX_REQUEST_ATTEMPTS delayMs=$retryDelayMs reason=${summarizeThrowable(error)} status=${busyStatus?.message ?: "unavailable"}",
                )
                reportProgress?.invoke(
                    segment.percentAt(0.0),
                    buildTileBusyRetryStatus(tileLabel, attempt + 1, busyStatus),
                )
                delay(retryDelayMs)
            }
        }
        throw lastFailure ?: IllegalStateException("OSM Overpass import failed.")
    }

    private fun fetchTilePointsOnce(
        bbox: BBox,
        tileLabel: String,
        requestLabel: String,
        selectedCategories: List<OsmPoiImportCategory>,
        segment: ProgressSegment,
        reportProgress: ((percent: Int, status: String) -> Unit)?,
    ): List<PoiSqlitePoint> {
        throwIfCancellationRequested()
        val requestStartedAtMs = SystemClock.elapsedRealtime()
        var completedSuccessfully = false
        val query = buildOverpassQuery(bbox, selectedCategories)
        val body = "data=${encode(query)}"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val connection =
            (URL(API_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8",
                )
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "GlanceMap-Companion")
                setRequestProperty("Connection", "Keep-Alive")
            }
        activeConnection = connection

        try {
            PhoneDownloadDiagnostics.log(
                "OSM",
                "request_start label=$requestLabel bbox=${bbox.asRefugesQueryParam()} bodyBytes=${bodyBytes.size}",
            )
            reportProgress?.invoke(segment.percentAt(0.02), "Connecting $tileLabel…")
            connection.outputStream.use { out ->
                out.write(bodyBytes)
                out.flush()
            }
            reportProgress?.invoke(
                segment.percentAt(0.04),
                "Waiting for $tileLabel server response…",
            )
            PhoneDownloadDiagnostics.log(
                "OSM",
                "request_body_sent label=$requestLabel elapsedMs=${SystemClock.elapsedRealtime() - requestStartedAtMs} bodyBytes=${bodyBytes.size}",
            )

            val code =
                try {
                    connection.responseCode
                } catch (error: SocketTimeoutException) {
                    if (cancelRequested) throw CancellationException("Cancelled by user")
                    throw OverpassHeaderWaitTimeoutException(
                        "Timed out waiting for $tileLabel server response.",
                        error,
                    )
                } catch (error: IOException) {
                    if (cancelRequested) throw CancellationException("Cancelled by user")
                    throw error
                }
            throwIfCancellationRequested()
            val contentLength = connection.contentLengthLong
            PhoneDownloadDiagnostics.log(
                "OSM",
                "response_headers_received label=$requestLabel code=$code contentLength=$contentLength elapsedMs=${SystemClock.elapsedRealtime() - requestStartedAtMs}",
            )
            var downloadedResponseBytes = 0L
            val responseBody =
                try {
                    if (code in 200..299) {
                        readResponseText(
                            stream = connection.inputStream,
                            maxBytes = MAX_RESPONSE_BYTES,
                            contentLength = contentLength,
                            onProgress = { bytesRead, expectedBytes ->
                                downloadedResponseBytes = bytesRead
                                val downloadFraction =
                                    resolveDownloadFraction(
                                        bytesRead = bytesRead,
                                        expectedBytes = expectedBytes,
                                        maxBytes = MAX_RESPONSE_BYTES,
                                    )
                                reportProgress?.invoke(
                                    segment.percentAt(interpolatePhase(0.08, 0.72, downloadFraction)),
                                    buildTileDownloadStatus(tileLabel, bytesRead, expectedBytes),
                                )
                            },
                        )
                    } else {
                        readResponseText(connection.errorStream, MAX_ERROR_RESPONSE_BYTES).also {
                            downloadedResponseBytes = it.toByteArray(Charsets.UTF_8).size.toLong()
                        }
                    }
                } catch (error: IOException) {
                    if (cancelRequested) throw CancellationException("Cancelled by user")
                    throw error
                }
            PhoneDownloadDiagnostics.log(
                "OSM",
                "download_complete label=$requestLabel bytes=$downloadedResponseBytes contentLength=$contentLength elapsedMs=${SystemClock.elapsedRealtime() - requestStartedAtMs}",
            )

            if (code !in 200..299) {
                val durationMs = SystemClock.elapsedRealtime() - requestStartedAtMs
                PhoneDownloadDiagnostics.warn(
                    "OSM",
                    "HTTP error label=$requestLabel code=$code durationMs=$durationMs detail=${sanitizeRemoteHttpDetail(responseBody)}",
                )
                throw OverpassHttpException(
                    summarizeOsmOverpassFailure(code, responseBody),
                    code,
                )
            }

            reportProgress?.invoke(segment.percentAt(0.74), "Parsing $tileLabel…")
            val points =
                parseOverpassResponse(
                    body = responseBody,
                    tileLabel = tileLabel,
                    segment = segment,
                    reportProgress = reportProgress,
                )
            PhoneDownloadDiagnostics.log(
                "OSM",
                "parse_complete label=$requestLabel points=${points.size} elapsedMs=${SystemClock.elapsedRealtime() - requestStartedAtMs}",
            )
            val durationMs = SystemClock.elapsedRealtime() - requestStartedAtMs
            PhoneDownloadDiagnostics.log(
                "OSM",
                "Tile complete label=$requestLabel points=${points.size} responseChars=${responseBody.length} durationMs=$durationMs",
            )
            completedSuccessfully = true
            return points
        } finally {
            if (!completedSuccessfully || cancelRequested) {
                connection.disconnect()
            }
            if (activeConnection === connection) {
                activeConnection = null
            }
        }
    }

    private fun shouldRetryRequest(error: Throwable): Boolean =
        when (error) {
            is OverpassHttpException -> isRetriableOsmOverpassStatus(error.statusCode)
            is SocketTimeoutException -> true
            is IOException -> true
            else -> false
        }

    private fun shouldInspectOverpassStatus(error: Throwable): Boolean =
        when (error) {
            is OverpassHeaderWaitTimeoutException -> true
            is OverpassHttpException -> error.statusCode == 429 || error.statusCode == 504
            else -> false
        }

    private fun shouldPreferAdaptiveSplit(
        tile: BBox,
        error: Throwable,
        attempt: Int,
        busyStatus: OverpassStatusSummary?,
    ): Boolean {
        if (splitBboxForOverpassOverflow(tile).size <= 1) {
            return false
        }
        val nextSlotSeconds = busyStatus?.nextSlotSeconds
        return when (error) {
            is OverpassHeaderWaitTimeoutException -> true
            is OverpassHttpException ->
                when {
                    error.statusCode == 504 -> true
                    error.statusCode == 429 &&
                        nextSlotSeconds != null &&
                        nextSlotSeconds > MAX_STATUS_WAIT_BEFORE_SPLIT_SECONDS -> true
                    error.statusCode in 500..599 && attempt >= 2 -> true
                    else -> false
                }
            is SocketTimeoutException -> attempt >= 2
            else -> false
        }
    }

    private fun resolveRetryDelayMs(
        attempt: Int,
        busyStatus: OverpassStatusSummary?,
    ): Long {
        val backoffMs = RETRY_BACKOFF_BASE_MS * attempt.toLong()
        val suggestedWaitMs =
            busyStatus
                ?.nextSlotSeconds
                ?.coerceIn(1, MAX_STATUS_RETRY_WAIT_SECONDS)
                ?.times(1000L)
                ?.plus(500L)
                ?: 0L
        return maxOf(backoffMs, suggestedWaitMs)
    }

    private fun fetchOverpassStatusSummary(requestLabel: String): OverpassStatusSummary? {
        val startedAtMs = SystemClock.elapsedRealtime()
        return runCatching {
            val connection =
                (URL(overpassStatusEndpoint()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = STATUS_TIMEOUT_MS
                    readTimeout = STATUS_TIMEOUT_MS
                    setRequestProperty("Accept", "text/plain")
                    setRequestProperty("User-Agent", "GlanceMap-Companion")
                    setRequestProperty("Connection", "close")
                }
            try {
                val code = connection.responseCode
                val body =
                    if (code in 200..299) {
                        readResponseText(connection.inputStream, MAX_STATUS_RESPONSE_BYTES)
                    } else {
                        readResponseText(connection.errorStream, MAX_STATUS_RESPONSE_BYTES)
                    }
                val summary = parseOverpassStatusSummary(body)
                PhoneDownloadDiagnostics.log(
                    "OSM",
                    "status_check label=$requestLabel code=$code elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} summary=${summary.message}",
                )
                if (code in 200..299) summary else null
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            PhoneDownloadDiagnostics.warn(
                "OSM",
                "status_check_failed label=$requestLabel reason=${summarizeThrowable(error)}",
            )
            null
        }
    }

    private fun parseOverpassResponse(
        body: String,
        tileLabel: String,
        segment: ProgressSegment,
        reportProgress: ((percent: Int, status: String) -> Unit)?,
    ): List<PoiSqlitePoint> {
        val root =
            runCatching { JSONObject(body) }.getOrElse {
                throw IllegalArgumentException("Invalid Overpass response.")
            }
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val points = ArrayList<PoiSqlitePoint>(elements.length())
        val totalElements = elements.length().coerceAtLeast(1)
        for (i in 0 until elements.length()) {
            if (i % 64 == 0) {
                throwIfCancellationRequested()
            }
            if (i % 128 == 0) {
                val parseFraction = i.toDouble() / totalElements.toDouble()
                reportProgress?.invoke(
                    segment.percentAt(interpolatePhase(0.74, 0.96, parseFraction)),
                    buildTileParseStatus(tileLabel, i, totalElements),
                )
            }
            val element = elements.optJSONObject(i) ?: continue
            val tagsObj = element.optJSONObject("tags") ?: continue
            val tags = extractTags(tagsObj)
            if (tags.isEmpty()) continue

            val latLon = resolveLatLon(element) ?: continue
            val mapped = classify(tags) ?: continue

            val elementType = element.optString("type", "").trim()
            val elementId = element.optLong("id", -1L).takeIf { it > 0L }
            points +=
                PoiSqlitePoint(
                    sourceId = elementId,
                    lat = latLon.first,
                    lon = latLon.second,
                    categoryName = mapped,
                    tags =
                        buildOutputTags(
                            rawTags = tags,
                            categoryName = mapped,
                            elementType = elementType,
                            elementId = elementId,
                        ),
                )
        }

        reportProgress?.invoke(
            segment.percentAt(0.96),
            buildTileParseStatus(tileLabel, elements.length(), totalElements),
        )
        return points
    }

    private fun resolveLatLon(element: JSONObject): Pair<Double, Double>? {
        val directLat = element.optDouble("lat", Double.NaN)
        val directLon = element.optDouble("lon", Double.NaN)
        if (directLat.isFinite() && directLon.isFinite()) {
            return directLat to directLon
        }

        val center = element.optJSONObject("center")
        val centerLat = center?.optDouble("lat", Double.NaN) ?: Double.NaN
        val centerLon = center?.optDouble("lon", Double.NaN) ?: Double.NaN
        if (centerLat.isFinite() && centerLon.isFinite()) {
            return centerLat to centerLon
        }

        return null
    }

    private fun extractTags(tagsObj: JSONObject): Map<String, String> {
        val tags = linkedMapOf<String, String>()
        val iter = tagsObj.keys()
        while (iter.hasNext()) {
            val key = iter.next().orEmpty().trim()
            if (key.isBlank()) continue
            val value = tagsObj.optString(key, "").trim()
            if (value.isBlank()) continue
            tags[key] = value
        }
        return tags
    }

    private fun classify(tags: Map<String, String>): String? {
        val tourism = tags["tourism"]?.lowercase(Locale.ROOT)
        val amenity = tags["amenity"]?.lowercase(Locale.ROOT)
        val natural = tags["natural"]?.lowercase(Locale.ROOT)

        if (tourism in setOf("alpine_hut", "wilderness_hut", "hut", "hostel", "guest_house")) {
            return "Alpine Huts"
        }
        if (tourism in setOf("camp_site", "caravan_site")) return "Camping"
        if (tourism == "viewpoint") return "Viewpoints"

        if (amenity in setOf("drinking_water", "water_point", "fountain")) return "Water"
        if (amenity == "shelter") return "Alpine Huts"
        if (amenity == "toilets") return "Toilets"
        if (amenity in setOf("restaurant", "cafe", "fast_food", "bar", "pub")) return "Food"
        if (amenity in setOf("parking", "parking_space")) return "Parking"

        if (natural in setOf("peak", "volcano")) return "Peaks"
        if (natural in setOf("spring", "waterfall")) return "Water"

        return null
    }

    private fun buildOutputTags(
        rawTags: Map<String, String>,
        categoryName: String,
        elementType: String,
        elementId: Long?,
    ): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val name = rawTags["name"]?.trim().orEmpty()
        out["name"] = if (name.isNotBlank()) name else "OSM $categoryName"

        val keyWhitelist =
            listOf(
                "amenity",
                "tourism",
                "natural",
                "highway",
                "public_transport",
                "railway",
                "leisure",
                "place",
                "historic",
                "man_made",
                "shop",
                "ele",
                "capacity",
            )
        keyWhitelist.forEach { key ->
            rawTags[key]?.trim()?.takeIf { it.isNotBlank() }?.let { out[key] = it }
        }

        val website =
            listOf("website", "contact:website", "url")
                .asSequence()
                .mapNotNull { rawTags[it]?.trim() }
                .firstOrNull { it.isNotBlank() }
        if (!website.isNullOrBlank()) {
            out["website"] = website
        }

        rawTags["description"]?.trim()?.takeIf { it.isNotBlank() }?.let {
            out["description"] = it.take(420)
        }

        out["source"] = "openstreetmap"
        if (!elementType.isBlank() && elementId != null) {
            out["osm:id"] = "$elementType/$elementId"
        }

        return out
    }

    private fun buildOverpassQuery(
        bbox: BBox,
        selectedCategories: List<OsmPoiImportCategory>,
    ): String {
        val clauses = buildOverpassSelectionClauses(bbox, selectedCategories)
        return """
            [out:json][timeout:$OVERPASS_TIMEOUT_SECONDS][maxsize:$OVERPASS_MAXSIZE_BYTES];
            (
            $clauses
            );
            out center tags qt;
            """.trimIndent()
    }

    private fun buildOverpassCountQuery(
        bbox: BBox,
        selectedCategories: List<OsmPoiImportCategory>,
    ): String {
        val clauses = buildOverpassSelectionClauses(bbox, selectedCategories)
        return """
            [out:json][timeout:$OVERPASS_COUNT_TIMEOUT_SECONDS][maxsize:$OVERPASS_MAXSIZE_BYTES];
            (
            $clauses
            );
            out count;
            """.trimIndent()
    }

    private fun parseBbox(input: String): BBox {
        val normalized = input.trim()
        val parts =
            normalized
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        if (parts.size != 4) {
            throw IllegalArgumentException("BBox must be: minLon,minLat,maxLon,maxLat")
        }
        val minLon =
            parts[0].toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid minLon in bbox.")
        val minLat =
            parts[1].toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid minLat in bbox.")
        val maxLon =
            parts[2].toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid maxLon in bbox.")
        val maxLat =
            parts[3].toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid maxLat in bbox.")
        if (minLon >= maxLon || minLat >= maxLat) {
            throw IllegalArgumentException("BBox min values must be smaller than max values.")
        }
        if (minLon < -180.0 || maxLon > 180.0 || minLat < -90.0 || maxLat > 90.0) {
            throw IllegalArgumentException("BBox coordinates are out of valid range.")
        }

        val lonSpan = maxLon - minLon
        val latSpan = maxLat - minLat
        val area = lonSpan * latSpan
        if (
            lonSpan > MAX_LON_SPAN_DEGREES ||
            latSpan > MAX_LAT_SPAN_DEGREES ||
            area > MAX_BBOX_AREA_DEGREES
        ) {
            throw IllegalArgumentException(
                "Selected area is too large. Please choose a smaller region.",
            )
        }

        return BBox(minLon = minLon, minLat = minLat, maxLon = maxLon, maxLat = maxLat)
    }

    private fun normalizeFileName(input: String): String {
        val base =
            input
                .trim()
                .ifBlank { "osm-overpass.poi" }
                .replace("\\", "_")
                .replace("/", "_")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('_')
                .ifBlank { "osm-overpass.poi" }
        return if (base.lowercase(Locale.ROOT).endsWith(".poi")) base else "$base.poi"
    }

    private fun readResponseText(
        stream: InputStream?,
        maxBytes: Int,
        contentLength: Long = -1L,
        onProgress: ((bytesRead: Long, expectedBytes: Long) -> Unit)? = null,
    ): String {
        if (stream == null) return ""
        stream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val out = ByteArray(maxBytes.coerceAtLeast(1))
            var total = 0
            var lastReportedTotal = 0
            try {
                while (true) {
                    throwIfCancellationRequested()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (total + read > maxBytes) {
                        throw IllegalStateException(OSM_OVERPASS_TOO_LARGE_MESSAGE)
                    }
                    System.arraycopy(buffer, 0, out, total, read)
                    total += read
                    if (onProgress != null && (total - lastReportedTotal >= 128 * 1024 || total == contentLength.toInt())) {
                        onProgress(total.toLong(), contentLength)
                        lastReportedTotal = total
                    }
                }
            } catch (error: IOException) {
                if (cancelRequested) throw CancellationException("Cancelled by user")
                throw error
            }
            onProgress?.invoke(total.toLong(), contentLength)
            return String(out, 0, total, Charsets.UTF_8)
        }
    }

    private fun shouldAdaptiveSplitOverpassFailure(error: Throwable): Boolean {
        if (isOverpassResponseTooLarge(error)) {
            return true
        }

        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is CancellationException -> return false
                is SocketTimeoutException -> return true
                is OverpassHttpException -> {
                    if (current.statusCode == 408 || current.statusCode == 429 || current.statusCode in 500..599) {
                        return true
                    }
                }
            }
            current = current.cause
        }
        return false
    }

    private fun buildAdaptiveSplitFailureMessage(areaSuffix: String): String =
        "OSM Overpass area is still too large or timing out after smaller requests. " +
            "Please try again later, use a smaller area, or select fewer POI categories.$areaSuffix"

    private fun buildTileLabel(
        tileIndex: Int,
        totalTiles: Int,
    ): String =
        if (totalTiles > 1) {
            "OpenStreetMap area $tileIndex/$totalTiles"
        } else {
            "OpenStreetMap"
        }

    private fun buildTileRetryStatus(
        tileLabel: String,
        attempt: Int,
    ): String = "Retrying $tileLabel ($attempt/$MAX_REQUEST_ATTEMPTS)…"

    private fun buildTileBusyRetryStatus(
        tileLabel: String,
        attempt: Int,
        busyStatus: OverpassStatusSummary?,
    ): String {
        val detail =
            when {
                busyStatus?.nextSlotSeconds != null ->
                    "waiting about ${busyStatus.nextSlotSeconds}s for a free slot"
                busyStatus?.slotsAvailable != null && busyStatus.slotsAvailable > 0 ->
                    "retrying while a slot is available"
                !busyStatus?.message.isNullOrBlank() ->
                    "server busy (${busyStatus.message})"
                else -> "server busy"
            }
        return "OpenStreetMap $detail for $tileLabel ($attempt/$MAX_REQUEST_ATTEMPTS)…"
    }

    private fun buildTileSplitStatus(tileLabel: String): String = "Trying smaller requests for $tileLabel…"

    private fun buildTileDownloadStatus(
        tileLabel: String,
        bytesRead: Long,
        expectedBytes: Long,
    ): String {
        val received = formatByteCount(bytesRead)
        return if (expectedBytes > 0L) {
            "Downloading $tileLabel… $received / ${formatByteCount(expectedBytes)}"
        } else {
            "Downloading $tileLabel… $received"
        }
    }

    private fun buildTileParseStatus(
        tileLabel: String,
        processed: Int,
        total: Int,
    ): String =
        if (total > 0) {
            "Parsing $tileLabel… ${formatCount(processed)}/${formatCount(total)}"
        } else {
            "Parsing $tileLabel…"
        }

    private fun buildTileCompletedStatus(
        tileIndex: Int,
        totalTiles: Int,
        importedCount: Int,
    ): String =
        if (totalTiles > 1) {
            "Imported OpenStreetMap area $tileIndex/$totalTiles… ${formatCount(importedCount)} POI so far"
        } else {
            "OpenStreetMap data received… ${formatCount(importedCount)} POI"
        }

    private fun buildPoiCountTooLargeMessage(total: Long): String =
        "About ${formatCount(total)} POIs found. " +
            "Too many, choose a smaller area or select fewer POI categories."

    private fun buildPoiCountPreflightMessage(total: Long): String {
        val sizeLabel =
            if (total > OSM_OVERPASS_PREFLIGHT_WARNING_POI_COUNT) {
                "Large import, trying anyway."
            } else {
                "Importing now."
            }
        return "About ${formatCount(total)} POIs found. $sizeLabel"
    }

    private fun resolveDownloadFraction(
        bytesRead: Long,
        expectedBytes: Long,
        maxBytes: Int,
    ): Double {
        if (bytesRead <= 0L) return 0.0
        if (expectedBytes > 0L) {
            return (bytesRead.toDouble() / expectedBytes.toDouble()).coerceIn(0.0, 1.0)
        }
        val safeMax = maxBytes.toDouble().coerceAtLeast(1.0)
        return (ln(bytesRead.toDouble() + 1.0) / ln(safeMax + 1.0)).coerceIn(0.0, 1.0)
    }

    private fun interpolatePhase(
        start: Double,
        end: Double,
        fraction: Double,
    ): Double {
        val clamped = fraction.coerceIn(0.0, 1.0)
        return start + ((end - start) * clamped)
    }

    private fun formatByteCount(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kib = bytes / 1024.0
        if (kib < 1024.0) {
            return String.format(Locale.US, "%.1f KB", kib)
        }
        val mib = kib / 1024.0
        return String.format(Locale.US, "%.1f MB", mib)
    }

    private fun formatCount(value: Int): String = formatCount(value.toLong())

    private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

    private fun throwIfCancellationRequested() {
        if (cancelRequested) {
            throw CancellationException("Cancelled by user")
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun overpassStatusEndpoint(): String = API_ENDPOINT.substringBeforeLast('/') + "/status"

    private fun summarizeThrowable(error: Throwable): String =
        error.localizedMessage
            ?.trim()
            .orEmpty()
            .ifBlank { error::class.java.simpleName }

    private fun BBox.asRefugesQueryParam(): String = "$minLon,$minLat,$maxLon,$maxLat"

    private class OverpassHttpException(
        message: String,
        val statusCode: Int,
    ) : IOException(message)

    private class OverpassHeaderWaitTimeoutException(
        message: String,
        cause: Throwable,
    ) : SocketTimeoutException(message) {
        init {
            initCause(cause)
        }
    }
}

private data class ProgressSegment(
    val start: Double,
    val end: Double,
) {
    fun percentAt(fraction: Double): Int {
        val clamped = fraction.coerceIn(0.0, 1.0)
        return (start + ((end - start) * clamped)).roundToInt().coerceIn(0, 100)
    }

    fun split(
        index: Int,
        count: Int,
    ): ProgressSegment {
        val safeCount = count.coerceAtLeast(1)
        val span = end - start
        val childStart = start + (span * index.toDouble() / safeCount.toDouble())
        val childEnd = start + (span * (index + 1).toDouble() / safeCount.toDouble())
        return ProgressSegment(start = childStart, end = childEnd)
    }
}
