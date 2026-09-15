package com.glancemap.glancemapwearos.core.service.transfer.runtime

import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.contract.ReceiverMetadata
import com.glancemap.glancemapwearos.core.service.transfer.contract.TransferConstants
import com.glancemap.glancemapwearos.core.service.transfer.http.HttpTransferProgressCallbacks
import com.glancemap.glancemapwearos.core.service.transfer.http.HttpTransferReceiveResult
import com.glancemap.glancemapwearos.core.service.transfer.http.HttpTransferResultNotifier
import com.glancemap.glancemapwearos.core.service.transfer.http.HttpTransferStrategy
import com.glancemap.glancemapwearos.core.service.transfer.http.HttpTransferTerminalResult
import com.glancemap.glancemapwearos.core.service.transfer.notifications.FGS_DATA_SYNC_TIMEOUT
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.util.Locale

@Suppress("LongParameterList")
internal class TransferRunner(
    private val host: TransferRuntimeHost,
    private val notificationHelper: NotificationHelper,
    private val httpReceiver: HttpTransferStrategy,
    private val sessionState: TransferSessionState,
    private val sendStatus: suspend (sourceNodeId: String, transferId: String, phase: String, detail: String) -> Unit,
    private val claimTerminal: (transferId: String, outcome: TransferTerminalOutcome) -> Boolean = { _, _ -> true },
) {
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
    suspend fun runHttp(
        metadata: ReceiverMetadata,
        path: String,
        onTerminalResult: (HttpTransferTerminalResult) -> Unit = {},
    ) {
        val totalAttemptStartMs = SystemClock.elapsedRealtime()
        val wakeLock = host.acquireWakeLock("GlanceMap::HttpTransfer", TransferConstants.WAKELOCK_MAX_MS)
        val wifiLock = host.acquireWifiLock("GlanceMap::WifiHighPerf")
        host.releasePrewarmWakeLock("http_transfer_start:${metadata.fileName}")

        val progressCallbacks =
            HttpTransferProgressCallbacks(
                host = host,
                notificationHelper = notificationHelper,
                metadata = metadata,
                sendStatus = sendStatus,
            )
        val resultNotifier =
            HttpTransferResultNotifier(
                notificationHelper = notificationHelper,
                claimTerminal = claimTerminal,
            )

        sessionState.registerActiveTransfer(
            transferId = metadata.transferId,
            job = currentCoroutineContext()[Job],
            fileName = metadata.fileName,
            sourceNodeId = metadata.sourceNodeId,
        )
        host.onTransferStarted()
        TransferDiagnostics.log(
            "Runner",
            "HTTP start id=${metadata.transferId} file=${metadata.fileName} size=${metadata.totalSize}",
        )

        var terminalResult: HttpTransferTerminalResult? = null
        var receiveResult: HttpTransferReceiveResult? = null
        var checksumVerified = false
        var checksumDurationMs: Long? = null
        try {
            sendStatus(metadata.sourceNodeId, metadata.transferId, "REQUEST_RECEIVED", "Connecting to Phone (HTTP)…")

            if (metadata.checksumSha256.isNullOrBlank()) {
                TransferDiagnostics.warn(
                    "Runner",
                    "Missing checksum id=${metadata.transferId} file=${metadata.fileName}",
                )
                throw IllegalStateException("MISSING_CHECKSUM")
            }
            if (metadata.authToken.isNullOrBlank()) {
                TransferDiagnostics.warn(
                    "Runner",
                    "Missing HTTP token id=${metadata.transferId} file=${metadata.fileName}",
                )
                throw IllegalStateException("MISSING_HTTP_TOKEN")
            }

            val currentReceiveResult =
                httpReceiver.receive(
                    host = host,
                    metadata = metadata,
                    path = path,
                    resumeOffset = 0L,
                    onTransferState = progressCallbacks::onTransferState,
                    onProgress = progressCallbacks::onProgress,
                )
            receiveResult = currentReceiveResult
            TransferDiagnostics.log(
                "Runner",
                "event=write_complete transferId=${metadata.transferId} " +
                    "fullFileSizeBytes=${currentReceiveResult.fullFileSizeBytes} " +
                    "resumeOffsetBytes=${currentReceiveResult.resumeOffsetBytes} " +
                    "finalFileSizeBytes=${currentReceiveResult.finalFileSizeBytes} " +
                    "bytesTransferredThisAttempt=${currentReceiveResult.bytesTransferredThisAttempt} " +
                    "commitSucceeded=true",
            )

            TransferDiagnostics.log(
                "Runner",
                "HTTP payload received id=${metadata.transferId} file=${metadata.fileName}; verifying checksum",
            )
            val checksumStartMs = SystemClock.elapsedRealtime()
            TransferDiagnostics.log(
                "Runner",
                "event=checksum_start transferId=${metadata.transferId}",
            )
            try {
                verifyChecksumIfNeeded(metadata, currentReceiveResult.sha256)
                checksumVerified = true
            } finally {
                checksumDurationMs = SystemClock.elapsedRealtime() - checksumStartMs
                TransferDiagnostics.log(
                    "Runner",
                    "event=checksum_complete transferId=${metadata.transferId} " +
                        "checksumVerified=$checksumVerified checksumDurationMs=$checksumDurationMs",
                )
            }
            TransferDiagnostics.log(
                "Runner",
                "event=commit_complete transferId=${metadata.transferId} commitSucceeded=true",
            )
            TransferDiagnostics.log(
                "Runner",
                "Summary id=${metadata.transferId} file=${metadata.fileName} " +
                    "fullFileSizeBytes=${currentReceiveResult.fullFileSizeBytes} " +
                    "resumeOffsetBytes=${currentReceiveResult.resumeOffsetBytes} " +
                    "finalFileSizeBytes=${currentReceiveResult.finalFileSizeBytes} " +
                    "bytesTransferredThisAttempt=${currentReceiveResult.bytesTransferredThisAttempt} " +
                    "durationMs=${currentReceiveResult.dataTransferDurationMs} " +
                    "attemptThroughputMiBps=${
                        String.format(Locale.US, "%.2f", currentReceiveResult.attemptThroughputMiBps)
                    }",
            )
            TransferDiagnostics.log(
                "Runner",
                "event=http_final_summary transferId=${metadata.transferId} " +
                    "fullFileSizeBytes=${currentReceiveResult.fullFileSizeBytes} " +
                    "resumeOffsetBytes=${currentReceiveResult.resumeOffsetBytes} " +
                    "finalFileSizeBytes=${currentReceiveResult.finalFileSizeBytes} " +
                    "bytesTransferredThisAttempt=${currentReceiveResult.bytesTransferredThisAttempt} " +
                    "startupElapsedMs=${currentReceiveResult.startupElapsedMs} " +
                    "wifiAcquireMs=${currentReceiveResult.wifiAcquireMs} " +
                    "timeToFileRequestMs=${currentReceiveResult.timeToFileRequestMs ?: "na"} " +
                    "dataTransferDurationMs=${currentReceiveResult.dataTransferDurationMs} " +
                    "attemptThroughputMiBps=${
                        String.format(Locale.US, "%.2f", currentReceiveResult.attemptThroughputMiBps)
                    } " +
                    "reconnectCount=${currentReceiveResult.reconnectCount} " +
                    "checksumDurationMs=$checksumDurationMs checksumVerified=$checksumVerified " +
                    "commitSucceeded=true " +
                    "totalAttemptDurationMs=${SystemClock.elapsedRealtime() - totalAttemptStartMs} " +
                    "finalResult=DONE",
            )
            terminalResult = resultNotifier.onSuccess(metadata)
        } catch (ce: CancellationException) {
            Log.w(TAG, "⛔ HTTP transfer cancelled", ce)
            TransferDiagnostics.warn(
                "Runner",
                "HTTP cancelled id=${metadata.transferId} file=${metadata.fileName}",
            )
            TransferDiagnostics.log(
                "Runner",
                "event=http_final_summary transferId=${metadata.transferId} " +
                    "fullFileSizeBytes=${metadata.totalSize} " +
                    "resumeOffsetBytes=${receiveResult?.resumeOffsetBytes ?: "na"} " +
                    "finalFileSizeBytes=${receiveResult?.finalFileSizeBytes ?: "na"} " +
                    "bytesTransferredThisAttempt=${receiveResult?.bytesTransferredThisAttempt ?: "na"} " +
                    "startupElapsedMs=${receiveResult?.startupElapsedMs ?: "na"} " +
                    "dataTransferDurationMs=${receiveResult?.dataTransferDurationMs ?: "na"} " +
                    "attemptThroughputMiBps=${receiveResult?.attemptThroughputMiBps ?: "na"} " +
                    "checksumVerified=$checksumVerified commitSucceeded=false " +
                    "totalAttemptDurationMs=${SystemClock.elapsedRealtime() - totalAttemptStartMs} " +
                    "finalResult=CANCELLED",
            )
            if (ce.message != FGS_DATA_SYNC_TIMEOUT) {
                terminalResult = resultNotifier.onCancelled(metadata)
            }
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "❌ HTTP transfer failed", e)
            TransferDiagnostics.error(
                "Runner",
                "HTTP failed id=${metadata.transferId} file=${metadata.fileName}",
                e,
            )
            TransferDiagnostics.log(
                "Runner",
                "event=http_final_summary transferId=${metadata.transferId} fullFileSizeBytes=${metadata.totalSize} " +
                    "resumeOffsetBytes=${receiveResult?.resumeOffsetBytes ?: "na"} " +
                    "finalFileSizeBytes=${receiveResult?.finalFileSizeBytes ?: "na"} " +
                    "bytesTransferredThisAttempt=${receiveResult?.bytesTransferredThisAttempt ?: "na"} " +
                    "startupElapsedMs=${receiveResult?.startupElapsedMs ?: "na"} " +
                    "dataTransferDurationMs=${receiveResult?.dataTransferDurationMs ?: "na"} " +
                    "attemptThroughputMiBps=${receiveResult?.attemptThroughputMiBps ?: "na"} " +
                    "checksumDurationMs=${checksumDurationMs ?: "na"} " +
                    "checksumVerified=$checksumVerified commitSucceeded=false " +
                    "totalAttemptDurationMs=${SystemClock.elapsedRealtime() - totalAttemptStartMs} finalResult=ERROR " +
                    "failureReason=${e.javaClass.simpleName}",
            )
            terminalResult = resultNotifier.onError(metadata, e)
        } finally {
            TransferDiagnostics.log(
                "Runner",
                "HTTP finished id=${metadata.transferId} file=${metadata.fileName}",
            )
            sessionState.clearActiveTransfer(metadata.transferId)
            host.onTransferFinished()
            host.releaseWakeLock(wakeLock)
            host.releaseWifiLock(wifiLock)
            terminalResult?.let(onTerminalResult)
        }
    }

    private suspend fun verifyChecksumIfNeeded(
        metadata: ReceiverMetadata,
        receivedSha256: String?,
    ) {
        val expectedSha = metadata.checksumSha256?.lowercase()
        if (expectedSha.isNullOrBlank()) return

        val initialDetail = "Validating checksum…"
        sendStatus(metadata.sourceNodeId, metadata.transferId, "VERIFYING", initialDetail)
        notificationHelper.updateForeground(metadata.notificationId, metadata.fileName, initialDetail, -1)

        var lastVerifyProgressPercent = -1
        var lastVerifyProgressUpdateMs = 0L
        val actualSha =
            receivedSha256?.lowercase()
                ?: host
                    .computeFinalFileSha256(metadata.fileName) verifyProgress@{ bytesRead, totalBytes ->
                        val progressPercent = computeVerificationPercent(bytesRead, totalBytes)
                        if (progressPercent < 0) return@verifyProgress

                        val nowMs = SystemClock.elapsedRealtime()
                        val shouldReport =
                            progressPercent >= 100 ||
                                lastVerifyProgressPercent < 0 ||
                                progressPercent - lastVerifyProgressPercent >= VERIFY_PROGRESS_MIN_STEP_PERCENT ||
                                (nowMs - lastVerifyProgressUpdateMs) >= VERIFY_PROGRESS_MIN_INTERVAL_MS
                        if (!shouldReport) return@verifyProgress

                        lastVerifyProgressPercent = progressPercent
                        lastVerifyProgressUpdateMs = nowMs
                        val detail = buildVerificationDetail(progressPercent)
                        notificationHelper.updateForeground(metadata.notificationId, metadata.fileName, detail, -1)
                        host.appScope.launch {
                            runCatching {
                                sendStatus(metadata.sourceNodeId, metadata.transferId, "VERIFYING", detail)
                            }
                        }
                    }?.lowercase()
        if (actualSha == null || actualSha != expectedSha) {
            TransferDiagnostics.warn(
                "Runner",
                "Checksum mismatch id=${metadata.transferId} file=${metadata.fileName}",
            )
            host.deleteByName(metadata.fileName)
            throw IllegalStateException("CHECKSUM_MISMATCH")
        }
        TransferDiagnostics.log(
            "Runner",
            "Checksum verified id=${metadata.transferId} file=${metadata.fileName}",
        )
    }

    private companion object {
        const val TAG = "TransferRunner"
        const val VERIFY_PROGRESS_MIN_INTERVAL_MS = 1_000L
        const val VERIFY_PROGRESS_MIN_STEP_PERCENT = 4
    }
}

internal fun computeVerificationPercent(
    bytesRead: Long,
    totalBytes: Long,
): Int {
    if (totalBytes <= 0L) return -1
    return ((bytesRead.coerceAtLeast(0L) * 100L) / totalBytes)
        .toInt()
        .coerceIn(0, 100)
}

internal fun buildVerificationDetail(progressPercent: Int): String = "Validating checksum… ${progressPercent.coerceIn(0, 100)}%"
