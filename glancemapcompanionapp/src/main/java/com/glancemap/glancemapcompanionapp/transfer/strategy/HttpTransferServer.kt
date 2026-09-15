package com.glancemap.glancemapcompanionapp.transfer.strategy

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneTransferDiagnostics
import com.glancemap.glancemapcompanionapp.transfer.datalayer.DataLayerPaths
import com.glancemap.glancemapcompanionapp.transfer.util.TransferUtils
import com.glancemap.shared.transfer.TransferDataLayerContract
import com.google.android.gms.wearable.Wearable
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

class HttpTransferServer :
    TransferStrategy,
    AutoCloseable {
    companion object {
        private const val TAG = "HttpTransferServer"
        const val RESULT_HTTP_PAUSED_PREFIX = "HTTP_PAUSED:"
        const val RESULT_HTTP_STALLED_PREFIX = "HTTP_STALLED_RETRY:"
        const val RESULT_HTTP_SLOW_PREFIX = "HTTP_SLOW_RETRY:"
        const val RESULT_HTTP_RECONNECT_TIMEOUT_PREFIX = "HTTP_RECONNECT_TIMEOUT:"
        const val RESULT_HTTP_NO_FIRST_REQUEST_PREFIX = "HTTP_NO_REQUEST:"

        private const val DEFAULT_BUFFER_SIZE = 2 * 1024 * 1024
        private const val MAP_BUFFER_SIZE = 4 * 1024 * 1024
        private const val ACK_TIMEOUT_MS = 45 * 60 * 1000L
        internal const val FIRST_REQUEST_TIMEOUT_MS = 45_000L

        private const val SERVER_READY_TIMEOUT_MS = 5_000L
        private const val SERVER_READY_POLL_DELAY_MS = 100L

        // progress throttling (server-side)
        private const val PROGRESS_MIN_INTERVAL_MS = 1_000L
        private const val SPEED_WARMUP_MIN_MS = 1_000L
        private const val HTTP_STALL_TIMEOUT_MS = 30_000L
        private const val HTTP_STALL_TIMEOUT_LARGE_FILE_MS = 45_000L
        private const val HTTP_STALL_TIMEOUT_RESUMED_LARGE_FILE_MS = 60_000L
        private const val HTTP_STALL_TIMEOUT_LATE_RESUMED_MS = 90_000L
        private const val HTTP_STALL_TIMEOUT_TAIL_RESUMED_MS = 120_000L
        private const val HTTP_STALL_TIMEOUT_LARGE_FILE_BYTES = 256L * 1024L * 1024L
        private const val HTTP_STALL_TIMEOUT_LATE_RESUMED_BYTES = 384L * 1024L * 1024L
        private const val HTTP_STALL_TIMEOUT_TAIL_BYTES = 64L * 1024L * 1024L
        private const val HTTP_STALL_CHECK_MS = 1_000L
        private const val HTTP_SLOW_PROGRESS_WINDOW_MS = 90_000L
        private const val HTTP_SLOW_PROGRESS_MIN_BYTES = 12L * 1024L * 1024L
        private const val HTTP_SLOW_PROGRESS_START_AFTER_BYTES = 128L * 1024L * 1024L
        private const val HTTP_SLOW_PROGRESS_TAIL_BYTES = 8L * 1024L * 1024L
        private const val HTTP_MILESTONE_LOG_STEP_BYTES = 32L * 1024L * 1024L
        private const val HTTP_MILESTONE_LOG_MIN_FILE_BYTES = 128L * 1024L * 1024L
    }

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var assignedPort: Int? = null

    @Volatile
    private var activeProbeRequest: CompletableDeferred<Unit>? = null

    @Volatile
    private var activeProbeDownload: ActiveDownload? = null

    private val activeDownloads = ConcurrentHashMap<String, ActiveDownload>()

    override suspend fun transfer(
        context: Context,
        fileUri: android.net.Uri,
        targetNodeId: String,
        metadata: TransferMetadata,
        ackDeferred: CompletableDeferred<TransferResult>,
        awaitIfPaused: suspend () -> Unit,
        onProgress: (Float, String) -> Unit,
    ): TransferResult =
        withContext(Dispatchers.IO) {
            val totalStartMs = SystemClock.elapsedRealtime()

            val ipAddress = TransferUtils.getWifiIpAddress(context)
            Log.d(TAG, "Phone Wi-Fi IP resolved")
            PhoneTransferDiagnostics.log(
                "Http",
                "Transfer start ${metadata.diagnosticContext()} file=${metadata.displayFileName} " +
                    "size=${metadata.totalSize} interfaceCategory=wifi addressFamily=ipv4",
            )

            if (ipAddress.isNullOrBlank()) {
                PhoneTransferDiagnostics.warn(
                    "Http",
                    "Wi-Fi unavailable for file=${metadata.displayFileName}",
                )
                return@withContext TransferResult(
                    false,
                    "Wi-Fi required. Please connect phone and watch to the same Wi-Fi.",
                )
            }

            val routeFileName = metadata.safeFileName
            val fileSize = metadata.totalSize
            val downloadPath = "/download/$routeFileName"
            val transferToken = generateTransferToken()
            val firstRequest = CompletableDeferred<Unit>()
            val pauseBlocked = AtomicBoolean(false)
            val interruptionDeferred = CompletableDeferred<TransferResult>()
            val activeDownload =
                ActiveDownload(
                    context = context.applicationContext,
                    fileUri = fileUri,
                    metadata = metadata,
                    transferToken = transferToken,
                    firstRequest = firstRequest,
                    startedAtElapsedMs = totalStartMs,
                    interruptionDeferred = interruptionDeferred,
                    awaitIfPaused = {
                        pauseBlocked.set(true)
                        try {
                            awaitIfPaused()
                        } finally {
                            pauseBlocked.set(false)
                        }
                    },
                    isAwaitingPause = { pauseBlocked.get() },
                    onProgress = onProgress,
                )

            try {
                val serverStartMs = SystemClock.elapsedRealtime()
                val port = ensureServerStarted(ipAddress)
                val serverReadyMs = SystemClock.elapsedRealtime() - serverStartMs
                activeDownloads[downloadPath] = activeDownload
                activeProbeRequest = firstRequest
                activeProbeDownload = activeDownload

                Log.d(TAG, "✅ HTTP server ready port=$port")
                PhoneTransferDiagnostics.log(
                    "Http",
                    "event=http_server_ready ${metadata.diagnosticContext()} " +
                        "file=${metadata.displayFileName} port=$port",
                )

                val json =
                    JSONObject().apply {
                        put("id", metadata.transferId)
                        put("protocol", "http")
                        put("ip", ipAddress)
                        put("port", port)
                        put("name", metadata.safeFileName)
                        put("size", fileSize)
                        put("path", downloadPath)
                        put(TransferDataLayerContract.HTTP_AUTH_TOKEN_JSON_KEY, transferToken)
                        metadata.checksumSha256?.let { put("sha256", it) }
                        put("v", 2)
                    }

                Log.d(
                    TAG,
                    "Sending START_WIFI_TRANSFER id=${metadata.transferId} file=${metadata.displayFileName} " +
                        "port=$port size=$fileSize",
                )
                PhoneTransferDiagnostics.log(
                    "Http",
                    "event=start_wifi_transfer_sent ${metadata.diagnosticContext()} " +
                        "file=${metadata.displayFileName} port=$port",
                )
                Wearable
                    .getMessageClient(context)
                    .sendMessage(targetNodeId, DataLayerPaths.PATH_START_WIFI_TRANSFER, json.toString().toByteArray())
                    .await()

                onProgress(0f, "Waiting for watch to download (HTTP)…")

                val firstRequestStartMs = SystemClock.elapsedRealtime()
                PhoneTransferDiagnostics.log(
                    "Http",
                    "event=http_wait_first_request " +
                        "transferId=${metadata.transferId} file=${metadata.displayFileName} " +
                        "size=${metadata.totalSize} timeoutMs=$FIRST_REQUEST_TIMEOUT_MS",
                )
                var ackBeforeFirstRequest: TransferResult? = null
                val firstRequestHit =
                    withTimeoutOrNull(FIRST_REQUEST_TIMEOUT_MS) {
                        select<Boolean> {
                            firstRequest.onAwait {
                                true
                            }
                            ackDeferred.onAwait { ackResult ->
                                ackBeforeFirstRequest = ackResult
                                false
                            }
                            interruptionDeferred.onAwait { controlResult ->
                                ackBeforeFirstRequest = controlResult
                                false
                            }
                        }
                    }
                val firstRequestMs = SystemClock.elapsedRealtime() - firstRequestStartMs
                if (firstRequestHit == null) {
                    val detail =
                        "Watch did not connect to phone HTTP server within ${FIRST_REQUEST_TIMEOUT_MS / 1000}s. " +
                            "Check watch Wi-Fi or phone hotspot."
                    Log.w(
                        TAG,
                        "Watch did not hit server within ${FIRST_REQUEST_TIMEOUT_MS / 1000}s " +
                            "transferId=${metadata.transferId} file=${metadata.displayFileName} port=$port",
                    )
                    PhoneTransferDiagnostics.warn(
                        "Http",
                        "event=http_no_request_timeout ${metadata.diagnosticContext()} " +
                            "elapsedMs=$firstRequestMs timeoutMs=$FIRST_REQUEST_TIMEOUT_MS " +
                            "file=${metadata.displayFileName}",
                    )
                    return@withContext TransferResult(
                        success = false,
                        message = "$RESULT_HTTP_NO_FIRST_REQUEST_PREFIX detail=$detail",
                    )
                }

                if (ackBeforeFirstRequest != null) {
                    Log.w(
                        TAG,
                        "Watch finished HTTP attempt before first request file=${metadata.displayFileName} " +
                            "result=${ackBeforeFirstRequest.message}",
                    )
                    PhoneTransferDiagnostics.warn(
                        "Http",
                        "Early HTTP result ${metadata.diagnosticContext()} file=${metadata.displayFileName} " +
                            "firstRequest=${firstRequestMs}ms result=${ackBeforeFirstRequest.message}",
                    )
                    return@withContext ackBeforeFirstRequest
                }

                PhoneTransferDiagnostics.log(
                    "Http",
                    "event=http_first_signal_received ${metadata.diagnosticContext()} " +
                        "elapsedMs=$firstRequestMs file=${metadata.displayFileName} size=${metadata.totalSize}",
                )

                val ackStartMs = SystemClock.elapsedRealtime()
                val result =
                    withTimeoutOrNull(ACK_TIMEOUT_MS) {
                        select<TransferResult> {
                            ackDeferred.onAwait { it }
                            interruptionDeferred.onAwait { it }
                        }
                    }
                        ?: TransferResult(false, "Transfer timed out")
                val ackWaitMs = SystemClock.elapsedRealtime() - ackStartMs
                Log.d(
                    TAG,
                    "HTTP metrics file=${metadata.displayFileName} serverReady=${serverReadyMs}ms " +
                        "firstRequest=${firstRequestMs}ms ack=${ackWaitMs}ms " +
                        "total=${SystemClock.elapsedRealtime() - totalStartMs}ms",
                )
                PhoneTransferDiagnostics.log(
                    "Http",
                    "event=http_metrics ${metadata.diagnosticContext()} file=${metadata.displayFileName} " +
                        "serverReadyMs=$serverReadyMs firstSignalMs=$firstRequestMs ackWaitMs=$ackWaitMs " +
                        "totalMs=${SystemClock.elapsedRealtime() - totalStartMs} result=${result.message}",
                )

                return@withContext result
            } catch (e: Exception) {
                Log.e(TAG, "Server Error", e)
                PhoneTransferDiagnostics.error(
                    "Http",
                    "Server error file=${metadata.displayFileName}",
                    e,
                )
                return@withContext TransferResult(false, "Server Error: ${e.message}")
            } finally {
                activeDownloads.remove(downloadPath)
                if (activeProbeRequest === firstRequest) {
                    activeProbeRequest = null
                }
                if (activeProbeDownload === activeDownload) {
                    activeProbeDownload = null
                }
            }
        }

    override fun close() {
        activeDownloads.clear()
        activeProbeRequest = null
        activeProbeDownload = null
        runCatching { server?.stop(1000, 2000) }
        server = null
        assignedPort = null
    }

    fun requestPause(transferId: String): Boolean {
        val active =
            activeDownloads.values.firstOrNull {
                it.metadata.transferId == transferId
            } ?: return false

        PhoneTransferDiagnostics.warn(
            "Http",
            "Manual pause requested file=${active.metadata.displayFileName} id=$transferId",
        )
        active.manualPauseRequested.set(true)
        if (!active.interruptionDeferred.isCompleted) {
            active.interruptionDeferred.complete(
                TransferResult(
                    success = false,
                    message = "$RESULT_HTTP_PAUSED_PREFIX file=${active.metadata.displayFileName}",
                ),
            )
        }
        active.abortRequest?.invoke()
        return true
    }

    private suspend fun ensureServerStarted(ipAddress: String): Int {
        assignedPort?.let { existingPort ->
            waitUntilPortOpen(ipAddress, existingPort)
            return existingPort
        }

        val newServer =
            embeddedServer(CIO, port = 0, host = "0.0.0.0") {
                routing {
                    get("/download/{name}") {
                        val requestPath = call.request.path()
                        val active = activeDownloads[requestPath]
                        if (active == null) {
                            call.respondText("Not Found", status = HttpStatusCode.NotFound)
                            return@get
                        }

                        handleDownload(call = call, active = active)
                    }

                    get("/") {
                        activeProbeRequest?.let { probe ->
                            if (!probe.isCompleted) probe.complete(Unit)
                        }
                        activeProbeDownload?.let { active ->
                            PhoneTransferDiagnostics.log(
                                "Http",
                                "event=http_probe_received ${active.metadata.diagnosticContext()} " +
                                    "requestType=probe " +
                                    "elapsedMs=${SystemClock.elapsedRealtime() - active.startedAtElapsedMs}",
                            )
                        }
                        call.respondText("OK")
                    }
                }
            }

        newServer.start(wait = false)

        val connectors = newServer.engine.resolvedConnectors()
        val connector =
            connectors.firstOrNull()
                ?: throw IllegalStateException("Failed to bind server port")
        val port = connector.port

        Log.d(TAG, "HTTP connectors=${connectors.size} port=$port")
        PhoneTransferDiagnostics.log("Http", "Server connectors=${connectors.size} port=$port")

        server = newServer
        assignedPort = port
        waitUntilPortOpen(ipAddress, port)
        return port
    }

    private suspend fun handleDownload(
        call: io.ktor.server.application.ApplicationCall,
        active: ActiveDownload,
    ) {
        val providedToken = call.request.headers[TransferDataLayerContract.HTTP_AUTH_HEADER]
        if (providedToken != active.transferToken) {
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            return
        }

        val fileRequestElapsedMs = SystemClock.elapsedRealtime() - active.startedAtElapsedMs
        if (!active.firstRequest.isCompleted) active.firstRequest.complete(Unit)
        PhoneTransferDiagnostics.log(
            "Http",
            "event=http_file_request_received ${active.metadata.diagnosticContext()} " +
                "requestType=file elapsedMs=$fileRequestElapsedMs",
        )

        val displayFileName = active.metadata.displayFileName
        val fileSize = active.metadata.totalSize
        val ioBufferSize = if (active.metadata.isMapFile) MAP_BUFFER_SIZE else DEFAULT_BUFFER_SIZE
        val stepBytes: Long = if (active.metadata.isMapFile) 8L * 1024 * 1024 else 1L * 1024 * 1024

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, displayFileName)
                .toString(),
        )
        call.response.header(HttpHeaders.AcceptRanges, "bytes")

        val rangeHeader = call.request.headers[HttpHeaders.Range]
        val range = parseRange(rangeHeader, fileSize)
        if (rangeHeader != null && range == null && fileSize > 0L) {
            call.respondText(
                "Invalid Range",
                status = HttpStatusCode.RequestedRangeNotSatisfiable,
            )
            return
        }

        val startOffset = range?.first ?: 0L
        val endOffset = range?.second ?: (fileSize - 1L)
        val hasKnownLength = fileSize > 0L
        val stallTimeoutMs =
            computeHttpStallTimeoutMs(
                totalSize = fileSize,
                resumeOffset = startOffset,
                isMapFile = active.metadata.isMapFile,
            )
        Log.d(TAG, "Incoming HTTP request rangeStart=$startOffset")
        PhoneTransferDiagnostics.log(
            "Http",
            "Incoming request ${active.metadata.diagnosticContext()} file=${active.metadata.displayFileName} " +
                "rangeStart=$startOffset stallTimeoutMs=$stallTimeoutMs",
        )
        if (startOffset > 0L) {
            PhoneTransferDiagnostics.log(
                "Http",
                "Resuming partial ${active.metadata.diagnosticContext()} file=${active.metadata.displayFileName} " +
                    "from=${formatBytes(startOffset)} stallTimeoutMs=$stallTimeoutMs",
            )
        }
        val responseStatus =
            if (range != null) {
                call.response.header(
                    HttpHeaders.ContentRange,
                    "bytes $startOffset-$endOffset/$fileSize",
                )
                HttpStatusCode.PartialContent
            } else {
                HttpStatusCode.OK
            }

        val responseLength =
            if (hasKnownLength) {
                if (range != null) (endOffset - startOffset + 1L) else fileSize
            } else {
                null
            }

        var sourceReadMs = 0L
        var responseWriteMs = 0L
        var rangeSeekOrSkipMs = 0L
        call.respondOutputStream(
            contentType = ContentType.Application.OctetStream,
            status = responseStatus,
            contentLength = responseLength,
        ) {
            val responseStream = this
            val input =
                active.context.contentResolver.openInputStream(active.fileUri)
                    ?: throw IllegalStateException("Cannot open input stream")

            input.use { raw ->
                coroutineScope {
                    val inp = BufferedInputStream(raw, ioBufferSize)
                    val buffer = ByteArray(ioBufferSize)
                    val requestJob = currentCoroutineContext()[Job]

                    if (startOffset > 0L) {
                        val skipStartMs = SystemClock.elapsedRealtime()
                        skipFully(inp, startOffset)
                        rangeSeekOrSkipMs += SystemClock.elapsedRealtime() - skipStartMs
                    }

                    active.abortRequest = {
                        runCatching { responseStream.close() }
                        requestJob?.cancel(
                            CancellationException(
                                "HTTP request aborted for $displayFileName",
                            ),
                        )
                    }

                    var sent = startOffset
                    var remaining =
                        if (range != null) {
                            (endOffset - startOffset + 1L).coerceAtLeast(0L)
                        } else {
                            Long.MAX_VALUE
                        }

                    var lastUiMs = 0L
                    var lastUiBytes = sent
                    var lastSpeedMs = SystemClock.elapsedRealtime()
                    var lastSpeedBytes = sent
                    val sentBytes = AtomicLong(sent)
                    val lastProgressAt = AtomicLong(SystemClock.elapsedRealtime())
                    val slowProgressStartBytes = AtomicLong(sent)
                    val slowProgressStartMs = AtomicLong(SystemClock.elapsedRealtime())
                    val stallReported = AtomicBoolean(false)
                    val milestoneLoggingEnabled = fileSize >= HTTP_MILESTONE_LOG_MIN_FILE_BYTES
                    var nextMilestoneBytes =
                        if (milestoneLoggingEnabled) {
                            nextMilestoneAfter(sent)
                        } else {
                            Long.MAX_VALUE
                        }

                    if (fileSize > 0L) {
                        val progress =
                            calculateHttpProgressFraction(
                                resumeOffsetBytes = startOffset,
                                bytesTransferredThisAttempt = (sent - startOffset).coerceAtLeast(0L),
                                fullFileSizeBytes = fileSize,
                            )
                        val completedBytes =
                            calculateCumulativeHttpProgressBytes(
                                resumeOffsetBytes = startOffset,
                                bytesTransferredThisAttempt = (sent - startOffset).coerceAtLeast(0L),
                                fullFileSizeBytes = fileSize,
                            )
                        active.onProgress(
                            progress,
                            formatActiveTransferText(
                                completedBytes = completedBytes,
                                totalSize = fileSize,
                                speedMiBps = null,
                            ),
                        )
                    } else {
                        active.onProgress(
                            0f,
                            formatActiveTransferText(
                                completedBytes = 0L,
                                totalSize = 0L,
                                speedMiBps = null,
                            ),
                        )
                    }

                    val stallWatchdog =
                        launch(Dispatchers.Default) {
                            while (isActive) {
                                delay(HTTP_STALL_CHECK_MS)
                                val now = SystemClock.elapsedRealtime()
                                val currentSent = sentBytes.get()
                                if (active.isAwaitingPause()) {
                                    slowProgressStartBytes.set(currentSent)
                                    slowProgressStartMs.set(now)
                                    continue
                                }

                                val idleMs = now - lastProgressAt.get()
                                if (idleMs < stallTimeoutMs) continue
                                if (!stallReported.compareAndSet(false, true)) continue

                                val currentCompletedBytes =
                                    calculateCumulativeHttpProgressBytes(
                                        resumeOffsetBytes = startOffset,
                                        bytesTransferredThisAttempt =
                                            (currentSent - startOffset).coerceAtLeast(0L),
                                        fullFileSizeBytes = fileSize,
                                    )
                                val progress =
                                    calculateHttpProgressFraction(
                                        resumeOffsetBytes = startOffset,
                                        bytesTransferredThisAttempt =
                                            (currentSent - startOffset).coerceAtLeast(0L),
                                        fullFileSizeBytes = fileSize,
                                    )
                                val waitText = buildReconnectWaitText(currentCompletedBytes, fileSize)
                                Log.w(
                                    TAG,
                                    "HTTP stall detected file=$displayFileName idle=${idleMs}ms timeout=${stallTimeoutMs}ms sent=$currentSent",
                                )
                                PhoneTransferDiagnostics.warn(
                                    "Http",
                                    "Stall detected ${active.metadata.diagnosticContext()} file=$displayFileName " +
                                        "idle=${idleMs}ms timeoutMs=$stallTimeoutMs sent=$currentSent",
                                )
                                active.onProgress(progress, waitText)
                                if (!active.interruptionDeferred.isCompleted) {
                                    active.interruptionDeferred.complete(
                                        TransferResult(
                                            success = false,
                                            message = "$RESULT_HTTP_STALLED_PREFIX file=$displayFileName idleMs=$idleMs timeoutMs=$stallTimeoutMs sent=$currentSent",
                                        ),
                                    )
                                }
                                PhoneTransferDiagnostics.warn(
                                    "Http",
                                    "Cancelling blocked HTTP request file=$displayFileName after stall",
                                )
                                runCatching { responseStream.close() }
                                requestJob?.cancel(
                                    CancellationException(
                                        "HTTP stalled for ${idleMs}ms while sending $displayFileName",
                                    ),
                                )
                                return@launch
                            }
                        }

                    val slowProgressWatchdog =
                        launch(Dispatchers.Default) {
                            while (isActive) {
                                delay(HTTP_STALL_CHECK_MS)
                                if (stallReported.get()) return@launch

                                val now = SystemClock.elapsedRealtime()
                                val currentSent = sentBytes.get()
                                if (active.isAwaitingPause()) {
                                    slowProgressStartBytes.set(currentSent)
                                    slowProgressStartMs.set(now)
                                    continue
                                }
                                if (fileSize < HTTP_MILESTONE_LOG_MIN_FILE_BYTES) continue
                                if (currentSent < HTTP_SLOW_PROGRESS_START_AFTER_BYTES) {
                                    slowProgressStartBytes.set(currentSent)
                                    slowProgressStartMs.set(now)
                                    continue
                                }
                                if (fileSize > 0L && (fileSize - currentSent) <= HTTP_SLOW_PROGRESS_TAIL_BYTES) {
                                    slowProgressStartBytes.set(currentSent)
                                    slowProgressStartMs.set(now)
                                    continue
                                }

                                val windowStartBytes = slowProgressStartBytes.get()
                                val progressBytes = (currentSent - windowStartBytes).coerceAtLeast(0L)
                                val windowMs = now - slowProgressStartMs.get()

                                if (progressBytes >= HTTP_SLOW_PROGRESS_MIN_BYTES) {
                                    slowProgressStartBytes.set(currentSent)
                                    slowProgressStartMs.set(now)
                                    continue
                                }
                                if (windowMs < HTTP_SLOW_PROGRESS_WINDOW_MS) continue
                                if (!stallReported.compareAndSet(false, true)) return@launch

                                val currentCompletedBytes =
                                    calculateCumulativeHttpProgressBytes(
                                        resumeOffsetBytes = startOffset,
                                        bytesTransferredThisAttempt =
                                            (currentSent - startOffset).coerceAtLeast(0L),
                                        fullFileSizeBytes = fileSize,
                                    )
                                val progress =
                                    calculateHttpProgressFraction(
                                        resumeOffsetBytes = startOffset,
                                        bytesTransferredThisAttempt =
                                            (currentSent - startOffset).coerceAtLeast(0L),
                                        fullFileSizeBytes = fileSize,
                                    )
                                val waitText = buildReconnectWaitText(currentCompletedBytes, fileSize)
                                Log.w(
                                    TAG,
                                    "HTTP slow-progress detected file=$displayFileName window=${windowMs}ms bytes=$progressBytes sent=$currentSent",
                                )
                                PhoneTransferDiagnostics.warn(
                                    "Http",
                                    "Slow progress detected file=$displayFileName window=${windowMs}ms bytes=$progressBytes sent=$currentSent",
                                )
                                active.onProgress(progress, waitText)
                                if (!active.interruptionDeferred.isCompleted) {
                                    active.interruptionDeferred.complete(
                                        TransferResult(
                                            success = false,
                                            message = "$RESULT_HTTP_SLOW_PREFIX file=$displayFileName windowMs=$windowMs progressBytes=$progressBytes sent=$currentSent",
                                        ),
                                    )
                                }
                                PhoneTransferDiagnostics.warn(
                                    "Http",
                                    "Cancelling slow HTTP request file=$displayFileName after low throughput",
                                )
                                runCatching { responseStream.close() }
                                requestJob?.cancel(
                                    CancellationException(
                                        "HTTP slow progress for ${windowMs}ms while sending $displayFileName",
                                    ),
                                )
                                return@launch
                            }
                        }

                    try {
                        while (true) {
                            active.awaitIfPaused()

                            val toRead =
                                if (remaining == Long.MAX_VALUE) {
                                    buffer.size
                                } else {
                                    min(buffer.size.toLong(), remaining).toInt()
                                }
                            if (toRead <= 0) break

                            val readStartMs = SystemClock.elapsedRealtime()
                            val read = inp.read(buffer, 0, toRead)
                            sourceReadMs += SystemClock.elapsedRealtime() - readStartMs
                            if (read < 0) break

                            val writeStartMs = SystemClock.elapsedRealtime()
                            write(buffer, 0, read)
                            responseWriteMs += SystemClock.elapsedRealtime() - writeStartMs
                            sent += read.toLong()
                            sentBytes.set(sent)
                            lastProgressAt.set(SystemClock.elapsedRealtime())
                            stallReported.set(false)
                            if (remaining != Long.MAX_VALUE) {
                                remaining -= read.toLong()
                            }

                            while (milestoneLoggingEnabled && sent >= nextMilestoneBytes) {
                                val completedBytes =
                                    calculateCumulativeHttpProgressBytes(
                                        resumeOffsetBytes = startOffset,
                                        bytesTransferredThisAttempt = (sent - startOffset).coerceAtLeast(0L),
                                        fullFileSizeBytes = fileSize,
                                    )
                                val milestoneText = buildMilestoneText(completedBytes, fileSize)
                                PhoneTransferDiagnostics.log(
                                    "Http",
                                    "Milestone ${active.metadata.diagnosticContext()} " +
                                        "file=$displayFileName $milestoneText",
                                )
                                nextMilestoneBytes += HTTP_MILESTONE_LOG_STEP_BYTES
                            }

                            val now = SystemClock.elapsedRealtime()
                            val timeOk = (now - lastUiMs) >= PROGRESS_MIN_INTERVAL_MS
                            val bytesOk = (sent - lastUiBytes) >= stepBytes
                            val done = (fileSize > 0 && sent >= fileSize)

                            if (timeOk || bytesOk || done) {
                                lastUiMs = now
                                lastUiBytes = sent

                                val timeDelta = max(1L, now - lastSpeedMs)
                                val bytesDelta = sent - lastSpeedBytes
                                val mibps =
                                    if (timeDelta >= SPEED_WARMUP_MIN_MS) {
                                        (bytesDelta * 1000.0) / timeDelta / (1024.0 * 1024.0)
                                    } else {
                                        null
                                    }

                                if (mibps != null) {
                                    lastSpeedMs = now
                                    lastSpeedBytes = sent
                                }

                                val completedBytes =
                                    calculateCumulativeHttpProgressBytes(
                                        resumeOffsetBytes = startOffset,
                                        bytesTransferredThisAttempt = (sent - startOffset).coerceAtLeast(0L),
                                        fullFileSizeBytes = fileSize,
                                    )
                                val progress =
                                    calculateHttpProgressFraction(
                                        resumeOffsetBytes = startOffset,
                                        bytesTransferredThisAttempt =
                                            (sent - startOffset).coerceAtLeast(0L),
                                        fullFileSizeBytes = fileSize,
                                    )

                                val text =
                                    formatActiveTransferText(
                                        completedBytes = completedBytes,
                                        totalSize = fileSize,
                                        speedMiBps = mibps,
                                    )

                                active.onProgress(progress, text)
                            }

                            if (remaining == 0L) break
                        }

                        val finalCompletedBytes =
                            calculateCumulativeHttpProgressBytes(
                                resumeOffsetBytes = startOffset,
                                bytesTransferredThisAttempt = (sent - startOffset).coerceAtLeast(0L),
                                fullFileSizeBytes = fileSize,
                            )
                        val finalProgress =
                            calculateHttpProgressFraction(
                                resumeOffsetBytes = startOffset,
                                bytesTransferredThisAttempt =
                                    (sent - startOffset).coerceAtLeast(0L),
                                fullFileSizeBytes = fileSize,
                            )
                        active.onProgress(
                            finalProgress,
                            formatFinalizationText(
                                completedBytes = finalCompletedBytes,
                                totalSize = fileSize,
                                resumeOffset = startOffset,
                            ),
                        )
                    } finally {
                        active.abortRequest = null
                        stallWatchdog.cancel()
                        slowProgressWatchdog.cancel()
                        val bytesServedThisAttempt =
                            (sent - startOffset).coerceAtLeast(0L)
                        PhoneTransferDiagnostics.log(
                            "Http",
                            "event=http_response_summary ${active.metadata.diagnosticContext()} " +
                                "fullFileSizeBytes=$fileSize resumeOffsetBytes=$startOffset " +
                                "finalFileSizeBytes=$sent " +
                                "bytesServedThisAttempt=$bytesServedThisAttempt " +
                                "sourceReadMs=$sourceReadMs responseWriteMs=$responseWriteMs " +
                                "rangeSeekOrSkipMs=$rangeSeekOrSkipMs " +
                                "totalAttemptDurationMs=${SystemClock.elapsedRealtime() - active.startedAtElapsedMs}",
                        )
                    }
                }
            }
        }
    }

    private fun buildReconnectWaitText(
        completedBytes: Long,
        totalSize: Long,
    ): String {
        val detail =
            if (totalSize > 0L) {
                "HTTP: ${formatBytes(completedBytes)} / ${formatBytes(totalSize)}"
            } else {
                "HTTP: ${formatBytes(completedBytes)}"
            }
        return "Waiting for watch reconnect…\n$detail"
    }

    private fun buildMilestoneText(
        completedBytes: Long,
        totalSize: Long,
    ): String {
        val base =
            if (totalSize > 0L) {
                "${formatBytes(completedBytes)} / ${formatBytes(totalSize)}"
            } else {
                formatBytes(completedBytes)
            }
        val percent =
            if (totalSize > 0L) {
                val ratio = (completedBytes.toDouble() / totalSize.toDouble()).coerceIn(0.0, 1.0) * 100.0
                String.format(Locale.US, " (%.1f%%)", ratio)
            } else {
                ""
            }
        return base + percent
    }

    private fun nextMilestoneAfter(bytes: Long): Long {
        if (bytes <= 0L) return HTTP_MILESTONE_LOG_STEP_BYTES
        val steps = (bytes / HTTP_MILESTONE_LOG_STEP_BYTES) + 1L
        return steps * HTTP_MILESTONE_LOG_STEP_BYTES
    }

    private suspend fun waitUntilPortOpen(
        host: String,
        port: Int,
    ) {
        val deadline = SystemClock.elapsedRealtime() + SERVER_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 250)
                }
            }.onSuccess { return }
            delay(SERVER_READY_POLL_DELAY_MS)
        }
        throw IllegalStateException("HTTP server did not become reachable on $host:$port")
    }

    internal fun formatBytes(bytes: Long): String {
        val b = max(bytes, 0L).toDouble()
        val kib = 1024.0
        val mib = kib * 1024.0
        val gib = mib * 1024.0
        return when {
            b >= gib -> String.format(Locale.US, "%.2f GiB", b / gib)
            b >= mib -> String.format(Locale.US, "%.2f MiB", b / mib)
            b >= kib -> String.format(Locale.US, "%.0f KiB", b / kib)
            else -> "$bytes B"
        }
    }

    internal fun formatProgressText(
        completedBytes: Long,
        totalSize: Long,
        speedMiBps: Double?,
        prefix: String = "",
    ): String {
        val base =
            if (totalSize > 0L) {
                "${formatBytes(completedBytes)} / ${formatBytes(totalSize)}"
            } else {
                formatBytes(completedBytes)
            }
        val speedSuffix =
            speedMiBps
                ?.let {
                    " (${String.format(Locale.US, "%.2f", it)} MiB/s)"
                }.orEmpty()
        return "$prefix$base$speedSuffix"
    }

    internal fun formatActiveTransferText(
        completedBytes: Long,
        totalSize: Long,
        speedMiBps: Double?,
    ): String = formatProgressText(completedBytes, totalSize, speedMiBps, prefix = "HTTP: ")

    private fun formatFinalizationText(
        completedBytes: Long,
        totalSize: Long,
        resumeOffset: Long,
    ): String {
        val detail = formatProgressText(completedBytes, totalSize, speedMiBps = null, prefix = "HTTP: ")
        return if (resumeOffset > 0L) {
            "Verifying…\n$detail"
        } else {
            detail
        }
    }

    private fun generateTransferToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    internal fun computeHttpStallTimeoutMs(
        totalSize: Long,
        resumeOffset: Long,
        isMapFile: Boolean,
    ): Long {
        val normalizedTotalSize = totalSize.coerceAtLeast(0L)
        val normalizedResumeOffset = resumeOffset.coerceAtLeast(0L)
        val remainingBytes =
            if (normalizedTotalSize > 0L) {
                (normalizedTotalSize - normalizedResumeOffset).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }

        var timeoutMs = HTTP_STALL_TIMEOUT_MS
        val isLargeTransfer = normalizedTotalSize >= HTTP_STALL_TIMEOUT_LARGE_FILE_BYTES
        if (isMapFile && isLargeTransfer) {
            timeoutMs = max(timeoutMs, HTTP_STALL_TIMEOUT_LARGE_FILE_MS)
        }
        if (normalizedResumeOffset > 0L && isLargeTransfer) {
            timeoutMs = max(timeoutMs, HTTP_STALL_TIMEOUT_RESUMED_LARGE_FILE_MS)
        }
        if (normalizedResumeOffset > 0L && remainingBytes in 1..HTTP_STALL_TIMEOUT_LATE_RESUMED_BYTES) {
            timeoutMs = max(timeoutMs, HTTP_STALL_TIMEOUT_LATE_RESUMED_MS)
        }
        if (normalizedResumeOffset > 0L && remainingBytes in 1..HTTP_STALL_TIMEOUT_TAIL_BYTES) {
            timeoutMs = max(timeoutMs, HTTP_STALL_TIMEOUT_TAIL_RESUMED_MS)
        }
        return timeoutMs
    }

    private fun parseRange(
        rangeHeader: String?,
        totalSize: Long,
    ): Pair<Long, Long>? {
        if (rangeHeader.isNullOrBlank()) return null
        if (totalSize <= 0L) return null
        if (!rangeHeader.startsWith("bytes=", ignoreCase = true)) return null

        val spec = rangeHeader.substringAfter("bytes=").trim()
        val dash = spec.indexOf('-')
        if (dash <= 0) return null

        val start = spec.substring(0, dash).trim().toLongOrNull() ?: return null
        val endRaw = spec.substring(dash + 1).trim()
        val end = if (endRaw.isBlank()) totalSize - 1L else endRaw.toLongOrNull() ?: return null

        if (start < 0L || start >= totalSize) return null
        val boundedEnd = min(end, totalSize - 1L)
        if (boundedEnd < start) return null
        return start to boundedEnd
    }

    private fun skipFully(
        input: BufferedInputStream,
        bytesToSkip: Long,
    ) {
        var remaining = bytesToSkip
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            if (input.read() == -1) break
            remaining--
        }
    }

    private data class ActiveDownload(
        val context: Context,
        val fileUri: android.net.Uri,
        val metadata: TransferMetadata,
        val transferToken: String,
        val firstRequest: CompletableDeferred<Unit>,
        val startedAtElapsedMs: Long,
        val interruptionDeferred: CompletableDeferred<TransferResult>,
        val awaitIfPaused: suspend () -> Unit,
        val isAwaitingPause: () -> Boolean,
        val onProgress: (Float, String) -> Unit,
    ) {
        val manualPauseRequested = AtomicBoolean(false)

        @Volatile
        var abortRequest: (() -> Unit)? = null
    }
}

