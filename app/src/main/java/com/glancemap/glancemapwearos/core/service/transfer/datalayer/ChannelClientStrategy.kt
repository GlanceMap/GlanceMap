package com.glancemap.glancemapwearos.core.service.transfer.datalayer

import android.util.Log
import com.glancemap.glancemapwearos.core.service.DataLayerListenerService
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
        service: DataLayerListenerService,
        channel: ChannelClient.Channel,
        metadata: ReceiverMetadata,
        onProgress: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val channelClient = Wearable.getChannelClient(service)

        try {
            Log.d(TAG, "📥 Channel opened: file=${metadata.fileName}")
            TransferDiagnostics.log(
                "ChannelIO",
                "Receiving channel payload id=${metadata.transferId} file=${metadata.fileName}",
            )

            val rawInput = channelClient.getInputStream(channel).await()

            BufferedInputStream(rawInput, INPUT_BUFFER_BYTES).use { inp ->
                service.saveFile(
                    fileName = metadata.fileName,
                    inputStream = inp,
                    expectedSize = null, // Channel size usually unknown
                    resumeOffset = 0L,
                    diagnosticContext = "transferId=${metadata.transferId}",
                    onProgress = onProgress,
                )
            }

            Log.d(TAG, "✅ Channel receive done: file=${metadata.fileName}")
            TransferDiagnostics.log(
                "ChannelIO",
                "Channel payload saved id=${metadata.transferId} file=${metadata.fileName}",
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Channel receive failed: file=${metadata.fileName}", e)
            TransferDiagnostics.error(
                "ChannelIO",
                "Channel payload failed id=${metadata.transferId} file=${metadata.fileName}",
                e,
            )
            throw e
        } finally {
            runCatching { channelClient.close(channel).await() }
        }
    }
}
