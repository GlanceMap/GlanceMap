package com.glancemap.glancemapwearos.core.service.transfer.http

import android.net.ConnectivityManager
import android.net.Network
import android.net.TrafficStats
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.contract.ReceiverMetadata
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferRuntimeHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL

internal const val HTTP_STARTUP_BUDGET_MS = 40_000L

internal fun remainingHttpStartupBudget(
    deadlineElapsedMs: Long,
    nowElapsedMs: Long,
): Long = (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)

internal fun cappedHttpTimeoutMs(
    normalTimeoutMs: Long,
    remainingBudgetMs: Long,
): Int? {
    if (remainingBudgetMs <= 0L) return null
    return minOf(normalTimeoutMs, remainingBudgetMs)
        .coerceIn(1L, Int.MAX_VALUE.toLong())
        .toInt()
}

internal fun cappedHttpRetryDelayMs(
    normalDelayMs: Long,
    remainingBudgetMs: Long,
): Long = minOf(normalDelayMs, remainingBudgetMs.coerceAtLeast(0L))

internal data class HttpTransferReceiveResult(
    val sha256: String?,
    val fullFileSizeBytes: Long,
    val resumeOffsetBytes: Long,
    val finalFileSizeBytes: Long,
    val bytesTransferredThisAttempt: Long,
    val attemptThroughputMiBps: Double,
    val startupElapsedMs: Long,
    val wifiAcquireMs: Long,
    val timeToFileRequestMs: Long?,
    val dataTransferDurationMs: Long,
    val reconnectCount: Int,
)

internal class HttpTransferStrategy : AutoCloseable {
    companion object {
        private const val TAG = "HttpReceiver"

        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 12_000

        private const val CONNECT_RETRY_WINDOW_MS = 120_000L
        private const val CONNECT_RETRY_DELAY_MS = 500L
        private const val NETWORK_PAUSE_TIMEOUT_MS = 10 * 60_000L
        private const val NETWORK_RECHECK_MS = 350L
        private const val SESSION_IDLE_TTL_MS = 20_000L
    }

    private val reusableSessionLock = Any()
    private var reusableSession: ReusableHttpSession? = null

    @Suppress("LongMethod")
    suspend fun receive(
        host: TransferRuntimeHost,
        metadata: ReceiverMetadata,
        path: String,
        resumeOffset: Long,
        onTransferState: (phase: String, detail: String) -> Unit,
        onProgress: (Long) -> Unit,
    ): HttpTransferReceiveResult =
        withContext(Dispatchers.IO) {
            TrafficStats.setThreadStatsTag(0x1000)

            val cleanPath = if (path.startsWith("/")) path else "/$path"
            val baseUrlStr = "http://${metadata.ip}:${metadata.port}"
            val urlStr = "$baseUrlStr$cleanPath"
            val url = URL(urlStr)

            Log.d(TAG, "🌐 HTTP transfer start interface=wifi size=${metadata.totalSize} resumeOffset=$resumeOffset")
            TransferDiagnostics.log(
                "Http",
                "Open HTTP session id=${metadata.transferId} file=${metadata.fileName} " +
                    "interfaceCategory=wifi addressFamily=ipv4 resumeOffset=$resumeOffset",
            )
            EnergyDiagnostics.recordSample(
                context = host.context,
                reason = "http_transfer_start",
                detail = "file=${metadata.fileName} size=${metadata.totalSize} resumeOffset=$resumeOffset",
            )

            val connectivityManager =
                host.context.getSystemService(ConnectivityManager::class.java)
                    ?: throw IOException("ConnectivityManager not found")

            val nowMs = SystemClock.elapsedRealtime()
            val startupDeadlineMs = nowMs + HTTP_STARTUP_BUDGET_MS
            TransferDiagnostics.log(
                "Http",
                "event=http_startup_budget transferId=${metadata.transferId} " +
                    "startupBudgetMs=$HTTP_STARTUP_BUDGET_MS startupStartElapsedMs=$nowMs",
            )
            val warmSession =
                obtainReusableSession(
                    baseUrlStr = baseUrlStr,
                    nowMs = nowMs,
                    transferId = metadata.transferId,
                    fileName = metadata.fileName,
                )
            val networkSession = warmSession?.networkSession ?: HttpTransferNetworkSession(connectivityManager)
            val wifiAcquireStartMs = SystemClock.elapsedRealtime()
            val initialNetwork =
                warmSession?.network
                    ?: networkSession.acquireWifi(
                        remainingHttpStartupBudget(startupDeadlineMs, SystemClock.elapsedRealtime()),
                        transferId = metadata.transferId,
                        startupDeadlineElapsedMs = startupDeadlineMs,
                    )
            val wifiAcquireMs =
                if (warmSession != null) 0L else SystemClock.elapsedRealtime() - wifiAcquireStartMs
            if (initialNetwork == null) {
                val remainingBudgetMs =
                    remainingHttpStartupBudget(startupDeadlineMs, SystemClock.elapsedRealtime())
                TransferDiagnostics.warn(
                    "Http",
                    "event=http_startup_failure transferId=${metadata.transferId} file=${metadata.fileName} " +
                        "startupElapsedMs=${SystemClock.elapsedRealtime() - nowMs} " +
                        "startupFailurePhase=wifi_acquisition " +
                        "remainingBudgetMs=$remainingBudgetMs " +
                        "failureReason=no_wifi",
                )
                throw IOException("No Wi-Fi network available. Cannot perform HTTP transfer.")
            }
            val skipInitialProbe = warmSession?.probeValidated == true
            if (warmSession != null) {
                Log.d(TAG, "Reusing warm HTTP session")
                TransferDiagnostics.log(
                    "Http",
                    "Reusing warm session id=${metadata.transferId} file=${metadata.fileName}",
                )
            }

            val connectionLoop =
                HttpTransferConnectionLoop(
                    host = host,
                    networkSession = networkSession,
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = READ_TIMEOUT_MS,
                    connectRetryWindowMs = CONNECT_RETRY_WINDOW_MS,
                    connectRetryDelayMs = CONNECT_RETRY_DELAY_MS,
                    networkPauseTimeoutMs = NETWORK_PAUSE_TIMEOUT_MS,
                    networkRecheckMs = NETWORK_RECHECK_MS,
                )

            try {
                networkSession.bindToNetwork(initialNetwork)

                val receiveResult =
                    connectionLoop.receive(
                        metadata = metadata,
                        baseUrlStr = baseUrlStr,
                        url = url,
                        initialNetwork = initialNetwork,
                        resumeOffset = resumeOffset,
                        skipInitialProbe = skipInitialProbe,
                        startupDeadlineElapsedMs = startupDeadlineMs,
                        startupStartElapsedMs = nowMs,
                        wifiAcquireMs = wifiAcquireMs,
                        onTransferState = onTransferState,
                        onProgress = onProgress,
                    )
                cacheReusableSession(
                    ReusableHttpSession(
                        baseUrlStr = baseUrlStr,
                        networkSession = networkSession,
                        network = receiveResult.finalNetwork,
                        probeValidated = true,
                        lastUsedElapsedMs = SystemClock.elapsedRealtime(),
                    ),
                )
                HttpTransferReceiveResult(
                    sha256 = receiveResult.sha256,
                    fullFileSizeBytes = receiveResult.fullFileSizeBytes,
                    resumeOffsetBytes = receiveResult.resumeOffsetBytes,
                    finalFileSizeBytes = receiveResult.finalFileSizeBytes,
                    bytesTransferredThisAttempt = receiveResult.bytesTransferredThisAttempt,
                    attemptThroughputMiBps = receiveResult.attemptThroughputMiBps,
                    startupElapsedMs = receiveResult.startupElapsedMs,
                    wifiAcquireMs = wifiAcquireMs,
                    timeToFileRequestMs = receiveResult.timeToFileRequestMs,
                    dataTransferDurationMs = receiveResult.dataTransferDurationMs,
                    reconnectCount = receiveResult.reconnectCount,
                )
            } catch (e: Exception) {
                TransferDiagnostics.error(
                    "Http",
                    "HTTP session failed id=${metadata.transferId} file=${metadata.fileName}",
                    e,
                )
                discardReusableSession(
                    networkSession = networkSession,
                    transferId = metadata.transferId,
                    fileName = metadata.fileName,
                    reason = "receive_failed",
                )
                throw e
            } finally {
                EnergyDiagnostics.recordSample(
                    context = host.context,
                    reason = "http_transfer_end",
                    detail = "file=${metadata.fileName}",
                )
                TrafficStats.clearThreadStatsTag()
            }
        }