internal fun calculateCumulativeHttpProgressBytes(
    resumeOffsetBytes: Long,
    bytesTransferredThisAttempt: Long,
    fullFileSizeBytes: Long,
): Long {
    val resumeOffset = resumeOffsetBytes.coerceAtLeast(0L)
    val attemptBytes = bytesTransferredThisAttempt.coerceAtLeast(0L)
    val cumulativeBytes =
        if (attemptBytes > Long.MAX_VALUE - resumeOffset) {
            Long.MAX_VALUE
        } else {
            resumeOffset + attemptBytes
        }
    return cumulativeBytes.coerceIn(0L, fullFileSizeBytes.coerceAtLeast(0L))
}

internal fun calculateHttpProgressFraction(
    resumeOffsetBytes: Long,
    bytesTransferredThisAttempt: Long,
    fullFileSizeBytes: Long,
): Float {
    val totalBytes = fullFileSizeBytes.coerceAtLeast(0L)
    if (totalBytes == 0L) return 0f

    return (
        calculateCumulativeHttpProgressBytes(
            resumeOffsetBytes = resumeOffsetBytes,
            bytesTransferredThisAttempt = bytesTransferredThisAttempt,
            fullFileSizeBytes = totalBytes,
        ).toDouble() / totalBytes.toDouble()
    ).toFloat().coerceIn(0f, 1f)
}
