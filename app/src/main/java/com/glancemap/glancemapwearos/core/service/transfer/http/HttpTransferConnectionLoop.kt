package com.glancemap.glancemapwearos.core.service.transfer.http

import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.contract.ReceiverMetadata
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferRuntimeHost
import com.glancemap.shared.transfer.TransferDataLayerContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

@Suppress("LargeClass")
internal class HttpTransferConnectionLoop(
    private val host: TransferRuntimeHost,
    private val networkSession: HttpTransferNetworkSession,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val connectRetryWindowMs: Long,
    private val connectRetryDelayMs: Long,
    private val networkPauseTimeoutMs: Long,
    private val networkRecheckMs: Long,
) {
    private sealed interface ResumePreparation {
        data class Continue(
            val offset: Long,
        ) : ResumePreparation

        data class Completed(
            val sha256: String?,
        ) : ResumePreparation
    }

    internal data class ReceiveResult(
        val sha256: String?,
        val finalNetwork: Network,
        val fullFileSizeBytes: Long,
        val resumeOffsetBytes: Long,
        val finalFileSizeBytes: Long,
        val bytesTransferredThisAttempt: Long,
        val attemptThroughputMiBps: Double,
        val startupElapsedMs: Long,
        val timeToFileRequestMs: Long? = null,
        val dataTransferDurationMs: Long = 0L,
        val reconnectCount: Int = 0,
    )

    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
        "LongParameterList",
        "NestedBlockDepth",
        "ReturnCount",
        "ThrowsCount",
    )
    suspend fun receive(
        metadata: ReceiverMetadata,
        baseUrlStr: String,
        url: URL,
        initialNetwork: Network,
        resumeOffset: Long,
        skipInitialProbe: Boolean,
        startupDeadlineElapsedMs: Long? = null,
        startupStartElapsedMs: Long = SystemClock.elapsedRealtime(),
        wifiAcquireMs: Long = 0L,
        onTransferState: (phase: String, detail: String) -> Unit,
        onProgress: (Long) -> Unit,
    ): ReceiveResult {
        var activeWifi = initialNetwork
        val preparedOffset =
            when (val prepared = prepareResumeOffset(metadata, resumeOffset, onTransferState)) {
                is ResumePreparation.Completed -> {
                    TransferDiagnostics.log(
                        "HttpConn",
                        "Recovered completed partial id=${metadata.transferId} file=${metadata.fileName}",
                    )
                    return ReceiveResult(
                        sha256 = prepared.sha256,
                        finalNetwork = activeWifi,
                        fullFileSizeBytes = metadata.totalSize,
                        resumeOffsetBytes = metadata.totalSize.takeIf { it > 0L } ?: resumeOffset,
                        finalFileSizeBytes = metadata.totalSize.takeIf { it > 0L } ?: resumeOffset,
                        bytesTransferredThisAttempt = 0L,
                        attemptThroughputMiBps = 0.0,
                        startupElapsedMs = SystemClock.elapsedRealtime() - startupStartElapsedMs,
                    )
                }
                is ResumePreparation.Continue -> prepared.offset
            }
        if (!skipInitialProbe) {
            val probeTimeoutMs =
                remainingHttpStartupBudget(startupDeadlineElapsedMs ?: Long.MAX_VALUE, SystemClock.elapsedRealtime())
                    .coerceAtMost(PROBE_TIMEOUT_MS.toLong())
            if (probeTimeoutMs > 0L) {
                runCatching {
                    probeServer(activeWifi, URL("$baseUrlStr/"), metadata, probeTimeoutMs.toInt())
                }.onFailure {
                    // The file request below is the retryable reachability check. Do not spend a
                    // second independent retry window after this best-effort probe.
                    Log.w(TAG, "Initial HTTP probe failed; continuing with request retries: ${it.message}")
                    TransferDiagnostics.warn(
                        "HttpConn",
                        "Initial probe failed id=${metadata.transferId}; continuing with request retries",
                    )
                }
            }
        } else {
            Log.d(TAG, "Skipping HTTP root probe for warm session")
            TransferDiagnostics.log(
                "HttpConn",
                "Skip root probe id=${metadata.transferId} for warm session file=${metadata.fileName}",
            )
        }

        var conn: HttpURLConnection? = null
        var networkPaused = false
        var pausedSinceMs = 0L

        try {
            var startupComplete = startupDeadlineElapsedMs == null
            var connectDeadlineMs =
                nextHttpConnectDeadlineElapsedMs(
                    startupComplete = startupComplete,
                    startupDeadlineElapsedMs = startupDeadlineElapsedMs,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                    retryWindowMs = connectRetryWindowMs,
                )
            var currentRetryBudgetMs = connectRetryWindowMs
            var lastError: Throwable? = null
            var attempt = 0

            var desiredOffset = preparedOffset

            TransferDiagnostics.log(
                "HttpConn",
                "Session start id=${metadata.transferId} file=${metadata.fileName} " +
                    "resumeOffset=$desiredOffset totalSize=${metadata.totalSize} " +
                    "isResume=${desiredOffset > 0L} " +
                    "startupBudgetMs=${startupDeadlineElapsedMs?.let { it - startupStartElapsedMs } ?: "na"} " +
                    "reconnectBudgetMs=$connectRetryWindowMs wifiAcquireMs=$wifiAcquireMs",
            )

            var fileRequestStartElapsedMs: Long? = null
            var timeToFileRequestMs: Long? = null
            var reconnectCount = 0

            while (SystemClock.elapsedRealtime() < connectDeadlineMs) {
                coroutineContext.ensureActive()
                attempt++

                runCatching { conn?.disconnect() }
                conn = null

                try {
                    val startupRemainingMs =
                        httpStartupRemainingBudgetMs(
                            startupComplete = startupComplete,
                            startupDeadlineElapsedMs = startupDeadlineElapsedMs,
                            nowElapsedMs = SystemClock.elapsedRealtime(),
                        )
                    val attemptTimeoutMs =
                        startupRemainingMs?.let {
                            cappedHttpTimeoutMs(connectTimeoutMs.toLong(), it)
                        }
                    if (startupRemainingMs != null && attemptTimeoutMs == null) break

                    val startupElapsedBeforeAttemptMs = SystemClock.elapsedRealtime() - startupStartElapsedMs
                    if (attempt == 1 || attempt == 2 || attempt % 4 == 0) {
                        TransferDiagnostics.log(
                            "HttpConn",
                            "event=http_connect_attempt transferId=${metadata.transferId} " +
                                "connectAttempt=$attempt startupElapsedMs=$startupElapsedBeforeAttemptMs " +
                                "remainingBudgetMs=${startupRemainingMs ?: "na"} resumeOffset=$desiredOffset",
                        )
                    }
                    if (fileRequestStartElapsedMs == null) {
                        fileRequestStartElapsedMs = startupElapsedBeforeAttemptMs
                        TransferDiagnostics.log(
                            "HttpConn",
                            "event=http_file_request_start transferId=${metadata.transferId} " +
                                "connectAttempt=$attempt fileRequestStartElapsedMs=$startupElapsedBeforeAttemptMs " +
                                "remainingBudgetMs=${startupRemainingMs ?: "na"}",
                        )
                    }

                    val connection =
                        (activeWifi.openConnection(url) as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = attemptTimeoutMs ?: connectTimeoutMs
                            readTimeout =
                                startupRemainingMs?.let {
                                    cappedHttpTimeoutMs(readTimeoutMs.toLong(), it)
                                } ?: readTimeoutMs
                            doInput = true
                            useCaches = false
                            instanceFollowRedirects = true
                            setRequestProperty("Connection", "Keep-Alive")
                            setRequestProperty("Accept-Encoding", "identity")
                            metadata.authToken?.let {
                                setRequestProperty(TransferDataLayerContract.HTTP_AUTH_HEADER, it)
                            }

                            httpRangeHeader(desiredOffset)?.let { setRequestProperty("Range", it) }
                        }
                    conn = connection

                    Log.d(TAG, "HTTP connect id=${metadata.transferId} attempt=$attempt rangeOffset=$desiredOffset")
                    connection.connect()

                    if (!startupComplete) {
                        connection.readTimeout =
                            cappedHttpTimeoutMs(
                                readTimeoutMs.toLong(),
                                remainingHttpStartupBudget(
                                    requireNotNull(startupDeadlineElapsedMs),
                                    SystemClock.elapsedRealtime(),
                                ),
                            ) ?: throw IOException("HTTP startup budget exhausted before the first request")
                    }
                    val code = connection.responseCode
                    Log.d(TAG, "HTTP Response: $code")
                    if (!startupComplete) {
                        startupComplete = true
                        timeToFileRequestMs = SystemClock.elapsedRealtime() - startupStartElapsedMs
                        TransferDiagnostics.log(
                            "HttpConn",
                            "event=http_file_request_response transferId=${metadata.transferId} " +
                                "responseCode=$code timeToFileRequestMs=$timeToFileRequestMs " +
                                "startupElapsedMs=$timeToFileRequestMs " +
                                "remainingBudgetMs=" +
                                remainingHttpStartupBudget(
                                    requireNotNull(startupDeadlineElapsedMs),
                                    SystemClock.elapsedRealtime(),
                                ),
                        )
                        connectDeadlineMs = SystemClock.elapsedRealtime() + currentRetryBudgetMs
                        connection.readTimeout = readTimeoutMs
                    }

                    if (desiredOffset > 0L) {
                        when (code) {
                            HttpURLConnection.HTTP_PARTIAL -> {
                                // expected resume behavior
                            }

                            HttpURLConnection.HTTP_OK -> {
                                Log.w(TAG, "Server ignored Range; restarting from 0")
                                TransferDiagnostics.warn(
                                    "HttpConn",
                                    "Server ignored range id=${metadata.transferId} file=${metadata.fileName} offset=$desiredOffset; restarting",
                                )
                                desiredOffset = 0L
                                connection.disconnect()
                                delay(connectRetryDelayMs)
                                continue
                            }

                            HTTP_REQUESTED_RANGE_NOT_SATISFIABLE -> {
                                when (val repaired = recoverFromInvalidRange(metadata, onTransferState)) {
                                    is ResumePreparation.Completed -> {
                                        return ReceiveResult(
                                            sha256 = repaired.sha256,
                                            finalNetwork = activeWifi,
                                            fullFileSizeBytes = metadata.totalSize,
                                            resumeOffsetBytes = desiredOffset,
                                            finalFileSizeBytes = desiredOffset,
                                            bytesTransferredThisAttempt = 0L,
                                            attemptThroughputMiBps = 0.0,
                                            startupElapsedMs = SystemClock.elapsedRealtime() - startupStartElapsedMs,
                                        )
                                    }
                                    is ResumePreparation.Continue -> {
                                        desiredOffset = repaired.offset
                                        connection.disconnect()
                                        delay(connectRetryDelayMs)
                                        continue
                                    }
                                }
                            }

                            else -> throw IOException("Server Error $code: ${connection.responseMessage}")
                        }
                    } else if (code != HttpURLConnection.HTTP_OK) {
                        throw IOException("Server Error $code: ${connection.responseMessage}")
                    }

                    if (networkPaused) {
                        networkPaused = false
                        pausedSinceMs = 0L
                        attempt = 0
                        TransferDiagnostics.log(
                            "HttpConn",
                            "Connection restored id=${metadata.transferId} file=${metadata.fileName} resumeOffset=$desiredOffset",
                        )
                        onTransferState("RESUMED", "Connection restored. Resuming transfer…")
                    }

                    val dataTransferStartMs = SystemClock.elapsedRealtime()
                    var bytesReceived = desiredOffset
                    val receivedSha256 =
                        connection.inputStream.use { input ->
                            host.saveFile(
                                fileName = metadata.fileName,
                                inputStream = input,
                                expectedSize = metadata.totalSize.takeIf { it > 0 },
                                resumeOffset = desiredOffset,
                                keepPartialOnFailure = true,
                                computeSha256 = shouldComputeInlineChecksumForHttp(desiredOffset),
                                diagnosticContext = "transferId=${metadata.transferId}",
                                onProgress = { bytes ->
                                    bytesReceived = maxOf(bytesReceived, bytes)
                                    onProgress(bytes)
                                },
                            )
                        }
                    val dataTransferDurationMs = SystemClock.elapsedRealtime() - dataTransferStartMs
                    val finalFileSizeBytes = bytesReceived.coerceAtLeast(desiredOffset)
                    val bytesTransferredThisAttempt =
                        calculateBytesTransferredThisAttempt(
                            resumeOffsetBytes = desiredOffset,
                            finalFileSizeBytes = finalFileSizeBytes,
                        )
                    val attemptThroughputMiBps =
                        calculateAttemptThroughputMiBps(
                            bytesTransferredThisAttempt = bytesTransferredThisAttempt,
                            dataTransferDurationMs = dataTransferDurationMs,
                        )
                    val formattedAttemptThroughputMiBps =
                        String.format(java.util.Locale.US, "%.2f", attemptThroughputMiBps)
                    val startupElapsedMs =
                        timeToFileRequestMs ?: (SystemClock.elapsedRealtime() - startupStartElapsedMs)

                    Log.d(TAG, "✅ HTTP Receive Complete")
                    TransferDiagnostics.log(
                        "HttpConn",
                        "event=http_data_summary transferId=${metadata.transferId} file=${metadata.fileName} " +
                            "fullFileSizeBytes=${metadata.totalSize} resumeOffsetBytes=$desiredOffset " +
                            "finalFileSizeBytes=$finalFileSizeBytes " +
                            "bytesTransferredThisAttempt=$bytesTransferredThisAttempt " +
                            "dataTransferDurationMs=$dataTransferDurationMs " +
                            "attemptThroughputMiBps=$formattedAttemptThroughputMiBps " +
                            "reconnectCount=$reconnectCount " +
                            "startupElapsedMs=$startupElapsedMs " +
                            "timeToFileRequestMs=${timeToFileRequestMs ?: "na"}",
                    )
                    return ReceiveResult(
                        sha256 = receivedSha256,
                        finalNetwork = activeWifi,
                        fullFileSizeBytes = metadata.totalSize,
                        resumeOffsetBytes = desiredOffset,
                        finalFileSizeBytes = finalFileSizeBytes,
                        bytesTransferredThisAttempt = bytesTransferredThisAttempt,
                        attemptThroughputMiBps = attemptThroughputMiBps,
                        startupElapsedMs = startupElapsedMs,
                        timeToFileRequestMs = timeToFileRequestMs,
                        dataTransferDurationMs = dataTransferDurationMs,
                        reconnectCount = reconnectCount,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: IOException) {
                    lastError = e
                    val attemptResumeOffsetBytes = desiredOffset
                    desiredOffset = host.getPartialSize(metadata.fileName)
                    val bytesTransferredThisAttempt =
                        calculateBytesTransferredThisAttempt(
                            resumeOffsetBytes = attemptResumeOffsetBytes,
                            finalFileSizeBytes = desiredOffset,
                        )
                    val hasPartialData = desiredOffset > 0L
                    val nowMs = SystemClock.elapsedRealtime()
                    val currentWifi = networkSession.findWifiNetwork()
                    if (!networkPaused) {
                        networkPaused = true
                        pausedSinceMs = nowMs
                        reconnectCount++
                        val detail =
                            if (currentWifi == null) {
                                "Network lost. Waiting for Wi-Fi…"
                            } else {
                                "Connection interrupted. Waiting to resume…"
                            }
                        Log.w(
                            TAG,
                            "HTTP paused file=${metadata.fileName} partialBytes=$desiredOffset " +
                                "hasPartial=$hasPartialData currentWifi=${currentWifi != null} " +
                                "attempt=$attempt error=${e.message}",
                        )
                        TransferDiagnostics.warn(
                            "HttpConn",
                            "Paused id=${metadata.transferId} file=${metadata.fileName} " +
                                "resumeOffsetBytes=$attemptResumeOffsetBytes finalFileSizeBytes=$desiredOffset " +
                                "bytesTransferredThisAttempt=$bytesTransferredThisAttempt hasPartial=$hasPartialData " +
                                "wifiAvailable=${currentWifi != null} attempt=$attempt",
                        )
                        onTransferState("PAUSED", detail)
                    }

                    if (networkPaused && pausedSinceMs > 0L) {
                        val pausedForMs = nowMs - pausedSinceMs
                        val pauseBudgetMs =
                            if (hasPartialData) {
                                networkPauseTimeoutMs
                            } else {
                                connectRetryWindowMs
                            }
                        currentRetryBudgetMs = pauseBudgetMs
                        if (startupComplete) {
                            connectDeadlineMs = maxOf(connectDeadlineMs, pausedSinceMs + pauseBudgetMs)
                        } else {
                            connectDeadlineMs = requireNotNull(startupDeadlineElapsedMs)
                        }
                        Log.d(
                            TAG,
                            "HTTP retry budget file=${metadata.fileName} partialBytes=$desiredOffset " +
                                "pausedForMs=$pausedForMs budgetMs=$pauseBudgetMs deadlineInMs=${connectDeadlineMs - nowMs}",
                        )
                        TransferDiagnostics.log(
                            "HttpConn",
                            "Retry budget id=${metadata.transferId} file=${metadata.fileName} partialBytes=$desiredOffset pausedForMs=$pausedForMs budgetMs=$pauseBudgetMs",
                        )
                        if (pausedForMs > pauseBudgetMs) {
                            throw IOException(
                                "Transfer paused for more than ${pauseBudgetMs / 1000}s",
                                e,
                            )
                        }
                    }

                    if (currentWifi == null) {
                        val startupRemainingMs =
                            httpStartupRemainingBudgetMs(
                                startupComplete = startupComplete,
                                startupDeadlineElapsedMs = startupDeadlineElapsedMs,
                                nowElapsedMs = SystemClock.elapsedRealtime(),
                            )
                        if (startupRemainingMs != null && startupRemainingMs <= 0L) {
                            throw IOException("HTTP startup budget exhausted before the first request", e)
                        }
                        val remainingPauseBudgetMs =
                            (networkPauseTimeoutMs - (nowMs - pausedSinceMs))
                                .coerceAtLeast(0L)
                        val boundedPauseBudgetMs =
                            startupRemainingMs?.let { minOf(remainingPauseBudgetMs, it) }
                                ?: remainingPauseBudgetMs
                        val restoredNetwork =
                            networkSession.waitForWifiReconnect(
                                timeoutMs = boundedPauseBudgetMs,
                                recheckMs = networkRecheckMs,
                            ) ?: throw IOException(
                                "No Wi-Fi network after ${networkPauseTimeoutMs / 1000}s",
                                e,
                            )

                        Log.i(
                            TAG,
                            "Wi-Fi restored for file=${metadata.fileName} partialBytes=$desiredOffset " +
                                "remainingPauseBudgetMs=$remainingPauseBudgetMs",
                        )
                        TransferDiagnostics.log(
                            "HttpConn",
                            "Wi-Fi restored id=${metadata.transferId} file=${metadata.fileName} partialBytes=$desiredOffset remainingBudgetMs=$remainingPauseBudgetMs",
                        )
                        activeWifi = restoredNetwork
                        networkSession.bindToNetwork(restoredNetwork)
                        desiredOffset =
                            when (val prepared = prepareResumeOffset(metadata, desiredOffset, onTransferState)) {
                                is ResumePreparation.Completed -> {
                                    return ReceiveResult(
                                        sha256 = prepared.sha256,
                                        finalNetwork = activeWifi,
                                        fullFileSizeBytes = metadata.totalSize,
                                        resumeOffsetBytes = desiredOffset,
                                        finalFileSizeBytes = desiredOffset,
                                        bytesTransferredThisAttempt = 0L,
                                        attemptThroughputMiBps = 0.0,
                                        startupElapsedMs = SystemClock.elapsedRealtime() - startupStartElapsedMs,
                                    )
                                }
                                is ResumePreparation.Continue -> prepared.offset
                            }
                        attempt = 0
                        // Start a fresh connection window once Wi-Fi is back.
                        connectDeadlineMs =
                            nextHttpConnectDeadlineElapsedMs(
                                startupComplete = startupComplete,
                                startupDeadlineElapsedMs = startupDeadlineElapsedMs,
                                nowElapsedMs = SystemClock.elapsedRealtime(),
                                retryWindowMs = if (startupComplete) currentRetryBudgetMs else connectRetryWindowMs,
                            )
                        currentRetryBudgetMs =
                            if (desiredOffset > 0L) {
                                networkPauseTimeoutMs
                            } else {
                                connectRetryWindowMs
                            }
                        continue
                    }

                    if (shouldRefreshBinding(attempt, e)) {
                        Log.i(
                            TAG,
                            "Refreshing Wi-Fi binding file=${metadata.fileName} partialBytes=$desiredOffset " +
                                "attempt=$attempt error=${e.message}",
                        )
                        TransferDiagnostics.warn(
                            "HttpConn",
                            "Refreshing Wi-Fi binding file=${metadata.fileName} partialBytes=$desiredOffset attempt=$attempt",
                        )
                        val refreshTimeoutMs: Long? =
                            if (!startupComplete && startupDeadlineElapsedMs != null) {
                                cappedHttpTimeoutMs(
                                    REFRESH_BIND_TIMEOUT_MS,
                                    remainingHttpStartupBudget(startupDeadlineElapsedMs, SystemClock.elapsedRealtime()),
                                )?.toLong()
                            } else {
                                REFRESH_BIND_TIMEOUT_MS
                            }
                        val reboundWifi =
                            refreshTimeoutMs?.let {
                                networkSession.acquireWifi(
                                    timeoutMs = it,
                                    transferId = metadata.transferId,
                                    startupDeadlineElapsedMs = startupDeadlineElapsedMs.takeIf { !startupComplete },
                                )
                            } ?: currentWifi
                        activeWifi = reboundWifi
                        networkSession.bindToNetwork(reboundWifi)
                        val probeTimeoutMs: Int? =
                            if (!startupComplete && startupDeadlineElapsedMs != null) {
                                cappedHttpTimeoutMs(
                                    PROBE_TIMEOUT_MS.toLong(),
                                    remainingHttpStartupBudget(startupDeadlineElapsedMs, SystemClock.elapsedRealtime()),
                                )
                            } else {
                                PROBE_TIMEOUT_MS
                            }
                        if (probeTimeoutMs != null) {
                            runCatching { probeServer(reboundWifi, URL("$baseUrlStr/"), metadata, probeTimeoutMs) }
                                .onSuccess {
                                    Log.d(TAG, "HTTP probe recovered after reconnect attempt")
                                    TransferDiagnostics.log(
                                        "HttpConn",
                                        "Probe recovered after rebind id=${metadata.transferId} " +
                                            "file=${metadata.fileName}",
                                    )
                                    attempt = 0
                                }.onFailure {
                                    Log.w(TAG, "HTTP probe still failing after Wi-Fi rebind: ${it.message}")
                                    TransferDiagnostics.warn(
                                        "HttpConn",
                                        "Probe still failing after rebind id=${metadata.transferId} file=${metadata.fileName}",
                                    )
                                }
                        }
                    } else if (currentWifi != activeWifi) {
                        activeWifi = currentWifi
                        networkSession.bindToNetwork(currentWifi)
                    }

                    Log.w(TAG, "HTTP failed (${e.message}). Retrying...")
                    val backoff = (connectRetryDelayMs * attempt).coerceAtMost(3_000L)
                    val delayMs =
                        if (!startupComplete && startupDeadlineElapsedMs != null) {
                            cappedHttpRetryDelayMs(
                                backoff,
                                remainingHttpStartupBudget(startupDeadlineElapsedMs, SystemClock.elapsedRealtime()),
                            )
                        } else {
                            backoff
                        }
                    if (delayMs > 0L) delay(delayMs)
                }
            }

            Log.e(
                TAG,
                "HTTP connect window exhausted file=${metadata.fileName} partialBytes=$desiredOffset " +
                    "budgetMs=$currentRetryBudgetMs lastError=${lastError?.message}",
            )
            val failureEvent = httpFailureEventName(startupComplete)
            val remainingBudgetMs =
                if (startupComplete) {
                    (connectDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                } else {
                    startupDeadlineElapsedMs?.let {
                        remainingHttpStartupBudget(it, SystemClock.elapsedRealtime())
                    } ?: "na"
                }
            TransferDiagnostics.error(
                "HttpConn",
                "event=$failureEvent transferId=${metadata.transferId} file=${metadata.fileName} " +
                    "startupElapsedMs=${SystemClock.elapsedRealtime() - startupStartElapsedMs} " +
                    "failurePhase=${if (startupComplete) "recovery" else "file_request"} " +
                    "remainingBudgetMs=$remainingBudgetMs " +
                    "failureReason=connect_window_exhausted partialBytes=$desiredOffset " +
                    "reconnectCount=$reconnectCount currentRetryBudgetMs=$currentRetryBudgetMs",
                lastError,
            )
            val failureMessage =
                if (!startupComplete) {
                    "HTTP startup budget exhausted before the first request. Last error: ${lastError?.message}"
                } else {
                    "HTTP recovery window exhausted after ${currentRetryBudgetMs}ms. Last error: ${lastError?.message}"
                }
            throw IOException(failureMessage, lastError)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private suspend fun prepareResumeOffset(
        metadata: ReceiverMetadata,
        resumeOffset: Long,
        onTransferState: (phase: String, detail: String) -> Unit,
    ): ResumePreparation {
        val expectedSize = metadata.totalSize.takeIf { it > 0L }
        var desiredOffset =
            maxOf(
                resumeOffset.coerceAtLeast(0L),
                host.getPartialSize(metadata.fileName),
            )

        if (expectedSize == null || desiredOffset < expectedSize) {
            return ResumePreparation.Continue(desiredOffset)
        }

        val expectedSha = metadata.checksumSha256?.lowercase()
        if (desiredOffset > expectedSize) {
            TransferDiagnostics.warn(
                "HttpConn",
                "Oversized partial id=${metadata.transferId} file=${metadata.fileName} partialBytes=$desiredOffset expectedBytes=$expectedSize",
            )
            onTransferState("VERIFYING", "Repairing partial file…")
            val truncated = host.truncatePartial(metadata.fileName, expectedSize)
            if (!truncated) {
                host.deletePartial(metadata.fileName)
                return ResumePreparation.Continue(0L)
            }
            desiredOffset = host.getPartialSize(metadata.fileName)
        }

        if (desiredOffset == expectedSize && !expectedSha.isNullOrBlank()) {
            onTransferState("VERIFYING", "Checking partial file…")
            val partialSha = host.computePartialFileSha256(metadata.fileName)?.lowercase()
            if (partialSha == expectedSha && host.promotePartialToFinal(metadata.fileName)) {
                return ResumePreparation.Completed(partialSha)
            }
            TransferDiagnostics.warn(
                "HttpConn",
                "Invalid complete partial reset id=${metadata.transferId} file=${metadata.fileName}",
            )
            host.deletePartial(metadata.fileName)
            return ResumePreparation.Continue(0L)
        }

        if (desiredOffset >= expectedSize) {
            TransferDiagnostics.warn(
                "HttpConn",
                "Partial at/over expected size without valid checksum id=${metadata.transferId} file=${metadata.fileName}; resetting",
            )
            host.deletePartial(metadata.fileName)
            return ResumePreparation.Continue(0L)
        }

        return ResumePreparation.Continue(desiredOffset)
    }

    private suspend fun recoverFromInvalidRange(
        metadata: ReceiverMetadata,
        onTransferState: (phase: String, detail: String) -> Unit,
    ): ResumePreparation {
        TransferDiagnostics.warn(
            "HttpConn",
            "Server rejected range id=${metadata.transferId} file=${metadata.fileName}; repairing partial",
        )
        return prepareResumeOffset(metadata, 0L, onTransferState)
    }

    private fun shouldRefreshBinding(
        attempt: Int,
        error: IOException,
    ): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return attempt % 4 == 0 || "timeout" in message || "timed out" in message
    }

    private fun probeServer(
        network: Network,
        url: URL,
        metadata: ReceiverMetadata,
        timeoutMs: Int = PROBE_TIMEOUT_MS,
    ) {
        val probeStartMs = SystemClock.elapsedRealtime()
        TransferDiagnostics.log(
            "HttpConn",
            "event=http_probe_start transferId=${metadata.transferId} timeoutMs=$timeoutMs",
        )
        var c: HttpURLConnection? = null
        try {
            c =
                (network.openConnection(url) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    doInput = true
                    useCaches = false
                    setRequestProperty("Connection", "close")
                    setRequestProperty("Accept-Encoding", "identity")
                }
            c.connect()
            val code = c.responseCode
            Log.d(TAG, "✅ Probe / => $code")
            TransferDiagnostics.log(
                "HttpConn",
                "event=http_probe_complete transferId=${metadata.transferId} responseCode=$code " +
                    "probeDurationMs=${SystemClock.elapsedRealtime() - probeStartMs}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "❌ Probe failed")
            TransferDiagnostics.warn(
                "HttpConn",
                "event=http_probe_failure transferId=${metadata.transferId} file=${metadata.fileName} " +
                    "probeDurationMs=${SystemClock.elapsedRealtime() - probeStartMs} reason=${e.javaClass.simpleName}",
            )
            throw IOException("Cannot reach phone HTTP server (${e.message})", e)
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    private companion object {
        const val TAG = "HttpConnLoop"
        const val PROBE_TIMEOUT_MS = 1_500
        const val REFRESH_BIND_TIMEOUT_MS = 2_000L
        const val HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416
    }
}

internal fun shouldComputeInlineChecksumForHttp(resumeOffset: Long): Boolean = resumeOffset <= 0L

internal fun calculateBytesTransferredThisAttempt(
    resumeOffsetBytes: Long,
    finalFileSizeBytes: Long,
): Long =
    (finalFileSizeBytes.coerceAtLeast(0L) - resumeOffsetBytes.coerceAtLeast(0L))
        .coerceAtLeast(0L)

internal fun calculateAttemptThroughputMiBps(
    bytesTransferredThisAttempt: Long,
    dataTransferDurationMs: Long,
): Double =
    if (bytesTransferredThisAttempt > 0L && dataTransferDurationMs > 0L) {
        bytesTransferredThisAttempt / (1024.0 * 1024.0) / (dataTransferDurationMs / 1000.0)
    } else {
        0.0
    }
