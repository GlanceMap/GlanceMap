package com.glancemap.glancemapwearos.core.service.transfer.datalayer

import android.util.Log
import com.glancemap.glancemapwearos.core.service.ChannelTransferForegroundService
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.contract.ReceiverMetadata
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream

class ChannelClientStrategy {
    companion object {
        private const val TAG = "ChannelReceiver"
        private const val INPUT_BUFFER_BYTES = 1024 * 1024 // 1MB
    }

    suspend fun receiveFromChannel(
        service: ChannelTransferForegroundService,
        channel: ChannelClient.Channel,
        metadata: ReceiverMetadata,
        onProgress: (Long) -> Unit,
    ): String =
        withContext(Dispatchers.IO) {
            val channelClient = Wearable.getChannelClient(service)

            try {
                Log.d(TAG, "📥 Channel opened: file=${metadata.fileName}")
                TransferDiagnostics.log(
                    "ChannelIO",
                    "Receiving channel payload id=${metadata.transferId} file=${metadata.fileName}",
                )

                val rawInput = channelClient.getInputStream(channel).await()

                val checksum =
                    BufferedInputStream(rawInput, INPUT_BUFFER_BYTES).use { inp ->
                        checkNotNull(
                            service.saveFile(
                                fileName = metadata.fileName,
                                inputStream = inp,
                                expectedSize = metadata.totalSize.takeIf { it >= 0L },
                                expectedSha256 = metadata.checksumSha256,
                                resumeOffset = 0L,
                                keepPartialOnFailure = false,
                                keepPartialOnCancel = false,
                                computeSha256 = true,
                                diagnosticContext = "transferId=${metadata.transferId}",
                                onProgress = onProgress,
                            ),
                        ) { "checksum_compute_failed" }
                    }

                Log.d(TAG, "✅ Channel receive done: file=${metadata.fileName}")
                TransferDiagnostics.log(
                    "ChannelIO",
                    "Channel payload saved id=${metadata.transferId} file=${metadata.fileName} " +
                        "expectedBytes=${metadata.totalSize} receivedChecksumAvailable=yes",
                )
                checksum
            } catch (e: Exception) {
                val reason = channelTransferFailureReason(e.message)
                Log.e(TAG, "Channel receive failed: file=${metadata.fileName} reason=$reason")
                TransferDiagnostics.error(
                    "ChannelIO",
                    "Channel payload failed id=${metadata.transferId} file=${metadata.fileName} reason=$reason",
                )
                throw e
            } finally {
                runCatching { channelClient.close(channel).await() }
            }
        }
}
