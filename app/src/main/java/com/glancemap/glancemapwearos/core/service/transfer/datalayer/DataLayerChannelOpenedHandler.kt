package com.glancemap.glancemapwearos.core.service.transfer.datalayer

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.core.service.DataLayerListenerService
import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.contract.ReceiverMetadata
import com.glancemap.glancemapwearos.core.service.transfer.contract.TransferConstants
import com.glancemap.glancemapwearos.core.service.transfer.notifications.FGS_DATA_SYNC_TIMEOUT
import com.glancemap.glancemapwearos.core.service.transfer.notifications.NotificationHelper
import com.glancemap.glancemapwearos.core.service.transfer.notifications.foregroundStartFailureDetail
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferTerminalOutcome
import com.glancemap.glancemapwearos.core.service.transfer.storage.WatchFileOps
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.Locale

internal class DataLayerChannelOpenedHandler(
    private val service: DataLayerListenerService,
    private val notificationHelper: NotificationHelper,
    private val fileOps: WatchFileOps,
    private val transferMutex: Mutex,
    private val channelReceiver: ChannelClientStrategy,
    private val sendAck: suspend (sourceNodeId: String, transferId: String, status: String, detail: String) -> Unit,
    private val popChannelChecksum: (transferId: String) -> String? = { null },
) {
    // Keep channel validation, foreground ownership, and cleanup in one mutex scope.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    suspend fun handleChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(TransferConstants.CHANNEL_PREFIX)) return

        val parsed =
            parseChannelPath(channel.path) ?: run {
                Log.w(TAG, "Invalid channel path: ${channel.path}")
                TransferDiagnostics.warn("Channel", "Invalid channel path")
                return
            }

        val transferId = parsed.first
        val fileName = fileOps.sanitizeFileName(parsed.second)
        val notificationId = transferId.hashCode()

        val expectedChecksum = popChannelChecksum(transferId)
        val metadata =
            ReceiverMetadata(
                transferId = transferId,
                fileName = fileName,
                totalSize = -1L,
                sourceNodeId = channel.nodeId,
                notificationId = notificationId,
                checksumSha256 = expectedChecksum,
            )
        EnergyDiagnostics.recordEvent(
            reason = "channel_transfer_start",
            detail = "file=$fileName transferId=$transferId",
        )
        TransferDiagnostics.log(
            "Channel",
            "Open id=$transferId file=$fileName",
        )

        transferMutex.withLock {
            if (fileOps.fileBlocksIncomingTransfer(fileName)) {
                val msg = "FILE_EXISTS:$fileName"
                TransferDiagnostics.warn("Channel", "Target file already exists id=$transferId file=$fileName")
                notificationHelper.showError(metadata.notificationId, metadata.fileName, "Already exists")
                runCatching { Wearable.getChannelClient(service).close(channel).await() }
                service.appScope().launch(Dispatchers.IO) {
                    runCatching { sendAck(metadata.sourceNodeId, metadata.transferId, "ERROR", msg) }
                        .onFailure {
                            TransferDiagnostics.warn(
                                "Channel",
                                "Terminal ACK failed id=${metadata.transferId} status=ERROR",
                            )
                        }
                }
                return
            }

            val wakeLock = service.acquireWakeLock("GlanceMap::ChannelTransfer", TransferConstants.WAKELOCK_MAX_MS)
            service.releasePrewarmWakeLock("channel_transfer_start:$fileName")
            var transferStarted = false
            var foregroundStarted = false
            var foregroundStopped = false
            var receiverStarted = false
            var terminalAck: TerminalAck? = null

            try {
                service.beginForegroundTransfer(
                    transferId = metadata.transferId,
                    fileName = metadata.fileName,
                    sourceNodeId = metadata.sourceNodeId,
                    notificationId = metadata.notificationId,
                    job = requireNotNull(currentCoroutineContext()[Job]),
                )
                TransferDiagnostics.log(
                    "Channel",
                    "event=fgs_start_attempt id=${metadata.transferId} file=${metadata.fileName} " +
                        "size=${metadata.totalSize} fgsType=dataSync",
                )
                notificationHelper.startForeground(metadata.notificationId, metadata.fileName, "Receiving (Bluetooth)…")
                foregroundStarted = true
                TransferDiagnostics.log(
                    "Channel",
                    "event=fgs_start_success id=${metadata.transferId} file=${metadata.fileName}",
                )
                service.onTransferStarted()
                transferStarted = true
                val startMs = SystemClock.elapsedRealtime()
                var lastBytesCopied = 0L
                receiverStarted = true
                channelReceiver.receiveFromChannel(service, channel, metadata) { bytesCopied ->
                    lastBytesCopied = bytesCopied
                    // Channel progress is optional; keep minimal to avoid overhead
                    if (bytesCopied > 0L) {
                        notificationHelper.updateForeground(
                            metadata.notificationId,
                            metadata.fileName,
                            "Receiving… ${bytesCopied / (1024 * 1024)} MB",
                            -1, // Indeterminate progress for channel transfers
                        )
                    }
                }
                val durationMs = SystemClock.elapsedRealtime() - startMs
                TransferDiagnostics.log(
                    "Channel",
                    "event=write_complete transferId=${metadata.transferId} bytesReceived=$lastBytesCopied " +
                        "commitSucceeded=true",
                )

                // Verify checksum if available
                val expectedSha = metadata.checksumSha256?.lowercase()
                val checksumStartMs = SystemClock.elapsedRealtime()
                TransferDiagnostics.log(
                    "Channel",
                    "event=checksum_start transferId=${metadata.transferId}",
                )
                if (!expectedSha.isNullOrBlank()) {
                    val actualSha = fileOps.computeFinalFileSha256(metadata.fileName)?.lowercase()
                    if (actualSha != null && actualSha != expectedSha) {
                        TransferDiagnostics.warn(
                            "Channel",
                            "event=checksum_complete transferId=${metadata.transferId} " +
                                "checksumVerified=false " +
                                "checksumDurationMs=${SystemClock.elapsedRealtime() - checksumStartMs}",
                        )
                        TransferDiagnostics.warn(
                            "Channel",
                            "Checksum mismatch id=${metadata.transferId} file=${metadata.fileName}",
                        )
                        fileOps.deleteLocalFile(metadata.fileName)
                        throw IllegalStateException("CHECKSUM_MISMATCH")
                    }
                    TransferDiagnostics.log(
                        "Channel",
                        "Checksum verified id=${metadata.transferId} file=${metadata.fileName}",
                    )
                }
                TransferDiagnostics.log(
                    "Channel",
                    "event=checksum_complete transferId=${metadata.transferId} " +
                        "checksumVerified=${if (expectedSha.isNullOrBlank()) "na" else "true"} " +
                        "checksumDurationMs=${SystemClock.elapsedRealtime() - checksumStartMs}",
                )
                TransferDiagnostics.log(
                    "Channel",
                    "event=commit_complete transferId=${metadata.transferId} commitSucceeded=true",
                )

                if (!service.claimForegroundTransfer(metadata.transferId, TransferTerminalOutcome.DONE)) return@withLock
                terminalAck = TerminalAck(status = "DONE", detail = "")
                notificationHelper.stopForeground(metadata.notificationId)
                foregroundStopped = true
                notificationHelper.showCompletion(metadata.notificationId, metadata.fileName, "Saved ✓")
                val sizeMiB = lastBytesCopied / (1024.0 * 1024.0)
                val speedMiBps = if (durationMs > 0) sizeMiB / (durationMs / 1000.0) else 0.0
                TransferDiagnostics.log(
                    "Channel",
                    "Summary id=${metadata.transferId} file=${metadata.fileName} size=$lastBytesCopied " +
                        "durationMs=$durationMs speedMiBps=${String.format(Locale.US, "%.2f", speedMiBps)} " +
                        "bytesReceived=$lastBytesCopied copyDurationMs=$durationMs " +
                        "finalAckStatus=${terminalAck.status}",
                )
                EnergyDiagnostics.recordEvent(
                    reason = "channel_transfer_done",
                    detail = "file=${metadata.fileName} transferId=${metadata.transferId}",
                )
            } catch (e: CancellationException) {
                if (e.message != FGS_DATA_SYNC_TIMEOUT) throw e
                TransferDiagnostics.warn(
                    "Channel",
                    "Timeout cancellation handled id=${metadata.transferId} file=${metadata.fileName}",
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Channel transfer failed", e)
                val detail =
                    if (e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException") {
                        foregroundStartFailureDetail(e.javaClass.name, Build.VERSION.SDK_INT, e.message)
                    } else {
                        e.message ?: "Unknown error"
                    }
                TransferDiagnostics.error(
                    "Channel",
                    "Failed id=${metadata.transferId} file=${metadata.fileName} detail=$detail",
                    e,
                )
                if (service.claimForegroundTransfer(metadata.transferId, TransferTerminalOutcome.ERROR)) {
                    terminalAck = TerminalAck(status = "ERROR", detail = detail)
                    if (foregroundStarted && !foregroundStopped) {
                        runCatching { notificationHelper.stopForeground(metadata.notificationId) }
                    }
                    notificationHelper.showError(metadata.notificationId, metadata.fileName, "Failed: $detail")
                    EnergyDiagnostics.recordEvent(
                        reason = "channel_transfer_error",
                        detail = "file=${metadata.fileName} transferId=${metadata.transferId} msg=$detail",
                    )
                }
            } finally {
                if (
                    foregroundStarted &&
                    !foregroundStopped &&
                    service.foregroundTransferOutcome(metadata.transferId) != TransferTerminalOutcome.TIMEOUT
                ) {
                    runCatching { notificationHelper.stopForeground(metadata.notificationId) }
                }
                if (!receiverStarted) {
                    runCatching { Wearable.getChannelClient(service).close(channel).await() }
                }
                service.endForegroundTransfer(metadata.transferId)
                runCatching {
                    if (transferStarted) service.onTransferFinished()
                }.onFailure {
                    TransferDiagnostics.warn(
                        "Channel",
                        "Transfer-finished cleanup failed id=${metadata.transferId}",
                    )
                }
                runCatching { service.releaseWakeLock(wakeLock) }
                    .onFailure {
                        TransferDiagnostics.warn(
                            "Channel",
                            "Wake-lock cleanup failed id=${metadata.transferId}",
                        )
                    }
                TransferDiagnostics.log(
                    "Channel",
                    "event=cleanup_complete transferId=${metadata.transferId}",
                )
                terminalAck?.let { ack ->
                    service.appScope().launch(Dispatchers.IO) {
                        runCatching {
                            sendAck(metadata.sourceNodeId, metadata.transferId, ack.status, ack.detail)
                        }.onFailure {
                            TransferDiagnostics.warn(
                                "Channel",
                                "Terminal ACK failed id=${metadata.transferId} status=${ack.status}",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun parseChannelPath(path: String): Pair<String, String>? {
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.size < 4) return null
        val transferId = parts[2]
        val safeName = parts[3]
        return transferId to Uri.decode(safeName)
    }

    private companion object {
        const val TAG = "DataLayerChOpen"
    }

    private data class TerminalAck(
        val status: String,
        val detail: String,
    )
}
