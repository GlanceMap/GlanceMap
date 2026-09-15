package com.glancemap.glancemapwearos.core.service.transfer.datalayer

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.core.service.DataLayerListenerService
import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.contract.TransferConstants
import com.glancemap.glancemapwearos.core.service.transfer.notifications.FGS_DATA_SYNC_TIMEOUT
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import com.glancemap.glancemapwearos.core.service.transfer.notifications.foregroundStartFailureDetail
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferTerminalOutcome
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchFileOps
import com.google.android.gms.wearable.MessageEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.util.Locale

internal class DataLayerSmallFileRequestHandler(
    private val service: DataLayerListenerService,
    private val notificationHelper: NotificationHelper,
    private val fileOps: WatchFileOps,
    private val transferMutex: Mutex,
    private val sendAck: suspend (sourceNodeId: String, transferId: String, status: String, detail: String) -> Unit,
) {
    private val appScope get() = service.appScope()

    // Keep message validation, foreground ownership, and cleanup in one mutex scope.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun handle(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val sourceNodeId = messageEvent.sourceNodeId
        val bytes = messageEvent.data

        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.size < 4) {
            Log.w(TAG, "Invalid small file path: $path")
            TransferDiagnostics.warn("Small", "Invalid small-file path")
            return
        }

        // Path formats:
        //   v1: /glancemap/small_file/{transferId}/{encodedName}          (4 parts)
        //   v2: /glancemap/small_file/{transferId}/{sha256}/{encodedName} (5 parts)
        val transferId = parts[2]
        val expectedChecksum: String?
        val encodedName: String
        if (parts.size >= 5) {
            expectedChecksum = parts[3].takeIf { it.isNotBlank() }
            encodedName = parts[4]
        } else {
            expectedChecksum = null
            encodedName = parts[3]
        }
        val fileName = fileOps.sanitizeFileName(Uri.decode(encodedName))
        val notificationId = transferId.hashCode()
        EnergyDiagnostics.recordEvent(
            reason = "small_transfer_start",
            detail = "file=$fileName transferId=$transferId bytes=${bytes.size}",
        )
        TransferDiagnostics.log(
            "Small",
            "Start id=$transferId file=$fileName bytes=${bytes.size}",
        )

        if (bytes.size > TransferConstants.SMALL_FILE_MAX_BYTES) {
            TransferDiagnostics.warn(
                "Small",
                "Too large for small-file path id=$transferId file=$fileName bytes=${bytes.size}",
            )
            appScope.launch(Dispatchers.IO) {
                sendAck(
                    sourceNodeId,
                    transferId,
                    "ERROR",
                    "Small file too large (${bytes.size} bytes). Use Channel.",
                )
            }
            return
        }

        if (!fileOps.isSupportedTransferFileName(fileName)) {
            TransferDiagnostics.warn(
                "Small",
                "Unsupported file type id=$transferId file=$fileName",
            )
            appScope.launch(Dispatchers.IO) {
                sendAck(sourceNodeId, transferId, "ERROR", "Unsupported file type")
            }
            return
        }

        appScope.launch(Dispatchers.IO) {
            transferMutex.withLock {
                if (fileOps.fileBlocksIncomingTransfer(fileName)) {
                    val msg = "FILE_EXISTS:$fileName"
                    TransferDiagnostics.warn("Small", "Target file already exists id=$transferId file=$fileName")
                    notificationHelper.showError(notificationId, fileName, "Already exists")
                    appScope.launch(Dispatchers.IO) {
                        runCatching { sendAck(sourceNodeId, transferId, "ERROR", msg) }
                            .onFailure {
                                TransferDiagnostics.warn(
                                    "Small",
                                    "Terminal ACK failed id=$transferId status=ERROR",
                                )
                            }
                    }
                    return@withLock
                }

                val wakeLock = service.acquireWakeLock("GlanceMap::SmallTransfer", TransferConstants.SMALL_WAKELOCK_MS)
                service.releasePrewarmWakeLock("small_transfer_start:$fileName")
                var transferStarted = false
                var foregroundStarted = false
                var foregroundStopped = false
                var terminalAck: TerminalAck? = null
                try {
                    service.beginForegroundTransfer(
                        transferId = transferId,
                        fileName = fileName,
                        sourceNodeId = sourceNodeId,
                        notificationId = notificationId,
                        job = requireNotNull(currentCoroutineContext()[Job]),
                    )
                    TransferDiagnostics.log(
                        "Small",
                        "event=fgs_start_attempt id=$transferId file=$fileName size=${bytes.size} fgsType=dataSync",
                    )
                    notificationHelper.startForeground(notificationId, fileName, "Saving…")
                    foregroundStarted = true
                    TransferDiagnostics.log(
                        "Small",
                        "event=fgs_start_success id=$transferId file=$fileName",
                    )
                    service.onTransferStarted()
                    transferStarted = true

                    val startMs = SystemClock.elapsedRealtime()
                    ByteArrayInputStream(bytes).use { input ->
                        service.saveFile(
                            fileName = fileName,
                            inputStream = input,
                            expectedSize = bytes.size.toLong(),
                            resumeOffset = 0L,
                            diagnosticContext = "transferId=$transferId",
                            onProgress = { /* no progress */ },
                        )
                    }
                    val durationMs = SystemClock.elapsedRealtime() - startMs
                    TransferDiagnostics.log(
                        "Small",
                        "event=write_complete transferId=$transferId bytesReceived=${bytes.size} commitSucceeded=true",
                    )

                    // Verify checksum if available
                    val expectedSha = expectedChecksum?.lowercase()
                    val checksumStartMs = SystemClock.elapsedRealtime()
                    TransferDiagnostics.log(
                        "Small",
                        "event=checksum_start transferId=$transferId",
                    )
                    if (!expectedSha.isNullOrBlank()) {
                        val actualSha = fileOps.computeFinalFileSha256(fileName)?.lowercase()
                        if (actualSha != null && actualSha != expectedSha) {
                            TransferDiagnostics.warn(
                                "Small",
                                "event=checksum_complete transferId=$transferId checksumVerified=false " +
                                    "checksumDurationMs=${SystemClock.elapsedRealtime() - checksumStartMs}",
                            )
                            TransferDiagnostics.warn(
                                "Small",
                                "Checksum mismatch id=$transferId file=$fileName",
                            )
                            fileOps.deleteLocalFile(fileName)
                            throw IllegalStateException("CHECKSUM_MISMATCH")
                        }
                        TransferDiagnostics.log(
                            "Small",
                            "Checksum verified id=$transferId file=$fileName",
                        )
                    }
                    TransferDiagnostics.log(
                        "Small",
                        "event=checksum_complete transferId=$transferId " +
                            "checksumVerified=${if (expectedSha.isNullOrBlank()) "na" else "true"} " +
                            "checksumDurationMs=${SystemClock.elapsedRealtime() - checksumStartMs}",
                    )
                    TransferDiagnostics.log(
                        "Small",
                        "event=commit_complete transferId=$transferId commitSucceeded=true",
                    )

                    if (!service.claimForegroundTransfer(transferId, TransferTerminalOutcome.DONE)) return@withLock
                    terminalAck = TerminalAck(status = "DONE", detail = "")
                    notificationHelper.stopForeground(notificationId)
                    foregroundStopped = true
                    notificationHelper.showCompletion(notificationId, fileName, "Saved ✓")
                    val sizeMiB = bytes.size / (1024.0 * 1024.0)
                    val speedMiBps = if (durationMs > 0) sizeMiB / (durationMs / 1000.0) else 0.0
                    TransferDiagnostics.log(
                        "Small",
                        "Summary id=$transferId file=$fileName size=${bytes.size} durationMs=$durationMs " +
                            "speedMiBps=${String.format(Locale.US, "%.2f", speedMiBps)} bytesReceived=${bytes.size} " +
                            "copyDurationMs=$durationMs finalAckStatus=${terminalAck.status}",
                    )
                    EnergyDiagnostics.recordEvent(
                        reason = "small_transfer_done",
                        detail = "file=$fileName transferId=$transferId bytes=${bytes.size}",
                    )
                } catch (e: CancellationException) {
                    if (e.message != FGS_DATA_SYNC_TIMEOUT) throw e
                    TransferDiagnostics.warn(
                        "Small",
                        "Timeout cancellation handled id=$transferId file=$fileName",
                    )
                } catch (e: Exception) {
                    val detail =
                        if (e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException") {
                            foregroundStartFailureDetail(e.javaClass.name, Build.VERSION.SDK_INT, e.message)
                        } else {
                            e.message ?: "Unknown error"
                        }
                    Log.e(TAG, "❌ Small file save failed", e)
                    TransferDiagnostics.error(
                        "Small",
                        "Failed id=$transferId file=$fileName detail=$detail",
                        e,
                    )
                    if (service.claimForegroundTransfer(transferId, TransferTerminalOutcome.ERROR)) {
                        terminalAck = TerminalAck(status = "ERROR", detail = detail)
                        if (foregroundStarted && !foregroundStopped) {
                            runCatching { notificationHelper.stopForeground(notificationId) }
                        }
                        notificationHelper.showError(notificationId, fileName, "Failed: $detail")
                        EnergyDiagnostics.recordEvent(
                            reason = "small_transfer_error",
                            detail = "file=$fileName transferId=$transferId msg=$detail",
                        )
                    }
                } finally {
                    if (
                        foregroundStarted &&
                        !foregroundStopped &&
                        service.foregroundTransferOutcome(transferId) != TransferTerminalOutcome.TIMEOUT
                    ) {
                        runCatching { notificationHelper.stopForeground(notificationId) }
                    }
                    service.endForegroundTransfer(transferId)
                    runCatching {
                        if (transferStarted) service.onTransferFinished()
                    }.onFailure {
                        TransferDiagnostics.warn(
                            "Small",
                            "Transfer-finished cleanup failed id=$transferId",
                        )
                    }
                    runCatching { service.releaseWakeLock(wakeLock) }
                        .onFailure {
                            TransferDiagnostics.warn(
                                "Small",
                                "Wake-lock cleanup failed id=$transferId",
                            )
                        }
                    TransferDiagnostics.log(
                        "Small",
                        "event=cleanup_complete transferId=$transferId",
                    )
                    terminalAck?.let { ack ->
                        appScope.launch(Dispatchers.IO) {
                            runCatching {
                                sendAck(sourceNodeId, transferId, ack.status, ack.detail)
                            }.onFailure {
                                TransferDiagnostics.warn(
                                    "Small",
                                    "Terminal ACK failed id=$transferId status=${ack.status}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "DataLayerSmallReq"
    }

    private data class TerminalAck(
        val status: String,
        val detail: String,
    )
}