    override fun close() {
        synchronized(reusableSessionLock) {
            reusableSession?.let {
                TransferDiagnostics.warn(
                    "Http",
                    "Discard warm session reason=strategy_close",
                )
            }
            reusableSession?.networkSession?.close()
            reusableSession = null
        }
    }

    private fun obtainReusableSession(
        baseUrlStr: String,
        nowMs: Long,
        transferId: String,
        fileName: String,
    ): ReusableHttpSession? {
        synchronized(reusableSessionLock) {
            val current = reusableSession ?: return null
            val expired = nowMs - current.lastUsedElapsedMs > SESSION_IDLE_TTL_MS
            val wrongTarget = current.baseUrlStr != baseUrlStr
            val unusable = !current.networkSession.isNetworkUsable(current.network)
            if (expired || wrongTarget || unusable) {
                val reason =
                    when {
                        expired -> "expired"
                        wrongTarget -> "wrong_target"
                        else -> "network_unusable"
                    }
                TransferDiagnostics.warn(
                    "Http",
                    "Discard warm session id=$transferId file=$fileName reason=$reason",
                )
                current.networkSession.close()
                reusableSession = null
                return null
            }
            return current
        }
    }

    private fun cacheReusableSession(session: ReusableHttpSession) {
        synchronized(reusableSessionLock) {
            if (reusableSession !== session) {
                reusableSession
                    ?.takeUnless { it.networkSession === session.networkSession }
                    ?.also {
                        TransferDiagnostics.warn(
                            "Http",
                            "Discard warm session reason=replaced",
                        )
                    }?.networkSession
                    ?.close()
            }
            reusableSession = session
        }
    }

    private fun discardReusableSession(
        networkSession: HttpTransferNetworkSession,
        transferId: String,
        fileName: String,
        reason: String,
    ) {
        synchronized(reusableSessionLock) {
            if (reusableSession?.networkSession === networkSession) {
                TransferDiagnostics.warn(
                    "Http",
                    "Discard warm session id=$transferId file=$fileName reason=$reason",
                )
                reusableSession?.networkSession?.close()
                reusableSession = null
                return
            }
        }
        networkSession.close()
    }

    private class ReusableHttpSession(
        val baseUrlStr: String,
        val networkSession: HttpTransferNetworkSession,
        var network: Network,
        var probeValidated: Boolean,
        var lastUsedElapsedMs: Long,
    )
}
